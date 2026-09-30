package tv.reely.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.screens.Shots
import tv.reely.ui.screens.LivePlay
import tv.reely.ui.screens.PlayerScreen
import tv.reely.ui.screens.livePlayAction
import tv.reely.ui.theme.ReelyTheme

/**
 * Rewinding live television, on a channel with an archive: back from now goes into the
 * programme on now, and from behind, Go live comes back to the channel.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class LiveRewindTest {
    @get:Rule val compose = createComposeRule()

    private val nowS = System.currentTimeMillis() / 1000
    // Twenty minutes into an hour-long programme.
    private val window = Timeshift(streamId = 3, start = nowS - 20 * 60, stop = nowS + 40 * 60)
    private val timeshifts = mutableListOf<Long>()
    private var wentLive = 0

    private fun player(playback: Playback) {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val livePlayer = tv.reely.core.LivePlayer(context)
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                PlayerScreen(
                    playback = playback,
                    prefs = PlayerPrefs(matchFrameRate = false), live = LiveState(), guide = GuideState(), upNext = null,
                    onExit = {}, onEnded = {}, onCredits = {}, onPlayUpNext = {}, onDismissUpNext = {},
                    onStepChannel = {}, onSelectChannel = {}, onOpenCategory = {}, livePlayer = livePlayer,
                    multiview = emptyList(), onAddToMultiview = {}, onRemoveTile = {}, onClearTiles = {},
                    onReplaceTile = { _, _ -> }, onCollapseToChannel = {}, onStepEpisode = {},
                    onDecodeFailure = {}, onConvertAudio = {}, onToggleFormat = {}, onReportProgress = { _, _ -> },
                    onNudgeSubtitleScale = {}, onToggleSubtitleBackground = {},
                    liveWindow = window,
                    onTimeshift = { timeshifts += it },
                    onGoLive = { wentLive++ },
                    imageUrl = { _, _, _, _ -> null }, logoUrl = { _, _ -> null },
                )
            }
        }
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    @Test fun `rewind on live goes back into the programme on now`() {
        player(Playback(title = "News", subtitle = null, url = "", isLive = true, channelIndex = 3))
        press(Key.MediaRewind)
        assertEquals("one step back, out of the archive", 1, timeshifts.size)
        // By the clock: about 30 seconds before now.
        val behind = System.currentTimeMillis() - timeshifts[0]
        assertTrue("about 30 s behind now: $behind", behind in 20_000..45_000)
    }

    @Test fun `fast-forward on live stays live`() {
        player(Playback(title = "News", subtitle = null, url = "", isLive = true, channelIndex = 3))
        press(Key.MediaFastForward)
        assertTrue(timeshifts.isEmpty())
    }

    @Test fun `behind live, Go live comes back to the channel`() {
        player(
            Playback(
                title = "News", subtitle = null, url = "", isLive = false,
                durationMs = 60 * 60_000L, startPositionMs = 5 * 60_000L, timeshift = window,
            )
        )
        assertTrue("Go live is offered", compose.onAllNodes(hasText("Go live")).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Go live").performClick()
        compose.waitForIdle()
        assertEquals(1, wentLive)
    }

    @Test fun `play after a pause on live carries on from the pause, where the channel can`() {
        val now = System.currentTimeMillis()
        val paused = now - 5 * 60_000
        assertEquals(LivePlay.FROM_PAUSE, livePlayAction(isLive = true, isPlaying = false, rewindable = true, pausedAt = paused, now = now))
        // No archive: back to now, the only place there is.
        assertEquals(LivePlay.REJOIN, livePlayAction(isLive = true, isPlaying = false, rewindable = false, pausedAt = paused, now = now))
        // A moment's pause is still live.
        assertEquals(LivePlay.REJOIN, livePlayAction(isLive = true, isPlaying = false, rewindable = true, pausedAt = now - 5_000, now = now))
        assertEquals(LivePlay.TOGGLE, livePlayAction(isLive = true, isPlaying = true, rewindable = true, pausedAt = null, now = now))
        assertEquals(LivePlay.TOGGLE, livePlayAction(isLive = false, isPlaying = false, rewindable = false, pausedAt = null, now = now))
    }
}
