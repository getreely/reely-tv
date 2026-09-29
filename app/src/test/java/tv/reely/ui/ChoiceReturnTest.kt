package tv.reely.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.screens.Shots
import tv.reely.ui.screens.SettingsScreen
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.ReelyTheme
import tv.reely.xtream.StreamFormat

/**
 * Picking from a list puts the cursor back on the row that opened it, even when what was
 * picked changes the screen under it. Stream type sent it to the search tab instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class ChoiceReturnTest {
    @get:Rule val compose = createComposeRule()

    private var live by mutableStateOf(
        LiveState(
            credentials = tv.reely.xtream.XtreamCredentials("http://line.example.tv:8080", "u", "p"),
            account = tv.reely.xtream.XtreamAccount("Active", "2", "1", "1798761600"),
            categories = List(3) { tv.reely.xtream.XtreamCategory("c$it", "Category $it") },
        )
    )

    private fun focused(): String =
        compose.onAllNodes(isFocused()).fetchSemanticsNodes().joinToString { n -> (n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString() ?: "") + " tag=" + (n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null } ?: "") }

    @OptIn(ExperimentalTestApi::class)
    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
    }

    @Test fun `picking a stream type returns to the row, not the top of the app`() {
        compose.setContent {
            ReelyTheme {
                Column(Modifier.fillMaxSize().background(Ink)) {
                    // Stands in for the tab row, whose search button is the first thing in the window.
                    Box(Modifier.fillMaxWidth().height(40.dp).testTag("search").focusable())
                    SettingsScreen(
                        plex = PlexState(), live = live, guide = GuideState(), prefs = PlayerPrefs(),
                        onSignOutPlex = {}, onSignOutXtream = {}, onSwitchServer = {}, onToggleFavourite = {},
                        onToggleFormat = {
                            live = live.copy(format = if (live.format == StreamFormat.TS) StreamFormat.HLS else StreamFormat.TS)
                        },
                        onNudgeSubtitleScale = {}, onToggleSubtitleBackground = {},
                        onNudgeUpNext = {}, onToggleGuidePreview = {}, onSetPlaybackMode = {},
                        onSetMaxBitrate = {}, onToggleMultiviewLayout = {}, onToggleThemeMusic = {},
                        onToggleMatchFrameRate = {}, onToggleLargerBuffer = {}, onToggleSkipIntros = {},
                        onNudgeThemeVolume = {}, onRefreshChannels = {}, onRefreshGuide = {},
                        update = UpdateStatus.Idle, updateUrl = "https://example",
                        onCheckForUpdate = {}, onInstallUpdate = {},
                    )
                }
            }
        }
        // As the remote gets there: onto Live TV, right into its options, down to Stream type.
        compose.onNodeWithText("Live TV").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Live TV").requestFocus()
        compose.waitForIdle()
        press(Key.DirectionRight)
        repeat(4) { if (!focused().contains("Stream type")) press(Key.DirectionDown) }
        press(Key.DirectionCenter)
        // Onto the other type, and choose it.
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        val now = focused()
        assertTrue("the cursor is back on Stream type: $now", now.contains("Stream type"))
    }
}
