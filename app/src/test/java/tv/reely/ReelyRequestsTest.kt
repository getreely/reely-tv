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
    private var sessionValid = true
    private val requested = mutableListOf<JSONObject>()
    private var alreadyRequested = false

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
                    assertEquals("plex-account-token", JSONObject(body).getString("token"))
                    sessionValid = true
                    if (signInStatus == 200) {
                        ex.reply(200, """{"status":"ok","user":{"role":"user"}}""", "reely_session=abc; Path=/; HttpOnly")
                    } else {
                        ex.reply(signInStatus, """{"error":"this server isn't shared with your Plex account"}""")
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

    private fun reely() = ReelyRequests("127.0.0.1:${server.address.port}") { "plex-account-token" }

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
}
