package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.reely.core.cycleWithOff
import tv.reely.core.wrapIndex

class IndexingTest {

    @Test
    fun `channel surfing wraps in both directions`() {
        assertEquals(1, wrapIndex(1, 5))
        assertEquals(0, wrapIndex(5, 5))
        assertEquals(4, wrapIndex(-1, 5))
        assertEquals(3, wrapIndex(-2, 5))
        assertEquals(0, wrapIndex(10, 5))
    }

    @Test
    fun `an empty channel list cannot be surfed`() {
        assertEquals(0, wrapIndex(3, 0))
        assertEquals(0, wrapIndex(-3, 0))
    }

    @Test
    fun `subtitle cycling treats off as a position`() {
        // Two tracks: off, 0, 1, then back to off.
        assertEquals(0, cycleWithOff(0, 2))
        assertEquals(1, cycleWithOff(1, 2))
        assertEquals(-1, cycleWithOff(2, 2))
        assertEquals(-1, cycleWithOff(-1, 2))
        // Stepping back from off lands on the last track.
        assertEquals(1, cycleWithOff(-2, 2))
    }

    @Test
    fun `with no subtitle tracks the only choice is off`() {
        assertEquals(-1, cycleWithOff(0, 0))
        assertEquals(-1, cycleWithOff(-1, 0))
        assertEquals(-1, cycleWithOff(7, 0))
    }
}
