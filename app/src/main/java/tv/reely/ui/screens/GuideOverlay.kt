package tv.reely.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import tv.reely.ui.components.GuideNowLine
import tv.reely.ui.components.GuideRow
import tv.reely.ui.components.isSelect
import tv.reely.ui.components.rememberSelectPress
import tv.reely.ui.components.GuideRuler
import tv.reely.ui.components.guideTimeRange
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.components.guideWidthFor
import tv.reely.ui.components.requestWhenReady
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Line
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Chalk
import tv.reely.xtream.EpgProgramme
import tv.reely.xtream.XtreamCategory
import tv.reely.xtream.XtreamChannel
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.focus.onFocusChanged
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Muted

/** Half an hour per press of left or right, which is how a guide is read. */
private const val TIME_STEP_SECONDS = 1_800L

/**
 * The guide, raised over a channel that keeps playing behind it.
 *
 * Nothing here is opaque: the blocks are the same translucent cards the Live TV page
 * draws, over a gradient that only deepens towards the bottom of the screen. The point
 * is to pick the next channel while still watching the current one.
 */
@Composable
fun GuideOverlay(
    channels: List<XtreamChannel>,
    programmes: Map<String, List<EpgProgramme>>,
    windowStart: Long,
    windowEnd: Long,
    playingIndex: Int,
    /** Every category the provider offers, for when the channel wanted is in another. */
    categories: List<XtreamCategory>,
    selectedCategory: XtreamCategory?,
    onSelectCategory: (XtreamCategory) -> Unit,
    /** Opened to put a channel beside the one playing rather than to change channel. */
    addMode: Boolean = false,
    /** What choosing a channel will do, said plainly: "Add" or "Replace with". */
    pickVerb: String = "Add",
    onSelect: (Int) -> Unit,
    onAddToMultiview: (XtreamChannel) -> Unit,
    canAddTile: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Categories are drawn even with nothing under them: switching to one that is still
    // loading used to take the whole guide off the screen, with no way back to the list.
    val hasCategories = categories.size > 1
    if (channels.isEmpty() && !hasCategories) return

    val density = LocalDensity.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1_000) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis() / 1_000
            delay(30_000)
        }
    }

    // The window the guide page last loaded. Coming straight into the player from
    // somewhere else it may never have been set, so fall back to the next few hours.
    val start = if (windowEnd > windowStart) windowStart else (now / TIME_STEP_SECONDS) * TIME_STEP_SECONDS
    val end = if (windowEnd > windowStart) windowEnd else start + 6 * 3_600

    var cursor by remember {
        mutableIntStateOf(playingIndex.coerceIn(0, channels.lastIndex.coerceAtLeast(0)))
    }
    // Where the cursor is: down among the channels, or up in the row of categories.
    var inCategories by remember { mutableStateOf(false) }
    // Switching category replaces the channel list under the cursor.
    LaunchedEffect(selectedCategory?.id) { cursor = 0 }
    var focusTime by remember { mutableLongStateOf(now) }
    val rows = rememberLazyListState()
    val scroll = rememberScrollState()
    val grabFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        rows.scrollToItem(cursor)
        grabFocus.requestWhenReady()
    }
    LaunchedEffect(cursor) { rows.animateScrollToItem(cursor) }
    LaunchedEffect(focusTime) {
        val target = with(density) {
            guideWidthFor(focusTime - start).toPx() - 150.dp.toPx()
        }
        scroll.animateScrollTo(target.coerceAtLeast(0f).roundToInt())
    }

    // A held OK opens this instead of switching channel, which is the only way to reach
    // multiview without giving the grid a second set of buttons.
    var menuFor by remember { mutableStateOf<XtreamChannel?>(null) }
    val press = rememberSelectPress()
    val menuFocus = remember { FocusRequester() }
    val categoryFocus = remember { FocusRequester() }

    // Focus follows the menu both ways. The grid stops being focusable while the menu is
    // up, so without handing focus back when it closes the guide would be left dead.
    LaunchedEffect(menuFor, inCategories) {
        when {
            inCategories -> categoryFocus.requestWhenReady()
            menuFor != null -> menuFocus.requestWhenReady()
            else -> grabFocus.requestWhenReady()
        }
    }

    val channel = channels.getOrNull(cursor)
    val listing = channel?.epgChannelId?.let { programmes[it] }.orEmpty()
    val onNow = listing.firstOrNull { it.isOnAt(focusTime) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(grabFocus)
            .focusable(enabled = menuFor == null && !inCategories)
            .onPreviewKeyEvent { event ->
                /*
                 * With the cursor up in the categories the chips own left, right and OK;
                 * down brings it back to the channels and up has nowhere to go. Back is
                 * left alone either way, because the player closes the guide with it.
                 */
                if (inCategories) {
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    return@onPreviewKeyEvent when (event.key) {
                        Key.DirectionDown -> { inCategories = false; true }
                        Key.DirectionUp -> true
                        else -> false
                    }
                }
                // The menu owns everything while it is up.
                if (menuFor != null) {
                    /*
                     * Except the rest of the press that opened it, and *only* that. The
                     * hold fires while the finger is still down, Compose acts on key-up,
                     * and the menu has taken focus by then — so letting go would press
                     * whatever button it focused. That one release is swallowed.
                     *
                     * Swallowing every select instead, which is what this did at first,
                     * left the menu looking alive and doing nothing: its own buttons
                     * never saw a press. awaitingRelease is the difference between the
                     * tail of the opening hold and a press somebody actually made.
                     */
                    if (event.isSelect() && press.awaitingRelease) {
                        press.handle(event, onPress = {}, onHold = {})
                        return@onPreviewKeyEvent true
                    }
                    if (event.key == Key.Back && event.type == KeyEventType.KeyDown) {
                        menuFor = null
                        return@onPreviewKeyEvent true
                    }
                    return@onPreviewKeyEvent false
                }

                if (event.isSelect()) {
                    press.handle(
                        event,
                        onPress = {
                            val channel = channels.getOrNull(cursor)
                            if (addMode && channel != null) onAddToMultiview(channel)
                            else onSelect(cursor)
                        },
                        onHold = { menuFor = channels.getOrNull(cursor) },
                    )
                    return@onPreviewKeyEvent true
                }

                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    // Off the top of the list is the row of categories, not the way out.
                    Key.DirectionUp -> {
                        when {
                            cursor > 0 -> cursor--
                            hasCategories -> inCategories = true
                            else -> onDismiss()
                        }
                        true
                    }

                    Key.DirectionDown -> {
                        if (cursor < channels.lastIndex) cursor++
                        true
                    }

                    Key.DirectionLeft -> {
                        focusTime = (focusTime - TIME_STEP_SECONDS).coerceAtLeast(start)
                        true
                    }

                    Key.DirectionRight -> {
                        focusTime = (focusTime + TIME_STEP_SECONDS).coerceAtMost(end)
                        true
                    }

                    else -> false
                }
            },
    ) {
        /*
         * Categories and channels in one stack, both on screen at once.
         *
         * These were two panels of the same size in the same corner, shown one at a time,
         * which made back mean "up a level" before it meant "close the guide". A row of
         * chips above the list is smaller, always legible, and reached by pressing up —
         * so back is free to do the one obvious thing.
         */
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Ink.copy(alpha = 0.82f),
                            Ink.copy(alpha = 0.94f),
                        )
                    )
                )
                .padding(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasCategories) {
                CategoryStrip(
                    categories = categories,
                    selected = selectedCategory,
                    active = inCategories,
                    focusRequester = categoryFocus,
                    onSelect = { category ->
                        inCategories = false
                        onSelectCategory(category)
                    },
                )
            }
            Text(
                text = when {
                    channels.isEmpty() -> "Nothing in this category"
                    addMode -> "$pickVerb — ${channel?.name.orEmpty()}"
                    else -> channel?.name.orEmpty()
                },
                color = Chalk,
                fontSize = 17.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    channels.isEmpty() -> "Pick another above"
                    addMode -> "OK · ${pickVerb.lowercase()} this channel"
                    onNow != null -> "${onNow.title}  ·  ${guideTimeRange(onNow)}"
                    hasCategories -> "OK to watch · hold OK for more · up for categories"
                    else -> "OK to watch · hold OK for more"
                },
                color = Faint,
                fontSize = 14.sp,
                lineHeight = 19.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Box(modifier = Modifier.fillMaxWidth().height(254.dp).clipToBounds()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    GuideRuler(windowStart = start, windowEnd = end, scroll = scroll)
                    LazyColumn(
                        state = rows,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        itemsIndexed(
                            items = channels,
                            key = { _, entry -> entry.streamId },
                        ) { index, entry ->
                            GuideRow(
                                channelId = entry.streamId.toString(),
                                name = entry.name,
                                logo = entry.icon,
                                listing = entry.epgChannelId?.let { programmes[it] }.orEmpty(),
                                windowStart = start,
                                windowEnd = end,
                                now = now,
                                focusTime = focusTime,
                                isCurrent = index == cursor,
                                scroll = scroll,
                                translucent = true,
                            )
                        }
                    }
                }
                GuideNowLine(windowStart = start, now = now, scroll = scroll)
            }
        }

        menuFor?.let { target ->
            ChannelMenu(
                channel = target,
                canAddTile = canAddTile,
                pickVerb = pickVerb,
                focusRequester = menuFocus,
                onWatch = {
                    menuFor = null
                    onSelect(channels.indexOfFirst { it.streamId == target.streamId })
                },
                onAdd = {
                    menuFor = null
                    onAddToMultiview(target)
                },
                onCancel = { menuFor = null },
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun ChannelMenu(
    channel: XtreamChannel,
    canAddTile: Boolean,
    pickVerb: String,
    focusRequester: FocusRequester,
    onWatch: () -> Unit,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Ink.copy(alpha = 0.97f))
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .padding(20.dp)
            // Without this the requester has nothing focusable of its own to give focus
            // to, so the request quietly did nothing and every button here was dead.
            .focusGroup()
            .focusRequester(focusRequester),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = channel.name,
            color = Chalk,
            fontSize = 16.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        TvActionButton(label = "Watch this channel", onClick = onWatch, emphasised = true)
        if (canAddTile) {
            TvActionButton(
                label = if (pickVerb == "Add") "Add beside what is playing"
                else "Replace the channel in that tile",
                onClick = onAdd,
            )
        } else {
            Text(
                text = "You can watch up to four channels at once.",
                color = Faint,
                fontSize = 14.sp,
                lineHeight = 19.sp,
            )
        }
        TvActionButton(label = "Cancel", onClick = onCancel)
    }
}


/**
 * Every category the provider offers, as a row above the channels.
 *
 * The guide only ever showed the category already open, so putting a channel from another
 * one into a split meant leaving the player, changing category and coming back — by which
 * point whatever was on had moved on. It sits above the list rather than over it so the
 * two can be read together, and dims when the cursor is down among the channels.
 */
@Composable
private fun CategoryStrip(
    categories: List<XtreamCategory>,
    selected: XtreamCategory?,
    active: Boolean,
    focusRequester: FocusRequester,
    onSelect: (XtreamCategory) -> Unit,
) {
    val list = rememberLazyListState()
    LaunchedEffect(selected?.id) {
        val start = categories.indexOfFirst { it.id == selected?.id }
        if (start > 0) runCatching { list.scrollToItem(start) }
    }

    LazyRow(
        state = list,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (active) 1f else 0.72f)
            .focusGroup()
            .focusRequester(focusRequester),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(categories, key = { it.id }) { category ->
            CategoryChip(
                label = category.name,
                selected = category.id == selected?.id,
                onClick = { onSelect(category) },
            )
        }
    }
}

@Composable
private fun CategoryChip(label: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = label,
        color = if (focused || selected) Chalk else Muted,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .clip(RoundedCornerShape(9.dp))
            .background(if (focused) Accent.copy(alpha = 0.22f) else Ink.copy(alpha = 0.6f))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = when {
                    focused -> Accent
                    selected -> Chalk.copy(alpha = 0.5f)
                    else -> Line
                },
                shape = RoundedCornerShape(9.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    )
}
