package tv.reely.ui.touch

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import tv.reely.plex.PlexItem
import tv.reely.ui.screens.episodeLine
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Geist
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.OnAccent
import tv.reely.ui.theme.SurfaceRaised

/*
 * The frame around the phone's pages, as the iPhone app has it: the tabs in a capsule
 * floating over the bottom, round frosted buttons in the top corner, a large title, and
 * Home opening on a full-width picture of what's on.
 */

/** Room left at the bottom of a page for the floating tabs, so its end scrolls clear of them. */
internal val LocalBarSpace = compositionLocalOf { 0.dp }

@Composable
internal fun barSpace(): Dp = LocalBarSpace.current

/** How much the floating tabs take up, with the gap under them. */
internal val FLOATING_BAR_SPACE = 84.dp

/** Frosted, as near as a phone without a blur behind gets: dark, a little see-through, edged in light. */
private val Glass = SurfaceRaised.copy(alpha = 0.9f)
private val GlassEdge = Color.White.copy(alpha = 0.1f)

/**
 * The tabs: an icon each in a floating capsule, and the one showing in a pill of the
 * accent color with its name beside it. Pressed again, a tab goes back to its top.
 */
@Composable
internal fun TouchFloatingBar(selected: TouchTab?, onSelect: (TouchTab) -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    Row(
        modifier = modifier
            .shadow(18.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CircleShape)
            .background(Glass)
            .border(0.5.dp, GlassEdge, CircleShape)
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TouchTab.entries.forEach { tab ->
            val on = tab == selected
            val fill by animateColorAsState(if (on) Accent else Color.Transparent, label = "tab")
            val ink by animateColorAsState(if (on) OnAccent else Chalk.copy(alpha = 0.62f), label = "tab-ink")
            Row(
                modifier = Modifier
                    .height(46.dp)
                    .clip(CircleShape)
                    .background(fill)
                    .clickable(role = Role.Tab) {
                        if (!on) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(tab)
                    }
                    .semantics { contentDescription = tab.label; this.selected = on }
                    .animateContentSize(spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow))
                    .padding(horizontal = if (on) 16.dp else 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                when (tab) {
                    TouchTab.HOME -> HomeTabGlyph(ink, 20.dp)
                    TouchTab.MOVIES -> FilmTabGlyph(ink, 20.dp)
                    TouchTab.SHOWS -> ShowTabGlyph(ink, 20.dp)
                    TouchTab.LIVE -> LiveTabGlyph(ink, 20.dp)
                    TouchTab.REQUESTS -> RequestTabGlyph(ink, 20.dp)
                }
                if (on) Text(tab.label, color = ink, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}

/** A round frosted button, for the top corners. */
@Composable
internal fun GlassCircle(onClick: (() -> Unit)?, description: String, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Glass)
            .border(0.5.dp, GlassEdge, CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Search, Settings and who's watching, together in a frosted capsule in the top corner. */
@Composable
internal fun GlassActions(actions: TouchActions) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(Glass)
            .border(0.5.dp, GlassEdge, CircleShape)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CornerButton("Search", actions.search) { tv.reely.ui.components.SearchGlyph(Chalk, 20.dp) }
        CornerButton("Settings", actions.settings) { tv.reely.ui.components.GearGlyph(Chalk, 20.dp) }
        actions.profile?.let { onProfile ->
            CornerButton("Switch profile", onProfile) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(Chalk), contentAlignment = Alignment.Center) {
                    Text(actions.initial ?: "?", color = Ink, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun CornerButton(description: String, onClick: () -> Unit, glyph: @Composable () -> Unit) {
    Box(
        modifier = Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { glyph() }
}

/** The mark, in its own frosted circle: Home's top corner. */
@Composable
internal fun MarkCircle() {
    GlassCircle(onClick = null, description = "Reely") {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(tv.reely.R.drawable.ic_mark),
            contentDescription = null,
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Accent),
            modifier = Modifier.height(24.dp),
        )
    }
}

/** Which titles Home's picture shows, as the iPhone picks them: what's on, then what's new. */
internal fun heroItems(continueWatching: List<PlexItem>, movies: List<PlexItem>, episodes: List<PlexItem>): List<PlexItem> {
    val seen = mutableSetOf<String>()
    return (continueWatching.take(3) + movies.take(3) + episodes.take(2))
        .filter { it.art != null && seen.add(it.listKey) }
        .take(6)
}

/**
 * The top of Home: a title at a time, swiped across, its picture the full width of the
 * phone, with its logo, what it is, how far in, and Play and Details. [corner] goes over
 * the top of it: the mark and the frosted buttons.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TouchHero(
    items: List<PlexItem>,
    actions: TouchActions,
    onPlay: (PlexItem) -> Unit,
    corner: @Composable () -> Unit,
) {
    val height = (LocalConfiguration.current.screenHeightDp * 0.62f).dp.coerceIn(420.dp, 600.dp)
    val pager = rememberPagerState { items.size }
    Box(Modifier.fillMaxWidth().height(height)) {
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
            HeroSlide(items[page], actions, onPlay)
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = TouchMargin, vertical = 8.dp)) { corner() }
        if (items.size > 1) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                repeat(items.size) { i ->
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (i == pager.currentPage) Chalk else Chalk.copy(alpha = 0.35f)))
                }
            }
        }
    }
}

@Composable
private fun HeroSlide(item: PlexItem, actions: TouchActions, onPlay: (PlexItem) -> Unit) {
    Box(Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { actions.open(item) }) {
        AsyncImage(
            model = actions.image(item.serverBase, item.art ?: item.thumb, 1280, 720),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Ink.copy(alpha = 0.45f), 0.22f to Color.Transparent, 0.42f to Color.Transparent,
                    0.76f to Ink.copy(alpha = 0.86f), 1f to Ink,
                ),
            ),
        )
        Column(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val logo = actions.logo(item.serverBase, item.logo)
            if (logo != null) {
                AsyncImage(model = logo, contentDescription = item.rowTitle, contentScale = ContentScale.Fit, modifier = Modifier.widthIn(max = 260.dp).heightIn(max = 90.dp))
            } else {
                Text(
                    item.rowTitle, color = Chalk, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 36.sp,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            val line = (listOfNotNull(episodeLine(item)) + item.qualities.take(2)).joinToString("  ·  ")
            if (line.isNotEmpty()) Text(line, color = Chalk.copy(alpha = 0.75f), fontFamily = Geist, fontWeight = FontWeight.Medium, fontSize = 13.sp, maxLines = 1)
            item.resumeFraction?.takeIf { it > 0f }?.let { progress ->
                Box(Modifier.width(120.dp).height(4.dp).clip(CircleShape).background(Chalk.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).height(4.dp).clip(CircleShape).background(Accent))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeroButton(if (item.viewOffsetMs > 0) "Resume" else "Play", filled = true, onClick = { onPlay(item) }) {
                    tv.reely.ui.components.PlayGlyph(Ink, 14.dp)
                }
                HeroButton("Details", filled = false, onClick = { actions.open(item) }) {
                    tv.reely.ui.components.InfoGlyph(Chalk, 18.dp)
                }
            }
        }
    }
}

@Composable
private fun HeroButton(label: String, filled: Boolean, onClick: () -> Unit, glyph: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .width(148.dp)
            .height(46.dp)
            .clip(CircleShape)
            .background(if (filled) Chalk else Glass)
            .then(if (filled) Modifier else Modifier.border(0.5.dp, GlassEdge, CircleShape))
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        glyph()
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (filled) Ink else Chalk, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

/** A tab's large title, under the corner buttons, as iOS sets them. */
@Composable
internal fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = Chalk, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp, maxLines = 1,
        overflow = TextOverflow.Ellipsis, modifier = modifier.padding(start = TouchMargin, end = TouchMargin, top = 4.dp, bottom = 2.dp),
    )
}

/** Choices side by side in one capsule, the chosen one lit: iOS's segmented control. */
@Composable
internal fun TouchSegments(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().height(36.dp).clip(CircleShape).background(SurfaceRaised).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            val fill by animateColorAsState(if (on) Chalk.copy(alpha = 0.22f) else Color.Transparent, label = "segment")
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(fill)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .semantics { this.selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = Chalk, fontFamily = Geist, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, fontSize = 14.sp, maxLines = 1)
            }
        }
    }
}

/** A small downward chevron: there's a list behind this. */
@Composable
internal fun Chevron(color: Color, size: Dp = 12.dp) = androidx.compose.foundation.Canvas(Modifier.size(size)) {
    val w = this.size.width
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(w * 0.15f, w * 0.35f); lineTo(w * 0.5f, w * 0.7f); lineTo(w * 0.85f, w * 0.35f)
    }
    drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = w * 0.16f, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}
