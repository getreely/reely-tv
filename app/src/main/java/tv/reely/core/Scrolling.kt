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
