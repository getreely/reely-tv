package tv.reely.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.screens.Shots
import tv.reely.ui.screens.PostPlay
import tv.reely.ui.theme.ReelyTheme

/**
 * The Up Next countdown runs out and plays the next episode on its own — unless the
 * remote is touched, when it stops and waits. OK on Play next is the one press that
 * doesn't stop it, since that is the press that wants the next episode.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class UpNextCountdownTest {
    @get:Rule val compose = createComposeRule()

    private val focus = FocusRequester()
    private var played = 0

    private fun open() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                PostPlay(
                    item = Shots.item("north"),
                    stillUrl = null,
                    logoUrl = null,
                    nowTitle = "Cold Open",
                    ended = false,
                    countdownSeconds = 10,
                    focusRequester = focus,
                    onPlay = { played++ },
                    onDecline = {},
                ) { Box(Modifier.fillMaxSize()) }
            }
        }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { focus.requestFocus() }
        compose.mainClock.advanceTimeBy(2_000)
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.mainClock.advanceTimeBy(100)
    }

    @Test fun `left alone, it plays the next episode`() {
        open()
        compose.mainClock.advanceTimeBy(10_000)
        compose.waitForIdle()
        assertEquals(1, played)
    }

    @Test fun `moving stops it`() {
        open()
        press(Key.DirectionRight)
        compose.mainClock.advanceTimeBy(15_000)
        compose.waitForIdle()
        assertEquals(0, played)
        // Back on Play next it waits to be pressed, with no number counting down.
        press(Key.DirectionLeft)
        compose.onNodeWithText("Play next").assertExists()
        compose.mainClock.advanceTimeBy(15_000)
        assertEquals(0, played)
        press(Key.DirectionCenter)
        compose.waitForIdle()
        assertEquals(1, played)
    }

    @Test fun `OK on Play next plays it, once`() {
        open()
        press(Key.DirectionCenter)
        compose.mainClock.advanceTimeBy(15_000)
        compose.waitForIdle()
        assertEquals(1, played)
    }

    @Test fun `the volume doesn't stop it`() {
        open()
        press(Key.VolumeUp)
        compose.mainClock.advanceTimeBy(10_000)
        compose.waitForIdle()
        assertEquals(1, played)
    }
}
