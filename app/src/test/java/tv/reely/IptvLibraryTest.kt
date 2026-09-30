package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.plex.PlexIndexEntry
import tv.reely.plex.PlexItem
import tv.reely.ui.IptvBrowseOptions
import tv.reely.ui.IptvLibrary
import tv.reely.ui.LibraryKind
import tv.reely.ui.LibrarySort
import tv.reely.xtream.IptvWatch
import tv.reely.xtream.VodCatalog
import tv.reely.xtream.VodItems
import tv.reely.xtream.XtreamCategory
import tv.reely.xtream.XtreamVod
import tv.reely.xtream.isIptv

/** The provider's films and series as the tabs, Home and search show them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class IptvLibraryTest {

    @get:Rule val folder = TemporaryFolder()

    private fun film(id: Int, name: String, year: Int?, added: Long = 0, category: String = "1", tmdb: String? = null, rating: String? = null) =
        XtreamVod.titleOf(buildMap {
            put("stream_id", id.toString()); put("name", name); put("added", added.toString()); put("category_id", category)
            year?.let { put("year", it.toString()) }; tmdb?.let { put("tmdb", it) }; rating?.let { put("rating", it) }
        }, series = false)!!

    private val vod = VodCatalog(
        movies = listOf(
            film(1, "The Matrix", 1999, added = 100, tmdb = "603", rating = "8.7"),
            film(2, "Heat", 1995, added = 300, rating = "8.3"),
            film(3, "Amélie", 2001, added = 200, category = "2", rating = "8.0"),
            film(4, "Zodiac", 2007, added = 50, category = "2"),
        ),
        movieCategories = listOf(XtreamCategory("1", "Action"), XtreamCategory("2", "Drama"), XtreamCategory("3", "Empty")),
    )

    private fun plexMovie(key: String, title: String, year: Int) = PlexItem(
        ratingKey = key, title = title, type = "movie", thumb = null, art = null, summary = null, year = year,
        index = null, parentIndex = null, parentRatingKey = null, parentTitle = null, grandparentRatingKey = null,
        grandparentTitle = null, grandparentThumb = null, durationMs = 0, viewOffsetMs = 0, leafCount = 0,
        viewedLeafCount = 0, viewCount = 0, addedAt = 0, librarySectionId = "1", serverBase = "http://plex",
    )

    private fun library() = IptvLibrary().apply {
        setCatalog(vod)
        // Plex has The Matrix, by its id, under a different name; and Heat by name and year.
        setPlex(
            movies = listOf(
                PlexIndexEntry("10", "http://plex", "Matrix", null, 1999, setOf("tmdb://603")),
                PlexIndexEntry("11", "http://plex", "Heat", null, 1995, emptySet()),
            ),
            shows = emptyList(),
        )
    }

    private fun names(items: List<PlexItem>) = items.map { it.title }

    @Test fun `with Plex winning, the IPTV library leaves out what Plex has`() {
        val grid = library().browse(LibraryKind.MOVIES, IptvBrowseOptions(), iptvWins = false)
        assertEquals(listOf("Amélie", "Zodiac"), names(grid.items))
        assertTrue(grid.items.all { it.isIptv })
        // Categories with nothing left in them aren't offered.
        assertEquals(listOf("Drama"), grid.genres.map { it.title })
    }

    @Test fun `with IPTV winning, it keeps everything and Plex's copies give way`() {
        val iptv = library()
        val grid = iptv.browse(LibraryKind.MOVIES, IptvBrowseOptions(), iptvWins = true)
        assertEquals(4, grid.items.size)
        assertTrue(iptv.hides(plexMovie("11", "Heat", 1995), iptvWins = true))
        assertFalse("not while Plex wins", iptv.hides(plexMovie("11", "Heat", 1995), iptvWins = false))
        assertFalse("nothing like it on IPTV", iptv.hides(plexMovie("12", "Alien", 1979), iptvWins = true))
    }

    @Test fun `sorted and filtered like a Plex library`() {
        val iptv = library()
        fun grid(options: IptvBrowseOptions) = names(iptv.browse(LibraryKind.MOVIES, options, iptvWins = true).items)
        assertEquals(listOf("Amélie", "Heat", "The Matrix", "Zodiac"), grid(IptvBrowseOptions()))
        assertEquals(listOf("Heat", "Amélie", "The Matrix", "Zodiac"), grid(IptvBrowseOptions(sort = LibrarySort.ADDED)))
        assertEquals(listOf("The Matrix", "Heat", "Amélie", "Zodiac"), grid(IptvBrowseOptions(sort = LibrarySort.RATED)))
        assertEquals(listOf("Zodiac", "Amélie", "The Matrix", "Heat"), grid(IptvBrowseOptions(sort = LibrarySort.RELEASED)))
        assertEquals(listOf("Amélie", "Zodiac"), grid(IptvBrowseOptions(categoryId = "2")))
    }

    @Test fun `"The Matrix" goes under M, and the rail counts the letters`() {
        val grid = library().browse(LibraryKind.MOVIES, IptvBrowseOptions(), iptvWins = true)
        assertEquals(listOf("A" to 1, "H" to 1, "M" to 1, "Z" to 1), grid.letters.map { it.letter to it.count })
        assertEquals(2, grid.letterStart("M"))
    }

    @Test fun `unwatched only leaves out what's been watched here`() {
        val iptv = library().apply { watch = IptvWatch(folder.newFile("w.json").apply { delete() }) }
        iptv.watch!!.setWatched(VodItems.movie(vod.movies[1]), true)
        val grid = iptv.browse(LibraryKind.MOVIES, IptvBrowseOptions(unwatchedOnly = true), iptvWins = true)
        assertEquals(listOf("Amélie", "The Matrix", "Zodiac"), names(grid.items))
    }

    @Test fun `Home's row is the newest, less what Plex has`() {
        assertEquals(listOf("Amélie", "Zodiac"), names(library().newest(LibraryKind.MOVIES, iptvWins = false)))
        assertEquals(listOf("Heat", "Amélie", "The Matrix", "Zodiac"), names(library().newest(LibraryKind.MOVIES, iptvWins = true)))
    }

    @Test fun `search finds by name, accents and all, best first`() {
        val iptv = library()
        assertEquals(listOf("Amélie"), names(iptv.search("amelie", iptvWins = false)))
        assertEquals(listOf("The Matrix"), names(iptv.search("matrix", iptvWins = true)))
        assertTrue("Plex's copy is the one found", iptv.search("matrix", iptvWins = false).isEmpty())
        assertTrue(iptv.search("  ", iptvWins = true).isEmpty())
    }

    @Test fun `more like this is the same category`() {
        val related = library().related(VodItems.movie(vod.movies[2]), iptvWins = true)
        assertEquals(listOf("Zodiac"), names(related))
    }

    @Test fun `switched off, there's nothing`() {
        val iptv = library().apply { clear() }
        assertTrue(iptv.browse(LibraryKind.MOVIES, IptvBrowseOptions(), iptvWins = true).items.isEmpty())
        assertTrue(iptv.newest(LibraryKind.MOVIES, iptvWins = true).isEmpty())
        assertTrue(iptv.search("heat", iptvWins = true).isEmpty())
    }
}
