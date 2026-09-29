package tv.reely.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.reely.ui.screens.Tour
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.ReelyTheme

/** The tour of the remote: its first step, the one about holding OK, and Live TV's. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class TourShots {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() = Shots.onlyWhenAsked()

    @Test fun steps() {
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Box(Modifier.fillMaxSize().background(Ink)) { Tour(onDone = {}) }
            }
        }
        Shots.save(compose, "tour-welcome")
        repeat(2) { next() }
        Shots.save(compose, "tour-hold", settleMs = 300)
        repeat(2) { next() }
        Shots.save(compose, "tour-live")
    }

    private fun next() {
        compose.onRoot().performKeyInput { pressKey(Key.DirectionCenter) }
        compose.mainClock.advanceTimeBy(500)
    }
}
