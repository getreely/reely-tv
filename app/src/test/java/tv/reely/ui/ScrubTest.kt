package tv.reely.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.reely.screens.Shots
import tv.reely.ui.screens.Controls
import tv.reely.ui.screens.scrubStep
import tv.reely.ui.theme.ReelyTheme

/**
 * Scrubbing moves a mark along the bar and goes there once, when the presses stop or OK
 * is pressed — not a seek, and a fresh buffer, on every press.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class ScrubTest {
    @get:Rule val compose = createComposeRule()

    private val seeks = mutableListOf<Long>()
    private var toggled = 0
    private val start = 24 * 60_000L

    private fun open(previews: Boolean = false) {
        val play = FocusRequester()
        val scrubber = FocusRequester()
        val skip = FocusRequester()
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Box(Modifier.fillMaxSize()) {
                    Controls(
                        playback = Playback(
                            title = "The Weigh Station", subtitle = "Northbound", url = "", isLive = false,
                            durationMs = 42 * 60_000L,
                            previewUrl = if (previews) Shots.imageUrl("backdrop/north", 320, 180) + "&t={ms}" else null,
                        ),
                        playing = true,
                        positionMs = start,
                        durationMs = 42 * 60_000L,
                        bufferedMs = 27 * 60_000L,
                        canSkipBack = true,
                        canSkipForward = true,
                        playFocus = play,
                        scrubberFocus = scrubber,
                        onScrubberFocus = {},
                        onSeekTo = { seeks += it }, onSkip = {}, onTogglePlay = { toggled++ }, onAddChannel = {},
                        onOpenSubtitles = {}, onOpenAudio = {}, onOpenStats = {}, onToggleFormat = {},
                        skipLabel = null,
                        skipFocus = skip,
                        modifier = Modifier.align(Alignment.BottomStart),
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { scrubber.requestFocus() }
        compose.mainClock.advanceTimeBy(100)
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.mainClock.advanceTimeBy(100)
    }

    @Test fun `three presses, then one seek to where they went`() {
        open()
        repeat(3) { press(Key.DirectionRight) }
        assertEquals("nothing yet", emptyList<Long>(), seeks)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals(listOf(start + 30_000), seeks)
    }

    @Test fun `OK goes there straight away, and doesn't pause`() {
        open()
        press(Key.DirectionLeft)
        press(Key.DirectionCenter)
        assertEquals(listOf(start - 10_000), seeks)
        assertEquals(0, toggled)
        compose.mainClock.advanceTimeBy(1_000)
        assertEquals("not a second time", 1, seeks.size)
    }

    @Test fun `OK with no scrub pauses, as before`() {
        open()
        press(Key.DirectionCenter)
        assertEquals(1, toggled)
        assertEquals(emptyList<Long>(), seeks)
    }

    @Test fun `held, it goes further each step`() {
        assertEquals(10_000L, scrubStep(0))
        assertEquals(30_000L, scrubStep(10))
        assertEquals(60_000L, scrubStep(40))
    }

    @Test fun preview() {
        open(previews = true)
        repeat(4) { press(Key.DirectionRight) }
        Shots.saveIfAsked(compose, "player-scrub-preview", settleMs = 200)
    }
}
