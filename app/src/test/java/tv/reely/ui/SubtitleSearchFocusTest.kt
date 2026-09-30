package tv.reely.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.plex.PlexOnlineSubtitle
import tv.reely.screens.Shots
import tv.reely.ui.components.requestWhenReady
import tv.reely.ui.screens.SubtitleSearchPanel
import tv.reely.ui.theme.ReelyTheme

/** Subtitles found online can be chosen: the cursor goes onto them when they arrive. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class SubtitleSearchFocusTest {
    @get:Rule val compose = createComposeRule()

    private var search by mutableStateOf(SubtitleSearch(language = "en"))

    @OptIn(ExperimentalTestApi::class)
    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test fun `results that arrive while the cursor waits on Close can be chosen`() {
        val picked = mutableListOf<String>()
        compose.setContent {
            ReelyTheme {
                Box(Modifier.fillMaxSize()) {
                    val focus = remember { FocusRequester() }
                    // What the player does as the panel opens, while the server is still looking.
                    LaunchedEffect(Unit) { focus.requestWhenReady() }
                    SubtitleSearchPanel(
                        search = search,
                        focusRequester = focus,
                        onPick = { picked += it.title },
                        onClose = {},
                    )
                }
            }
        }
        compose.waitForIdle()
        search = search.copy(
            busy = false,
            results = listOf(
                PlexOnlineSubtitle("a", "Film.2024.1080p.srt", "OpenSubtitles", "en", "srt", false, false),
                PlexOnlineSubtitle("b", "Film.2024.720p.srt", "OpenSubtitles", "en", "srt", false, false),
            ),
        )
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        press(Key.DirectionDown)
        press(Key.DirectionCenter)
        assertEquals(listOf("Film.2024.720p.srt"), picked)
    }
}
