package tv.reely.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.screens.Shots
import tv.reely.ui.components.TvChip
import tv.reely.ui.screens.SettingsScreen
import tv.reely.ui.theme.ReelyTheme

/**
 * Settings driven with the remote's keys, inside the same page area the app draws every
 * page in. Moving left out of the options into the sections was cancelled once by the
 * rule that keeps sideways presses from wandering up into the tab row.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class SettingsFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val content = FocusRequester()
    private val showsTab = FocusRequester()
    private val gearTab = FocusRequester()

    private fun section(name: String): SemanticsNodeInteraction =
        compose.onNode(hasText(name) and isFocusable() and !hasSetTextAction(), useUnmergedTree = false)

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    private fun optionsHaveFocus(): Boolean {
        val focused = compose.onAllNodes(isFocused()).fetchSemanticsNodes()
        val sections = listOf("Playback", "Live TV", "Plex", "Updates", "About", "TV Shows", "Settings tab")
        return focused.isNotEmpty() && focused.none { node ->
            sections.any { name -> hasText(name).matches(node) }
        }
    }

    private fun open() {
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Column(Modifier.fillMaxSize()) {
                    Row {
                        TvChip("TV Shows", selected = false, onClick = {}, modifier = Modifier.focusRequester(showsTab))
                        TvChip("Settings tab", selected = true, onClick = {}, modifier = Modifier.focusRequester(gearTab))
                    }
                    Box(Modifier.fillMaxWidth().weight(1f).focusRequester(content).pageArea(upTo = gearTab)) {
                        SettingsScreen(
                            plex = PlexState(), live = LiveState(), guide = GuideState(), prefs = PlayerPrefs(),
                            onSignOutPlex = {}, onSignOutXtream = {}, onSwitchServer = {}, onToggleFavourite = {},
                            onToggleFormat = {}, onNudgeSubtitleScale = {}, onToggleSubtitleBackground = {},
                            onNudgeUpNext = {}, onToggleGuidePreview = {}, onCyclePlaybackMode = {},
                            onCycleMaxBitrate = {}, onToggleMultiviewLayout = {}, onToggleThemeMusic = {},
                            onToggleMatchFrameRate = {}, onToggleLargerBuffer = {}, onNudgeThemeVolume = {}, onRefreshChannels = {},
                            onRefreshGuide = {}, update = UpdateStatus.Idle, updateUrl = "https://example",
                            onCheckForUpdate = {}, onInstallUpdate = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun `settings opens on its sections`() {
        open()
        compose.runOnIdle { content.requestFocus() }
        compose.waitForIdle()
        section("Playback").assertIsFocused()
    }

    @Test fun `left from the options goes back to their section, and sideways never reaches the tabs`() {
        open()
        compose.runOnIdle { content.requestFocus() }
        compose.waitForIdle()
        repeat(3) { press(Key.DirectionDown) }
        section("Updates").assertIsFocused()

        press(Key.DirectionRight)
        assertTrue("right from a section goes into its options", optionsHaveFocus())

        repeat(3) { press(Key.DirectionRight) }
        section("TV Shows").assertIsNotFocused()
        assertTrue("right off the options goes nowhere", optionsHaveFocus())

        press(Key.DirectionLeft)
        section("Updates").assertIsFocused()
    }

    @Test fun `back from the options goes back to their section`() {
        open()
        compose.runOnIdle { content.requestFocus() }
        compose.waitForIdle()
        repeat(3) { press(Key.DirectionDown) }
        section("Updates").assertIsFocused()
        press(Key.DirectionRight)
        assertTrue(optionsHaveFocus())

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        section("Updates").assertIsFocused()
    }

    @Test fun `up from the top section goes to the tab`() {
        open()
        compose.runOnIdle { content.requestFocus() }
        compose.waitForIdle()
        press(Key.DirectionUp)
        section("Settings tab").assertIsFocused()
    }
}
