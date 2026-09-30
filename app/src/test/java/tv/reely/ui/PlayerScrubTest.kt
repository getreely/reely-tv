package tv.reely.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
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
import tv.reely.ui.screens.PlayerScreen
import tv.reely.ui.theme.ReelyTheme

/**
 * The whole player, not the bar on its own: right, or fast-forward, with the controls down
 * has to put the cursor on the bar, which is where the preview is drawn. Tested on the bar
 * alone this passed while on a television the cursor went to Play and no preview showed.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class PlayerScrubTest {
    @get:Rule val compose = createComposeRule()

    private fun player(key: Key) {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val livePlayer = tv.reely.core.LivePlayer(context)
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                PlayerScreen(
                    playback = Playback(
                        title = "Low Orbit", subtitle = null, url = "", isLive = false,
                        durationMs = 2 * 3_600_000L, startPositionMs = 20 * 60_000L,
                    ),
                    prefs = PlayerPrefs(matchFrameRate = false), live = LiveState(), guide = GuideState(), upNext = null,
                    onExit = {}, onEnded = {}, onCredits = {}, onPlayUpNext = {}, onDismissUpNext = {},
                    onStepChannel = {}, onSelectChannel = {}, onOpenCategory = {}, livePlayer = livePlayer,
                    multiview = emptyList(), onAddToMultiview = {}, onRemoveTile = {}, onClearTiles = {},
                    onReplaceTile = { _, _ -> }, onCollapseToChannel = {}, onStepEpisode = {},
                    onDecodeFailure = {}, onConvertAudio = {}, onToggleFormat = {}, onReportProgress = { _, _, _ -> },
                    onNudgeSubtitleScale = {}, onToggleSubtitleBackground = {},
                    imageUrl = { _, _, _, _ -> null }, logoUrl = { _, _ -> null },
                )
            }
        }
        compose.waitForIdle()
        // Controls down, as they are a few seconds into a film: Back puts them away.
        compose.onRoot().performKeyInput { pressKey(Key.Back) }
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    /** The bar runs most of the screen's width; Play and the other buttons are small circles. */
    private fun cursorOnTheBar() {
        val width = compose.onNode(isFocused()).fetchSemanticsNode().size.width
        assertTrue("the cursor is on something $width px wide, not the bar", width > 800)
    }

    @Test fun `right with the controls down puts the cursor on the bar`() {
        player(Key.DirectionRight)
        cursorOnTheBar()
    }

    @Test fun `fast-forward does the same`() {
        player(Key.MediaFastForward)
        cursorOnTheBar()
    }
}
