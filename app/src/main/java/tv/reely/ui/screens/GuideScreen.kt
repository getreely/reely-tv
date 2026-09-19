package tv.reely.ui.screens

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import tv.reely.ui.components.TvChip
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Line
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Parchment
import tv.reely.ui.theme.SurfaceHigh
import tv.reely.ui.theme.SurfaceRaised
import tv.reely.xtream.EpgProgramme
import tv.reely.xtream.XtreamApi
import tv.reely.xtream.XtreamCategory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val MINUTE_WIDTH = 5.dp
private val CHANNEL_COLUMN = 196.dp
private val ROW_HEIGHT = 64.dp

/**
 * Long enough that passing over channels does not open a connection for each one. The
 * provider caps how many streams run at once, and a preview spends one of them.
 */
private const val PREVIEW_DELAY_MS = 1_200L

@Composable
fun GuideScreen(
    live: LiveState,
    guide: GuideState,
    previewEnabled: Boolean,
    onSelectCategory: (XtreamCategory) -> Unit,
    onMoveChannel: (Int) -> Unit,
    onMoveTime: (Int) -> Unit,
    onJumpToNow: () -> Unit,
    onRefresh: () -> Unit,
    onPlaySelected: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!live.isConnected) {
        EmptyNote(
            "Sign in on the Live TV tab first — the guide comes from your provider.",
            modifier = modifier.padding(40.dp),
        )
        return
    }

    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            delay(30_000)
        }
    }

    val scroll = rememberScrollState()
    val rows = rememberLazyListState()
    val density = LocalDensity.current
    val gridFocus = remember { FocusRequester() }

    val context = LocalContext.current
    val selectedChannel = live.channels.getOrNull(guide.channelIndex)
    val previewUrl = live.credentials?.let { credentials ->
        selectedChannel?.let { XtreamApi.streamUrl(credentials, it, live.format) }
    }

    // A second, small player for the guide preview. Kept here rather than inside the
    // preview composable so that choosing a channel can release the connection before the
    // full-screen player asks the provider for another one.
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
        delay(PREVIEW_DELAY_MS)
        preview.setMediaItem(MediaItem.fromUri(previewUrl))
        preview.prepare()
        preview.playWhenReady = true
    }
    val selectedListing = selectedChannel?.epgChannelId?.let { guide.programmes[it] }.orEmpty()
    val selectedProgramme = selectedListing.firstOrNull { it.isOnAt(guide.focusTime) }

    // Keep the cursor on screen without pinning it to the edge.
    LaunchedEffect(guide.focusTime, guide.windowStart) {
        val offset = with(density) {
            (secondsToDp(guide.focusTime - guide.windowStart).toPx() - 160.dp.toPx())
        }
        scroll.animateScrollTo(offset.coerceAtLeast(0f).roundToInt())
    }
    LaunchedEffect(guide.channelIndex) {
        rows.animateScrollToItem(guide.channelIndex.coerceAtLeast(0))
    }
    LaunchedEffect(live.channels.size) {
        if (live.channels.isNotEmpty()) runCatching { gridFocus.requestFocus() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GuideHeader(
            live = live,
            guide = guide,
            selected = selectedProgramme,
            preview = preview,
            previewEnabled = previewEnabled,
            previewFailed = previewFailed,
            onSelectCategory = onSelectCategory,
            onJumpToNow = onJumpToNow,
            onRefresh = onRefresh,
        )

        if (live.channels.isEmpty()) {
            EmptyNote("No channels in this category.")
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
                        // At the top row, let focus escape upwards to the header controls.
                        Key.DirectionUp -> if (guide.channelIndex == 0) false
                        else { onMoveChannel(-1); true }

                        Key.DirectionDown -> {
                            if (guide.channelIndex < live.channels.lastIndex) onMoveChannel(1)
                            true
                        }

                        Key.DirectionLeft -> { onMoveTime(-1); true }
                        Key.DirectionRight -> { onMoveTime(1); true }
                        Key.DirectionCenter, Key.Enter -> {
                            // Hand the connection back before asking for the real stream.
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
                TimeRuler(guide = guide, scroll = scroll)

                LazyColumn(state = rows, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(
                        items = live.channels,
                        key = { _, channel -> channel.streamId },
                    ) { index, channel ->
                        ChannelRow(
                            number = channel.number,
                            name = channel.name,
                            logo = channel.icon,
                            listing = channel.epgChannelId?.let { guide.programmes[it] }.orEmpty(),
                            guide = guide,
                            now = now,
                            isCurrentChannel = index == guide.channelIndex,
                            scroll = scroll,
                        )
                    }
                }
            }

            // The line marking this instant, which is what makes a grid read as a guide.
            val nowOffset = with(density) {
                secondsToDp(now - guide.windowStart).toPx() - scroll.value + CHANNEL_COLUMN.toPx()
            }
            if (nowOffset >= with(density) { CHANNEL_COLUMN.toPx() }) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(nowOffset.roundToInt(), 0) }
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(Accent.copy(alpha = 0.85f)),
                )
            }
        }
    }
}

@Composable
private fun GuideHeader(
    live: LiveState,
    guide: GuideState,
    selected: EpgProgramme?,
    preview: ExoPlayer,
    previewEnabled: Boolean,
    previewFailed: Boolean,
    onSelectCategory: (XtreamCategory) -> Unit,
    onJumpToNow: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = selected?.title ?: "Guide",
                    color = Parchment,
                    fontSize = 21.sp,
                    lineHeight = 27.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = selected?.let {
                        listOfNotNull(timeRange(it), it.description).joinToString("   ·   ")
                    } ?: guide.status.describe(guide.importedAt),
                    color = Muted,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TvActionButton(label = "Now", onClick = onJumpToNow)
            TvActionButton(
                label = if (guide.status is GuideStatus.Importing) "Loading…" else "Refresh guide",
                onClick = onRefresh,
            )
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(live.categories, key = { it.id }) { category ->
                TvChip(
                    label = category.name,
                    selected = live.selectedCategory?.id == category.id,
                    onClick = { onSelectCategory(category) },
                )
            }
        }
    }

    if (previewEnabled) {
        PreviewWindow(
            preview = preview,
            failed = previewFailed,
            modifier = Modifier.width(248.dp),
        )
    }
    }
}

/** A live window on the channel under the cursor, the way a set-top box guide behaves. */
@Composable
private fun PreviewWindow(
    preview: ExoPlayer,
    failed: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .border(1.dp, Line, RoundedCornerShape(8.dp)),
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
        if (failed) {
            Text(
                text = "Preview unavailable",
                color = Faint,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun TimeRuler(guide: GuideState, scroll: androidx.compose.foundation.ScrollState) {
    Row(modifier = Modifier.fillMaxWidth().height(26.dp)) {
        Spacer(modifier = Modifier.width(CHANNEL_COLUMN))
        Row(modifier = Modifier.horizontalScroll(scroll, enabled = false)) {
            var tick = guide.windowStart
            while (tick < guide.windowEnd) {
                Box(modifier = Modifier.width(secondsToDp(1_800))) {
                    Text(
                        text = clock(tick),
                        color = Faint,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
                tick += 1_800
            }
        }
    }
}

@Composable
private fun ChannelRow(
    number: Int,
    name: String,
    logo: String?,
    listing: List<EpgProgramme>,
    guide: GuideState,
    now: Long,
    isCurrentChannel: Boolean,
    scroll: androidx.compose.foundation.ScrollState,
) {
    Row(modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT)) {
        Row(
            modifier = Modifier
                .width(CHANNEL_COLUMN)
                .fillMaxHeight()
                .background(if (isCurrentChannel) SurfaceHigh else Ink)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (logo != null) {
                AsyncImage(
                    model = logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.width(38.dp).height(28.dp),
                )
            }
            Column {
                Text(
                    text = name,
                    color = if (isCurrentChannel) Parchment else Muted,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (number > 0) {
                    Text(text = "#$number", color = Faint, fontSize = 10.sp, lineHeight = 13.sp)
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxHeight()
                .horizontalScroll(scroll, enabled = false),
        ) {
            if (listing.isEmpty()) {
                ProgrammeBlock(
                    label = "No guide data",
                    width = secondsToDp(guide.windowEnd - guide.windowStart),
                    selected = false,
                    past = false,
                    placeholder = true,
                )
                return@Row
            }

            var cursor = guide.windowStart
            listing.forEach { programme ->
                val start = programme.start.coerceAtLeast(guide.windowStart)
                val stop = programme.stop.coerceAtMost(guide.windowEnd)
                if (stop <= start) return@forEach

                if (start > cursor) {
                    Spacer(modifier = Modifier.width(secondsToDp(start - cursor)))
                }
                ProgrammeBlock(
                    label = programme.title,
                    width = secondsToDp(stop - start),
                    selected = isCurrentChannel && programme.isOnAt(guide.focusTime),
                    past = programme.stop <= now,
                    placeholder = false,
                )
                cursor = stop
            }
            if (cursor < guide.windowEnd) {
                Spacer(modifier = Modifier.width(secondsToDp(guide.windowEnd - cursor)))
            }
        }
    }
}

@Composable
private fun ProgrammeBlock(
    label: String,
    width: androidx.compose.ui.unit.Dp,
    selected: Boolean,
    past: Boolean,
    placeholder: Boolean,
) {
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .padding(end = 2.dp, top = 2.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(
                when {
                    selected -> Accent.copy(alpha = 0.32f)
                    placeholder -> Color.Transparent
                    past -> SurfaceRaised.copy(alpha = 0.45f)
                    else -> SurfaceRaised
                }
            )
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) Accent else Color.Transparent,
                shape = RoundedCornerShape(5.dp),
            )
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            color = when {
                selected -> Parchment
                placeholder -> Faint
                past -> Faint
                else -> Muted
            },
            fontSize = 12.sp,
            lineHeight = 16.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun GuideStatus.describe(importedAt: Long): String = when (this) {
    is GuideStatus.Idle -> "No guide loaded yet."
    is GuideStatus.Importing ->
        if (written == 0 && scanned == 0) "Downloading the guide…"
        else "Importing… ${written.formatted()} programmes kept of ${scanned.formatted()} read"

    is GuideStatus.Ready ->
        "${count.formatted()} programmes" +
            (if (importedAt > 0) ", updated ${clock(importedAt)}" else "")

    is GuideStatus.Failed -> message
}

private fun Int.formatted(): String = toString().reversed().chunked(3).joinToString(",").reversed()

private fun secondsToDp(seconds: Long): androidx.compose.ui.unit.Dp =
    (seconds.coerceAtLeast(0) / 60f * MINUTE_WIDTH.value).dp

private val clockFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun clock(epochSeconds: Long): String = clockFormat.format(Date(epochSeconds * 1000))

private fun timeRange(programme: EpgProgramme): String =
    "${clock(programme.start)} – ${clock(programme.stop)}"
