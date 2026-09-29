package tv.reely.ui.screens

import tv.reely.ui.components.MenuPanel
import tv.reely.ui.components.MenuHeading
import tv.reely.ui.components.MenuItem
import tv.reely.ui.components.MenuScrim
import tv.reely.ui.components.PlayGlyph
import tv.reely.ui.components.TilesGlyph
import tv.reely.ui.components.ClockGlyph
import tv.reely.ui.components.HeartGlyph
import tv.reely.ui.components.GUIDE_ROW_HEIGHT
import tv.reely.ui.components.GUIDE_CHANNEL_COLUMN
import tv.reely.ui.components.placeholder
import tv.reely.ui.components.Shimmer
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
import tv.reely.ui.components.isSelect
import tv.reely.ui.components.requestWhenReady
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.GlassEdge
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Chalk
import tv.reely.xtream.EpgProgramme
import tv.reely.xtream.XtreamChannel
import tv.reely.ui.theme.ReelyType
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.widthIn
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
    /**
     * Whether to put the cursor in the guide once its channels are in. Not when the Live TV
     * tab was only reached by moving along the tabs: the cursor is on its way somewhere
     * else, to Settings say, and pulling it down into the guide stopped it there.
     */
    takeFocus: Boolean = true,
    /** Programmes with a reminder, marked in the grid. */
    reminders: List<tv.reely.core.Reminder> = emptyList(),
    /** A reminder for a programme still to come, set or cleared from a channel's menu. */
    onToggleReminder: (XtreamChannel, EpgProgramme) -> Unit = { _, _ -> },
    /** Channels marked as favorites, by stream id, and marking one. */
    favorites: Set<Int> = emptySet(),
    onToggleFavorite: (XtreamChannel) -> Unit = {},
) {
    val press = tv.reely.ui.components.rememberSelectPress()
    // Hold OK: the channel's menu. Watch, a favorite or not, and a reminder for what's to come.
    var menuOpen by remember { mutableStateOf(false) }
    var menuWasOpen by remember { mutableStateOf(false) }
    val menuFocus = remember { FocusRequester() }
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

    LaunchedEffect(menuOpen) {
        if (menuOpen) {
            menuWasOpen = true
            menuFocus.requestWhenReady()
        } else if (menuWasOpen) {
            // Back to the grid where it was, rather than wherever the net would put it.
            menuWasOpen = false
            gridFocus.requestWhenReady()
        }
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
        if (live.channels.isNotEmpty() && takeFocus) gridFocus.requestWhenReady()
    }

    BackHandler {
        livePlayer.stop()
        onBackToCategories()
    }

    Box(modifier = modifier.fillMaxSize()) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 12.dp),
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
            if (live.busy) GuidePlaceholder() else EmptyNote("No channels in this category.")
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
                    // The menu's own keys are the menu's. The release of the hold that opened
                    // it is swallowed, so it doesn't press the first button as well.
                    if (menuOpen) {
                        if (event.isSelect() && press.awaitingRelease) {
                            press.handle(event, onPress = {}, onHold = {})
                            return@onPreviewKeyEvent true
                        }
                        if (event.key == Key.Back && event.type == KeyEventType.KeyDown) {
                            menuOpen = false
                            return@onPreviewKeyEvent true
                        }
                        return@onPreviewKeyEvent false
                    }
                    // OK plays the channel, or the programme again; held, the channel's menu.
                    if (event.isSelect()) {
                        return@onPreviewKeyEvent press.handle(
                            event,
                            onPress = onPlaySelected,
                            onHold = { if (channel != null) menuOpen = true },
                        )
                    }
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
                            catchUpFrom = entry.catchUpFrom(now),
                            reminded = reminders.filter { it.streamId == entry.streamId }.map { it.start }.toSet(),
                            favorite = entry.streamId in favorites,
                        )
                    }
                }
            }

            GuideNowLine(windowStart = guide.windowStart, now = now, scroll = scroll)

        }
    }

    // Over the whole screen, from the right, like every other menu; see Menus.kt.
    if (menuOpen && channel != null) {
        val upcoming = selected?.takeIf { it.start > now }
        val reminded = upcoming != null &&
            reminders.any { it.streamId == channel.streamId && it.start == upcoming.start }
        MenuScrim()
        MenuPanel(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .focusGroup()
                .focusRequester(menuFocus),
        ) {
            MenuHeading(title = channel.name, subtitle = selected?.let { "${it.title}  ·  ${guideTimeRange(it)}" })
            MenuItem(
                label = "Watch",
                icon = { PlayGlyph(it, size = 20.dp) },
                onClick = {
                    menuOpen = false
                    onPlaySelected()
                },
            )
            MenuItem(
                label = if (channel.streamId in favorites) "Remove from Favorites" else "Add to Favorites",
                icon = { HeartGlyph(it, size = 20.dp) },
                onClick = {
                    menuOpen = false
                    onToggleFavorite(channel)
                },
            )
            if (upcoming != null) {
                MenuItem(
                    label = if (reminded) "Cancel the reminder" else "Remind me",
                    detail = if (reminded) upcoming.title else "${upcoming.title}, ${guideTimeRange(upcoming)}",
                    icon = { ClockGlyph(it, size = 20.dp) },
                    onClick = {
                        menuOpen = false
                        onToggleReminder(channel, upcoming)
                    },
                )
            }
        }
    }
    }
}

private fun GuideStatus.describe(): String = when (this) {
    is GuideStatus.Idle -> "No TV guide yet."
    is GuideStatus.Importing ->
        if (written == 0 && scanned == 0) "Downloading the TV guide…"
        else "Updating the TV guide…"

    is GuideStatus.Ready -> "TV guide"
    is GuideStatus.Failed -> message
}

/** The grid's rows before there are channels to fill them: a channel, then its programmes. */
@Composable
private fun GuidePlaceholder() {
    // Programmes run to different lengths, so the blocks do too.
    val rows = listOf(
        listOf(180, 250, 140, 320),
        listOf(300, 160, 220, 200),
        listOf(120, 280, 260, 180),
        listOf(240, 200, 150, 300),
        listOf(200, 140, 310, 190),
    )
    Shimmer {
        Column(
            modifier = Modifier.padding(top = 34.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            rows.forEach { widths ->
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Box(
                        modifier = Modifier
                            .width(GUIDE_CHANNEL_COLUMN)
                            .height(GUIDE_ROW_HEIGHT)
                            .placeholder(corner = 10.dp),
                    )
                    widths.forEach { width ->
                        Box(modifier = Modifier.width(width.dp).height(GUIDE_ROW_HEIGHT).placeholder(corner = 10.dp))
                    }
                }
            }
        }
    }
}
