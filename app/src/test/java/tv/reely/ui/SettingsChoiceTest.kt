package tv.reely.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.reely.core.Settings
import tv.reely.screens.Shots
import tv.reely.ui.screens.SettingsScreen
import tv.reely.ui.theme.ReelyTheme

/**
 * A setting with several values opens a list of them, the current one under the cursor;
 * OK on another picks it straight away, Back changes nothing.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class SettingsChoiceTest {
    @get:Rule val compose = createComposeRule()

    private var prefs by mutableStateOf(PlayerPrefs(subtitleScale = 0.9f, playbackMode = Settings.MODE_AUTO))
    private val modes = mutableListOf<String>()

    private fun open() {
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Box(Modifier.fillMaxSize().background(tv.reely.ui.theme.Ink)) {
                    SettingsScreen(
                        plex = PlexState(), live = LiveState(), guide = GuideState(), prefs = prefs,
                        onSignOutPlex = {}, onSignOutXtream = {}, onSwitchServer = {}, onToggleFavourite = {},
                        onToggleFormat = {},
                        onNudgeSubtitleScale = { prefs = prefs.copy(subtitleScale = prefs.subtitleScale + it) },
                        onToggleSubtitleBackground = {},
                        onNudgeUpNext = {}, onToggleGuidePreview = {},
                        onSetPlaybackMode = { modes += it; prefs = prefs.copy(playbackMode = it) },
                        onSetMaxBitrate = {}, onToggleMultiviewLayout = {}, onToggleThemeMusic = {},
                        onToggleMatchFrameRate = {}, onToggleLargerBuffer = {}, onToggleSkipIntros = {}, onNudgeThemeVolume = {}, onRefreshChannels = {},
                        onRefreshGuide = {}, update = UpdateStatus.Idle, updateUrl = "https://example",
                        onCheckForUpdate = {}, onInstallUpdate = {},
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_500)
        compose.waitForIdle()
    }

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_500)
        compose.waitForIdle()
    }

    private fun row(title: String) =
        compose.onNode(hasText(title, substring = true) and isFocusable(), useUnmergedTree = false)

    private fun focusedText(): String =
        compose.onAllNodes(isFocused()).fetchSemanticsNodes().joinToString { it.config.toString() }

    @Test fun `subtitle size is picked from a list`() {
        open()
        row("Size").performClick()
        settle()
        assertTrue("the current size is under the cursor: ${focusedText()}", focusedText().contains("90%"))
        Shots.saveIfAsked(compose, "settings-choice")
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertEquals(1.2f, prefs.subtitleScale, 0.001f)
    }

    @Test fun `back leaves it as it was`() {
        open()
        row("Playback mode").performClick()
        settle()
        press(Key.DirectionDown)
        press(Key.Back)
        assertEquals(emptyList<String>(), modes)
    }

    @Test fun `choosing the one already in use changes nothing`() {
        open()
        row("Playback mode").performClick()
        settle()
        press(Key.DirectionCenter)
        assertEquals(emptyList<String>(), modes)
    }

    @Test fun `another mode is set directly`() {
        open()
        row("Playback mode").performClick()
        settle()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertEquals(listOf(Settings.MODE_DIRECT), modes)
    }
}
