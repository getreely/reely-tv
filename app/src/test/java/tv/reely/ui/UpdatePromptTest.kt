package tv.reely.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
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
import org.robolectric.annotation.GraphicsMode
import tv.reely.core.UpdateInfo
import tv.reely.screens.Shots
import tv.reely.ui.screens.UpdatePrompt
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.ReelyTheme

/** The update found at startup: Update now under the cursor, Later beside it. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class UpdatePromptTest {
    @get:Rule val compose = createComposeRule()

    private var updates = 0
    private var laters = 0
    private val info = UpdateInfo(
        url = "https://example/reely-tv.apk", versionCode = 9_999, versionName = "0.37.0",
        notes = null, sizeBytes = 14_700_000, published = null,
    )

    private fun open(status: UpdateStatus) {
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Box(Modifier.fillMaxSize().background(Ink)) {
                    UpdatePrompt(status = status, onUpdate = { updates++ }, onLater = { laters++ })
                }
            }
        }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_500)
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test fun `OK updates`() {
        open(UpdateStatus.Available(info))
        compose.onNodeWithText("Version 0.37.0 is ready", substring = true).assertExists()
        Shots.saveIfAsked(compose, "update-prompt")
        press(Key.DirectionCenter)
        assertEquals(1, updates)
    }

    @Test fun `right then OK is later`() {
        open(UpdateStatus.Available(info))
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(0, updates)
        assertEquals(1, laters)
    }

    @Test fun `downloading shows how far`() {
        open(UpdateStatus.Downloading(read = 7_350_000, total = 14_700_000))
        compose.onNodeWithText("50%").assertExists()
        Shots.saveIfAsked(compose, "update-downloading")
    }
}
