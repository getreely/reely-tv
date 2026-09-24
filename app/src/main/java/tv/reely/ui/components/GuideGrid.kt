package tv.reely.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Chalk
import tv.reely.xtream.EpgProgramme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The schedule grid, shared by the Live TV page and the overlay the player raises over a
 * playing channel. Both draw the same thing against the same timeline, so it lives here
 * rather than being written twice and drifting.
 */

/** A minute of schedule, in width. Every measurement in the grid derives from this. */
private val MINUTE_WIDTH = 5.dp

val GUIDE_CHANNEL_COLUMN = 132.dp
val GUIDE_ROW_HEIGHT = 78.dp

/** The spacing between blocks in a row, which the label maths has to account for. */
private val CARD_GAP = 5.dp

/** How much of a block stays reserved for its label before it is pushed off the end. */
private val LABEL_MIN_WIDTH = 130.dp

fun guideWidthFor(seconds: Long): Dp =
    (seconds.coerceAtLeast(0) / 60f * MINUTE_WIDTH.value).dp

private val clockFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

fun guideClock(epochSeconds: Long): String = clockFormat.format(Date(epochSeconds * 1000))

fun guideTimeRange(programme: EpgProgramme): String =
    "${guideClock(programme.start)} – ${guideClock(programme.stop)}"

/** Half-hour ticks along the top, scrolling in step with the rows beneath them. */
@Composable
fun GuideRuler(
    windowStart: Long,
    windowEnd: Long,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.fillMaxWidth().height(22.dp)) {
        Spacer(modifier = Modifier.width(GUIDE_CHANNEL_COLUMN))
        Row(modifier = Modifier.horizontalScroll(scroll, enabled = false)) {
            var tick = windowStart
            while (tick < windowEnd) {
                Box(modifier = Modifier.width(guideWidthFor(1_800))) {
                    Text(
                        text = guideClock(tick),
                        color = Faint,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                tick += 1_800
            }
        }
    }
}

/** One channel and everything it is showing across the window. */
@Composable
fun GuideRow(
    channelId: String,
    name: String,
    logo: String?,
    listing: List<EpgProgramme>,
    windowStart: Long,
    windowEnd: Long,
    now: Long,
    focusTime: Long,
    isCurrent: Boolean,
    scroll: ScrollState,
    modifier: Modifier = Modifier,
    /** Over a playing channel the tiles are lightened so the picture reads through. */
    translucent: Boolean = false,
) {
    Row(modifier = modifier.fillMaxWidth().height(GUIDE_ROW_HEIGHT)) {
        Box(
            modifier = Modifier
                .width(GUIDE_CHANNEL_COLUMN - 6.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(14.dp))
                .background(
                    channelTint(channelId).copy(
                        alpha = when {
                            isCurrent && translucent -> 0.92f
                            isCurrent -> 0.95f
                            translucent -> 0.7f
                            else -> 0.7f
                        }
                    )
                )
                .border(
                    width = if (isCurrent) 2.dp else 0.dp,
                    color = if (isCurrent) Chalk else Color.Transparent,
                    shape = RoundedCornerShape(14.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (logo != null) {
                AsyncImage(
                    model = logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(54.dp).clip(RoundedCornerShape(8.dp)),
                )
            } else {
                Text(
                    text = name.take(3).uppercase(),
                    color = Chalk,
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(modifier = Modifier.width(6.dp))

        Row(
            modifier = Modifier.fillMaxHeight().horizontalScroll(scroll, enabled = false),
            horizontalArrangement = Arrangement.spacedBy(CARD_GAP),
        ) {
            if (listing.isEmpty()) {
                ProgrammeCard(
                    title = "No guide data",
                    slot = null,
                    width = guideWidthFor(windowEnd - windowStart),
                    startsAt = 0.dp,
                    scroll = scroll,
                    selected = false,
                    past = true,
                )
                return@Row
            }

            // Each block's own left edge inside the scrolling row, so a block that began
            // before the window can still work out where its label should sit.
            var nextEdge = 0.dp
            var anyPlaced = false
            fun place(childWidth: Dp): Dp {
                if (anyPlaced) nextEdge += CARD_GAP
                anyPlaced = true
                val edge = nextEdge
                nextEdge += childWidth
                return edge
            }

            var cursor = windowStart
            listing.forEach { programme ->
                val start = programme.start.coerceAtLeast(windowStart)
                val stop = programme.stop.coerceAtMost(windowEnd)
                if (stop <= start) return@forEach
                if (start > cursor) {
                    val gap = guideWidthFor(start - cursor)
                    place(gap)
                    Spacer(modifier = Modifier.width(gap))
                }
                val cardWidth = guideWidthFor(stop - start)
                ProgrammeCard(
                    title = programme.title,
                    slot = guideTimeRange(programme),
                    width = cardWidth,
                    startsAt = place(cardWidth),
                    scroll = scroll,
                    selected = isCurrent && programme.isOnAt(focusTime),
                    past = programme.stop <= now,
                )
                cursor = stop
            }
            if (cursor < windowEnd) {
                Spacer(modifier = Modifier.width(guideWidthFor(windowEnd - cursor)))
            }
        }
    }
}

/**
 * A block carries its own time, so nobody has to count along the ruler.
 *
 * A three-hour programme with twenty minutes left begins far to the left of the window,
 * and its label went with it: the block on screen was a blank stripe that said nothing
 * about what was on. The label now rides the viewport's left edge while the block is
 * still under it, and is let go once the block's own start scrolls into view.
 */
@Composable
private fun ProgrammeCard(
    title: String,
    slot: String?,
    width: Dp,
    startsAt: Dp,
    scroll: ScrollState,
    selected: Boolean,
    past: Boolean,
) {
    Box(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    selected -> Accent.copy(alpha = 0.3f)
                    past -> Chalk.copy(alpha = 0.05f)
                    else -> Chalk.copy(alpha = 0.1f)
                }
            )
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = if (selected) Accent else Color.Transparent,
                shape = RoundedCornerShape(14.dp),
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(
            modifier = Modifier
                // Read in the layout pass, so scrolling the guide re-places the label
                // without recomposing every block in it.
                .offset {
                    val slack = (width.roundToPx() - LABEL_MIN_WIDTH.roundToPx()).coerceAtLeast(0)
                    IntOffset((scroll.value - startsAt.roundToPx()).coerceIn(0, slack), 0)
                }
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                color = if (past && !selected) Chalk.copy(alpha = 0.55f) else Chalk,
                fontSize = 15.sp,
                lineHeight = 19.sp,
                fontWeight = if (past && !selected) FontWeight.Normal else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (slot != null) {
                Text(
                    text = slot,
                    color = Chalk.copy(alpha = 0.45f),
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The line marking this instant, with the dot that makes a grid read as a guide. */
@Composable
fun GuideNowLine(windowStart: Long, now: Long, scroll: ScrollState) {
    val density = LocalDensity.current
    val nowX = with(density) {
        guideWidthFor(now - windowStart).toPx() - scroll.value + GUIDE_CHANNEL_COLUMN.toPx()
    }
    if (nowX < with(density) { GUIDE_CHANNEL_COLUMN.toPx() }) return

    Box(
        modifier = Modifier
            .offset { IntOffset(nowX.roundToInt(), 0) }
            .width(2.dp)
            .fillMaxHeight()
            .background(Chalk.copy(alpha = 0.8f)),
    )
    Box(
        modifier = Modifier
            .offset { IntOffset((nowX - with(density) { 4.dp.toPx() }).roundToInt(), 0) }
            .size(10.dp)
            .clip(RoundedCornerShape(50))
            .background(Chalk),
    )
}
