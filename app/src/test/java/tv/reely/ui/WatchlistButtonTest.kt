package tv.reely.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasClickAction
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
import tv.reely.plex.PlexDetail
import tv.reely.screens.Shots
import tv.reely.ui.screens.DetailScreen
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.ReelyTheme

/** The Watchlist button on a title's page: there when it can be used, and it toggles. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class WatchlistButtonTest {
    @get:Rule val compose = createComposeRule()

    private val film = PlexDetail(
        ratingKey = "m1", type = "movie", title = "Low Orbit",
        summary = "Two engineers keep a failing station in the sky one more week.",
        tagline = null, year = 2023, durationMs = 7_260_000, viewOffsetMs = 0,
        contentRating = "PG-13", rating = 7.9, audienceRating = 8.4, airDate = null,
        viewCount = 0, studio = null, thumb = "poster/orbit", art = "backdrop/orbit",
        theme = null, genres = listOf("Drama"), directors = emptyList(), roles = emptyList(),
        childCount = 0, leafCount = 0, grandparentTitle = null, index = null,
        parentIndex = null, guid = "plex://movie/5d7768",
    )

    private fun page(watchlisted: Boolean?, onToggle: () -> Unit = {}) {
        var on by mutableStateOf(watchlisted)
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Box(Modifier.fillMaxSize().background(Ink)) {
                    DetailScreen(
                        state = DetailState(ratingKey = "m1", serverBase = "http://server", busy = false, detail = film),
                        imageUrl = { _, path, w, h -> Shots.imageUrl(path, w, h) },
                        backdropUrl = { _, path -> Shots.imageUrl(path, 1280, 720) },
                        logoUrl = { _, _ -> null },
                        onPlay = {}, onPlayFromStart = {}, onPlayDetail = {}, onPlayDetailFromStart = {},
                        onPlayTrailer = {}, onToggleWatched = {}, onToggleWatchedDetail = {},
                        onFocusEpisode = {}, onSelectSeason = {},
                        watchlisted = on,
                        onToggleWatchlist = { onToggle(); on = on?.not() },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun `it toggles`() {
        var toggles = 0
        page(watchlisted = false) { toggles++ }
        compose.onNodeWithText("Watchlist").assertExists()
        Shots.saveIfAsked(compose, "detail-watchlist")
        // The buttons are circles over their names: Play, Watched, then Watchlist.
        compose.onAllNodes(hasClickAction() and androidx.compose.ui.test.hasAnySibling(androidx.compose.ui.test.hasText("Watchlist")))[2]
            .performClick()
        compose.waitForIdle()
        assertEquals(1, toggles)
    }

    @Test fun `no button when it can't be known`() {
        page(watchlisted = null)
        compose.onNodeWithText("Watchlist").assertDoesNotExist()
    }
}
