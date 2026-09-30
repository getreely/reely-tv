package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.plex.PlexIndexEntry
import tv.reely.xtream.IPTV_SOURCE
import tv.reely.xtream.IptvKey
import tv.reely.xtream.IptvWatch
import tv.reely.xtream.TitleIndex
import tv.reely.xtream.VodCatalog
import tv.reely.xtream.VodCatalogCache
import tv.reely.xtream.VodItems
import tv.reely.xtream.VodNames
import tv.reely.xtream.XtreamCategory
import tv.reely.xtream.XtreamVod
import java.io.StringReader

/** The provider's films and series: read, tidied, matched with Plex, and remembered. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VodTest {

    @get:Rule val folder = TemporaryFolder()

    @Test fun `provider names come apart into title, year and tag`() {
        assertEquals(VodNames.Parsed("The Matrix", 1999, "EN"), VodNames.parse("EN - The Matrix (1999)"))
        assertEquals(VodNames.Parsed("Dune", null, "4K"), VodNames.parse("|4K| Dune"))
        assertEquals(VodNames.Parsed("Amélie", 2001, "FR"), VodNames.parse("[FR] Amélie - 2001"))
        assertEquals(VodNames.Parsed("Heat", 1995, null), VodNames.parse("Heat [1995]"))
    }

    @Test fun `names that only look like tags are left alone`() {
        assertEquals("CSI: Miami", VodNames.parse("CSI: Miami").name)
        assertEquals("WALL-E", VodNames.parse("WALL-E").name)
        assertEquals("2012", VodNames.parse("2012").name)
        assertEquals("Blade Runner 2049", VodNames.parse("Blade Runner 2049").name)
        // A year the list gives is believed over one guessed from the name.
        assertEquals(2017, VodNames.parse("Blade Runner 2049 (2017)", knownYear = 2017).year)
    }

    @Test fun `a film list is read as it streams, odd fields and all`() {
        val body = """[
            {"num":1,"name":"EN - The Matrix (1999)","stream_id":10,"stream_icon":"http://img/m.jpg",
             "rating":"8.7","added":"1600000000","category_id":"3","container_extension":"mkv","tmdb":"603"},
            {"num":2,"name":"Heat","stream_id":"11","stream_icon":"","rating":0,"added":null,"category_id":3},
            {"num":3,"name":"No id"},
            "junk",
            {"num":4,"name":"The Matrix again","stream_id":10}
        ]"""
        val titles = XtreamVod.readTitles(StringReader(body), series = false)
        assertEquals(listOf(10, 11), titles.map { it.id })
        val matrix = titles[0]
        assertEquals("The Matrix", matrix.name)
        assertEquals(1999, matrix.year)
        assertEquals("EN", matrix.tag)
        assertEquals(8.7, matrix.rating!!, 0.001)
        assertEquals(1_600_000_000L, matrix.addedAt)
        assertEquals("603", matrix.tmdbId)
        assertEquals("mkv", matrix.extension)
        assertNull("no picture is no picture", titles[1].poster)
        assertNull(titles[1].rating)
    }

    @Test fun `a refused login is no films, not a crash`() {
        assertTrue(XtreamVod.readTitles(StringReader("""{"user_info":{"auth":0}}"""), series = false).isEmpty())
    }

    @Test fun `a series list reads its own fields`() {
        val body = """[{"name":"Breaking Bad","series_id":5,"cover":"http://img/bb.jpg","plot":"Chemistry.",
            "genre":"Drama","releaseDate":"2008-01-20","last_modified":"1700000000","category_id":"9","tmdb":"1396"}]"""
        val show = XtreamVod.readTitles(StringReader(body), series = true).single()
        assertTrue(show.series)
        assertEquals(5, show.id)
        assertEquals(2008, show.year)
        assertEquals("Chemistry.", show.plot)
        assertEquals(1_700_000_000L, show.addedAt)
    }

    @Test fun `a film's page`() {
        val info = XtreamVod.parseMovieInfo(
            """{"info":{"name":"The Matrix","plot":"Neo.","cast":"Keanu Reeves, Carrie-Anne Moss","director":"Lana Wachowski",
               "genre":"Action / Sci-Fi","duration_secs":"8160","backdrop_path":["http://img/back.jpg"],"releasedate":"1999-03-31",
               "rating":"8.7","tmdb_id":"603"},"movie_data":{"stream_id":10,"container_extension":"mkv"}}"""
        )!!
        assertEquals(listOf("Keanu Reeves", "Carrie-Anne Moss"), info.cast)
        assertEquals(listOf("Action", "Sci-Fi"), info.genres)
        assertEquals(8_160_000L, info.durationMs)
        assertEquals("http://img/back.jpg", info.backdrop)
        assertEquals("mkv", info.extension)
    }

    @Test fun `a page with nothing to say is still a page`() {
        val info = XtreamVod.parseMovieInfo("""{"info":[],"movie_data":{"stream_id":10}}""")!!
        assertNull(info.plot)
        assertEquals(0L, info.durationMs)
    }

    @Test fun `a series' seasons and episodes, keyed by season or listed`() {
        val keyed = XtreamVod.parseSeriesInfo(
            """{"info":{"name":"Show","plot":"About.","backdrop_path":[]},
                "seasons":[{"season_number":1,"cover":"http://img/s1.jpg"}],
                "episodes":{"2":[{"id":"22","episode_num":1,"title":"Two One","container_extension":"mp4","info":{"duration":"00:42:00"}}],
                            "1":[{"id":"12","episode_num":2,"title":"One Two"},{"id":"11","episode_num":1,"title":"One One","info":{"plot":"First."}}],
                            "0":[{"id":"1","episode_num":1,"title":"Special"}]}}"""
        )!!
        assertEquals(listOf(0, 1, 2), keyed.seasons.map { it.number })
        assertEquals(listOf("Specials", "Season 1", "Season 2"), keyed.seasons.map { it.name })
        assertEquals(listOf(11, 12), keyed.seasons[1].episodes.map { it.id })
        assertEquals("http://img/s1.jpg", keyed.seasons[1].poster)
        assertEquals(2_520_000L, keyed.seasons[2].episodes[0].durationMs)

        val listed = XtreamVod.parseSeriesInfo(
            """{"info":{},"episodes":[[{"id":"5","season":1,"episode_num":1,"title":"A"}],[{"id":"6","season":2,"episode_num":1,"title":"B"}]]}"""
        )!!
        assertEquals(listOf(1, 2), listed.seasons.map { it.number })
    }

    @Test fun `keys say what they are and carry what playing needs`() {
        assertEquals(IptvKey.Movie(10, "mkv"), IptvKey.parse(IptvKey.movie(10, "mkv")))
        assertEquals(IptvKey.Movie(10, null), IptvKey.parse(IptvKey.movie(10, null)))
        assertEquals(IptvKey.Show(5), IptvKey.parse(IptvKey.show(5)))
        assertEquals(IptvKey.Season(5, 2), IptvKey.parse(IptvKey.season(5, 2)))
        assertEquals(IptvKey.Episode(22, "mp4"), IptvKey.parse(IptvKey.episode(22, "mp4")))
        assertNull(IptvKey.parse("12345"))
        assertNull(IptvKey.parse("x"))
    }

    @Test fun `episodes are items under their show, specials outside any season`() {
        val info = XtreamVod.parseSeriesInfo(
            """{"info":{},"episodes":{"0":[{"id":"1","episode_num":1,"title":"Special"}],"1":[{"id":"11","episode_num":1,"title":"Pilot","container_extension":"mkv"}]}}"""
        )!!
        val specials = VodItems.episodes(5, "Show", null, null, info.seasons[0])
        val first = VodItems.episodes(5, "Show", null, null, info.seasons[1]).single()
        assertNull(specials.single().parentIndex)
        assertEquals(1, first.parentIndex)
        assertEquals("s5", first.grandparentRatingKey)
        assertEquals("s5:1", first.parentRatingKey)
        assertEquals(IPTV_SOURCE, first.serverBase)
        assertEquals(IptvKey.Episode(11, "mkv"), IptvKey.parse(first.ratingKey))
    }

    private fun title(id: Int, name: String, year: Int?, tmdb: String? = null) =
        XtreamVod.titleOf(buildMap {
            put("stream_id", id.toString()); put("name", name)
            year?.let { put("year", it.toString()) }; tmdb?.let { put("tmdb", it) }
        }, series = false)!!

    @Test fun `a title in both is matched by its id, else by name and year`() {
        val plex = TitleIndex.ofPlex(
            listOf(
                PlexIndexEntry("1", "http://a", "The Matrix", null, 1999, setOf("tmdb://603", "imdb://tt0133093")),
                PlexIndexEntry("2", "http://a", "Amélie", "Le Fabuleux Destin d'Amélie Poulain", 2001, emptySet()),
            )
        )
        val matrix = title(10, "EN - The Matrix Reloaded", 2003, tmdb = "603")
        assertTrue("same id, whatever it's called", plex.has(matrix.tmdbId, matrix.key, matrix.year))
        val amelie = title(11, "Amelie (2002)", null)
        assertTrue("accents off, a year out", plex.has(amelie.tmdbId, amelie.key, amelie.year))
        val remake = title(12, "Amelie", 2021)
        assertFalse("a different year is a different film", plex.has(remake.tmdbId, remake.key, remake.year))
        val undated = title(13, "The Matrix", null)
        assertTrue("no year, by name alone", plex.has(undated.tmdbId, undated.key, undated.year))
        assertFalse(TitleIndex.EMPTY.has("603", "thematrix", 1999))
    }

    @Test fun `where a film was left is kept, and near the end is watched`() {
        val file = folder.newFile("watch.json").apply { delete() }
        var clock = 1_000L
        val watch = IptvWatch(file) { clock }
        val film = VodItems.movie(title(10, "Heat", 1995))
        watch.progress(film, positionMs = 600_000, durationMs = 6_000_000)
        assertEquals(600_000L, watch.apply(film).viewOffsetMs)
        assertEquals(listOf(film.ratingKey), watch.continueWatching().map { it.ratingKey })

        clock = 2_000L
        watch.progress(film, positionMs = 5_500_000, durationMs = 6_000_000)
        val done = IptvWatch(file).apply(film)
        assertTrue("watched, and read back from the file", done.isWatched)
        assertEquals(0L, done.viewOffsetMs)
        assertTrue(IptvWatch(file).continueWatching().isEmpty())

        watch.setWatched(film, false)
        assertFalse(watch.apply(film).isWatched)
    }

    @Test fun `a show counts its watched episodes`() {
        val file = folder.newFile("shows.json").apply { delete() }
        val watch = IptvWatch(file)
        val info = XtreamVod.parseSeriesInfo(
            """{"info":{},"episodes":{"1":[{"id":"11","episode_num":1,"title":"A"},{"id":"12","episode_num":2,"title":"B"}]}}"""
        )!!
        val episodes = VodItems.episodes(5, "Show", null, null, info.seasons[0])
        watch.setWatched(episodes[0], true)
        val season = VodItems.seasons(5, "Show", null, info).single()
        assertEquals(1, watch.apply(season).viewedLeafCount)
        assertFalse(watch.apply(season).isWatched)
        watch.setWatched(episodes[1], true)
        assertTrue(watch.apply(season).isWatched)
    }

    @Test fun `the catalogue is kept on the device and read back whole`() {
        val catalog = VodCatalog(
            movies = listOf(title(10, "EN - The Matrix (1999)", null, tmdb = "603"), title(11, "Heat", null)),
            series = XtreamVod.readTitles(StringReader("""[{"name":"Show","series_id":5,"plot":"About."}]"""), series = true),
            movieCategories = listOf(XtreamCategory("3", "Action")),
            seriesCategories = listOf(XtreamCategory("9", "Drama")),
            loadedAt = 42,
        )
        val file = folder.newFile("vod.json")
        VodCatalogCache.write(file, catalog)
        assertEquals(catalog, VodCatalogCache.read(file))
        assertNull(VodCatalogCache.read(folder.root.resolve("missing.json")))
    }
}
