package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.reely.core.minimumScrollDistance

/**
 * Compose uses a pivot rule on televisions, which keeps whatever has focus three tenths
 * down the viewport and so scrolls even when the thing is already in plain sight. These
 * pin the ordinary rule: move only when something would otherwise be off screen.
 */
class ScrollingTest {

    private fun scroll(offset: Float, size: Float, container: Float = 100f) =
        minimumScrollDistance(offset, size, container)

    @Test
    fun `something already in view does not move the page`() {
        assertEquals(0f, scroll(offset = 10f, size = 30f), 0.001f)
        assertEquals(0f, scroll(offset = 0f, size = 100f), 0.001f)
        assertEquals(0f, scroll(offset = 70f, size = 30f), 0.001f)
    }

    @Test
    fun `something off the top comes down by exactly the overlap`() {
        assertEquals(-20f, scroll(offset = -20f, size = 30f), 0.001f)
    }

    @Test
    fun `something off the bottom comes up by exactly the overlap`() {
        // Ends at 130 in a window of 100, so 30 of scrolling brings it in.
        assertEquals(30f, scroll(offset = 100f, size = 30f), 0.001f)
    }

    @Test
    fun `something too tall but starting in view is pulled up to the top`() {
        // Starts 10 in and runs well past the bottom. Bringing the top edge to the top is
        // the shorter move, and shows the beginning of it rather than the end.
        assertEquals(10f, scroll(offset = 10f, size = 200f, container = 100f), 0.001f)
    }

    @Test
    fun `something hanging off both ends is left alone`() {
        // Any move here trades one edge for the other, which is not an improvement — the
        // case the assertion above used to get wrong.
        assertEquals(0f, scroll(offset = -5f, size = 130f, container = 100f), 0.001f)
    }

    @Test
    fun `something taller than the window is left where it is`() {
        // Already covering the whole window. Moving either way trades one edge for the
        // other, so the page stays put.
        assertEquals(0f, scroll(offset = -50f, size = 300f), 0.001f)
    }

    @Test
    fun `an item exactly filling the window does not move`() {
        assertEquals(0f, scroll(offset = 0f, size = 100f), 0.001f)
    }

    @Test
    fun `touching an edge counts as in view`() {
        assertEquals(0f, scroll(offset = 0f, size = 40f), 0.001f)
        assertEquals(0f, scroll(offset = 60f, size = 40f), 0.001f)
    }
}
