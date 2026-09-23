package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.reely.ui.screens.hasSpareCell
import tv.reely.ui.screens.tileNeighbour

/**
 * Where the remote's arrows lead in a split view. Every tile has to be reachable from
 * every other one, or a channel ends up on screen with no way to select it.
 */
class MultiViewLayoutTest {

    @Test
    fun `a single tile has nowhere to go`() {
        assertNull(tileNeighbour(1, 0, 1, 0))
        assertNull(tileNeighbour(1, 0, -1, 0))
        assertNull(tileNeighbour(1, 0, 0, 1))
        assertNull(tileNeighbour(1, 0, 0, -1))
    }

    @Test
    fun `two tiles are side by side`() {
        assertEquals(1, tileNeighbour(2, 0, 1, 0))
        assertEquals(0, tileNeighbour(2, 1, -1, 0))
        // Nothing above or below, and nothing past either edge.
        assertNull(tileNeighbour(2, 0, -1, 0))
        assertNull(tileNeighbour(2, 1, 1, 0))
        assertNull(tileNeighbour(2, 0, 0, 1))
        assertNull(tileNeighbour(2, 1, 0, -1))
    }

    @Test
    fun `three tiles put one on the left and stack two on the right`() {
        assertEquals(1, tileNeighbour(3, 0, 1, 0))
        assertEquals(0, tileNeighbour(3, 1, -1, 0))
        assertEquals(0, tileNeighbour(3, 2, -1, 0))
        assertEquals(2, tileNeighbour(3, 1, 0, 1))
        assertEquals(1, tileNeighbour(3, 2, 0, -1))
        // The tall tile on the left has no one above or below it.
        assertNull(tileNeighbour(3, 0, 0, 1))
        assertNull(tileNeighbour(3, 0, 0, -1))
    }

    @Test
    fun `four tiles make a square`() {
        assertEquals(1, tileNeighbour(4, 0, 1, 0))
        assertEquals(3, tileNeighbour(4, 2, 1, 0))
        assertEquals(0, tileNeighbour(4, 1, -1, 0))
        assertEquals(2, tileNeighbour(4, 3, -1, 0))
        assertEquals(2, tileNeighbour(4, 0, 0, 1))
        assertEquals(3, tileNeighbour(4, 1, 0, 1))
        assertEquals(0, tileNeighbour(4, 2, 0, -1))
        assertEquals(1, tileNeighbour(4, 3, 0, -1))
    }

    @Test
    fun `the edges of a square are edges`() {
        assertNull(tileNeighbour(4, 0, -1, 0))
        assertNull(tileNeighbour(4, 0, 0, -1))
        assertNull(tileNeighbour(4, 3, 1, 0))
        assertNull(tileNeighbour(4, 3, 0, 1))
    }

    @Test
    fun `every tile is reachable from every other one`() {
        for (slots in 2..4) {
            for (from in 0 until slots) {
                val reached = reachableFrom(slots, from)
                assertEquals(
                    "with $slots tiles, starting at $from",
                    (0 until slots).toSet(),
                    reached,
                )
            }
        }
    }

    private fun reachableFrom(slots: Int, start: Int): Set<Int> {
        val seen = mutableSetOf(start)
        val queue = ArrayDeque(listOf(start))
        val directions = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        while (queue.isNotEmpty()) {
            val here = queue.removeFirst()
            for ((dx, dy) in directions) {
                val next = tileNeighbour(slots, here, dx, dy) ?: continue
                if (seen.add(next)) queue.addLast(next)
            }
        }
        return seen
    }

    @Test
    fun `a direction never leads outside the grid`() {
        for (slots in 1..4) {
            for (from in 0 until slots) {
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val next = tileNeighbour(slots, from, dx, dy) ?: continue
                    assert(next in 0 until slots) { "$slots tiles: $from -> $next" }
                    assert(next != from) { "$slots tiles: $from led back to itself" }
                }
            }
        }
    }

    @Test
    fun `a count the layout does not know about moves nowhere`() {
        // Nothing should ever ask for five, but asking must not land focus on a tile that
        // is not drawn.
        assertNull(tileNeighbour(5, 0, 1, 0))
        assertNull(tileNeighbour(0, 0, 1, 0))
    }

    /*
     * The side-by-side layout looked like it stopped at two channels. The spare cell was
     * offered only by the grid and only with exactly three up, and the transport's own
     * Add button disappears the moment there is more than one channel — so beyond the
     * second there was nothing to press but a hold nobody would guess at.
     */
    @Test
    fun `the focus layout offers a spare from the second channel on`() {
        assertFalse(hasSpareCell(tileCount = 1, focusLayout = true))
        assertTrue(hasSpareCell(tileCount = 2, focusLayout = true))
        assertTrue(hasSpareCell(tileCount = 3, focusLayout = true))
        assertFalse(hasSpareCell(tileCount = 4, focusLayout = true))
    }

    /** The grid waits, because showing one there halves a stream that is playing. */
    @Test
    fun `the grid offers a spare only with three up`() {
        assertFalse(hasSpareCell(tileCount = 1, focusLayout = false))
        assertFalse(hasSpareCell(tileCount = 2, focusLayout = false))
        assertTrue(hasSpareCell(tileCount = 3, focusLayout = false))
        assertFalse(hasSpareCell(tileCount = 4, focusLayout = false))
    }
}
