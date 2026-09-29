package tv.reely.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
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
import tv.reely.ui.screens.TOUR
import tv.reely.ui.screens.Tour
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.ReelyTheme

/** The tour steps through with OK, holds the cursor on its button, and ends when asked. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class TourTest {
    @get:Rule val compose = createComposeRule()
    private var done = 0

    private fun tour() {
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Box(Modifier.fillMaxSize().background(Ink)) { Tour(onDone = { done++ }) }
            }
        }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test fun `OK walks through every step, then starts watching`() {
        tour()
        TOUR.forEachIndexed { i, step ->
            compose.onNodeWithText(step.title).assertExists()
            val button = if (i == TOUR.lastIndex) "Start watching" else "Next"
            compose.onNode(isFocused() and hasText(button)).assertExists()
            press(Key.DirectionCenter)
        }
        assertEquals(1, done)
    }

    @Test fun `back goes back a step, and from the first ends the tour`() {
        tour()
        press(Key.DirectionCenter)
        compose.onNodeWithText(TOUR[1].title).assertExists()
        press(Key.Back)
        compose.onNodeWithText(TOUR[0].title).assertExists()
        press(Key.Back)
        assertEquals(1, done)
    }

    @Test fun `skip ends it from anywhere`() {
        tour()
        press(Key.DirectionCenter)
        // Next, Back, then Skip tour.
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        compose.onNode(isFocused() and hasText("Skip tour")).assertExists()
        press(Key.DirectionCenter)
        assertEquals(1, done)
    }

    @Test fun `the cursor can't wander out of it`() {
        tour()
        repeat(4) { press(Key.DirectionUp) }
        repeat(4) { press(Key.DirectionLeft) }
        compose.onNode(isFocused() and hasText("Next")).assertExists()
    }
}
