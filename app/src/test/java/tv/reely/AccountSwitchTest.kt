package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.reely.plex.PlexItem
import tv.reely.ui.DetailState
import tv.reely.ui.HomeState
import tv.reely.ui.ReelyState
import tv.reely.ui.RequestsState
import tv.reely.ui.Route
import tv.reely.ui.SearchState
import tv.reely.ui.forgetAccount

/** A different profile or server keeps nothing of the last one's, and no way back to its pages. */
class AccountSwitchTest {

    private fun film(key: String) = PlexItem(
        ratingKey = key, title = key, type = "movie", thumb = null, art = null, summary = null,
        year = null, index = null, parentIndex = null, parentRatingKey = null, parentTitle = null,
        grandparentRatingKey = null, grandparentTitle = null, grandparentThumb = null,
        durationMs = 1_000_000, viewOffsetMs = 0, leafCount = 0, viewedLeafCount = 0,
        viewCount = 0, addedAt = 0, lastViewedAt = 0, librarySectionId = null, serverBase = null,
    )

    private val before = ReelyState(
        stack = listOf(Route.Home, Route.Detail("42"), Route.Settings),
        home = HomeState(continueWatching = listOf(film("1"))),
        detail = DetailState(ratingKey = "42"),
        search = SearchState(query = "orbit", results = listOf(film("2")), recent = listOf("orbit")),
        requests = RequestsState(plexMovies = setOf("tmdb://1")),
        focused = film("1"),
    )

    @Test fun `switching from Settings leaves only Home under it`() {
        val after = before.forgetAccount()
        assertEquals(listOf(Route.Home, Route.Settings), after.stack)
        assertNull(after.detail)
        assertNull(after.focused)
        assertTrue(after.home.continueWatching.isEmpty())
        assertTrue(after.search.results.isEmpty())
        assertTrue(after.requests.plexMovies.isEmpty())
        // What this television searched for lately isn't anybody's library.
        assertEquals(listOf("orbit"), after.search.recent)
    }

    @Test fun `from anywhere else, back to Home`() {
        val after = before.copy(stack = listOf(Route.Home, Route.Detail("42"))).forgetAccount()
        assertEquals(listOf(Route.Home), after.stack)
    }
}
