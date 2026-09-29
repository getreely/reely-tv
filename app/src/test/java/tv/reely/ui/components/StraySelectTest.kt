package tv.reely.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Letting go of a held OK must not press whatever the hold opened. */
class StraySelectTest {
    @Test
    fun `the rest of the hold that opened the menu is thrown away`() {
        val stray = StraySelect()
        // The finger is still down: the remote keeps repeating.
        assertTrue(stray.down(repeatCount = 2))
        assertTrue(stray.down(repeatCount = 3))
        assertTrue(stray.up())
    }

    @Test
    fun `a press of the menu's own goes through`() {
        val stray = StraySelect()
        assertTrue(stray.up())
        assertFalse(stray.down(repeatCount = 0))
        assertFalse(stray.up())
    }
}
