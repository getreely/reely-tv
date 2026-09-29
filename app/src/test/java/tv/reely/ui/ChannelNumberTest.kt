package tv.reely.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.screens.Shots
import tv.reely.ui.screens.PlayerScreen
import tv.reely.ui.theme.ReelyTheme
import tv.reely.xtream.XtreamChannel

/** Number keys on a remote that has them: type a channel, see which it is, go there. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class ChannelNumberTest {
    @get:Rule val compose = createComposeRule()

    private val news = XtreamChannel(streamId = 9, number = 105, name = "World News", icon = null, epgChannelId = null)
    private var tuned: XtreamChannel? = null

    private fun watching() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val livePlayer = tv.reely.core.LivePlayer(context)
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                PlayerScreen(
                    playback = Playback(title = "Sports One", subtitle = null, url = "", isLive = true, channelIndex = 0),
                    prefs = PlayerPrefs(matchFrameRate = false), live = LiveState(), guide = GuideState(), upNext = null,
                    onExit = {}, onEnded = {}, onCredits = {}, onPlayUpNext = {}, onDismissUpNext = {},
                    onStepChannel = {}, onSelectChannel = {}, onOpenCategory = {}, livePlayer = livePlayer,
                    multiview = emptyList(), onAddToMultiview = {}, onRemoveTile = {}, onClearTiles = {},
                    onReplaceTile = { _, _ -> }, onCollapseToChannel = {}, onStepEpisode = {},
                    onDecodeFailure = {}, onConvertAudio = {}, onToggleFormat = {}, onReportProgress = { _, _ -> },
                    onNudgeSubtitleScale = {}, onToggleSubtitleBackground = {},
                    findChannel = { if (it == 105) news else null },
                    onTuneChannel = { tuned = it },
                    imageUrl = { _, _, _, _ -> null }, logoUrl = { _, _ -> null },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun press(keys: List<Key>) {
        keys.forEach { key ->
            compose.onRoot().performKeyInput { pressKey(key) }
            compose.waitForIdle()
        }
    }

    @Test fun `typing a number shows the channel, and OK goes there`() {
        watching()
        press(listOf(Key.One, Key.Zero, Key.Five))
        compose.onNodeWithText("105").assertExists()
        compose.onNodeWithText("World News").assertExists()
        assertNull("not until OK, or a pause", tuned)
        press(listOf(Key.Enter))
        assertEquals(news, tuned)
    }

    @Test fun `a number with no channel says so`() {
        watching()
        press(listOf(Key.Four, Key.Two))
        compose.onNodeWithText("No channel with this number").assertExists()
        press(listOf(Key.Enter))
        compose.onNodeWithText("There's no channel 42.").assertExists()
        assertNull(tuned)
    }

    @Test fun `back while typing only forgets the number`() {
        watching()
        press(listOf(Key.One, Key.Back))
        compose.onNodeWithText("1").assertDoesNotExist()
        assertNull(tuned)
    }
}
