package tv.reely

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.requests.ReelyRequests
import tv.reely.requests.RequestOutcome
import java.net.InetSocketAddress

/**
 * The television talking to Reely, against a stand-in that answers the way Reely's own
 * handlers do: a session cookie from the Plex-token sign-in, then JSON. org.json is
 * Android's, hence Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReelyRequestsTest {
    private lateinit var server: HttpServer
    private var signIns = 0
    private var signInStatus = 200
    private var signInError = "this server isn't shared with your Plex account"
    private var sessionValid = true
    private val requested = mutableListOf<JSONObject>()
    private var alreadyRequested = false
    private var role = "user"

    private fun HttpExchange.reply(code: Int, body: String, cookie: String? = null) {
        cookie?.let { responseHeaders.add("Set-Cookie", it) }
        responseHeaders.add("Content-Type", "application/json")
        val bytes = body.toByteArray()
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun HttpExchange.signedIn() =
        sessionValid && requestHeaders["Cookie"].orEmpty().any { "reely_session=abc" in it }

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/") { ex ->
            val path = ex.requestURI.path
            val query = ex.requestURI.query.orEmpty()
            val body = ex.requestBody.readBytes().decodeToString()
            when {
                path == "/api/v1/auth/plex/token" -> {
                    signIns++
                    if (JSONObject(body).getString("token") == "profile-token") {
                        // A Home profile's sign-in, which plex.tv takes only from the device that switched.
                        ex.reply(401, """{"error":"plex.tv didn't accept that sign-in"}""")
                        return@createContext
                    }
                    assertEquals("plex-account-token", JSONObject(body).getString("token"))
                    sessionValid = true
                    if (signInStatus == 200) {
                        ex.reply(200, """{"status":"ok","user":{"role":"user"}}""", "reely_session=abc; Path=/; HttpOnly")
                    } else {
                        ex.reply(signInStatus, JSONObject().put("error", signInError).toString())
                    }
                }
                !ex.signedIn() -> ex.reply(401, """{"error":"login required"}""")
                path == "/api/v1/explore" -> ex.reply(200, """
                    {"imageBase":"https://image.tmdb.org/t/p",
                     "movies":[{"tmdbId":603,"kind":"movie","title":"The Matrix","year":1999,"poster":"/matrix.jpg"}],
                     "shows":[{"tmdbId":1399,"kind":"show","title":"Game of Thrones","year":2011,"poster":"/got.jpg"}],
                     "popularMovies":[], "popularShows":null, "topMovies":[], "topShows":[],
                     "providers":[{"key":"netflix","name":"Netflix","kind":"show",
                       "results":[{"tvdbId":81189,"kind":"show","title":"Breaking Bad","year":2008,"poster":"https://artworks.thetvdb.com/bb.jpg"}]}]}
                """.trimIndent())
                path == "/api/v1/auth/me" -> ex.reply(200, """
                    {"authRequired":true,"user":{"id":3,"role":"$role","mayAdd":false,"defaultLibraryId":2}}""")
                path == "/api/v1/libraries" -> ex.reply(200, """{"libraries":[
                    {"id":1,"name":"Movies","kind":"movies"},
                    {"id":2,"name":"Kids Movies","kind":"movies"},
                    {"id":3,"name":"TV","kind":"shows"}]}""")
                path == "/api/v1/search" && query.startsWith("q=") -> ex.reply(200, """
                    {"imageBase":"https://image.tmdb.org/t/p","results":[
                     {"tmdbId":27205,"kind":"movie","title":"Inception","year":2010,"poster":"/inc.jpg"}]}
                """.trimIndent())
                path == "/api/v1/preview/show/1399" -> ex.reply(200, """
                    {"imageBase":"https://image.tmdb.org/t/p","inLibraries":[],
                     "preview":{"tmdbId":1399,"kind":"show","title":"Game of Thrones","year":2011,"poster":"/got.jpg",
                       "backdrop":"/got-wide.jpg","genres":["Drama"],"status":"Ended",
                       "seasons":[{"number":0,"name":"Specials","episodes":[{}]},
                                  {"number":1,"name":"Season 1","episodes":[{},{}]},
                                  {"number":2,"name":"Season 2","episodes":[{}]}]}}
                """.trimIndent())
                // A show asked for one season of, which has arrived: two more to ask for.
                path == "/api/v1/preview/show/1396" -> ex.reply(200, """
                    {"imageBase":"https://image.tmdb.org/t/p","inLibraries":[4],
                     "seasonsAsked":[{"libraryId":4,"seasons":[1]}],
                     "preview":{"tmdbId":1396,"kind":"show","title":"Breaking Bad","year":2008,
                       "seasons":[{"number":1,"name":"Season 1","episodes":[{}]},
                                  {"number":2,"name":"Season 2","episodes":[{}]},
                                  {"number":3,"name":"Season 3","episodes":[{}]}]}}
                """.trimIndent())
                path == "/api/v1/preview/movie/603" -> ex.reply(200, """
                    {"imageBase":"https://image.tmdb.org/t/p","inLibraries":[3],
                     "preview":{"tmdbId":603,"kind":"movie","title":"The Matrix","year":1999,"runtime":136,"genres":[]}}
                """.trimIndent())
                path == "/api/v1/movies" -> ex.reply(200, """{"movies":[
                    {"tmdbId":603,"filePath":"/films/matrix.mkv"},
                    {"tmdbId":27205,"filePath":"","downloading":true},
                    {"tmdbId":11,"filePath":""}]}""")
                path == "/api/v1/shows" -> ex.reply(200, """{"shows":[
                    {"tmdbId":1399,"onDisk":10,"aired":73,"wanted":63},
                    {"tmdbId":1396,"onDisk":7,"aired":62,"wanted":0},
                    {"tmdbId":0,"tvdbId":81189,"onDisk":62,"aired":62,"wanted":0}]}""")
                path == "/api/v1/requests" && ex.requestMethod == "GET" && !query.contains("mine=1") ->
                    ex.reply(200, """{"requests":[{"id":2,"kind":"movie","tmdbId":550,"title":"Fight Club","status":"pending"}]}""")
                path == "/api/v1/requests" && ex.requestMethod == "GET" -> {
                    ex.reply(200, """{"requests":[
                        {"id":4,"kind":"movie","tmdbId":603,"title":"The Matrix","year":1999,"poster":"/matrix.jpg","status":"approved"},
                        {"id":9,"kind":"show","tmdbId":1399,"title":"Game of Thrones","poster":"/got.jpg","seasons":[1],"status":"pending"}]}""")
                }
                path == "/api/v1/requests" && ex.requestMethod == "POST" -> {
                    if (alreadyRequested) {
                        ex.reply(409, """{"error":"already requested"}""")
                    } else {
                        requested += JSONObject(body)
                        ex.reply(201, """{"id":10,"status":"pending"}""")
                    }
                }
                else -> ex.reply(404, """{"error":"no such endpoint"}""")
            }
        }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private fun reely() = ReelyRequests("127.0.0.1:${server.address.port}", { "plex-account-token" })

    @Test fun `signs in with the Plex account, then keeps the session`() = runBlocking {
        val reely = reely()
        assertNull(reely.signIn())
        reely.explore()
        reely.myRequests()
        assertEquals("one sign-in for both calls", 1, signIns)
    }

    @Test fun `signs in again when the session has lapsed`() = runBlocking {
        val reely = reely()
        reely.explore()
        sessionValid = false
        reely.explore()
        assertEquals(2, signIns)
    }

    @Test fun `an account the server isn't shared with is told so`() = runBlocking {
        signInStatus = 403
        assertEquals("Your Plex account doesn't have access to this server's requests.", reely().signIn())
    }

    @Test fun `plex_tv turning this device's sign-in down is said in words`() = runBlocking {
        signInStatus = 401
        signInError = "plex.tv didn't accept that sign-in"
        assertEquals(ReelyRequests.PLEX_REJECTED, reely().signIn())
    }

    @Test fun `Reely's own Plex link failing is the owner's to fix, and never shown as markup`() = runBlocking {
        // What Reely sends when the sharing check, made with the owner's saved sign-in, is refused.
        signInStatus = 502
        signInError = "plex.tv: Unauthorized: <?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<errors>\n  <error>Invalid authentication token.</error>\n</errors>"
        assertEquals(ReelyRequests.OWNER_LINK_BROKEN, reely().signIn())
        signInStatus = 500
        signInError = "<html><body>Bad gateway</body></html>"
        assertEquals("Reely couldn't do that. Try again.", reely().signIn())
    }

    @Test fun `a Home profile's sign-in plex_tv won't take from Reely falls back to the account's`() = runBlocking {
        val base = "127.0.0.1:${server.address.port}"
        assertNull(ReelyRequests(base, { "profile-token" }, { "plex-account-token" }).signIn())
        assertEquals(2, signIns)
        assertEquals(ReelyRequests.PLEX_REJECTED, ReelyRequests(base, { "profile-token" }).signIn())
    }

    @Test fun `explore rows, with posters from either TMDB or TheTVDB`() = runBlocking {
        val rows = reely().explore()
        assertEquals(listOf("Trending Movies", "Trending Shows", "Shows on Netflix"), rows.map { it.title })
        assertEquals("https://image.tmdb.org/t/p/w342/matrix.jpg", rows[0].titles[0].poster)
        assertEquals("https://artworks.thetvdb.com/bb.jpg", rows[2].titles[0].poster)
        assertEquals(81189, rows[2].titles[0].tvdbId)
    }

    @Test fun `a show's seasons, without the specials`() = runBlocking {
        val reely = reely()
        val show = reely.explore()[1].titles[0]
        val detail = reely.detail(show)
        assertEquals(listOf(1, 2), detail.seasons.map { it.number })
        assertEquals(2, detail.seasons[0].episodes)
        assertEquals("https://image.tmdb.org/t/p/w1280/got-wide.jpg", detail.backdrop)
        assertEquals(false, detail.inLibrary)
    }

    @Test fun `a film already in the library says so`() = runBlocking {
        val reely = reely()
        val film = reely.explore()[0].titles[0]
        assertTrue(reely.detail(film).inLibrary)
    }

    @Test fun `requesting seasons sends them, and the poster as Reely gave it`() = runBlocking {
        val reely = reely()
        val show = reely.explore()[1].titles[0]
        assertEquals(RequestOutcome.Sent(approved = false), reely.request(show, listOf(1, 2)))
        val sent = requested.single()
        assertEquals("show", sent.getString("kind"))
        assertEquals(1399, sent.getInt("tmdbId"))
        assertEquals("[1,2]", sent.getJSONArray("seasons").toString())
        assertEquals("/got.jpg", sent.getString("poster"))
    }

    @Test fun `the whole show sends no season list`() = runBlocking {
        val reely = reely()
        reely.request(reely.explore()[1].titles[0], null)
        assertTrue(!requested.single().has("seasons"))
    }

    @Test fun `asking twice is already requested`() = runBlocking {
        alreadyRequested = true
        val reely = reely()
        assertEquals(RequestOutcome.AlreadyRequested, reely.request(reely.explore()[0].titles[0], null))
    }

    @Test fun `my requests, newest first, with their status`() = runBlocking {
        val mine = reely().myRequests()
        assertEquals(listOf("Game of Thrones", "The Matrix"), mine.map { it.title.title })
        assertEquals(listOf("pending", "approved"), mine.map { it.status })
        assertEquals(listOf(1), mine[0].seasons)
    }

    @Test fun `search finds what isn't on the server`() = runBlocking {
        assertEquals(listOf("Inception"), reely().search("incep").map { it.title })
    }

    @Test fun `where a request can go, as Reely's own request button offers it`() = runBlocking {
        val reely = reely()
        val places = reely.places()
        val matrix = reely.explore()[0].titles[0]
        val detail = reely.detail(matrix)
        assertEquals(setOf(3L), detail.inLibraries)
        val addable = places.librariesFor(matrix, detail.inLibraries)
        assertEquals(listOf("Movies", "Kids Movies"), addable.map { it.name })
        assertEquals("their default", "Kids Movies", places.preferred(addable)?.name)
        assertEquals(false, places.adds)
    }

    @Test fun `the owner adds rather than asks`() = runBlocking {
        role = "admin"
        assertEquals(true, reely().places().adds)
    }

    @Test fun `the library goes with the request, and who it's for is left to Reely`() = runBlocking {
        val reely = reely()
        reely.request(reely.explore()[0].titles[0], null, libraryId = 1)
        val sent = requested.single()
        assertEquals(1L, sent.getLong("libraryId"))
        assertTrue(!sent.has("audience"))
    }

    @Test fun `what was asked for and has arrived is ready, once`() {
        fun film(id: Int) = tv.reely.requests.RequestTitle("movie", id, 0, "Film $id", null, null, null, null)
        fun show(id: Int) = tv.reely.requests.RequestTitle("show", id, 0, "Show $id", null, null, null, null)
        val mine = listOf(
            tv.reely.requests.RequestRecord(1, film(1), "approved", null),  // here
            tv.reely.requests.RequestRecord(2, film(2), "approved", null),  // still downloading
            tv.reely.requests.RequestRecord(3, film(3), "pending", null),   // not approved
            tv.reely.requests.RequestRecord(4, show(4), "approved", null),  // some of it here
            tv.reely.requests.RequestRecord(5, film(5), "approved", null),  // here, said before
        )
        val marks = tv.reely.requests.TitleMarks(
            movies = mapOf(1 to "In library", 2 to "Downloading", 3 to "In library", 5 to "In library"),
            showsByTmdb = mapOf(4 to "Partial"),
        )
        val ready = tv.reely.requests.readyRequests(mine, marks, seen = setOf(film(5).key))
        assertEquals(listOf("Film 1", "Show 4"), ready.map { it.title })
    }

    @Test fun `addresses are taken as people type them`() {
        assertEquals("http://reely.example.com", ReelyRequests.normalize(" reely.example.com/ "))
        assertEquals("https://r.example.com:8789", ReelyRequests.normalize("https://r.example.com:8789"))
        assertTrue(ReelyRequests.isValid("192.168.1.20:8788"))
        assertTrue(!ReelyRequests.isValid(""))
    }

    @Test fun `titles are marked the way Reely's own Explore marks them`() = runBlocking {
        val marks = reely().marks()
        fun film(id: Int) = tv.reely.requests.RequestTitle("movie", id, 0, "x", null, null, null, null)
        fun show(tmdb: Int, tvdb: Int = 0) = tv.reely.requests.RequestTitle("show", tmdb, tvdb, "x", null, null, null, null)
        assertEquals("In library", marks.badge(film(603)))
        assertEquals("Downloading", marks.badge(film(27205)))
        assertEquals("Requested", marks.badge(film(11)))      // held, file not here yet
        assertEquals("Requested", marks.badge(film(550)))     // somebody's open request
        assertNull(marks.badge(film(99)))
        assertEquals("Partial", marks.badge(show(1399)))
        // One season asked for and all of it here: still only part of the show.
        assertEquals("Partial", marks.badge(show(1396)))
        assertEquals("In library", marks.badge(show(0, 81189)))
    }

    @Test fun `what's held comes first, then my own request, then anybody's`() {
        val title = tv.reely.requests.RequestTitle("movie", 550, 0, "Fight Club", null, null, null, null)
        val mine = listOf(tv.reely.requests.RequestRecord(1, title, "approved", null))
        val asked = tv.reely.requests.TitleMarks(requested = setOf("movie-550"))
        assertEquals("Approved", tv.reely.ui.RequestsState(mine = mine, marks = asked).badgeFor(title))
        assertEquals("Requested", tv.reely.ui.RequestsState(marks = asked).badgeFor(title))
        val held = tv.reely.requests.TitleMarks(movies = mapOf(550 to "In library"))
        assertEquals("In library", tv.reely.ui.RequestsState(mine = mine, marks = held).badgeFor(title))
    }
    @Test fun `browsing rows leave out what's in the library, and drop a row left empty`() {
        fun film(id: Int) = tv.reely.requests.RequestTitle("movie", id, 0, "Film $id", null, null, null, null)
        fun show(id: Int) = tv.reely.requests.RequestTitle("show", id, 0, "Show $id", null, null, null, null)
        val rows = listOf(
            tv.reely.requests.RequestRow("provider:max:movie", "Movies on Max", listOf(film(1), film(2), film(3))),
            tv.reely.requests.RequestRow("shows", "Trending Shows", listOf(show(4), show(5))),
            tv.reely.requests.RequestRow("topMovies", "Top Rated Movies", listOf(film(6))),
        )
        val marks = tv.reely.requests.TitleMarks(
            movies = mapOf(1 to "In library", 2 to "Downloading"),
            showsByTmdb = mapOf(4 to "Partial"),
        )
        // In Plex, though Reely never added it: in the library all the same.
        val state = tv.reely.ui.RequestsState(rows = rows, marks = marks, plexMovies = setOf("tmdb://6"))
        assertEquals(listOf("Movies on Max", "Trending Shows"), state.shownRows.map { it.title })
        assertEquals(listOf("Film 2", "Film 3"), state.shownRows[0].titles.map { it.title })
        // A show with seasons still to come is still there to ask for.
        assertEquals(listOf("Show 4", "Show 5"), state.shownRows[1].titles.map { it.title })
    }

    @Test fun `a show partly here offers the seasons it hasn't got`() = runBlocking {
        val title = tv.reely.requests.RequestTitle("show", 1396, 0, "Breaking Bad", null, null, null, null)
        val detail = reely().detail(title)
        assertEquals(false, detail.complete(4))
        assertEquals(listOf(2, 3), detail.seasonsLeft(4).map { it.number })
        assertEquals(listOf(1, 2, 3), detail.seasonsLeft(5).map { it.number })
        val series = tv.reely.requests.RequestLibrary(4, "TV", "shows")
        val page = tv.reely.ui.RequestDetailState(
            title, detail, busy = false, chosen = setOf(1, 2, 3),
            places = tv.reely.requests.RequestPlaces(listOf(series), 4, adds = false), libraryId = 4,
        )
        assertEquals("the library holding part of it can still take more", listOf(series), page.addable)
        assertEquals(listOf(2, 3), page.seasonsOffered.map { it.number })
        assertEquals(setOf(2, 3), page.chosenOffered)
        assertEquals("Request the other 2 seasons", page.askLabel("Request"))
        assertEquals("Season 1 here already. Pick more to ask for.", page.partNote)
        assertEquals("Request 1 season", page.copy(chosen = setOf(3)).askLabel("Request"))
    }

    @Test fun `a show with every season asked for, or from a Reely that doesn't say, is all here`() = runBlocking {
        val title = tv.reely.requests.RequestTitle("show", 1396, 0, "Breaking Bad", null, null, null, null)
        val detail = reely().detail(title)
        assertEquals(true, detail.copy(seasonsAsked = mapOf(4L to setOf(1, 2, 3))).complete(4))
        assertEquals(true, detail.copy(seasonsAsked = null).complete(4))
    }
}
