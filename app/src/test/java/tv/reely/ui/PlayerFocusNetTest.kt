package tv.reely.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.plex.PlexOnlineSubtitle
import tv.reely.screens.Shots
import tv.reely.ui.screens.PlayerScreen
import tv.reely.ui.theme.ReelyTheme

/**
 * The whole player, driven by the remote: a menu whose rows are replaced while the cursor
 * is on one still has a cursor in it afterwards. Finding subtitles online left the remote
 * dead once the results came in.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class PlayerFocusNetTest {
    @get:Rule val compose = createComposeRule()

    private var search by mutableStateOf<SubtitleSearch?>(null)
    private val added = mutableListOf<String>()

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(400)
        compose.waitForIdle()
    }

    private fun showing(text: String) =
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test fun `subtitles found online can be chosen with the remote`() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val livePlayer = tv.reely.core.LivePlayer(context)
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                PlayerScreen(
                    playback = Playback(
                        title = "Low Orbit", subtitle = null, url = "", isLive = false,
                        durationMs = 2 * 3_600_000L, ratingKey = "42",
                    ),
                    prefs = PlayerPrefs(matchFrameRate = false), live = LiveState(), guide = GuideState(), upNext = null,
                    onExit = {}, onEnded = {}, onCredits = {}, onPlayUpNext = {}, onDismissUpNext = {},
                    onStepChannel = {}, onSelectChannel = {}, onOpenCategory = {}, livePlayer = livePlayer,
                    multiview = emptyList(), onAddToMultiview = {}, onRemoveTile = {}, onClearTiles = {},
                    onReplaceTile = { _, _ -> }, onCollapseToChannel = {}, onStepEpisode = {},
                    onDecodeFailure = {}, onConvertAudio = {}, onToggleFormat = {}, onReportProgress = { _, _ -> },
                    onNudgeSubtitleScale = {}, onToggleSubtitleBackground = {},
                    subtitleSearch = search,
                    onFindSubtitles = { search = SubtitleSearch(language = "en") },
                    onAddSubtitle = { added += it.title },
                    imageUrl = { _, _, _, _ -> null }, logoUrl = { _, _ -> null },
                )
            }
        }
        compose.waitForIdle()
        // Along the bar from Play to Subtitles, the way the remote gets there.
        repeat(6) {
            if (!showing("Find subtitles online")) {
                press(Key.DirectionRight)
                press(Key.DirectionCenter)
            }
        }
        assertTrue("the Subtitles menu is open", showing("Find subtitles online"))
        // With no subtitles of its own, it's the first thing in the menu, under the cursor.
        press(Key.DirectionCenter)
        assertTrue("looking for subtitles", search != null)
        // The server answers.
        search = search!!.copy(
            busy = false,
            results = listOf(
                PlexOnlineSubtitle("a", "Low.Orbit.1080p.srt", "OpenSubtitles", "en", "srt", false, false),
                PlexOnlineSubtitle("b", "Low.Orbit.720p.srt", "OpenSubtitles", "en", "srt", false, false),
            ),
        )
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        press(Key.DirectionCenter)
        assertEquals(listOf("Low.Orbit.1080p.srt"), added)
    }
}
