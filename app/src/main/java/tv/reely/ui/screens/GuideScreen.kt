package tv.reely.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import tv.reely.ui.GuideState
import tv.reely.ui.GuideStatus
import tv.reely.ui.LiveState
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.components.channelTint
import tv.reely.ui.components.glass
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.GlassEdge
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Parchment
import tv.reely.xtream.EpgProgramme
import tv.reely.xtream.XtreamApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val MINUTE_WIDTH = 5.dp
private val CHANNEL_COLUMN = 132.dp
private val ROW_HEIGHT = 78.dp
private const val PREVIEW_DELAY_MS = 1_200L

@Composable
fun GuideScreen(
    live: LiveState,
    guide: GuideState,
    previewEnabled: Boolean,
    onMoveChannel: (Int) -> Unit,
    onMoveTime: (Int) -> Unit,
    onJumpToNow: () -> Unit,
    onRefresh: () -> Unit,
    onPlaySelected: () -> Unit,
    onBackToCategories: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(30_000)
        }
    }

    val context = LocalContext.current
    val density = LocalDensity.current
    val scroll = rememberScrollState()
    val rows = rememberLazyListState()
    val gridFocus = remember { FocusRequester() }

    val channel = live.channels.getOrNull(guide.channelIndex)
    val listing = channel?.epgChannelId?.let { guide.programmes[it] }.orEmpty()
    val selected = listing.firstOrNull { it.isOnAt(guide.focusTime) }

    val previewUrl = live.credentials?.let { credentials ->
        channel?.let { XtreamApi.streamUrl(credentials, it, live.format) }
    }

    val preview = remember {
        ExoPlayer.Builder(context)
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(1_500, 12_000, 700, 1_500)
                    .build()
            )
            .build()
    }
    var previewFailed by remember { mutableStateOf(false) }

    DisposableEffect(preview) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                previewFailed = true
            }
        }
        preview.addListener(listener)
        onDispose {
            preview.removeListener(listener)
            preview.release()
        }
    }

    LaunchedEffect(previewUrl, previewEnabled) {
        preview.stop()
        preview.clearMediaItems()
        previewFailed = false
        if (!previewEnabled || previewUrl == null) return@LaunchedEffect
        // Long enough that walking past channels does not open a connection for each.
        delay(PREVIEW_DELAY_MS)
        preview.setMediaItem(MediaItem.fromUri(previewUrl))
        preview.prepare()
        preview.playWhenReady = true
    }

    LaunchedEffect(guide.focusTime, guide.windowStart) {
        val target = with(density) {
            widthFor(guide.focusTime - guide.windowStart).toPx() - 150.dp.toPx()
        }
        scroll.animateScrollTo(target.coerceAtLeast(0f).roundToInt())
    }
    LaunchedEffect(guide.channelIndex) {
        rows.animateScrollToItem(guide.channelIndex.coerceAtLeast(0))
    }
    LaunchedEffect(live.channels.size) {
        if (live.channels.isNotEmpty()) runCatching { gridFocus.requestFocus() }
    }

    BackHandler {
        preview.stop()
        onBackToCategories()
    }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = live.selectedCategory?.name.orEmpty().uppercase(),
                    color = Faint,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    letterSpacing = 1.4.sp,
                    maxLines = 1,
                )
                Text(
                    text = selected?.title ?: channel?.name ?: "Guide",
                    color = Parchment,
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = selected?.let { "${channel?.name}  ·  ${timeRange(it)}" }
                        ?: guide.status.describe(),
                    color = Muted,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                selected?.description?.let {
                    Text(
                        text = it,
                        color = Muted,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TvActionButton(label = "Categories", onClick = onBackToCategories)
                    TvActionButton(label = "Now", onClick = onJumpToNow)
                    TvActionButton(
                        label = if (guide.status is GuideStatus.Importing) "Loading…" else "Refresh",
                        onClick = onRefresh,
                    )
                }
            }

            if (previewEnabled) {
                Box(
                    modifier = Modifier
                        .width(252.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.Black)
                        .border(1.dp, GlassEdge, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    AndroidView(
                        factory = { viewContext ->
                            PlayerView(viewContext).apply {
                                useController = false
                                setShutterBackgroundColor(android.graphics.Color.BLACK)
                                player = preview
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (previewFailed) {
                        Text(text = "Preview unavailable", color = Faint, fontSize = 12.sp, lineHeight = 16.sp)
                    }
                }
            }
        }

        if (live.channels.isEmpty()) {
            EmptyNote(if (live.busy) "Loading channels…" else "No channels in this category.")
            return@Column
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
                .focusRequester(gridFocus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> if (guide.channelIndex == 0) false
                        else { onMoveChannel(-1); true }

                        Key.DirectionDown -> {
                            if (guide.channelIndex < live.channels.lastIndex) onMoveChannel(1)
                            true
                        }

                        Key.DirectionLeft -> { onMoveTime(-1); true }
                        Key.DirectionRight -> { onMoveTime(1); true }
                        Key.DirectionCenter, Key.Enter -> {
                            preview.stop()
                            preview.clearMediaItems()
                            onPlaySelected()
                            true
                        }

                        else -> false
                    }
                },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.fillMaxWidth().height(22.dp)) {
                    Spacer(modifier = Modifier.width(CHANNEL_COLUMN))
                    Row(modifier = Modifier.horizontalScroll(scroll, enabled = false)) {
                        var tick = guide.windowStart
                        while (tick < guide.windowEnd) {
                            Box(modifier = Modifier.width(widthFor(1_800))) {
                                Text(
                                    text = clock(tick),
                                    color = Faint,
                                    fontSize = 12.sp,
                                    lineHeight = 15.sp,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            tick += 1_800
                        }
                    }
                }

                LazyColumn(
                    state = rows,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(
                        items = live.channels,
                        key = { _, entry -> entry.streamId },
                    ) { index, entry ->
                        GuideRow(
                            channelId = entry.streamId.toString(),
                            name = entry.name,
                            logo = entry.icon,
                            listing = entry.epgChannelId?.let { guide.programmes[it] }.orEmpty(),
                            guide = guide,
                            now = now,
                            isCurrent = index == guide.channelIndex,
                            scroll = scroll,
                        )
                    }
                }
            }

            // The line marking this instant, with the dot that makes a grid read as a guide.
            val nowX = with(density) {
                widthFor(now - guide.windowStart).toPx() - scroll.value + CHANNEL_COLUMN.toPx()
            }
            if (nowX >= with(density) { CHANNEL_COLUMN.toPx() }) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(nowX.roundToInt(), 0) }
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Parchment.copy(alpha = 0.8f)),
                )
                Box(
                    modifier = Modifier
                        .offset { IntOffset((nowX - with(density) { 4.dp.toPx() }).roundToInt(), 0) }
                        .size(10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Parchment),
                )
            }
        }
    }
}

@Composable
private fun GuideRow(
    channelId: String,
    name: String,
    logo: String?,
    listing: List<EpgProgramme>,
    guide: GuideState,
    now: Long,
    isCurrent: Boolean,
    scroll: androidx.compose.foundation.ScrollState,
) {
    Row(modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
        Box(
            modifier = Modifier
                .width(CHANNEL_COLUMN - 6.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(channelTint(channelId).copy(alpha = if (isCurrent) 0.95f else 0.7f))
                .border(
                    width = if (isCurrent) 2.dp else 0.dp,
                    color = if (isCurrent) Parchment else Color.Transparent,
                    shape = RoundedCornerShape(14.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (logo != null) {
                AsyncImage(
                    model = logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Text(
                    text = name.take(3).uppercase(),
                    color = Parchment,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(modifier = Modifier.width(6.dp))

        Row(
            modifier = Modifier.fillMaxHeight().horizontalScroll(scroll, enabled = false),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            if (listing.isEmpty()) {
                ProgrammeCard(
                    title = "No guide data",
                    slot = null,
                    width = widthFor(guide.windowEnd - guide.windowStart),
                    selected = false,
                    past = true,
                )
                return@Row
            }

            var cursor = guide.windowStart
            listing.forEach { programme ->
                val start = programme.start.coerceAtLeast(guide.windowStart)
                val stop = programme.stop.coerceAtMost(guide.windowEnd)
                if (stop <= start) return@forEach
                if (start > cursor) Spacer(modifier = Modifier.width(widthFor(start - cursor)))
                ProgrammeCard(
                    title = programme.title,
                    slot = timeRange(programme),
                    width = widthFor(stop - start),
                    selected = isCurrent && programme.isOnAt(guide.focusTime),
                    past = programme.stop <= now,
                )
                cursor = stop
            }
            if (cursor < guide.windowEnd) {
                Spacer(modifier = Modifier.width(widthFor(guide.windowEnd - cursor)))
            }
        }
    }
}

/** A block carries its own time, so nobody has to count along the ruler. */
@Composable
private fun ProgrammeCard(
    title: String,
    slot: String?,
    width: Dp,
    selected: Boolean,
    past: Boolean,
) {
    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    selected -> Accent.copy(alpha = 0.3f)
                    past -> Parchment.copy(alpha = 0.05f)
                    else -> Parchment.copy(alpha = 0.1f)
                }
            )
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) Accent else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            color = if (past && !selected) Parchment.copy(alpha = 0.55f) else Parchment,
            fontSize = 15.sp,
            lineHeight = 19.sp,
            fontWeight = if (past && !selected) FontWeight.Normal else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (slot != null) {
            Text(
                text = slot,
                color = Parchment.copy(alpha = 0.45f),
                fontSize = 13.sp,
                lineHeight = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun GuideStatus.describe(): String = when (this) {
    is GuideStatus.Idle -> "No guide loaded yet."
    is GuideStatus.Importing ->
        if (written == 0 && scanned == 0) "Downloading the guide…"
        else "Importing… $written programmes kept of $scanned read"

    is GuideStatus.Ready -> "$count programmes in the guide"
    is GuideStatus.Failed -> message
}

private fun widthFor(seconds: Long): Dp =
    (seconds.coerceAtLeast(0) / 60f * MINUTE_WIDTH.value).dp

private val clockFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

private fun clock(epochSeconds: Long): String = clockFormat.format(Date(epochSeconds * 1000))

private fun timeRange(programme: EpgProgramme): String =
    "${clock(programme.start)} – ${clock(programme.stop)}"
