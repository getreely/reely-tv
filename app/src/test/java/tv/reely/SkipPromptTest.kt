package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.reely.core.SkipPrompt
import tv.reely.core.skipPromptAt

/**
 * When the skip prompt is on screen. It was computed and thrown away for several
 * releases — the markers were fetched on every episode and the button never drawn — so
 * the point of these is that the thing is wired up as well as correct at the edges.
 */
class SkipPromptTest {

    private fun promptAt(
        positionMs: Long,
        intro: LongRange? = 30_000L..90_000L,
        creditsStartMs: Long? = 1_200_000L,
        canSkipForward: Boolean = true,
        upNextShowing: Boolean = false,
    ) = skipPromptAt(
        positionMs = positionMs,
        introStartMs = intro?.first,
        introEndMs = intro?.last,
        creditsStartMs = creditsStartMs,
        canSkipForward = canSkipForward,
        upNextShowing = upNextShowing,
    )

    @Test
    fun `nothing before the intro starts`() {
        assertNull(promptAt(29_999))
    }

    @Test
    fun `the intro offers a skip from its first frame`() {
        assertEquals(SkipPrompt.INTRO, promptAt(30_000))
        assertEquals(SkipPrompt.INTRO, promptAt(60_000))
    }

    /** The grace at the end, so the button is not pulled out from under a thumb. */
    @Test
    fun `the intro stops offering half a second before it ends`() {
        assertEquals(SkipPrompt.INTRO, promptAt(89_499))
        assertNull(promptAt(89_500))
        assertNull(promptAt(90_000))
    }

    @Test
    fun `the credits offer the next episode`() {
        assertNull(promptAt(1_199_999))
        assertEquals(SkipPrompt.NEXT_EPISODE, promptAt(1_200_000))
    }

    /** A film, or the last episode of a season: there is nowhere to go. */
    @Test
    fun `no next episode means no prompt in the credits`() {
        assertNull(promptAt(1_300_000, canSkipForward = false))
    }

    /** The Up Next card already offers this, a few pixels away. One is enough. */
    @Test
    fun `the up next card takes precedence in the credits`() {
        assertNull(promptAt(1_300_000, upNextShowing = true))
    }

    /** Up Next does not suppress a skip intro — they are never up together anyway. */
    @Test
    fun `the up next card does not suppress the intro`() {
        assertEquals(SkipPrompt.INTRO, promptAt(60_000, upNextShowing = true))
    }

    @Test
    fun `a server without marker detection offers nothing`() {
        assertNull(promptAt(60_000, intro = null, creditsStartMs = null))
    }

    /** An intro that runs under the grace period never offers, rather than flickering. */
    @Test
    fun `an intro shorter than the grace period is not offered`() {
        assertNull(promptAt(30_000, intro = 30_000L..30_400L))
    }
}
