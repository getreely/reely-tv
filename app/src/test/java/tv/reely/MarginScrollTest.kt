package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.reely.core.marginScrollDistance

/**
 * A focused card keeps room around it: its row's heading above, its lift and the safe
 * area below. On a real set the bottom of a focused card's caption fell off the screen
 * when only the card's own bounds were brought into view.
 */
class MarginScrollTest {

    // A 300 tall window, 40 kept above and 40 below.
    private fun scroll(offset: Float, size: Float) =
        marginScrollDistance(offset, size, containerSize = 300f, leadingMargin = 40f, trailingMargin = 40f)

    @Test
    fun `a card with its room already on screen does not move the page`() {
        assertEquals(0f, scroll(offset = 40f, size = 200f), 0.001f)
        assertEquals(0f, scroll(offset = 60f, size = 150f), 0.001f)
    }

    @Test
    fun `a card too near the bottom comes up far enough to keep the room below it`() {
        // Ends at 250, but 40 below it means 290 — still inside 300. Ends at 270: 10 short.
        assertEquals(10f, scroll(offset = 70f, size = 200f), 0.001f)
    }

    @Test
    fun `a card below the window comes up and keeps its room`() {
        assertEquals(240f, scroll(offset = 300f, size = 200f), 0.001f)
    }

    @Test
    fun `a card near the top comes down to show its heading`() {
        assertEquals(-30f, scroll(offset = 10f, size = 100f), 0.001f)
    }

    @Test
    fun `where the card and both margins cannot all fit, the heading stays`() {
        // 40 + 260 + 40 is more than 300: scrolling to clear the bottom would take the
        // heading off the top, so it stops with the heading at the top.
        assertEquals(60f, scroll(offset = 100f, size = 260f), 0.001f)
    }
}
