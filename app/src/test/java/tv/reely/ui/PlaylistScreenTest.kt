package tv.reely.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.reely.screens.Shots
import tv.reely.ui.screens.PlaylistScreen
import tv.reely.ui.screens.playlistLine
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.ReelyTheme

/** A playlist's page: Play, Shuffle, and what's in it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class PlaylistScreenTest {
    @get:Rule val compose = createComposeRule()

    private val items = listOf("north", "orbit", "harbor", "ember", "field").map(Shots::item)

    @Test fun `play and shuffle`() {
        val plays = mutableListOf<Boolean>()
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Box(Modifier.fillMaxSize().background(Ink)) {
                    PlaylistScreen(
                        state = PlaylistState(Route.Playlist("p1", "Friday Night"), items = items, busy = false),
                        imageUrl = { _, path, w, h -> Shots.imageUrl(path, w, h) },
                        onPlay = { plays += it },
                        onOpenItem = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Friday Night").assertExists()
        Shots.saveIfAsked(compose, "playlist")
        compose.onNodeWithText("Play").performClick()
        compose.onNodeWithText("Shuffle").performClick()
        assertEquals(listOf(false, true), plays)
    }

    @Test fun `the line under the name`() {
        val one = items[1].copy(durationMs = 90 * 60_000L)
        assertEquals("1 item  ·  1h 30m", playlistLine(listOf(one)))
    }
}
