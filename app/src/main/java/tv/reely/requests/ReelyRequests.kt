package tv.reely.requests

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import tv.reely.core.Http

/** Something that can be asked for: a movie or a show, as Reely's catalogue has it. */
data class RequestTitle(
    val kind: String,
    val tmdbId: Int,
    val tvdbId: Int,
    val title: String,
    val year: Int?,
    val overview: String?,
    /** The poster's full address, ready to load. */
    val poster: String?,
    /** Reely's own poster value, handed back as it came when the title is requested. */
    val posterPath: String?,
) {
    val isShow: Boolean get() = kind == "show"

    /** Unique across kinds: a film and a show can share a TMDB number. */
    val key: String get() = "$kind:${if (tmdbId > 0) "t$tmdbId" else "v$tvdbId"}"
}

/** One row of Reely's discovery: Trending Movies, Popular Shows, what's on a service. */
data class RequestRow(val id: String, val title: String, val titles: List<RequestTitle>)

data class RequestSeason(val number: Int, val name: String, val episodes: Int)

/** A title's page before it is asked for: what it is, its seasons, whether it's already here. */
data class RequestDetail(
    val title: RequestTitle,
    val backdrop: String?,
    val genres: List<String>,
    val runtime: Int?,
    val status: String?,
    val seasons: List<RequestSeason>,
    /** Already in a library this account can watch. */
    val inLibrary: Boolean,
    /** Which of Reely's libraries hold it already. */
    val inLibraries: Set<Long> = emptySet(),
)

/** One of Reely's libraries: a folder of movies or of shows. */
data class RequestLibrary(val id: Long, val name: String, val kind: String) {
    fun takes(title: RequestTitle) = kind == if (title.isShow) "shows" else "movies"
}

/**
 * Where this account's asks can go, as Reely's own request button offers it: a library
 * of the right kind, their default to begin with.
 *
 * Who it's for once it's in is left to Reely: the asker and the groups they're in, never
 * the whole household. That is what Reely's page does unless told otherwise, and on a
 * television nobody needs to tell it otherwise.
 */
data class RequestPlaces(
    val libraries: List<RequestLibrary>,
    val defaultLibraryId: Long,
    /** The owner, or an account that adds without asking: what they ask for is added. */
    val adds: Boolean,
) {
    /** The libraries [title] could go to: the right kind, and not holding it already. */
    fun librariesFor(title: RequestTitle, holding: Set<Long>) =
        libraries.filter { it.takes(title) && it.id !in holding }

    /** Where it goes unless another is picked: the default, when it's one of them. */
    fun preferred(among: List<RequestLibrary>) = among.firstOrNull { it.id == defaultLibraryId } ?: among.firstOrNull()
}

/** Something asked for, and where the asking has got to. */
data class RequestRecord(
    val id: Long,
    val title: RequestTitle,
    /** pending, approved or denied. */
    val status: String,
    /** Null for the whole show, and always for a film. */
    val seasons: List<Int>?,
)

/**
 * What Reely knows about titles it has been asked for or holds, to mark a poster the way
 * Reely's own Explore page does: Downloading, In library, Partial, or Requested.
 */
data class TitleMarks(
    val movies: Map<Int, String> = emptyMap(),
    val showsByTmdb: Map<Int, String> = emptyMap(),
    val showsByTvdb: Map<Int, String> = emptyMap(),
    /** Open requests from anybody who shares a library with this account, by title. */
    val requested: Set<String> = emptySet(),
) {
    /** The mark for [title], or null when Reely has nothing to say about it. */
    fun badge(title: RequestTitle): String? {
        val held = if (title.isShow) {
            (if (title.tvdbId > 0) showsByTvdb[title.tvdbId] else null) ?: showsByTmdb[title.tmdbId]
        } else {
            movies[title.tmdbId]
        }
        return held ?: "Requested".takeIf { requestedKeys(title).any { it in requested } }
    }

    companion object {
        /** Reely's own rule: a show asked for by TheTVDB is still the same show by TMDB. */
        internal fun requestedKeys(title: RequestTitle): List<String> = listOfNotNull(
            if (title.isShow && title.tvdbId > 0) "show-tvdb-${title.tvdbId}" else null,
            if (title.tmdbId > 0) "${title.kind}-${title.tmdbId}" else null,
        )
    }
}

/**
 * Which of this account's approved requests have arrived: a film in the library, or a
 * show with something of it there, as Reely's own marks say. Anything already in [seen]
 * has been said.
 */
fun readyRequests(mine: List<RequestRecord>, marks: TitleMarks, seen: Set<String>): List<RequestTitle> =
    mine.asSequence()
        .filter { it.status == "approved" && it.title.key !in seen }
        .map { it.title }
        .filter { marks.badge(it) == "In library" || (it.isShow && marks.badge(it) == "Partial") }
        .distinctBy { it.key }
        .toList()

/** The answer to asking. */
sealed interface RequestOutcome {
    /** Recorded; approved straight away for a trusted account or a title already held. */
    data class Sent(val approved: Boolean) : RequestOutcome
    data object AlreadyRequested : RequestOutcome
    data class Refused(val message: String) : RequestOutcome
}

/**
 * Reely — the owner's requesting app — as the television reaches it.
 *
 * Signing in is with the Plex account the television already uses: Reely
 * asks plex.tv whose it is and lets in the owner and anybody the server is
 * shared with, the same as its own sign-in. Its session is a cookie, kept
 * here for as long as the app runs; when it lapses, the next call signs
 * in again rather than failing.
 */
class ReelyRequests(
    baseUrl: String,
    private val plexToken: () -> String?,
    /**
     * The Plex account's own sign-in, tried when plex.tv won't take [plexToken] from
     * Reely: a Home profile's sign-in is good only on the device that switched to it.
     */
    private val accountToken: () -> String? = { null },
) {
    val base: String = normalize(baseUrl)

    private val cookies = object : CookieJar {
        private val held = mutableListOf<Cookie>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            held.removeAll { old -> cookies.any { it.name == old.name } }
            held += cookies
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> =
            held.filter { it.matches(url) }
        @Synchronized fun clear() = held.clear()
    }

    private val client = Http.client.newBuilder().cookieJar(cookies).build()
    @Volatile private var signedIn = false

    /** Signs in with the Plex account; the message to show when that can't be done. */
    suspend fun signIn(): String? = withContext(Dispatchers.IO) { signInNow() }

    private fun signInNow(): String? {
        val token = plexToken() ?: return "Sign in to Plex first."
        val problem = signInWith(token)
        if (problem != PLEX_REJECTED) return problem
        val account = accountToken()?.takeIf { it != token } ?: return problem
        return signInWith(account)
    }

    private fun signInWith(token: String): String? {
        cookies.clear()
        val body = JSONObject().put("token", token).toString()
        return runCatching {
            client.newCall(post("/api/v1/auth/plex/token", body)).execute().use { response ->
                signedIn = response.isSuccessful
                val error = if (response.isSuccessful) null else errorOf(response.body?.string())
                when {
                    response.isSuccessful -> null
                    plexRejected(error) -> PLEX_REJECTED
                    ownerLinkBroken(response.code, error) -> OWNER_LINK_BROKEN
                    response.code == 403 -> "Your Plex account doesn't have access to this server's requests."
                    response.code == 404 -> "That server doesn't sign in from the TV yet. Update Reely."
                    response.code == 412 -> "Signing in with Plex isn't set up on this Reely server yet."
                    response.code == 429 -> "Too many tries. Wait a minute and try again."
                    else -> readable(error) ?: "Reely couldn't do that. Try again."
                }
            }
        }.getOrElse { "Couldn't reach Reely at ${hostOf(base)}." }
    }

    suspend fun explore(): List<RequestRow> = withContext(Dispatchers.IO) {
        val root = JSONObject(get("/api/v1/explore"))
        val image = root.optString("imageBase").ifEmpty { TMDB_IMAGES }
        buildList {
            ROWS.forEach { (field, title) ->
                val titles = titlesOf(root.optJSONArray(field), image)
                if (titles.isNotEmpty()) add(RequestRow(field, title, titles))
            }
            val providers = root.optJSONArray("providers") ?: JSONArray()
            for (i in 0 until providers.length()) {
                val row = providers.optJSONObject(i) ?: continue
                val titles = titlesOf(row.optJSONArray("results"), image)
                if (titles.isEmpty()) continue
                val kind = if (row.optString("kind") == "show") "Shows" else "Movies"
                add(RequestRow("provider:${row.optString("key")}:${row.optString("kind")}", "$kind on ${row.optString("name")}", titles))
            }
        }
    }

    suspend fun search(query: String): List<RequestTitle> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val root = JSONObject(get("/api/v1/search?q=" + java.net.URLEncoder.encode(query.trim(), "UTF-8")))
        titlesOf(root.optJSONArray("results"), root.optString("imageBase").ifEmpty { TMDB_IMAGES })
    }

    suspend fun detail(title: RequestTitle): RequestDetail = withContext(Dispatchers.IO) {
        // A show found through TheTVDB has only that id; Reely looks it up there.
        val path = if (title.isShow && title.tmdbId == 0 && title.tvdbId > 0) {
            "/api/v1/preview/show/${title.tvdbId}?src=tvdb"
        } else {
            "/api/v1/preview/${title.kind}/${title.tmdbId}"
        }
        val root = JSONObject(get(path))
        val image = root.optString("imageBase").ifEmpty { TMDB_IMAGES }
        val p = root.getJSONObject("preview")
        val seasons = p.optJSONArray("seasons") ?: JSONArray()
        RequestDetail(
            title = titleOf(p, image) ?: title,
            backdrop = imageUrl(p.optString("backdrop"), image, "w1280"),
            genres = strings(p.optJSONArray("genres")),
            runtime = p.optInt("runtime").takeIf { it > 0 },
            status = p.optString("status").takeIf(String::isNotBlank),
            seasons = (0 until seasons.length()).mapNotNull { seasons.optJSONObject(it) }
                .map { RequestSeason(it.optInt("number"), it.optString("name").ifBlank { "Season ${it.optInt("number")}" }, it.optJSONArray("episodes")?.length() ?: 0) }
                // Specials are season nought; asked for with the rest, not on their own.
                .filter { it.number > 0 },
            inLibrary = (root.optJSONArray("inLibraries")?.length() ?: 0) > 0,
            inLibraries = root.optJSONArray("inLibraries")
                ?.let { a -> (0 until a.length()).map { a.optLong(it) }.toSet() }.orEmpty(),
        )
    }

    /** The libraries an ask can go to, and which it goes to unless told otherwise. */
    suspend fun places(): RequestPlaces = withContext(Dispatchers.IO) {
        val user = JSONObject(get("/api/v1/auth/me")).optJSONObject("user") ?: JSONObject()
        val libraries = JSONObject(get("/api/v1/libraries")).optJSONArray("libraries") ?: JSONArray()
        RequestPlaces(
            libraries = (0 until libraries.length()).mapNotNull { libraries.optJSONObject(it) }.map {
                RequestLibrary(it.optLong("id"), it.optString("name"), it.optString("kind"))
            },
            defaultLibraryId = user.optLong("defaultLibraryId"),
            adds = user.optString("role") == "admin" || user.optBoolean("mayAdd"),
        )
    }

    /**
     * How Reely's library and open requests mark titles, read from the same lists its
     * own Explore page reads. Best effort: without them a poster simply goes unmarked.
     */
    suspend fun marks(): TitleMarks = withContext(Dispatchers.IO) {
        // Each list on its own: one that can't be had leaves the others' marks standing,
        // rather than every poster unmarked.
        fun list(path: String, field: String) =
            runCatching { JSONObject(get(path)).optJSONArray(field) }.getOrNull() ?: JSONArray()
        val movies = list("/api/v1/movies", "movies")
        val shows = list("/api/v1/shows", "shows")
        val open = list("/api/v1/requests", "requests")
        val movieMarks = (0 until movies.length()).mapNotNull { movies.optJSONObject(it) }
            .filter { it.optInt("tmdbId") > 0 }
            .associate { m ->
                m.optInt("tmdbId") to when {
                    m.optBoolean("downloading") -> "Downloading"
                    m.optString("filePath").isNotBlank() -> "In library"
                    else -> "Requested"
                }
            }
        val showRows = (0 until shows.length()).mapNotNull { shows.optJSONObject(it) }
        fun showMark(s: JSONObject) = when {
            s.optBoolean("downloading") -> "Downloading"
            s.optInt("onDisk") == 0 -> "Requested"
            s.optInt("aired") > 0 && s.optInt("wanted") == 0 -> "In library"
            else -> "Partial"
        }
        val requested = buildSet {
            for (i in 0 until open.length()) {
                val r = open.optJSONObject(i) ?: continue
                val kind = r.optString("kind")
                val tvdb = r.optInt("tvdbId")
                val tmdb = r.optInt("tmdbId")
                if (kind == "show" && tvdb > 0) add("show-tvdb-$tvdb")
                if (tmdb > 0) add("$kind-$tmdb")
            }
        }
        TitleMarks(
            movies = movieMarks,
            showsByTmdb = showRows.filter { it.optInt("tmdbId") > 0 }.associate { it.optInt("tmdbId") to showMark(it) },
            showsByTvdb = showRows.filter { it.optInt("tvdbId") > 0 }.associate { it.optInt("tvdbId") to showMark(it) },
            requested = requested,
        )
    }

    /** What this account has asked for, newest first. */
    suspend fun myRequests(): List<RequestRecord> = withContext(Dispatchers.IO) {
        val list = JSONObject(get("/api/v1/requests?mine=1")).optJSONArray("requests") ?: JSONArray()
        (0 until list.length()).mapNotNull { list.optJSONObject(it) }.mapNotNull { r ->
            val title = titleOf(r, TMDB_IMAGES) ?: return@mapNotNull null
            RequestRecord(
                id = r.optLong("id"),
                title = title,
                status = r.optString("status"),
                seasons = r.optJSONArray("seasons")?.let { a -> (0 until a.length()).map { a.optInt(it) } },
            )
        }.sortedByDescending { it.id }
    }

    /**
     * Asks for [title]; for a show, [seasons] or, when null, the whole of it. It goes to
     * [libraryId], or where this account's asks usually land when that's null.
     */
    suspend fun request(
        title: RequestTitle,
        seasons: List<Int>?,
        libraryId: Long? = null,
    ): RequestOutcome = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("kind", title.kind)
            .put("tmdbId", title.tmdbId)
            .put("tvdbId", title.tvdbId)
            .put("title", title.title)
            .put("year", title.year ?: 0)
            .put("poster", title.posterPath ?: "")
            .apply { if (seasons != null) put("seasons", JSONArray(seasons)) }
            .apply { if (libraryId != null) put("libraryId", libraryId) }
            .toString()
        call(post("/api/v1/requests", body)) { code, text ->
            when (code) {
                in 200..299 -> RequestOutcome.Sent(JSONObject(text).optString("status") == "approved")
                409 -> RequestOutcome.AlreadyRequested
                else -> RequestOutcome.Refused(errorOf(text) ?: "Reely couldn't take that request. Try again.")
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun get(path: String): String = call(Request.Builder().url(base + path).get().build()) { code, text ->
        require(code in 200..299) { errorOf(text) ?: "Reely couldn't do that. Try again." }
        text
    }

    private fun post(path: String, json: String): Request =
        Request.Builder().url(base + path).post(json.toRequestBody(JSON)).build()

    /** Makes [request], signing in first when there's no session, and once more if it has lapsed. */
    private fun <T> call(request: Request, read: (Int, String) -> T): T {
        if (!signedIn) signInNow()?.let { error(it) }
        fun once() = client.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        var (code, text) = once()
        if (code == 401) {
            signInNow()?.let { error(it) }
            once().also { code = it.first; text = it.second }
        }
        return read(code, text)
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val TMDB_IMAGES = "https://image.tmdb.org/t/p"

        /** Reely's explore rows, in the order they're shown, and what they're called here. */
        private val ROWS = listOf(
            "movies" to "Trending Movies",
            "shows" to "Trending Shows",
            "popularMovies" to "Popular Movies",
            "popularShows" to "Popular Shows",
            "topMovies" to "Top Rated Movies",
            "topShows" to "Top Rated Shows",
        )

        /** Accepts "reely.example.com", "192.168.1.5:8788", "http://…/", and so on. */
        fun normalize(raw: String): String {
            val trimmed = raw.trim().trimEnd('/')
            if (trimmed.isEmpty()) return trimmed
            return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed
            else "http://$trimmed"
        }

        fun isValid(raw: String): Boolean = normalize(raw).toHttpUrlOrNull() != null

        internal fun hostOf(url: String) = url.substringAfter("://").substringBefore('/')

        internal fun titlesOf(array: JSONArray?, image: String): List<RequestTitle> {
            array ?: return emptyList()
            return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
                .mapNotNull { titleOf(it, image) }
                .distinctBy { it.key }
        }

        internal fun titleOf(o: JSONObject, image: String): RequestTitle? {
            val kind = o.optString("kind").takeIf { it == "movie" || it == "show" } ?: return null
            val tmdb = o.optInt("tmdbId")
            val tvdb = o.optInt("tvdbId")
            if (tmdb <= 0 && tvdb <= 0) return null
            val poster = o.optString("poster").takeIf(String::isNotBlank)
            return RequestTitle(
                kind = kind, tmdbId = tmdb, tvdbId = tvdb,
                title = o.optString("title").ifBlank { return null },
                year = o.optInt("year").takeIf { it > 0 },
                overview = o.optString("overview").takeIf(String::isNotBlank),
                poster = imageUrl(poster, image, "w342"),
                posterPath = poster,
            )
        }

        /** TMDB gives a path to put after its image address; TheTVDB gives a whole address. */
        internal fun imageUrl(value: String?, image: String, size: String): String? = when {
            value.isNullOrBlank() -> null
            value.startsWith("http", ignoreCase = true) -> value
            else -> "${image.trimEnd('/')}/$size/${value.trimStart('/')}"
        }

        private fun strings(array: JSONArray?): List<String> =
            array?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank) } }.orEmpty()

        /** Reely's errors are {"error": "…"}, written to be shown as they are. */
        /**
         * Reely's answer when plex.tv turned down the sign-in this device gave it: the sign-in
         * here was ended by Plex (a password change, or signed out of all devices).
         */
        const val PLEX_REJECTED = "Plex didn't accept this device's sign-in, so Reely can't sign you in. " +
            "Sign out of Plex in Settings and sign in again, then connect."

        /**
         * Reely couldn't check who the Plex server is shared with: the owner's own Plex
         * sign-in, saved when Plex was linked in Reely, has stopped working. Only the
         * owner signs in without that check, so everybody else is turned away until it's
         * linked again. Nothing on this device can fix it.
         */
        const val OWNER_LINK_BROKEN = "Reely's link to Plex has stopped working, so it can't check who the " +
            "server is shared with. The server's owner can fix it in Reely: Settings, Plex, Unlink, then " +
            "Link my Plex account."

        private fun errorOf(text: String?): String? =
            text?.let { runCatching { JSONObject(it).optString("error").takeIf(String::isNotBlank) }.getOrNull() }

        /** Reely's words for plex.tv refusing the token it was given. */
        internal fun plexRejected(error: String?): Boolean =
            error?.lowercase()?.contains("didn't accept that sign-in") == true

        /** Reely passing on plex.tv's refusal of its own saved sign-in, from the sharing check. */
        internal fun ownerLinkBroken(code: Int, error: String?): Boolean =
            code == 502 && error?.lowercase()?.startsWith("plex.tv") == true

        /** Reely's own words, but never a page of markup passed on from somewhere else. */
        internal fun readable(error: String?): String? =
            error?.takeUnless { '<' in it && '>' in it }
    }
}

/**
 * Whether a Plex library here has [title], by the outside ids its items carry (see
 * PlexApi.libraryGuids): films by TMDB, shows by TVDB or TMDB. Kept apart because the
 * same TMDB number means a different title for a film and for a show.
 */
fun plexHas(title: RequestTitle, movies: Set<String>, shows: Set<String>): Boolean =
    if (title.isShow) {
        (title.tvdbId > 0 && "tvdb://${title.tvdbId}" in shows) || (title.tmdbId > 0 && "tmdb://${title.tmdbId}" in shows)
    } else {
        title.tmdbId > 0 && "tmdb://${title.tmdbId}" in movies
    }
