package tv.reely.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screensaver waits while anything plays: the player, and live TV in the guide's preview. */
class ScreensaverRuleTest {
    @Test fun `comes up over a quiet screen`() {
        assertTrue(screensaverMayCome(minutes = 5, playerOpen = false, livePlaying = false))
    }

    @Test fun `not while the guide's preview is playing`() {
        assertFalse(screensaverMayCome(minutes = 5, playerOpen = false, livePlaying = true))
    }

    @Test fun `not while the player is open, paused or not`() {
        assertFalse(screensaverMayCome(minutes = 5, playerOpen = true, livePlaying = false))
    }

    @Test fun `not when switched off`() {
        assertFalse(screensaverMayCome(minutes = 0, playerOpen = false, livePlaying = false))
    }
}
