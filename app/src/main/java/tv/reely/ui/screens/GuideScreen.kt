package tv.reely.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
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
import tv.reely.core.LivePlayer
import tv.reely.ui.GuideState
import tv.reely.ui.GuideStatus
import tv.reely.ui.LiveState
import tv.reely.ui.components.EmptyNote
import tv.reely.ui.components.GuideNowLine
import tv.reely.ui.components.GuideRow
import tv.reely.ui.components.GuideRuler
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.components.glass
import tv.reely.ui.components.guideTimeRange
import tv.reely.ui.components.guideWidthFor
import tv.reely.ui.components.requestWhenReady
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.GlassEdge
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Chalk
import tv.reely.xtream.EpgProgramme
import tv.reely.xtream.XtreamApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

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
    livePlayer: LivePlayer,
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

    // The same player the full-screen view uses, so choosing this channel is a change of
    // where the picture is drawn rather than a new connection to the provider.
    val preview = livePlayer.player
    var previewFailed by remember { mutableStateOf(false) }

    DisposableEffect(preview) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                previewFailed = true
            }
        }
        preview.addListener(listener)
        onDispose { preview.removeListener(listener) }
    }

    LaunchedEffect(previewUrl, previewEnabled) {
        previewFailed = false
        if (!previewEnabled || previewUrl == null) {
            livePlayer.stop()
            return@LaunchedEffect
        }
        if (livePlayer.isShowing(previewUrl)) return@LaunchedEffect
        livePlayer.stop()
        // Long enough that walking past channels does not open a connection for each.
        delay(PREVIEW_DELAY_MS)
        livePlayer.play(previewUrl)
    }

    LaunchedEffect(guide.focusTime, guide.windowStart) {
        val target = with(density) {
            guideWidthFor(guide.focusTime - guide.windowStart).toPx() - 150.dp.toPx()
        }
        scroll.animateScrollTo(target.coerceAtLeast(0f).roundToInt())
    }
    LaunchedEffect(guide.channelIndex) {
        rows.animateScrollToItem(guide.channelIndex.coerceAtLeast(0))
    }
    LaunchedEffect(live.channels.size) {
        if (live.channels.isNotEmpty()) gridFocus.requestWhenReady()
    }

    BackHandler {
        livePlayer.stop()
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
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    letterSpacing = 1.4.sp,
                    maxLines = 1,
                )
                Text(
                    text = selected?.title ?: channel?.name ?: "Guide",
                    color = Chalk,
                    fontSize = 24.sp,
                    lineHeight = 30.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = selected?.let { "${channel?.name}  ·  ${guideTimeRange(it)}" }
                        ?: guide.status.describe(),
                    color = Muted,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                selected?.description?.let {
                    Text(
                        text = it,
                        color = Muted,
                        fontSize = 14.sp,
                        lineHeight = 19.sp,
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
                        Text(text = "Preview unavailable", color = Faint, fontSize = 14.sp, lineHeight = 19.sp)
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
                            // The stream stays exactly as it is; only the view changes.
                            onPlaySelected()
                            true
                        }

                        else -> false
                    }
                },
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                GuideRuler(
                    windowStart = guide.windowStart,
                    windowEnd = guide.windowEnd,
                    scroll = scroll,
                )

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
                            windowStart = guide.windowStart,
                            windowEnd = guide.windowEnd,
                            now = now,
                            focusTime = guide.focusTime,
                            isCurrent = index == guide.channelIndex,
                            scroll = scroll,
                        )
                    }
                }
            }

            GuideNowLine(windowStart = guide.windowStart, now = now, scroll = scroll)
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
