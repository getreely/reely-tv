package tv.reely.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.plex.PlexDetail
import tv.reely.screens.Shots
import tv.reely.ui.components.TvChip
import tv.reely.ui.screens.DetailScreen
import tv.reely.ui.theme.ReelyTheme

/**
 * A title's page opened the way the app opens it: focus asked for on the page area as a
 * whole. It should start on Play — not on the summary, which can be selected now, and
 * not anywhere up in the tabs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class DetailFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val content = FocusRequester()
    private val tab = FocusRequester()

    private val movie = PlexDetail(
        ratingKey = "m1", type = "movie", title = "Low Orbit",
        summary = List(6) { "Nine days to get home, and one seat too few, with the whole crew watching the clock." }
            .joinToString(" "),
        tagline = null, year = 2025, durationMs = 131 * 60_000L, viewOffsetMs = 40 * 60_000L,
        contentRating = "PG-13", rating = 7.9, audienceRating = 8.4, airDate = null, viewCount = 0,
        studio = "Harbourside", thumb = null, art = null, theme = null, genres = listOf("Drama"),
        directors = emptyList(), roles = emptyList(), childCount = 0, leafCount = 0,
        grandparentTitle = null, index = null, parentIndex = null, logo = null, qualities = emptyList(),
    )

    @Test fun `a movie's page starts on Play`() {
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Column(Modifier.fillMaxSize()) {
                    TvChip("Movies", selected = true, onClick = {}, modifier = Modifier.focusRequester(tab))
                    Box(Modifier.fillMaxWidth().weight(1f).focusRequester(content).pageArea(upTo = tab)) {
                        DetailScreen(
                            state = DetailState(ratingKey = "m1", serverBase = "http://server", busy = false, detail = movie),
                            imageUrl = { _, _, _, _ -> null }, backdropUrl = { _, _ -> null }, logoUrl = { _, _ -> null },
                            onPlay = {}, onPlayFromStart = {}, onPlayDetail = {}, onPlayDetailFromStart = {},
                            onPlayTrailer = {}, onToggleWatched = {}, onToggleWatchedDetail = {},
                            onFocusEpisode = {}, onSelectSeason = {},
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { content.requestFocus() }
        compose.waitForIdle()
        // The button is a circle with its label beneath, so it is found by the label it
        // sits over.
        val focused = compose.onAllNodes(isFocused()).fetchSemanticsNodes().single().boundsInRoot
        val label = compose.onNode(hasText("Resume")).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "focus should be on Resume, was at $focused (Resume is under ${label.center.x})",
            label.center.x in focused.left..focused.right && focused.bottom <= label.top + 1f,
        )
    }
}
