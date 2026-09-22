package tv.reely.core

import kotlin.math.abs
import kotlin.math.roundToInt

/** A display mode, reduced to what choosing between them needs. */
data class DisplayMode(
    val id: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float,
)

/**
 * Matching the screen's refresh rate to the frame rate of what is playing.
 *
 * Film runs at 23.976 frames a second and a television sits at 60Hz, which does not
 * divide. The panel has to show some frames twice and others three times — three-two
 * pulldown — and the unevenness is visible on anything that moves slowly across the
 * frame, which in practice means every establishing shot in every film.
 *
 * Media3 already asks for a frame rate on the surface from API 30, but the two-argument
 * call only permits a change the display can make seamlessly, and a television almost
 * never has a seamless path between 60 and 24. Asking for a display mode by id is what
 * actually moves a panel, and it works back to API 23.
 */
object FrameRate {

    /**
     * How far a refresh rate is from showing every frame for the same length of time.
     * Zero is exact. The error is spread over the multiple so that being out by a tenth
     * of a hertz counts for less at 120Hz than at 24Hz.
     */
    fun judder(refreshRate: Float, contentFps: Float): Float {
        if (contentFps <= 0f || refreshRate <= 0f) return Float.MAX_VALUE
        val ratio = refreshRate / contentFps
        val nearest = ratio.roundToInt()
        // A screen slower than the content cannot show every frame, whatever it does.
        if (nearest < 1) return Float.MAX_VALUE
        return abs(ratio - nearest) / nearest
    }

    /** Close enough that nobody could see the difference. */
    const val TOLERANCE = 0.01f

    /**
     * The mode to switch to, or null to stay where we are — either because the current
     * mode is already even, because nothing on offer is better, or because we do not
     * know what is playing yet.
     *
     * Only modes at the current resolution are considered. Changing resolution to chase
     * a refresh rate would trade a judder nobody asked about for a picture that is
     * suddenly softer, and on a stick it can renegotiate HDMI as well.
     */
    fun bestMode(
        contentFps: Float,
        current: DisplayMode,
        modes: List<DisplayMode>,
    ): DisplayMode? {
        if (contentFps <= 0f) return null
        val currentJudder = judder(current.refreshRate, contentFps)
        if (currentJudder <= TOLERANCE) return null

        val candidates = modes.filter { it.width == current.width && it.height == current.height }
        val best = candidates.minWithOrNull(
            // The evenest rate wins; where two are equally even the faster one does,
            // because the menus drawn over the picture are smoother on it.
            compareBy({ judder(it.refreshRate, contentFps) }, { -it.refreshRate })
        ) ?: return null

        val bestJudder = judder(best.refreshRate, contentFps)
        if (bestJudder >= currentJudder) return null
        if (bestJudder > TOLERANCE) return null
        return best.takeIf { it.id != current.id }
    }
}
