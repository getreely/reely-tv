package tv.reely.plex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.reely.core.qualityBadges
import tv.reely.core.versionDetail
import tv.reely.core.versionLabel
import tv.reely.core.Http
import java.net.URLDecoder
import java.net.URLEncoder

data class PlexPin(val id: Long, val code: String)

/**
 * Somebody in a Plex Home, or the account signed in. [protected] means switching to them
 * asks for their PIN; [admin] is the account that owns the Home.
 */
data class PlexHomeUser(
    val uuid: String,
    val title: String,
    val thumb: String?,
    val protected: Boolean,
    val admin: Boolean,
    val restricted: Boolean,
)

data class PlexServer(
    val name: String,
    val accessToken: String,
    val connections: List<String>,
)

data class PlexSection(
    val key: String,
    val title: String,
    val type: String,
)

/** One genre a library can be narrowed to. The id is what the filter takes. */
/** One title of a library, as far as matching it with a title elsewhere goes. */
data class PlexIndexEntry(
    val ratingKey: String,
    val serverBase: String?,
    val title: String,
    val originalTitle: String?,
    val year: Int?,
    /** "tmdb://603", "imdb://tt0133093". */
    val guids: Set<String>,
)

data class PlexGenre(val id: String, val title: String)

/** One letter of a library in title order, and how many titles start with it. "#" is digits and the rest. */
data class PlexLetter(val letter: String, val count: Int)

/**
 * A sidecar or embedded text subtitle Plex is willing to hand over as a separate file.
 * Image-based subtitles (PGS, VOBSUB) are deliberately absent: those can only be burned
 * into the video by the server, which is a transcode decision this build does not make.
 */
data class PlexSubtitle(
    val id: String,
    val label: String,
    val url: String,
    val mimeType: String,
    val language: String?,
)

/** Subtitles found online for a title, which the server can fetch and add to it. */
data class PlexOnlineSubtitle(
    val key: String,
    val title: String,
    val provider: String?,
    val language: String?,
    val codec: String?,
    val hearingImpaired: Boolean,
    val forced: Boolean,
)

/** A trailer or other extra attached to a library item. */
data class PlexExtra(
    val ratingKey: String,
    val title: String,
    val durationMs: Long,
)

/**
 * A stretch of an episode the server has identified — the intro, or the closing credits.
 * Plex generates these itself, as a Plex Pass feature, so a server without it simply
 * returns none and the buttons that use them never appear.
 */
data class PlexMarker(
    val type: String,
    val startMs: Long,
    val endMs: Long,
) {
    val isIntro: Boolean get() = type.equals("intro", ignoreCase = true)
    val isCredits: Boolean get() = type.equals("credits", ignoreCase = true)
}

/** One of a film's chapters, as the file names them, with the server's picture of it. */
data class PlexChapter(
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val thumbUrl: String? = null,
)

data class PlexPlayback(
    val url: String,
    val subtitles: List<PlexSubtitle>,
    val markers: List<PlexMarker> = emptyList(),
    /** The sound that will play, by Plex's name for it — `eac3`, `ac3`, `aac` — if known. */
    val audioCodec: String? = null,
    val audioChannels: Int = 0,
    /**
     * Where the server's preview pictures for this file are, with `{ms}` for the moment
     * wanted; null when it hasn't made any. They are the pictures above the bar while
     * scrubbing.
     */
    val previewUrl: String? = null,
    /** The file's chapters, in order; empty when it has none worth offering. */
    val chapters: List<PlexChapter> = emptyList(),
    /** The file's part, which a choice of sound or subtitles is saved against. */
    val partId: Long? = null,
    /** The sound and subtitle streams, in the file's order, with the server's choice marked. */
    val audioStreams: List<tv.reely.core.PlexStream> = emptyList(),
    val subtitleStreams: List<tv.reely.core.PlexStream> = emptyList(),
)

/** An actor who turned up in a search. */
data class PlexPerson(val id: String, val name: String, val thumb: String?, val serverBase: String?)

/** What one server found for a search: titles, the people named, and collections. */
data class PlexFound(
    val items: List<PlexItem> = emptyList(),
    val people: List<PlexPerson> = emptyList(),
    val collections: List<PlexItem> = emptyList(),
)

/** One of a title's files, when it has more than one: a 4K copy and a 1080p one, say. */
data class PlexVersion(val label: String, val detail: String?)

/** A person in the cast, as Plex records them. */
data class PlexRole(
    val name: String,
    val role: String?,
    val thumb: String?,
    /** The server's id for the person, which its libraries can be filtered by. */
    val id: String? = null,
)

data class PlexItem(
    val ratingKey: String,
    val title: String,
    val type: String,
    val thumb: String?,
    val art: String?,
    val summary: String?,
    val year: Int?,
    val index: Int?,
    val parentIndex: Int?,
    val parentRatingKey: String?,
    val parentTitle: String?,
    val grandparentRatingKey: String?,
    val grandparentTitle: String?,
    val grandparentThumb: String?,
    val durationMs: Long,
    val viewOffsetMs: Long,
    val leafCount: Int,
    val viewedLeafCount: Int,
    val viewCount: Int,
    val addedAt: Long,
    /**
     * When this was last played, as Unix seconds, or 0 if never. It is an absolute time,
     * so unlike a server's own ordering it can be compared across servers.
     */
    val lastViewedAt: Long = 0,
    /**
     * The title's own logo, as Plex serves it — a transparent image, set in the show's
     * or film's type, shown in place of the title as text. For an episode it is its
     * show's. Absent when the server has none.
     */
    val logo: String? = null,
    /** What the file is — 4K, Dolby Vision, 5.1 — as far as this listing says. */
    val qualities: List<String> = emptyList(),
    /** When it first aired or was released, as Plex gives it: "2008-09-16". */
    val airDate: String? = null,
    /** The title as Plex sorts it: "Matrix" for "The Matrix". Absent when it's the title. */
    val titleSort: String? = null,
    /** Which library this came from, so a tab can show only its own library's things. */
    val librarySectionId: String?,
    /**
     * Which server served it. Rows can hold things from several at once, and an item has
     * to be fetched, drawn and played against the server it actually lives on. Null means
     * whichever server is currently connected.
     */
    val serverBase: String? = null,
) {
    val isPlayable: Boolean get() = type == "movie" || type == "episode"

    /**
     * A film or episode is watched once Plex has counted a view. A show or season is
     * watched when every episode under it has been.
     */
    val isWatched: Boolean
        get() = when (type) {
            "movie", "episode" -> viewCount > 0
            "show", "season" -> leafCount > 0 && viewedLeafCount >= leafCount
            else -> false
        }

    val isShow: Boolean get() = type == "show"

    /** Where playback left off, as a fraction, or null when it has not been started. */
    val resumeFraction: Float?
        get() = if (viewOffsetMs > 0 && durationMs > 0) {
            (viewOffsetMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            null
        }

    /** "S2 · E7" for an episode, the year for a film, an episode count for a season. */
    val caption: String?
        get() = when (type) {
            "episode" -> listOfNotNull(
                parentIndex?.let { "S$it" },
                index?.let { "E$it" },
            ).joinToString(" · ").takeIf { it.isNotEmpty() }

            "season" -> leafCount.takeIf { it > 0 }?.let { "$it episodes" }
            "collection" -> leafCount.takeIf { it > 0 }?.let { if (it == 1) "1 title" else "$it titles" }
            else -> year?.toString()
        }

    /**
     * Unique across servers, which a rating key is not. Rows merged from several servers
     * would otherwise hand a lazy list the same key twice, which it treats as a fault.
     */
    val listKey: String get() = (serverBase ?: "") + "|" + ratingKey

    /** What to put under a poster in a home row: the show for an episode, else the title. */
    val rowTitle: String get() = if (type == "episode") grandparentTitle ?: title else title
}

data class PlexDetail(
    val ratingKey: String,
    val type: String,
    val title: String,
    val summary: String?,
    val tagline: String?,
    val year: Int?,
    val durationMs: Long,
    val viewOffsetMs: Long,
    val contentRating: String?,
    val rating: Double?,
    val audienceRating: Double?,
    val airDate: String?,
    val viewCount: Int,
    val studio: String?,
    val thumb: String?,
    val art: String?,
    /** A show's title music, when the server has it. Films do not carry one. */
    val theme: String?,
    val genres: List<String>,
    val directors: List<String>,
    val roles: List<PlexRole>,
    /** Who wrote it, as the server lists them. */
    val writers: List<String> = emptyList(),
    val childCount: Int,
    val leafCount: Int,
    val grandparentTitle: String?,
    val index: Int?,
    val parentIndex: Int?,
    /** See [PlexItem.logo]. */
    val logo: String? = null,
    /** See [PlexItem.qualities]; a full item carries its HDR, which a listing may not. */
    val qualities: List<String> = emptyList(),
    /** The files it comes in, in the server's order; empty when there is only the one. */
    val versions: List<PlexVersion> = emptyList(),
    /** Plex's own id for the title, "plex://movie/…", shared by every server that has it. */
    val guid: String? = null,
    /** For a show or season: how many of its episodes have been watched. */
    val viewedLeafCount: Int = 0,
    /** For a show: the episode to watch next, and its season, as the server has it. */
    val onDeckKey: String? = null,
    val onDeckSeasonKey: String? = null,
) {
    val isShow: Boolean get() = type == "show"

    /**
     * As for an item: a show or season is watched when every episode under it has been.
     * Its view count is how many views there have been, so one episode watched made the
     * whole show read as watched, and its page offered to unwatch it.
     */
    val isWatched: Boolean
        get() = when (type) {
            "movie", "episode" -> viewCount > 0
            "show", "season" -> leafCount > 0 && viewedLeafCount >= leafCount
            else -> false
        }

    /** "2014 · 2h 18m · TV-MA" — the line Plex puts under a title. */
    val facts: String
        get() = listOfNotNull(
            year?.toString(),
            formatDuration(durationMs).takeIf { durationMs > 0 },
            contentRating,
            studio,
        ).joinToString("  ·  ")
}

/**
 * "Sep 16, 2008" from Plex's "2008-09-16", in the device's own way of writing a date.
 * Null for anything that isn't a whole date, rather than showing it half-parsed.
 */
fun formatAirDate(date: String?, locale: java.util.Locale = java.util.Locale.getDefault()): String? {
    val text = date?.take(10)?.takeIf { Regex("""\d{4}-\d{2}-\d{2}""").matches(it) } ?: return null
    // java.text rather than java.time, which needs Android 8 and this runs on 6. Read and
    // written in UTC so the date can't slip a day either side of midnight.
    val utc = java.util.TimeZone.getTimeZone("UTC")
    val parser = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).apply {
        timeZone = utc
        isLenient = false
    }
    val parsed = runCatching { parser.parse(text) }.getOrNull() ?: return null
    return java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, locale)
        .apply { timeZone = utc }
        .format(parsed)
}

fun formatDuration(millis: Long): String {
    if (millis <= 0) return ""
    val totalMinutes = millis / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

/**
 * The plex.tv PIN flow plus the server endpoints this app reads. Everything here is a
 * plain JSON read: the hard streaming work stays on the server.
 */
object PlexApi {

    /**
     * Set once at startup. A Plex server tailors what it returns to who is asking, and a
     * request with no client identifier is not something any real client sends.
     */
    @Volatile
    var clientId: String = ""

    private const val PLEX_TV = "https://plex.tv"

    /** Where the account's Watchlist lives: Plex's own catalogue, not any one server. */
    private const val DISCOVER = "https://discover.provider.plex.tv"
    private const val PRODUCT = "Reely TV"
    // What Plex shows for this app in its dashboard and the account's list of devices.
    private val VERSION = tv.reely.BuildConfig.VERSION_NAME
    private val PLATFORM_VERSION = android.os.Build.VERSION.RELEASE.orEmpty()

    /** The television's own name, so two sets in one house can be told apart in Plex. */
    @Volatile
    var deviceName: String = "Reely TV"
    internal const val AUDIO_STREAM = 2
    internal const val SUBTITLE_STREAM = 3

    // Plex's own type filters, used when asking a section for one kind of thing.
    const val TYPE_MOVIE = 1
    const val TYPE_SHOW = 2
    const val TYPE_EPISODE = 4

    private fun Request.Builder.plexHeaders(clientId: String, token: String? = null) = apply {
        header("accept", "application/json")
        header("X-Plex-Product", PRODUCT)
        header("X-Plex-Version", VERSION)
        header("X-Plex-Client-Identifier", clientId)
        header("X-Plex-Platform", "Android")
        header("X-Plex-Platform-Version", PLATFORM_VERSION)
        header("X-Plex-Device", android.os.Build.MODEL.orEmpty().ifBlank { "Android TV" })
        header("X-Plex-Device-Name", deviceName)
        if (token != null) header("X-Plex-Token", token)
    }

    /**
     * A sign-in PIN. The short kind is the four characters typed at plex.tv/link. The
     * strong kind is a long string nobody could type, which is why it only travels
     * inside [authUrl] — the address the QR code on the sign-in screen holds.
     */
    suspend fun createPin(clientId: String, strong: Boolean = false): PlexPin = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$PLEX_TV/api/v2/pins")
            .plexHeaders(clientId)
            .post(FormBody.Builder().add("strong", strong.toString()).build())
            .build()
        Http.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            require(response.isSuccessful) { "Couldn't get a sign-in code from Plex. Try again." }
            val json = JSONObject(body)
            PlexPin(json.getLong("id"), json.getString("code"))
        }
    }

    /**
     * Plex's own sign-in page for a strong PIN: open it on a phone, sign in or just
     * confirm, and the PIN is approved without typing anything.
     */
    fun authUrl(clientId: String, code: String): String {
        fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        return "https://app.plex.tv/auth#?clientID=${enc(clientId)}&code=${enc(code)}" +
            "&context%5Bdevice%5D%5Bproduct%5D=${enc(PRODUCT)}"
    }

    /** Returns the account token once the code has been entered, or null while still pending. */
    suspend fun claimPin(clientId: String, pinId: Long): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$PLEX_TV/api/v2/pins/$pinId")
            .plexHeaders(clientId)
            .get()
            .build()
        Http.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) return@withContext null
            val token = JSONObject(body).optString("authToken")
            token.takeIf { it.isNotEmpty() && it != "null" }
        }
    }

    /**
     * Whether plex.tv still takes [token]: false when it turns it down (the sign-in was
     * ended there), null when plex.tv couldn't be asked.
     */
    suspend fun tokenAccepted(clientId: String, token: String): Boolean? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$PLEX_TV/api/v2/user")
            .plexHeaders(clientId, token)
            .get()
            .build()
        runCatching {
            Http.client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> true
                    response.code == 401 || response.code == 403 -> false
                    else -> null
                }
            }
        }.getOrNull()
    }

    /** Who the token belongs to: its name and picture, and its place in a Home. */
    suspend fun account(clientId: String, token: String): PlexHomeUser? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$PLEX_TV/api/v2/user")
            .plexHeaders(clientId, token)
            .get()
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            homeUserOf(JSONObject(response.body?.string().orEmpty()))
        }
    }

    /**
     * Everybody in the account's Plex Home, as Plex's own "Who's watching?" lists them.
     * Empty for an account that isn't in a Home, which is most of them.
     */
    suspend fun homeUsers(clientId: String, token: String): List<PlexHomeUser> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$PLEX_TV/api/v2/home/users")
            .plexHeaders(clientId, token)
            .get()
            .build()
        Http.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext emptyList()
            homeUsersFrom(response.body?.string().orEmpty())
        }
    }

    /** The users in a home/users answer, which is either the list or the Home around it. */
    internal fun homeUsersFrom(body: String): List<PlexHomeUser> {
        val text = body.trim()
        val users = runCatching {
            if (text.startsWith("[")) JSONArray(text) else JSONObject(text).optJSONArray("users")
        }.getOrNull() ?: return emptyList()
        return (0 until users.length()).mapNotNull { users.optJSONObject(it)?.let(::homeUserOf) }
    }

    /**
     * Becomes another member of the Home, and returns their token. [pin] is theirs, when
     * they have one; a wrong one comes back as an error that says so.
     */
    suspend fun switchHomeUser(clientId: String, token: String, uuid: String, pin: String?): String =
        withContext(Dispatchers.IO) {
            val url = "$PLEX_TV/api/v2/home/users/$uuid/switch" +
                (pin?.let { "?pin=" + URLEncoder.encode(it, "UTF-8") } ?: "")
            val request = Request.Builder()
                .url(url)
                .plexHeaders(clientId, token)
                .post(FormBody.Builder().build())
                .build()
            Http.client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                require(response.code != 401 && response.code != 403) {
                    if (pin != null) "That PIN isn't right. Try again." else "Plex didn't allow switching to this profile."
                }
                require(response.isSuccessful) { "Couldn't switch profiles. Try again." }
                JSONObject(body).optString("authToken").takeIf { it.isNotBlank() && it != "null" }
                    ?: error("Couldn't switch profiles. Try again.")
            }
        }

    private fun homeUserOf(entry: JSONObject): PlexHomeUser? {
        val uuid = entry.optString("uuid").takeIf(String::isNotBlank) ?: return null
        return PlexHomeUser(
            uuid = uuid,
            title = entry.optString("title").ifBlank { entry.optString("username") }.ifBlank { "Plex user" },
            thumb = entry.optString("thumb").takeIf { it.startsWith("http") },
            // A PIN on the profile. hasPassword is the account's password, not this.
            protected = entry.optBoolean("protected"),
            admin = entry.optBoolean("admin"),
            restricted = entry.optBoolean("restricted"),
        )
    }

    /**
     * Servers the signed-in account can reach — both its own and the ones shared with it,
     * which is how the library half works when somebody else owns the library.
     */
    suspend fun servers(clientId: String, token: String): List<PlexServer> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$PLEX_TV/api/v2/resources?includeHttps=1&includeRelay=1")
            .plexHeaders(clientId, token)
            .get()
            .build()
        Http.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            require(response.isSuccessful) { "Couldn't load your Plex servers. Try again." }
            val resources = JSONArray(body)
            val owned = mutableSetOf<String>()
            buildList {
                for (index in 0 until resources.length()) {
                    val resource = resources.getJSONObject(index)
                    if (!resource.optString("provides").contains("server")) continue

                    val connections = resource.optJSONArray("connections") ?: JSONArray()
                    val uris = connectionOrder(
                        (0 until connections.length()).map { index ->
                            val connection = connections.getJSONObject(index)
                            PlexConnection(
                                uri = connection.optString("uri"),
                                address = connection.optString("address"),
                                port = connection.optInt("port", 32400),
                                local = connection.optBoolean("local"),
                                relay = connection.optBoolean("relay"),
                            )
                        }
                    )

                    if (uris.isEmpty()) continue
                    val server = PlexServer(
                        name = resource.optString("name").ifEmpty { "Plex Media Server" },
                        accessToken = resource.optString("accessToken").ifEmpty { token },
                        connections = uris,
                    )
                    if (resource.optBoolean("owned")) owned += server.accessToken
                    add(server)
                }
            // The account's own servers first: signing in connects to the first that
            // answers, and plex.tv lists a friend's shared server as readily as your own.
            }.sortedBy { it.accessToken !in owned }
        }
    }

    /** One way to reach a server, as plex.tv lists it. */
    data class PlexConnection(
        val uri: String,
        val address: String,
        val port: Int,
        val local: Boolean,
        val relay: Boolean,
    )

    /**
     * The addresses to try, best first: on the home network, then over the internet, then
     * through Plex's relay.
     *
     * Plex gives a local connection only as an https name under plex.direct, which has to
     * be looked up in DNS to reach a 192.168 address — and plenty of home routers refuse
     * to answer that, as protection against DNS rebinding. When they do, the local address
     * fails, and everything went out to the internet and back in through the router
     * instead: slower, and counted by the server as a remote stream, with its remote
     * quality limits. So each local connection is followed by the same address over plain
     * http, which needs no lookup and works whenever the server allows insecure local
     * connections — Plex's default of "Preferred".
     */
    fun connectionOrder(connections: List<PlexConnection>): List<String> {
        val sorted = connections
            .filter { it.uri.isNotEmpty() }
            .sortedWith(compareBy<PlexConnection>({ it.relay }, { !it.local }))
        return buildList {
            for (connection in sorted) {
                add(connection.uri)
                if (connection.local && !connection.relay && connection.address.isNotEmpty()) {
                    add("http://${connection.address}:${connection.port}")
                }
            }
        }.distinct()
    }

    /**
     * The best address that actually answers, which is not knowable from the list alone.
     * All are asked at once: one by one, a server away from home kept somebody waiting
     * on every home address timing out in turn before the internet one was even tried.
     */
    suspend fun firstReachable(server: PlexServer): String? = coroutineScope {
        val probes = server.connections.map { uri -> async { reachable(server, uri) } }
        val best = server.connections.indices.firstOrNull { probes[it].await() }
        probes.forEach { it.cancel() }
        best?.let { server.connections[it] }
    }

    /** Whether the server answers at this address, within the probe's few seconds. */
    suspend fun reachable(server: PlexServer, uri: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$uri/identity")
            .header("accept", "application/json")
            .header("X-Plex-Token", server.accessToken)
            .build()
        runCatching { Http.probe.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
    }

    suspend fun sections(base: String, token: String): List<PlexSection> = withContext(Dispatchers.IO) {
        val directories = container("$base/library/sections", token).optJSONArray("Directory") ?: JSONArray()
        (0 until directories.length())
            .map { directories.getJSONObject(it) }
            .map {
                PlexSection(
                    key = it.optString("key"),
                    title = it.optString("title"),
                    type = it.optString("type"),
                )
            }
            .filter { it.key.isNotEmpty() }
    }

    /**
     * The genres this library actually contains, as the server counts them. Offering a
     * fixed list would show genres nothing in the library matches.
     */
    suspend fun genres(
        base: String,
        token: String,
        sectionKey: String,
        type: Int,
    ): List<PlexGenre> = filterValues(base, token, sectionKey, "genre", type)

    /** The decades the library has anything from, newest first: "2020s", "2010s"… */
    suspend fun decades(
        base: String,
        token: String,
        sectionKey: String,
        type: Int,
    ): List<PlexGenre> = filterValues(base, token, sectionKey, "decade", type)
        .sortedByDescending { it.id.toIntOrNull() ?: 0 }

    /**
     * How many titles start with each letter, for the grid as it's filtered. In title
     * order, these counts say exactly where each letter begins.
     */
    suspend fun firstCharacters(
        base: String,
        token: String,
        sectionKey: String,
        type: Int,
        filters: String,
    ): List<PlexLetter> = withContext(Dispatchers.IO) {
        val directories = container("$base/library/sections/$sectionKey/firstCharacter?type=$type$filters", token)
            .optJSONArray("Directory") ?: JSONArray()
        (0 until directories.length())
            .map { directories.getJSONObject(it) }
            .mapNotNull { entry ->
                val letter = entry.optString("title")
                    .ifBlank { runCatching { URLDecoder.decode(entry.optString("key"), "UTF-8") }.getOrDefault("") }
                    .takeIf(String::isNotBlank) ?: return@mapNotNull null
                val count = entry.optInt("size").takeIf { it > 0 } ?: return@mapNotNull null
                PlexLetter(letter, count)
            }
    }

    private suspend fun filterValues(
        base: String,
        token: String,
        sectionKey: String,
        field: String,
        type: Int,
    ): List<PlexGenre> = withContext(Dispatchers.IO) {
        val directories = container("$base/library/sections/$sectionKey/$field?type=$type", token)
            .optJSONArray("Directory") ?: JSONArray()
        (0 until directories.length())
            .map { directories.getJSONObject(it) }
            .mapNotNull { entry ->
                val key = entry.optString("key").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val title = entry.optString("title").takeIf(String::isNotBlank) ?: return@mapNotNull null
                PlexGenre(id = key, title = title)
            }
    }

    suspend fun items(
        base: String,
        token: String,
        path: String,
        limit: Int = 200,
        offset: Int = 0,
    ): List<PlexItem> = withContext(Dispatchers.IO) {
        val separator = if (path.contains('?')) "&" else "?"
        val url = "$base$path${separator}X-Plex-Container-Start=$offset&X-Plex-Container-Size=$limit"
        val metadata = container(url, token).optJSONArray("Metadata") ?: JSONArray()
        (0 until metadata.length()).map {
            parseItem(metadata.getJSONObject(it)).copy(serverBase = base)
        }
    }

    /**
     * On Deck, the older of the two lists. Only a fallback now, for a server too old to
     * answer [continueWatching].
     */
    suspend fun onDeck(base: String, token: String): List<PlexItem> =
        items(base, token, "/library/onDeck", limit = 40)

    /**
     * The Continue Watching row as Plex's own apps build it: the home screen's
     * `home.continue` and `home.ondeck` hubs, asked for together. This is what shows in
     * Plex itself, which `/library/onDeck` on its own is not — it lags the home screen and
     * leaves out things Plex considers in progress.
     *
     * Unordered and unmerged here: the hubs overlap, and the order across them is settled
     * by [tv.reely.core.continueWatchingOrder] so one rule decides it for every server.
     */
    suspend fun continueWatching(base: String, token: String): List<PlexItem> =
        withContext(Dispatchers.IO) {
            val url = "$base/hubs?identifier=" +
                URLEncoder.encode("home.continue,home.ondeck", "UTF-8") + "&count=40"
            val hubs = container(url, token).optJSONArray("Hub")
                ?: return@withContext onDeck(base, token)
            (0 until hubs.length())
                .mapNotNull { hubs.optJSONObject(it)?.optJSONArray("Metadata") }
                .flatMap { metadata ->
                    (0 until metadata.length()).map { parseItem(metadata.getJSONObject(it)) }
                }
                .filter { it.isPlayable }
                .map { it.copy(serverBase = base) }
        }

    /** The newest things in one library, of one kind. */
    suspend fun recentlyAdded(
        base: String,
        token: String,
        sectionKey: String,
        type: Int,
        limit: Int = 60,
        offset: Int = 0,
    ): List<PlexItem> =
        items(base, token, "/library/sections/$sectionKey/all?type=$type&sort=addedAt:desc", limit, offset)

    suspend fun children(base: String, token: String, ratingKey: String): List<PlexItem> =
        items(base, token, "/library/metadata/$ratingKey/children", limit = 400)

    /**
     * The outside ids of everything in one library, as Plex gives them: "tmdb://603",
     * "tvdb://81189", "imdb://tt0133093". Asked for all at once, ids only, so even a
     * big library is one quick answer.
     */
    suspend fun libraryGuids(base: String, token: String, sectionKey: String, type: Int): Set<String> =
        withContext(Dispatchers.IO) {
            val json = container(
                "$base/library/sections/$sectionKey/all?type=$type&includeGuids=1" +
                    "&X-Plex-Container-Start=0&X-Plex-Container-Size=100000",
                token,
            )
            guidsIn(json.optJSONArray("Metadata"))
        }

    /**
     * Everything in one library, by the little that tells one title from another: its
     * name, year and outside ids. What IPTV's films and series are matched against, so a
     * title in both shows once.
     */
    suspend fun libraryEntries(base: String, token: String, sectionKey: String, type: Int): List<PlexIndexEntry> =
        withContext(Dispatchers.IO) {
            val json = container(
                "$base/library/sections/$sectionKey/all?type=$type&includeGuids=1" +
                    "&X-Plex-Container-Start=0&X-Plex-Container-Size=100000",
                token,
            )
            entriesIn(json.optJSONArray("Metadata"), base)
        }

    internal fun entriesIn(metadata: JSONArray?, base: String?): List<PlexIndexEntry> {
        if (metadata == null) return emptyList()
        return (0 until metadata.length()).mapNotNull { i ->
            val entry = metadata.optJSONObject(i) ?: return@mapNotNull null
            val guids = entry.optJSONArray("Guid")
            PlexIndexEntry(
                ratingKey = entry.optString("ratingKey"),
                serverBase = base,
                title = entry.optString("title"),
                originalTitle = entry.optString("originalTitle").takeIf { it.isNotBlank() },
                year = entry.optInt("year").takeIf { it > 0 },
                guids = (0 until (guids?.length() ?: 0)).mapNotNull { guids?.optJSONObject(it)?.optString("id") }
                    .filter { it.isNotBlank() }.toSet(),
            )
        }
    }

    internal fun guidsIn(metadata: JSONArray?): Set<String> = buildSet {
        if (metadata == null) return@buildSet
        for (i in 0 until metadata.length()) {
            val guids = metadata.optJSONObject(i)?.optJSONArray("Guid") ?: continue
            for (g in 0 until guids.length()) {
                guids.optJSONObject(g)?.optString("id")?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
    }

    /** Every episode of a show, or of a season, in order. */
    suspend fun episodesOf(base: String, token: String, ratingKey: String): List<PlexItem> =
        items(base, token, "/library/metadata/$ratingKey/allLeaves", limit = 2000)
            .filter { it.type == "episode" }
            .map { it.copy(serverBase = base) }

    /**
     * Which of [episodes] (in order) to play next: the one part watched, else the first
     * not yet watched after the last one that was, else the first not watched at all,
     * else the first. What Plex's own apps mean by a show's next episode.
     *
     * Specials aside, unless they're all there is or one's part watched: they come first
     * in Plex's order, as season 0, and a show not started began with its first special
     * rather than its first episode.
     */
    fun nextEpisode(episodes: List<PlexItem>): PlexItem? {
        episodes.firstOrNull { it.resumeFraction != null && !it.isWatched }?.let { return it }
        // A special's season is 0, which is read as no season number at all.
        val main = episodes.filter { it.parentIndex != null }.ifEmpty { episodes }
        val lastWatched = main.indexOfLast { it.isWatched }
        if (lastWatched >= 0) {
            main.drop(lastWatched + 1).firstOrNull { !it.isWatched }?.let { return it }
        }
        return main.firstOrNull { !it.isWatched } ?: main.firstOrNull()
    }

    /**
     * The video playlists on a server, the account's own and smart ones. A playlist's
     * picture is a composite of what is in it, which Plex keeps apart from a thumb.
     */
    suspend fun playlists(base: String, token: String): List<PlexItem> = withContext(Dispatchers.IO) {
        val metadata = container("$base/playlists?playlistType=video", token).optJSONArray("Metadata") ?: JSONArray()
        (0 until metadata.length()).mapNotNull { metadata.optJSONObject(it) }
            .filter { it.optInt("leafCount") > 0 }
            .map { entry ->
                parseItem(entry).copy(
                    serverBase = base,
                    thumb = entry.optString("composite").takeIf(String::isNotEmpty)
                        ?: entry.optString("thumb").takeIf(String::isNotEmpty),
                )
            }
    }

    /** What is in a playlist, in its own order. */
    suspend fun playlistItems(base: String, token: String, ratingKey: String): List<PlexItem> =
        items(base, token, "/playlists/$ratingKey/items", limit = 500)

    /** A library's collections, as Plex lists them: its own, and smart ones. */
    suspend fun collections(base: String, token: String, sectionKey: String): List<PlexItem> =
        items(base, token, "/library/sections/$sectionKey/collections", limit = 500)

    /** What is in a collection, in the collection's own order. */
    suspend fun collectionItems(base: String, token: String, ratingKey: String): List<PlexItem> =
        items(base, token, "/library/collections/$ratingKey/children", limit = 500)

    suspend fun detail(base: String, token: String, ratingKey: String): PlexDetail? =
        withContext(Dispatchers.IO) {
            // With a show's next episode, as Plex's own apps ask for it: where the show's
            // page opens, without reading every episode it has to work it out.
            val entry = container("$base/library/metadata/$ratingKey?includeOnDeck=1", token)
                .optJSONArray("Metadata")?.optJSONObject(0) ?: return@withContext null
            val onDeck = entry.optJSONObject("OnDeck")?.optJSONArray("Metadata")?.optJSONObject(0)
            PlexDetail(
                onDeckKey = onDeck?.optString("ratingKey")?.takeIf(String::isNotEmpty),
                onDeckSeasonKey = onDeck?.optString("parentRatingKey")?.takeIf(String::isNotEmpty),
                ratingKey = entry.optString("ratingKey"),
                type = entry.optString("type"),
                title = entry.optString("title"),
                summary = entry.optString("summary").takeIf(String::isNotBlank),
                tagline = entry.optString("tagline").takeIf(String::isNotBlank),
                year = entry.optInt("year").takeIf { it > 0 },
                durationMs = entry.optLong("duration"),
                viewOffsetMs = entry.optLong("viewOffset"),
                contentRating = entry.optString("contentRating").takeIf(String::isNotBlank),
                rating = entry.optDouble("rating").takeIf { !it.isNaN() && it > 0 },
                audienceRating = entry.optDouble("audienceRating").takeIf { !it.isNaN() && it > 0 },
                airDate = entry.optString("originallyAvailableAt").takeIf(String::isNotBlank),
                viewCount = entry.optInt("viewCount"),
                viewedLeafCount = entry.optInt("viewedLeafCount"),
                studio = entry.optString("studio").takeIf(String::isNotBlank),
                thumb = entry.optString("thumb").takeIf(String::isNotEmpty),
                art = entry.optString("art").takeIf(String::isNotEmpty),
                theme = entry.optString("theme").takeIf(String::isNotEmpty),
                genres = tags(entry, "Genre"),
                directors = tags(entry, "Director"),
                roles = roles(entry),
                writers = tags(entry, "Writer"),
                childCount = entry.optInt("childCount"),
                // A collection says how many titles it holds as childCount; a show's is seasons.
        leafCount = entry.optInt("leafCount").takeIf { it > 0 }
            ?: entry.optInt("childCount").takeIf { entry.optString("type") == "collection" }
            ?: 0,
                grandparentTitle = entry.optString("grandparentTitle").takeIf(String::isNotBlank),
                index = entry.optInt("index").takeIf { it > 0 },
                parentIndex = entry.optInt("parentIndex").takeIf { it > 0 },
                logo = logoOf(entry),
                qualities = qualitiesOf(entry),
                versions = versionsOf(entry),
                guid = entry.optString("guid").takeIf { it.startsWith("plex://") },
            )
        }

    /** Every file an item has, when it has more than one. */
    internal fun versionsOf(entry: JSONObject): List<PlexVersion> {
        val media = entry.optJSONArray("Media") ?: return emptyList()
        if (media.length() < 2) return emptyList()
        return (0 until media.length()).mapNotNull { media.optJSONObject(it) }.map { file ->
            val part = file.optJSONArray("Part")?.optJSONObject(0)
            val streams = part?.optJSONArray("Stream")
            val video = streams?.let { list ->
                (0 until list.length()).mapNotNull { list.optJSONObject(it) }.firstOrNull { it.optInt("streamType") == 1 }
            }
            PlexVersion(
                label = versionLabel(
                    resolution = file.optString("videoResolution").takeIf(String::isNotBlank),
                    dolbyVision = video?.optBoolean("DOVIPresent") == true,
                    transfer = video?.optString("colorTrc")?.takeIf(String::isNotBlank),
                ),
                detail = versionDetail(
                    videoCodec = file.optString("videoCodec"),
                    audioCodec = file.optString("audioCodec"),
                    audioChannels = file.optInt("audioChannels"),
                    bitrateKbps = file.optInt("bitrate"),
                    sizeBytes = part?.optLong("size") ?: 0,
                ),
            )
        }
    }

    /**
     * Direct-play URL for the first part of an item, plus whatever text subtitles Plex
     * exposes as separate streams. Direct play only: no transcode is requested, so the
     * device has to decode what the file actually contains.
     */
    suspend fun playback(base: String, token: String, ratingKey: String, mediaIndex: Int = 0): PlexPlayback? =
        withContext(Dispatchers.IO) {
            // includeMarkers asks the server for its intro and credits detection.
            val metadata = container(
                "$base/library/metadata/$ratingKey?includeMarkers=1&includeChapters=1",
                token,
            )
                .optJSONArray("Metadata")?.optJSONObject(0) ?: return@withContext null
            // The version chosen, when there are several; the first when there aren't.
            val files = metadata.optJSONArray("Media") ?: return@withContext null
            val media = files.optJSONObject(mediaIndex) ?: files.optJSONObject(0) ?: return@withContext null
            val part = media.optJSONArray("Part")?.optJSONObject(0) ?: return@withContext null
            val key = part.optString("key").takeIf(String::isNotEmpty) ?: return@withContext null

            val sound = audioOf(media, part)
            // Plex makes preview pictures only when the library is set to; "sd" is their
            // name for the set. Asked for whether or not the part says it has them: the
            // attribute is missing from some servers' answers when the pictures are there,
            // and the player says there are none once asking finds none.
            val partId = part.optLong("id").takeIf { it > 0 }
            val previews = partId != null
            PlexPlayback(
                url = "$base$key?X-Plex-Token=$token",
                subtitles = subtitlesOf(part, base, token),
                markers = markersOf(metadata),
                audioCodec = sound?.first,
                audioChannels = sound?.second ?: 0,
                previewUrl = if (previews) "$base/library/parts/$partId/indexes/sd/{ms}?X-Plex-Token=$token" else null,
                chapters = chaptersOf(metadata, base, token),
                partId = partId,
                audioStreams = streamsOf(part, AUDIO_STREAM),
                subtitleStreams = streamsOf(part, SUBTITLE_STREAM),
            )
        }

    /**
     * Subtitles for [ratingKey] that the server can find online, in [language] (a two
     * letter code), from whichever providers it has turned on. The same search Plex's own
     * apps offer when a file has none.
     */
    suspend fun searchSubtitles(base: String, token: String, ratingKey: String, language: String): List<PlexOnlineSubtitle> =
        withContext(Dispatchers.IO) {
            val found = container(
                "$base/library/metadata/$ratingKey/subtitles?language=$language&hearingImpaired=0&forced=0",
                token,
            ).optJSONArray("Stream") ?: return@withContext emptyList()
            (0 until found.length()).mapNotNull { found.optJSONObject(it) }.mapNotNull { stream ->
                val key = stream.optString("key").takeIf(String::isNotBlank) ?: return@mapNotNull null
                PlexOnlineSubtitle(
                    key = key,
                    title = stream.optString("title").ifBlank { stream.optString("displayTitle") }
                        .ifBlank { stream.optString("languageTag").ifBlank { "Subtitles" } },
                    provider = stream.optString("providerTitle").takeIf(String::isNotBlank),
                    language = stream.optString("languageCode").takeIf(String::isNotBlank)
                        ?: stream.optString("languageTag").takeIf(String::isNotBlank),
                    codec = stream.optString("codec").takeIf(String::isNotBlank),
                    hearingImpaired = stream.optBoolean("hearingImpaired"),
                    forced = stream.optBoolean("forced"),
                )
            }
        }

    /** Has the server fetch [subtitle] and add it to the title, as a subtitle file of its own. */
    suspend fun addSubtitle(base: String, token: String, ratingKey: String, subtitle: PlexOnlineSubtitle, language: String) =
        withContext(Dispatchers.IO) {
            val query = buildString {
                append("key=").append(URLEncoder.encode(subtitle.key, "UTF-8"))
                subtitle.codec?.let { append("&codec=").append(URLEncoder.encode(it, "UTF-8")) }
                append("&language=").append(URLEncoder.encode(subtitle.language ?: language, "UTF-8"))
                append("&hearingImpaired=").append(if (subtitle.hearingImpaired) 1 else 0)
                append("&forced=").append(if (subtitle.forced) 1 else 0)
                subtitle.provider?.let { append("&providerTitle=").append(URLEncoder.encode(it, "UTF-8")) }
            }
            val request = Request.Builder()
                .url("$base/library/metadata/$ratingKey/subtitles?$query")
                .plexHeaders(clientId, token)
                .put(FormBody.Builder().build())
                .build()
            runCatching { Http.client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
        }

    /**
     * Saves a choice of sound or subtitles for this account, as Plex's own apps do, so
     * it is what plays next time here or anywhere else. Subtitles off is stream 0.
     */
    suspend fun selectStream(base: String, token: String, partId: Long, audioStreamId: String? = null, subtitleStreamId: String? = null) =
        withContext(Dispatchers.IO) {
            val choice = listOfNotNull(
                audioStreamId?.let { "audioStreamID=$it" },
                subtitleStreamId?.let { "subtitleStreamID=$it" },
            )
            if (choice.isEmpty()) return@withContext false
            val request = Request.Builder()
                .url("$base/library/parts/$partId?${choice.joinToString("&")}&allParts=1")
                .plexHeaders(clientId, token)
                .put(FormBody.Builder().build())
                .build()
            runCatching { Http.client.newCall(request).execute().use { it.isSuccessful } }.getOrDefault(false)
        }

    /**
     * Tell the server where playback is. Plex composes On Deck and Continue Watching from
     * this; without it the server never learns anything was watched and every client's
     * Continue Watching stays wrong.
     */
    suspend fun reportTimeline(
        base: String,
        token: String,
        ratingKey: String,
        positionMs: Long,
        durationMs: Long,
        state: String,
        sessionId: String,
    ) = withContext(Dispatchers.IO) {
        /*
         * Three things here are not optional, and all three were missing.
         *
         * `identifier` tells the server which agent the key belongs to; without it the
         * timeline is accepted with a 200 and then dropped, which is why nothing ever
         * appeared in Continue Watching. The client headers are what make the report
         * belong to a player at all — an anonymous timeline has no session to attach to.
         * And a session identifier is what lets consecutive reports be recognised as the
         * same sitting rather than a stream of unrelated ones.
         */
        val url = "$base/:/timeline?ratingKey=$ratingKey" +
            "&key=" + URLEncoder.encode("/library/metadata/$ratingKey", "UTF-8") +
            "&identifier=com.plexapp.plugins.library" +
            "&state=$state&time=$positionMs&duration=$durationMs" +
            "&playbackTime=$positionMs&playQueueItemID=-1"
        val request = Request.Builder()
            .url(url)
            .plexHeaders(clientId, token)
            .header("X-Plex-Session-Identifier", sessionId)
            .get()
            .build()
        runCatching { Http.client.newCall(request).execute().use { it.isSuccessful } }
        Unit
    }

    /**
     * Asks the server to transcode instead of handing over the file.
     *
     * This is the universal transcoder: Plex remuxes or re-encodes on the fly and serves
     * HLS, which is why a file the stick cannot decode still plays. The server only ever
     * transcodes when a client asks, which is the whole reason direct play could fail.
     */
    fun transcodeUrl(
        base: String,
        token: String,
        clientId: String,
        ratingKey: String,
        sessionId: String,
        maxBitrateKbps: Int,
        resolution: String,
        mediaIndex: Int = 0,
    ): String {
        /*
         * No `offset`, on purpose. With one, the transcode starts part-way in and the
         * player's clock starts again at nought — so resuming at twenty minutes read as
         * nought on the scrubber, Skip Intro fired against the wrong clock, and the
         * position reported to Plex was the one from the restarted clock, overwriting
         * the real resume point near the start. Starting at the beginning and letting the
         * player seek keeps every one of those in the file's own time.
         */
        val path = URLEncoder.encode("/library/metadata/$ratingKey", "UTF-8")
        val bitrate = if (maxBitrateKbps > 0) "&maxVideoBitrate=$maxBitrateKbps" else ""
        return "$base/video/:/transcode/universal/start.m3u8" +
            "?path=$path&mediaIndex=$mediaIndex&partIndex=0" +
            "&protocol=hls&fastSeek=1&directPlay=0&directStream=1" +
            // Burned in, because a transcode is exactly when the picture subtitles that
            // cannot be sideloaded become playable.
            "&subtitles=burn&audioBoost=100&videoQuality=100" +
            "&videoResolution=$resolution$bitrate" +
            "&session=$sessionId" +
            "&X-Plex-Client-Identifier=$clientId" +
            "&X-Plex-Platform=Android&X-Plex-Product=" + URLEncoder.encode(PRODUCT, "UTF-8") +
            "&X-Plex-Token=$token"
    }

    /**
     * Asks the server to convert only the audio, and hand the picture over untouched.
     *
     * For a file whose audio this device cannot play — Dolby Digital Plus on a stick with
     * no decoder for it and a television that will not take it over HDMI. The player
     * cannot select the track, so it plays the picture in silence and raises no error;
     * this is what Plex's own apps do about it.
     *
     * The shape is copied from a client that measured it against real servers, and each
     * part matters:
     *
     * - The Generic profile plus an explicit target, so the server's choice is settled by
     *   what is written here. The built-in Android profile lists E-AC3 as acceptable, and
     *   would be within its rights to convert it to E-AC3.
     * - Only codecs this device plays are on offer — see
     *   [tv.reely.core.conversionTargets]. Surround is kept as Dolby when the device can
     *   take it, and AAC always ends the list, because every Android device decodes it.
     *   Offering anything else would let the server pick something that leaves the
     *   viewer in silence again.
     * - `directStream=1` with H.264 and HEVC as copy targets: the picture already plays
     *   here, so it is passed through rather than re-encoded.
     * - `subtitles=none`. `burn` burns whatever subtitle is selected on the server, which
     *   can be a choice made on another device, and a burn is a full video transcode.
     * - No offset. The transcode starts at the beginning and the player seeks, so the
     *   timeline stays in the file's own time and subtitles loaded alongside still line up.
     */
    fun audioConvertUrl(
        base: String,
        token: String,
        clientId: String,
        ratingKey: String,
        sessionId: String,
        audioCodecs: List<String> = listOf("aac"),
        mediaIndex: Int = 0,
    ): String {
        val path = URLEncoder.encode("/library/metadata/$ratingKey", "UTF-8")
        val profile = URLEncoder.encode(audioConvertProfile(audioCodecs), "UTF-8")
        return "$base/video/:/transcode/universal/start.m3u8" +
            "?path=$path&mediaIndex=$mediaIndex&partIndex=0" +
            "&protocol=hls&fastSeek=1" +
            "&directPlay=0&directStream=1&directStreamAudio=0" +
            "&subtitles=none&audioBoost=100&location=lan" +
            "&session=$sessionId" +
            "&X-Plex-Client-Profile-Name=Generic&X-Plex-Platform=Generic" +
            "&X-Plex-Client-Profile-Extra=$profile" +
            "&X-Plex-Client-Identifier=$clientId" +
            "&X-Plex-Product=" + URLEncoder.encode(PRODUCT, "UTF-8") +
            "&X-Plex-Token=$token"
    }

    /**
     * What the server may send back when converting audio: the picture copied as it is,
     * the sound as the first of [audioCodecs] it can make. The commas are encoded inside
     * the clause because the server decodes the parameter once and then parses the clause
     * as a query of its own.
     */
    internal fun audioConvertProfile(audioCodecs: List<String>): String =
        "add-settings(DirectPlayStreamSelection=true)" +
            "+add-transcode-target(type=videoProfile&context=streaming&protocol=hls" +
            "&container=mpegts&videoCodec=h264%2Chevc" +
            "&audioCodec=" + audioCodecs.ifEmpty { listOf("aac") }.joinToString("%2C") + ")"

    /** Releases the server's encoder. Without this a session lingers and keeps working. */
    suspend fun stopTranscode(base: String, token: String, sessionId: String) =
        withContext(Dispatchers.IO) {
            val url = "$base/video/:/transcode/universal/stop?session=$sessionId&X-Plex-Token=$token"
            val request = Request.Builder().url(url).get().build()
            runCatching { Http.client.newCall(request).execute().use { it.isSuccessful } }
            Unit
        }

    /** Searches the whole library at once — films, shows and episodes together. */
    suspend fun search(base: String, token: String, query: String): List<PlexItem> =
        searchAll(base, token, query).items

    /**
     * Titles and people for a search. Plex answers with a hub per kind; actors come in
     * their own hub, as tags with the id a library is filtered by (see [withActor]).
     */
    suspend fun searchAll(base: String, token: String, query: String): PlexFound =
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext PlexFound()
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val hubs = container("$base/hubs/search?query=$encoded&limit=30", token)
                .optJSONArray("Hub") ?: JSONArray()
            PlexFound(
                items = itemsFromHubs(hubs, base),
                people = peopleFromHubs(hubs, base),
                collections = itemsFromHubs(hubs, base, setOf("collection")),
            )
        }

    internal fun peopleFromHubs(hubs: JSONArray, base: String): List<PlexPerson> = buildList {
        for (index in 0 until hubs.length()) {
            val hub = hubs.optJSONObject(index) ?: continue
            if (hub.optString("type") != "actor") continue
            val entries = hub.optJSONArray("Directory") ?: hub.optJSONArray("Metadata") ?: continue
            for (entry in 0 until entries.length()) {
                val person = entries.optJSONObject(entry) ?: continue
                val id = person.optString("id").takeIf(String::isNotBlank) ?: continue
                val name = person.optString("tag").ifBlank { person.optString("title") }.takeIf(String::isNotBlank) ?: continue
                add(PlexPerson(id, name, person.optString("thumb").takeIf(String::isNotBlank), base))
            }
        }
    }.distinctBy { it.id }

    internal fun itemsFromHubs(
        hubs: JSONArray,
        base: String,
        wanted: Set<String> = setOf("movie", "show", "episode"),
    ): List<PlexItem> {
        return buildList {
            for (index in 0 until hubs.length()) {
                val metadata = hubs.getJSONObject(index).optJSONArray("Metadata") ?: continue
                for (entry in 0 until metadata.length()) {
                    val item = parseItem(metadata.getJSONObject(entry)).copy(serverBase = base)
                    if (item.type in wanted && item.ratingKey.isNotEmpty()) add(item)
                }
            }
        }.distinctBy { it.ratingKey }
    }

    /**
     * Trailers and other extras. Locally stored ones are always here; Plex's own online
     * trailers arrive only for Plex Pass accounts, which is why the button that uses this
     * appears only when something actually comes back.
     *
     * Only the extra's own rating key is taken. The part key underneath it is not a file
     * this app can fetch: an online trailer's part is marked indirect, meaning the key has
     * to be followed a hop before it resolves, and the server rejects a direct request for
     * it. Playing an extra through the transcoder lets the server resolve its own source.
     */
    suspend fun trailers(base: String, token: String, ratingKey: String): List<PlexExtra> =
        withContext(Dispatchers.IO) {
            val metadata = runCatching {
                container("$base/library/metadata/$ratingKey/extras", token)
                    .optJSONArray("Metadata")
            }.getOrNull() ?: return@withContext emptyList()

            (0 until metadata.length())
                .map { metadata.getJSONObject(it) }
                .filter { it.optString("subtype").equals("trailer", ignoreCase = true) }
                .mapNotNull { entry ->
                    val key = entry.optString("ratingKey").takeIf(String::isNotBlank)
                        ?: return@mapNotNull null
                    PlexExtra(
                        ratingKey = key,
                        title = entry.optString("title").ifEmpty { "Trailer" },
                        durationMs = entry.optLong("duration"),
                    )
                }
        }

    /**
     * "More like this": what the server's related hubs suggest for a film or show, the
     * row Plex's own apps put under a title. Films and shows only, the title itself left
     * out, each once however many hubs it turns up in. Nothing, rather than an error, from
     * a server too old to have the endpoint: it is a nicety.
     */
    suspend fun related(base: String, token: String, ratingKey: String): List<PlexItem> =
        withContext(Dispatchers.IO) {
            val hubs = runCatching {
                container("$base/library/metadata/$ratingKey/related?count=24", token).optJSONArray("Hub")
            }.getOrNull() ?: return@withContext emptyList()
            (0 until hubs.length())
                .mapNotNull { hubs.optJSONObject(it)?.optJSONArray("Metadata") }
                .flatMap { metadata -> (0 until metadata.length()).map { parseItem(metadata.getJSONObject(it)) } }
                .filter { it.ratingKey != ratingKey && (it.type == "movie" || it.type == "show") }
                .distinctBy { it.ratingKey }
                .take(24)
                .map { it.copy(serverBase = base) }
        }

    /**
     * Takes something off Continue Watching without marking it watched, as Plex's own
     * apps offer. What was watched of it is kept.
     */
    /**
     * The account's Watchlist, newest first, as Plex's own ids ("plex://movie/…"). The
     * Watchlist belongs to the account, not to a server: what's on it may be on none of
     * them, so each id is matched to a server's copy with [byGuid].
     */
    suspend fun watchlist(token: String): List<String> = withContext(Dispatchers.IO) {
        val metadata = container(
            "$DISCOVER/library/sections/watchlist/all?includeFields=guid,type,title" +
                "&X-Plex-Container-Start=0&X-Plex-Container-Size=100",
            token,
        ).optJSONArray("Metadata") ?: JSONArray()
        (0 until metadata.length()).mapNotNull { index ->
            metadata.optJSONObject(index)?.optString("guid")?.takeIf { it.startsWith("plex://") }
        }
    }

    /** A server's copy of something, by Plex's own id for it, if the server has it. */
    suspend fun byGuid(base: String, token: String, guid: String): PlexItem? =
        items(base, token, "/library/all?guid=" + URLEncoder.encode(guid, "UTF-8"), limit = 1).firstOrNull()

    /** Puts something on the account's Watchlist, or takes it off. */
    suspend fun setWatchlisted(token: String, guid: String, on: Boolean) = withContext(Dispatchers.IO) {
        val key = guid.substringAfterLast('/')
        val action = if (on) "addToWatchlist" else "removeFromWatchlist"
        val request = Request.Builder()
            .url("$DISCOVER/actions/$action?ratingKey=$key")
            .plexHeaders(clientId, token)
            .put(FormBody.Builder().build())
            .build()
        Http.client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Couldn't change your Watchlist." }
        }
        Unit
    }

    suspend fun removeFromContinueWatching(base: String, token: String, ratingKey: String) =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$base/actions/removeFromContinueWatching?ratingKey=$ratingKey")
                .plexHeaders(clientId, token)
                .put(FormBody.Builder().build())
                .build()
            Http.client.newCall(request).execute().use { response ->
                require(response.isSuccessful) {
                    "Couldn't remove that from Continue Watching."
                }
            }
            Unit
        }

    /**
     * Marks something watched or unwatched on the server, so every Plex client agrees
     * rather than just this one.
     */
    suspend fun setWatched(
        base: String,
        token: String,
        ratingKey: String,
        watched: Boolean,
    ) = withContext(Dispatchers.IO) {
        val action = if (watched) "scrobble" else "unscrobble"
        val url = "$base/:/$action?key=$ratingKey" +
            "&identifier=com.plexapp.plugins.library&X-Plex-Token=$token"
        val request = Request.Builder().url(url).header("accept", "application/json").get().build()
        Http.client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Plex couldn't update the watched status." }
        }
        Unit
    }

    /**
     * Images go through Plex's photo transcoder at the size they will actually be drawn.
     * A stick has about 1.5 GB of RAM, and a screen of full-size posters is the quickest
     * way to spend it.
     */
    fun imageUrl(
        base: String,
        token: String,
        path: String?,
        width: Int,
        height: Int,
    ): String? {
        if (path.isNullOrEmpty()) return null
        val encoded = URLEncoder.encode(path, "UTF-8")
        return "$base/photo/:/transcode?width=$width&height=$height&minSize=1&upscale=1" +
            "&url=$encoded&X-Plex-Token=$token"
    }

    private fun parseItem(entry: JSONObject): PlexItem = PlexItem(
        ratingKey = entry.optString("ratingKey"),
        title = entry.optString("title"),
        titleSort = entry.optString("titleSort").takeIf(String::isNotBlank),
        type = entry.optString("type"),
        thumb = entry.optString("thumb").takeIf(String::isNotEmpty),
        art = entry.optString("art").takeIf(String::isNotEmpty),
        summary = entry.optString("summary").takeIf(String::isNotBlank),
        year = entry.optInt("year").takeIf { it > 0 },
        index = entry.optInt("index").takeIf { it > 0 },
        parentIndex = entry.optInt("parentIndex").takeIf { it > 0 },
        parentRatingKey = entry.optString("parentRatingKey").takeIf(String::isNotEmpty),
        parentTitle = entry.optString("parentTitle").takeIf(String::isNotBlank),
        grandparentRatingKey = entry.optString("grandparentRatingKey").takeIf(String::isNotEmpty),
        grandparentTitle = entry.optString("grandparentTitle").takeIf(String::isNotBlank),
        grandparentThumb = entry.optString("grandparentThumb").takeIf(String::isNotEmpty),
        durationMs = entry.optLong("duration"),
        viewOffsetMs = entry.optLong("viewOffset"),
        // A collection says how many titles it holds as childCount; a show's is seasons.
        leafCount = entry.optInt("leafCount").takeIf { it > 0 }
            ?: entry.optInt("childCount").takeIf { entry.optString("type") == "collection" }
            ?: 0,
        viewedLeafCount = entry.optInt("viewedLeafCount"),
        viewCount = entry.optInt("viewCount"),
        addedAt = entry.optLong("addedAt"),
        lastViewedAt = entry.optLong("lastViewedAt"),
        logo = logoOf(entry),
        qualities = qualitiesOf(entry),
        airDate = entry.optString("originallyAvailableAt").takeIf(String::isNotBlank),
        librarySectionId = entry.optString("librarySectionID").takeIf(String::isNotBlank),
    )

    /**
     * What an item's first file is, from its Media block, and from the video stream when
     * the response carries one — which a full item does and a listing usually does not.
     */
    private fun qualitiesOf(entry: JSONObject): List<String> {
        val media = entry.optJSONArray("Media")?.optJSONObject(0) ?: return emptyList()
        val streams = media.optJSONArray("Part")?.optJSONObject(0)?.optJSONArray("Stream")
        val video = streams?.let { list ->
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }.firstOrNull { it.optInt("streamType") == 1 }
        }
        return qualityBadges(
            resolution = media.optString("videoResolution").takeIf(String::isNotBlank),
            audioChannels = media.optInt("audioChannels"),
            dolbyVision = video?.optBoolean("DOVIPresent") == true,
            transfer = video?.optString("colorTrc")?.takeIf(String::isNotBlank),
        )
    }

    /** The clearLogo in an item's images, if it has one. */
    private fun logoOf(entry: JSONObject): String? {
        val images = entry.optJSONArray("Image") ?: return null
        return (0 until images.length())
            .mapNotNull { images.optJSONObject(it) }
            .firstOrNull { it.optString("type") == "clearLogo" }
            ?.optString("url")
            ?.takeIf(String::isNotBlank)
    }

    /**
     * Logos for several shows or films at once, by rating key.
     *
     * An episode on a server older than about 1.43 does not carry its show's logo, so it
     * is asked for here — one request for all of them, since the metadata endpoint takes
     * a comma-separated list. That is what Plex's own web app does. Best effort: a
     * failure leaves the titles as text.
     */
    suspend fun logos(base: String, token: String, ratingKeys: Collection<String>): Map<String, String> =
        withContext(Dispatchers.IO) {
            if (ratingKeys.isEmpty()) return@withContext emptyMap()
            runCatching {
                val metadata = container("$base/library/metadata/${ratingKeys.joinToString(",")}", token)
                    .optJSONArray("Metadata") ?: JSONArray()
                (0 until metadata.length())
                    .mapNotNull { metadata.optJSONObject(it) }
                    .mapNotNull { entry -> logoOf(entry)?.let { entry.optString("ratingKey") to it } }
                    .toMap()
            }.getOrDefault(emptyMap())
        }

    /**
     * A logo at its own size, straight from the server. Not through the image resizer:
     * that is asked to fill a frame, which is right for a poster and wrong for a logo,
     * and a logo is small enough not to need resizing at all.
     */
    fun logoUrl(base: String, token: String, path: String): String {
        val separator = if ('?' in path) '&' else '?'
        return "$base$path${separator}X-Plex-Token=$token"
    }

    private fun tags(entry: JSONObject, field: String): List<String> {
        val array = entry.optJSONArray(field) ?: return emptyList()
        return (0 until array.length())
            .mapNotNull { array.getJSONObject(it).optString("tag").takeIf(String::isNotBlank) }
    }

    private fun roles(entry: JSONObject): List<PlexRole> {
        val array = entry.optJSONArray("Role") ?: return emptyList()
        return (0 until array.length())
            .map { array.getJSONObject(it) }
            .mapNotNull { role ->
                val name = role.optString("tag").takeIf(String::isNotBlank) ?: return@mapNotNull null
                PlexRole(
                    name = name,
                    role = role.optString("role").takeIf(String::isNotBlank),
                    thumb = role.optString("thumb").takeIf(String::isNotEmpty),
                    id = role.optString("id").takeIf(String::isNotBlank),
                )
            }
    }

    /**
     * Everything in one library a person appears in, newest first. Plex filters a library
     * by the id it gives each person, which is the id in a title's cast list.
     */
    suspend fun withActor(base: String, token: String, section: PlexSection, type: Int, personId: String): List<PlexItem> =
        items(base, token, "/library/sections/${section.key}/all?type=$type&actor=$personId&sort=originallyAvailableAt:desc", limit = 300)

    /** Plex has been known to collapse a one-element collection to a bare object. */
    private fun markerArray(metadata: JSONObject): JSONArray? =
        metadata.optJSONArray("Marker")
            ?: metadata.optJSONObject("Marker")?.let { JSONArray().put(it) }

    /**
     * The chapters in a file, when the server read any. A single chapter spanning the
     * whole thing is how some encoders write "no chapters", and is not offered.
     */
    internal fun chaptersOf(metadata: JSONObject, base: String, token: String): List<PlexChapter> {
        val array = metadata.optJSONArray("Chapter")
            ?: metadata.optJSONObject("Chapter")?.let { JSONArray().put(it) }
            ?: return emptyList()
        val chapters = (0 until array.length()).mapNotNull { array.optJSONObject(it) }.mapIndexed { i, entry ->
            val thumb = entry.optString("thumb").takeIf(String::isNotBlank)
            PlexChapter(
                title = entry.optString("tag").takeIf(String::isNotBlank) ?: "Chapter ${entry.optInt("index", i + 1)}",
                startMs = entry.optLong("startTimeOffset"),
                endMs = entry.optLong("endTimeOffset"),
                thumbUrl = thumb?.let { if (it.startsWith("http")) it else "$base$it?X-Plex-Token=$token" },
            )
        }.sortedBy { it.startMs }
        return chapters.takeIf { it.size > 1 }.orEmpty()
    }

    private fun markersOf(metadata: JSONObject): List<PlexMarker> {
        val markers = markerArray(metadata) ?: return emptyList()
        return (0 until markers.length())
            .map { markers.getJSONObject(it) }
            .mapNotNull { marker ->
                val type = marker.optString("type").takeIf(String::isNotBlank) ?: return@mapNotNull null
                val start = marker.optLong("startTimeOffset", -1)
                val end = marker.optLong("endTimeOffset", -1)
                if (start < 0 || end <= start) return@mapNotNull null
                PlexMarker(type = type, startMs = start, endMs = end)
            }
    }

    /**
     * The sound that will play: the audio stream the server has selected, which is the
     * one it sends and the one a viewer may have chosen on another device. Failing that,
     * what the file as a whole says about its audio.
     */
    private fun audioOf(media: JSONObject, part: JSONObject): Pair<String, Int>? {
        val streams = part.optJSONArray("Stream")
        val selected = streams?.let { array ->
            (0 until array.length())
                .map { array.getJSONObject(it) }
                .filter { it.optInt("streamType") == AUDIO_STREAM }
                .let { audio -> audio.firstOrNull { isSelected(it) } ?: audio.firstOrNull() }
        }
        val codec = selected?.optString("codec")?.takeIf(String::isNotBlank)
            ?: media.optString("audioCodec").takeIf(String::isNotBlank)
            ?: return null
        val channels = selected?.optInt("channels")?.takeIf { it > 0 }
            ?: media.optInt("audioChannels")
        return codec.lowercase() to channels
    }

    /** Servers have said this as a boolean and as a number. */
    private fun isSelected(stream: JSONObject): Boolean =
        when (val value = stream.opt("selected")) {
            is Boolean -> value
            is Number -> value.toInt() == 1
            is String -> value == "1" || value.equals("true", ignoreCase = true)
            else -> false
        }

    internal fun streamsOf(part: JSONObject, type: Int): List<tv.reely.core.PlexStream> {
        val streams = part.optJSONArray("Stream") ?: return emptyList()
        return (0 until streams.length())
            .map { streams.getJSONObject(it) }
            .filter { it.optInt("streamType") == type }
            .mapNotNull { stream ->
                tv.reely.core.PlexStream(
                    id = stream.optString("id").takeIf(String::isNotEmpty) ?: return@mapNotNull null,
                    language = stream.optString("languageTag").takeIf(String::isNotEmpty)
                        ?: stream.optString("languageCode").takeIf(String::isNotEmpty),
                    selected = isSelected(stream),
                    external = stream.optString("key").isNotEmpty(),
                )
            }
    }

    private fun subtitlesOf(part: JSONObject, base: String, token: String): List<PlexSubtitle> {
        val streams = part.optJSONArray("Stream") ?: return emptyList()
        return (0 until streams.length())
            .map { streams.getJSONObject(it) }
            .filter { it.optInt("streamType") == SUBTITLE_STREAM }
            .mapNotNull { stream ->
                // Only streams Plex serves on their own URL can be sideloaded into the player.
                val streamKey = stream.optString("key").takeIf(String::isNotEmpty) ?: return@mapNotNull null
                val mime = subtitleMimeType(stream.optString("codec")) ?: return@mapNotNull null
                val language = stream.optString("language").takeIf(String::isNotEmpty)
                PlexSubtitle(
                    id = stream.optString("id"),
                    label = stream.optString("displayTitle").ifEmpty { language ?: "Subtitles" },
                    url = "$base$streamKey?X-Plex-Token=$token",
                    mimeType = mime,
                    language = stream.optString("languageTag").takeIf(String::isNotEmpty) ?: language,
                )
            }
    }

    private fun subtitleMimeType(codec: String): String? = when (codec.lowercase()) {
        "srt", "subrip" -> "application/x-subrip"
        "ass", "ssa" -> "text/x-ssa"
        "vtt", "webvtt" -> "text/vtt"
        // pgs, dvd_subtitle and friends are bitmaps: the server would have to burn them in.
        else -> null
    }

    private fun container(url: String, token: String): JSONObject {
        // Identify as a client on every read, not just on the plex.tv calls. Some server
        // responses vary by what the caller says it is, and an anonymous request is not
        // something a real Plex client ever sends.
        val request = Request.Builder()
            .url(url)
            .header("accept", "application/json")
            .header("X-Plex-Product", PRODUCT)
            .header("X-Plex-Version", VERSION)
            .header("X-Plex-Platform", "Android")
            .header("X-Plex-Platform-Version", PLATFORM_VERSION)
            .header("X-Plex-Device", android.os.Build.MODEL.orEmpty().ifBlank { "Android TV" })
            .header("X-Plex-Device-Name", deviceName)
            .apply { if (clientId.isNotEmpty()) header("X-Plex-Client-Identifier", clientId) }
            .header("X-Plex-Token", token)
            .build()
        Http.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            require(response.isSuccessful) { "Your Plex server couldn't do that. Try again." }
            return JSONObject(body).optJSONObject("MediaContainer") ?: JSONObject()
        }
    }
}
