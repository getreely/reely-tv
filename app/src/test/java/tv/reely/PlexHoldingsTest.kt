package tv.reely

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.plex.PlexApi
import tv.reely.requests.RequestTitle
import tv.reely.requests.TitleMarks
import tv.reely.requests.plexHas
import tv.reely.ui.RequestsState

/** A title already in Plex is marked In library, whether or not Reely keeps track of it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PlexHoldingsTest {
    private fun film(tmdb: Int) = RequestTitle(kind = "movie", tmdbId = tmdb, tvdbId = 0, title = "Film", year = 2020, overview = null, poster = null, posterPath = null)
    private fun show(tmdb: Int, tvdb: Int = 0) = RequestTitle(kind = "show", tmdbId = tmdb, tvdbId = tvdb, title = "Show", year = 2020, overview = null, poster = null, posterPath = null)

    @Test fun `the ids come out of Plex's answer`() {
        val metadata = JSONArray(
            """[{"title":"The Matrix","Guid":[{"id":"imdb://tt0133093"},{"id":"tmdb://603"}]},
               {"title":"No ids"}]"""
        )
        assertEquals(setOf("imdb://tt0133093", "tmdb://603"), PlexApi.guidsIn(metadata))
    }

    @Test fun `films match by TMDB, shows by TVDB or TMDB, and never each other`() {
        val movies = setOf("tmdb://603")
        val shows = setOf("tvdb://81189", "tmdb://1399")
        assertTrue(plexHas(film(603), movies, shows))
        assertTrue(plexHas(show(tmdb = 0, tvdb = 81189), movies, shows))
        assertTrue(plexHas(show(tmdb = 1399), movies, shows))
        // The same number is a different title for a film and a show.
        assertFalse(plexHas(film(1399), movies, shows))
        assertFalse(plexHas(show(tmdb = 603), movies, shows))
    }

    @Test fun `Plex's say fills in for Reely, and Reely's fuller word still wins`() {
        val state = RequestsState(
            marks = TitleMarks(movies = mapOf(10 to "Downloading", 11 to "Requested")),
            plexMovies = setOf("tmdb://10", "tmdb://11", "tmdb://12"),
        )
        assertEquals("Downloading", state.badgeFor(film(10)))
        assertEquals("In library", state.badgeFor(film(11)))
        assertEquals("In library", state.badgeFor(film(12)))
        assertEquals(null, state.badgeFor(film(13)))
    }
}
