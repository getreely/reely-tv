package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.reely.core.DisplayMode
import tv.reely.core.FrameRate

class FrameRateTest {

    private val hd = { id: Int, hz: Float -> DisplayMode(id, 1920, 1080, hz) }

    private val typicalTv = listOf(
        hd(1, 23.976f),
        hd(2, 24f),
        hd(3, 25f),
        hd(4, 30f),
        hd(5, 50f),
        hd(6, 59.94f),
        hd(7, 60f),
    )

    @Test
    fun `an exact multiple has no judder`() {
        assertEquals(0f, FrameRate.judder(60f, 30f), 0.0001f)
        assertEquals(0f, FrameRate.judder(60f, 60f), 0.0001f)
        assertEquals(0f, FrameRate.judder(50f, 25f), 0.0001f)
        assertEquals(0f, FrameRate.judder(59.94f, 29.97f), 0.0001f)
    }

    @Test
    fun `film on a sixty hertz panel is the case this exists for`() {
        // 59.94 / 23.976 is two and a half: every other frame held a beat longer.
        // Half a frame out, spread over the three the ratio rounds to.
        assertEquals(0.5f / 3f, FrameRate.judder(59.94f, 23.976f), 0.001f)
    }

    @Test
    fun `a screen slower than the content is never a candidate`() {
        assertEquals(Float.MAX_VALUE, FrameRate.judder(24f, 60f), 0f)
    }

    @Test
    fun `nonsense rates are never a candidate`() {
        assertEquals(Float.MAX_VALUE, FrameRate.judder(60f, 0f), 0f)
        assertEquals(Float.MAX_VALUE, FrameRate.judder(0f, 24f), 0f)
    }

    @Test
    fun `film on a sixty hertz panel switches to the film rate`() {
        val best = FrameRate.bestMode(23.976f, hd(7, 60f), typicalTv)
        assertEquals(23.976f, best!!.refreshRate, 0.001f)
    }

    @Test
    fun `european content switches to fifty`() {
        val best = FrameRate.bestMode(25f, hd(7, 60f), typicalTv)
        assertEquals(50f, best!!.refreshRate, 0.001f)
    }

    @Test
    fun `content already even with the screen does not switch`() {
        // 30 into 60 twice, so there is nothing to fix and no reason to blank the screen.
        assertNull(FrameRate.bestMode(30f, hd(7, 60f), typicalTv))
        assertNull(FrameRate.bestMode(60f, hd(7, 60f), typicalTv))
        assertNull(FrameRate.bestMode(29.97f, hd(6, 59.94f), typicalTv))
    }

    @Test
    fun `nothing to gain means staying put`() {
        // A panel that only does sixty has no answer for film.
        val only60 = listOf(hd(7, 60f))
        assertNull(FrameRate.bestMode(23.976f, hd(7, 60f), only60))
    }

    @Test
    fun `an unknown frame rate is left alone`() {
        assertNull(FrameRate.bestMode(0f, hd(7, 60f), typicalTv))
        assertNull(FrameRate.bestMode(-1f, hd(7, 60f), typicalTv))
    }

    @Test
    fun `resolution is never traded for a refresh rate`() {
        val modes = listOf(
            hd(7, 60f),
            DisplayMode(9, 1280, 720, 24f),
        )
        assertNull(FrameRate.bestMode(23.976f, hd(7, 60f), modes))
    }

    @Test
    fun `where two rates are equally even the faster one wins`() {
        // Both 24 and 120 show every frame evenly; 120 keeps the overlays smooth.
        val modes = listOf(hd(1, 24f), hd(2, 120f), hd(7, 60f))
        val best = FrameRate.bestMode(24f, hd(7, 60f), modes)
        assertEquals(120f, best!!.refreshRate, 0.001f)
    }

    @Test
    fun `a mode that is already current is not offered again`() {
        assertNull(FrameRate.bestMode(24f, hd(2, 24f), typicalTv))
    }
}
