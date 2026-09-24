package tv.reely.ui.components

import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.reely.core.marginScrollDistance
import tv.reely.ui.theme.ReelyType

/** Below a focused card: its lift, then the television's safe area. */
val ROWS_BOTTOM = 40.dp

/**
 * For a page of rows, each a heading over a line of cards: the row with focus snaps its
 * heading to the top of the page. One row at a time, whole, with nothing half-shown above
 * it. [headingGap] is the space between a row's heading and its cards.
 *
 * The television's own rule is a pivot, which left a lower row flush with the bottom of
 * the screen and the focused card's caption off it, with a sliver of the row before
 * still showing at the top.
 */
@Composable
fun rememberRowSnap(headingGap: Dp): BringIntoViewSpec {
    val density = LocalDensity.current
    val head = with(density) { ReelyType.RowTitle.lineHeight.toPx() + headingGap.toPx() }
    return remember(head) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                offset - head
        }
    }
}

/**
 * For a grid: scroll only as far as it takes to show the focused card with room around
 * it — [above] for its lift and whatever heads it, [below] for its lift and the safe area.
 */
@Composable
fun rememberMarginScroll(above: Dp, below: Dp = ROWS_BOTTOM): BringIntoViewSpec {
    val density = LocalDensity.current
    val lead = with(density) { above.toPx() }
    val trail = with(density) { below.toPx() }
    return remember(lead, trail) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
                marginScrollDistance(offset, size, containerSize, lead, trail)
        }
    }
}

/**
 * Widens a row by [margin] each side, out over the page's own margins, so the row can
 * scroll to the screen's edges and a focused card at either end has room for its lift
 * and ring instead of being clipped by the margin. Give the row the same [margin] as
 * content padding and its cards still start where the page's content does.
 */
fun Modifier.bleed(margin: Dp): Modifier = layout { measurable, constraints ->
    val extra = margin.roundToPx()
    val wide = constraints.copy(
        minWidth = constraints.minWidth + extra * 2,
        maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + extra * 2 else constraints.maxWidth,
    )
    val placeable = measurable.measure(wide)
    layout((placeable.width - extra * 2).coerceAtLeast(0), placeable.height) {
        placeable.place(-extra, 0)
    }
}
