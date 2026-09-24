package tv.reely.core

import kotlin.math.abs

/**
 * How far to scroll to put something on screen, and no further.
 *
 * Compose picks a different rule on a television: it checks for the leanback feature and
 * uses a pivot, which holds whatever has focus three tenths of the way down the viewport
 * and therefore scrolls on *every* move of the cursor, including moves between things
 * already in plain sight. On a row of cards that reads as the page sliding about under
 * the remote, and on a page with a title and a summary above the row it pushed both off
 * the top.
 *
 * This is the ordinary rule instead: nothing to do when it already fits, otherwise the
 * shortest distance that brings the nearer edge in.
 *
 * All three arguments are relative to the scrolling container: [offset] is the leading
 * edge of the thing wanting to be seen, [size] its length along the scroll axis, and
 * [containerSize] the length of the window it has to fit in.
 */
fun minimumScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
    val leading = offset
    val trailing = offset + size

    // Already in view, so there is nothing worth moving for.
    if (leading >= 0f && trailing <= containerSize) return 0f

    // Longer than the window can hold and already spanning it: any move would take one
    // edge off to bring the other on, which is not an improvement.
    if (leading < 0f && trailing > containerSize) return 0f

    // Off one end: close the smaller of the two gaps.
    return if (abs(leading) < abs(trailing - containerSize)) leading
    else trailing - containerSize
}

/**
 * The same, with room kept either side of the thing being brought into view.
 *
 * A focused card is drawn larger than its bounds — lifted, ringed, glowing — so bringing
 * exactly its bounds on screen left the lift and the ring off the edge, and on a real
 * set the bottom of the caption with them. [leadingMargin] is kept clear before it (a
 * row's heading, say) and [trailingMargin] after it (the lift, then the television's
 * safe area).
 *
 * Scrolling towards the far end goes only as far as it has to, and never so far that the
 * leading margin is lost: where both cannot fit, the heading wins.
 */
fun marginScrollDistance(
    offset: Float,
    size: Float,
    containerSize: Float,
    leadingMargin: Float,
    trailingMargin: Float,
): Float {
    val leading = offset - leadingMargin
    val trailing = offset + size + trailingMargin - containerSize
    return when {
        leading < 0f -> leading
        trailing > 0f -> minOf(trailing, leading)
        else -> 0f
    }
}
