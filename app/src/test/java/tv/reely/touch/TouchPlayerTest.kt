package tv.reely.touch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.reely.screens.Shots
import tv.reely.ui.Playback
import tv.reely.ui.screens.Controls
import tv.reely.ui.screens.LocalTouchPlayer
import tv.reely.ui.theme.ReelyTheme

/** The player's controls on a phone, held sideways: the bar takes a finger. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE_LANDSCAPE)
class TouchPlayerTest {

    @get:Rule val compose = createComposeRule()

    private fun controls(touch: Boolean, onSeekTo: (Long) -> Unit) = compose.setContent {
        ReelyTheme {
            CompositionLocalProvider(LocalTouchPlayer provides touch) {
                Box(Modifier.fillMaxSize().background(Color.DarkGray)) {
                    Controls(
                        playback = Playback(title = "The Weigh Station", subtitle = "Northbound  ·  S2 · E5", url = "", isLive = false, durationMs = DURATION),
                        playing = true,
                        positionMs = 24 * 60_000L,
                        durationMs = DURATION,
                        bufferedMs = 27 * 60_000L,
                        canSkipBack = true,
                        canSkipForward = true,
                        playFocus = androidx.compose.runtime.remember { FocusRequester() },
                        scrubberFocus = androidx.compose.runtime.remember { FocusRequester() },
                        onScrubberFocus = {},
                        onSeekTo = onSeekTo, onSkip = {}, onTogglePlay = {}, onAddChannel = {},
                        onOpenSubtitles = {}, onOpenAudio = {}, onOpenStats = {}, onToggleFormat = {},
                        modifier = Modifier.align(Alignment.BottomStart),
                    )
                }
            }
        }
    }

    /** The bar's middle: between the two times, which sit either end of its line. */
    private fun tapBar(fraction: Float) {
        val start = compose.onNodeWithText("24:00").fetchSemanticsNode().boundsInRoot
        val end = compose.onNodeWithText("\u221218:00").fetchSemanticsNode().boundsInRoot
        // The bar runs from just after the first time to just before the second.
        val left = start.right + GAP_PX
        val right = end.left - GAP_PX
        compose.onRoot().performTouchInput {
            click(androidx.compose.ui.geometry.Offset(left + (right - left) * fraction, start.center.y))
        }
    }

    @Test fun `a tap on the bar goes to that point`() {
        var sought = -1L
        controls(touch = true) { sought = it }
        tapBar(0.5f)
        compose.runOnIdle {
            assertTrue("sought $sought", sought in (DURATION / 2 - 120_000)..(DURATION / 2 + 120_000))
        }
        Shots.saveIfAsked(compose, "phone-player-controls")
    }

    @Test fun `on a television a tap does nothing to the bar`() {
        var sought = -1L
        controls(touch = false) { sought = it }
        tapBar(0.5f)
        compose.runOnIdle { assertTrue("sought $sought", sought == -1L) }
        compose.onNodeWithText("The Weigh Station").fetchSemanticsNode()
    }

    companion object {
        const val DURATION = 42 * 60_000L
        // 14 dp between each time and the bar, at this density (2x).
        const val GAP_PX = 28f
    }
}

const val PHONE_LANDSCAPE = "w860dp-h400dp-land-xhdpi"
