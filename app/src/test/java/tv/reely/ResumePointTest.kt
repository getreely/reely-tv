package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.reely.plex.PlexDetail
import tv.reely.plex.PlexItem
import tv.reely.ui.DetailState
import tv.reely.ui.HomeState
import tv.reely.ui.ReelyState
import tv.reely.ui.withProgress
import tv.reely.ui.withWatched
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Back from the player, Resume picks up where it was stopped, not where it started. */
class ResumePointTest {

    private fun episode(key: String, offset: Long = 0) = PlexItem(
        ratingKey = key, title = key, type = "episode", thumb = null, art = null, summary = null,
        year = null, index = null, parentIndex = null, parentRatingKey = null, parentTitle = null,
        grandparentRatingKey = "show", grandparentTitle = null, grandparentThumb = null,
        durationMs = 1_000_000, viewOffsetMs = offset, leafCount = 0, viewedLeafCount = 0,
        viewCount = 0, addedAt = 0, lastViewedAt = 0, librarySectionId = null, serverBase = null,
    )

    private val before = ReelyState(
        detail = DetailState(
            ratingKey = "show",
            episodes = listOf(episode("e1"), episode("e2", offset = 100_000), episode("e3")),
            focusedEpisode = episode("e2", offset = 100_000),
        ),
        home = HomeState(continueWatching = listOf(episode("e2", offset = 100_000))),
    )

    @Test fun `the show's page and Continue Watching have where it stopped`() {
        val after = before.withProgress("e2", positionMs = 412_345, durationMs = 1_000_000)
        assertEquals(412_345L, after.detail!!.episodes[1].viewOffsetMs)
        assertEquals(412_345L, after.detail!!.focusedEpisode!!.viewOffsetMs)
        assertEquals(412_345L, after.home.continueWatching[0].viewOffsetMs)
        // The others are left as they were.
        assertEquals(0L, after.detail!!.episodes[0].viewOffsetMs)
    }

    @Test fun `stopped in the credits, it's watched and starts from the top`() {
        val after = before.withProgress("e2", positionMs = 950_000, durationMs = 1_000_000)
        val e2 = after.detail!!.episodes[1]
        assertEquals(0L, e2.viewOffsetMs)
        assertEquals(1, e2.viewCount)
    }

    private fun page(key: String, type: String, leafCount: Int = 0, viewedLeafCount: Int = 0, viewCount: Int = 0) = PlexDetail(
        ratingKey = key, type = type, title = key, summary = null, tagline = null, year = null,
        durationMs = 1_000_000, viewOffsetMs = 0, contentRating = null, rating = null,
        audienceRating = null, airDate = null, viewCount = viewCount, studio = null, thumb = null,
        art = null, theme = null, genres = emptyList(), directors = emptyList(), roles = emptyList(),
        childCount = 1, leafCount = leafCount, grandparentTitle = null, index = null, parentIndex = null,
        viewedLeafCount = viewedLeafCount,
    )

    @Test fun `marking a film watched on its own page changes that page too`() {
        val state = ReelyState(detail = DetailState(ratingKey = "m1", detail = page("m1", "movie")))
        val after = state.withWatched("m1", watched = true)
        assertTrue(after.detail!!.detail!!.isWatched)
        assertFalse(after.withWatched("m1", watched = false).detail!!.detail!!.isWatched)
    }

    @Test fun `one episode watched doesn't make the whole show watched`() {
        assertFalse(page("show", "show", leafCount = 10, viewedLeafCount = 1, viewCount = 1).isWatched)
        assertTrue(page("show", "show", leafCount = 10, viewedLeafCount = 10, viewCount = 12).isWatched)
    }

    @Test fun `a show marked watched marks its episodes, and unmarked unmarks them`() {
        val state = before.copy(detail = before.detail!!.copy(detail = page("show", "show", leafCount = 3)))
        val watched = state.withWatched("show", watched = true)
        assertTrue(watched.detail!!.detail!!.isWatched)
        assertTrue(watched.detail!!.episodes.all { it.isWatched })
        assertEquals(0L, watched.detail!!.episodes[1].viewOffsetMs)
        val unwatched = watched.withWatched("show", watched = false)
        assertFalse(unwatched.detail!!.detail!!.isWatched)
        assertTrue(unwatched.detail!!.episodes.none { it.isWatched })
    }

    @Test fun `marked watched, it leaves a library showing only what's unwatched`() {
        val grid = tv.reely.ui.BrowseState(items = listOf(episode("e1"), episode("e2")), unwatchedOnly = true)
        val all = tv.reely.ui.BrowseState(items = listOf(episode("e1"), episode("e2")))
        val state = ReelyState(
            plex = tv.reely.ui.PlexState(
                browse = mapOf(tv.reely.ui.LibraryKind.SHOWS to grid, tv.reely.ui.LibraryKind.MOVIES to all),
            ),
        )
        val after = state.withWatched("e1", watched = true)
        assertEquals(listOf("e2"), after.plex.browse.getValue(tv.reely.ui.LibraryKind.SHOWS).items.map { it.ratingKey })
        // Everywhere else it stays, ticked.
        assertTrue(after.plex.browse.getValue(tv.reely.ui.LibraryKind.MOVIES).items.first().isWatched)
    }
}
