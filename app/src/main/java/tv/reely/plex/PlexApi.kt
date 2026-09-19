package tv.reely.plex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.reely.core.Http
import java.net.URLEncoder

data class PlexPin(val id: Long, val code: String)

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

data class PlexPlayback(
    val url: String,
    val subtitles: List<PlexSubtitle>,
)

/** A person in the cast, as Plex records them. */
data class PlexRole(
    val name: String,
    val role: String?,
    val thumb: String?,
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
    val addedAt: Long,
) {
    val isPlayable: Boolean get() = type == "movie" || type == "episode"

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
            else -> year?.toString()
        }

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
    val studio: String?,
    val thumb: String?,
    val art: String?,
    val genres: List<String>,
    val directors: List<String>,
    val roles: List<PlexRole>,
    val childCount: Int,
    val leafCount: Int,
    val grandparentTitle: String?,
    val index: Int?,
    val parentIndex: Int?,
) {
    val isShow: Boolean get() = type == "show"

    /** "2014 · 2h 18m · TV-MA" — the line Plex puts under a title. */
    val facts: String
        get() = listOfNotNull(
            year?.toString(),
            formatDuration(durationMs).takeIf { durationMs > 0 },
            contentRating,
            studio,
        ).joinToString("  ·  ")
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

    private const val PLEX_TV = "https://plex.tv"
    private const val PRODUCT = "Reely TV"
    private const val VERSION = "0.2.0"
    private const val SUBTITLE_STREAM = 3

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
        header("X-Plex-Device", "Android TV")
        header("X-Plex-Device-Name", "Reely TV")
        if (token != null) header("X-Plex-Token", token)
    }

    suspend fun createPin(clientId: String): PlexPin = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$PLEX_TV/api/v2/pins")
            .plexHeaders(clientId)
            // Not a strong PIN: plex.tv/link takes the short four-character kind.
            // A strong PIN is a long string meant for the app.plex.tv deep-link flow,
            // which nobody can type into four boxes on a television.
            .post(FormBody.Builder().add("strong", "false").build())
            .build()
        Http.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            require(response.isSuccessful) { "plex.tv returned ${response.code} asking for a link code" }
            val json = JSONObject(body)
            PlexPin(json.getLong("id"), json.getString("code"))
        }
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
            require(response.isSuccessful) { "plex.tv returned ${response.code} listing servers" }
            val resources = JSONArray(body)
            buildList {
                for (index in 0 until resources.length()) {
                    val resource = resources.getJSONObject(index)
                    if (!resource.optString("provides").contains("server")) continue

                    val connections = resource.optJSONArray("connections") ?: JSONArray()
                    val uris = (0 until connections.length())
                        .map { connections.getJSONObject(it) }
                        // Local first, relay last: a relay works everywhere but is slow.
                        .sortedWith(
                            compareBy<JSONObject>(
                                { it.optBoolean("relay") },
                                { !it.optBoolean("local") },
                            )
                        )
                        .mapNotNull { it.optString("uri").takeIf(String::isNotEmpty) }

                    if (uris.isEmpty()) continue
                    add(
                        PlexServer(
                            name = resource.optString("name").ifEmpty { "Plex Media Server" },
                            accessToken = resource.optString("accessToken").ifEmpty { token },
                            connections = uris,
                        )
                    )
                }
            }
        }
    }

    /** The first address that actually answers, which is not knowable from the list alone. */
    suspend fun firstReachable(server: PlexServer): String? = withContext(Dispatchers.IO) {
        for (uri in server.connections) {
            val request = Request.Builder()
                .url("$uri/identity")
                .header("accept", "application/json")
                .header("X-Plex-Token", server.accessToken)
                .build()
            val reachable = runCatching {
                Http.probe.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (reachable) return@withContext uri
        }
        null
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
        (0 until metadata.length()).map { parseItem(metadata.getJSONObject(it)) }
    }

    /**
     * On Deck — what Plex itself thinks you should carry on with. Rendered in the order
     * the server returns it: no merging, pruning or re-sorting on this side.
     */
    suspend fun onDeck(base: String, token: String): List<PlexItem> =
        items(base, token, "/library/onDeck", limit = 40)

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

    suspend fun detail(base: String, token: String, ratingKey: String): PlexDetail? =
        withContext(Dispatchers.IO) {
            val entry = container("$base/library/metadata/$ratingKey", token)
                .optJSONArray("Metadata")?.optJSONObject(0) ?: return@withContext null
            PlexDetail(
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
                studio = entry.optString("studio").takeIf(String::isNotBlank),
                thumb = entry.optString("thumb").takeIf(String::isNotEmpty),
                art = entry.optString("art").takeIf(String::isNotEmpty),
                genres = tags(entry, "Genre"),
                directors = tags(entry, "Director"),
                roles = roles(entry),
                childCount = entry.optInt("childCount"),
                leafCount = entry.optInt("leafCount"),
                grandparentTitle = entry.optString("grandparentTitle").takeIf(String::isNotBlank),
                index = entry.optInt("index").takeIf { it > 0 },
                parentIndex = entry.optInt("parentIndex").takeIf { it > 0 },
            )
        }

    /**
     * Direct-play URL for the first part of an item, plus whatever text subtitles Plex
     * exposes as separate streams. Direct play only: no transcode is requested, so the
     * device has to decode what the file actually contains.
     */
    suspend fun playback(base: String, token: String, ratingKey: String): PlexPlayback? =
        withContext(Dispatchers.IO) {
            val metadata = container("$base/library/metadata/$ratingKey", token)
                .optJSONArray("Metadata")?.optJSONObject(0) ?: return@withContext null
            val media = metadata.optJSONArray("Media")?.optJSONObject(0) ?: return@withContext null
            val part = media.optJSONArray("Part")?.optJSONObject(0) ?: return@withContext null
            val key = part.optString("key").takeIf(String::isNotEmpty) ?: return@withContext null

            PlexPlayback(
                url = "$base$key?X-Plex-Token=$token",
                subtitles = subtitlesOf(part, base, token),
            )
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
    ) = withContext(Dispatchers.IO) {
        val url = "$base/:/timeline?ratingKey=$ratingKey" +
            "&key=" + URLEncoder.encode("/library/metadata/$ratingKey", "UTF-8") +
            "&state=$state&time=$positionMs&duration=$durationMs&X-Plex-Token=$token"
        val request = Request.Builder().url(url).header("accept", "application/json").get().build()
        runCatching { Http.client.newCall(request).execute().use { it.isSuccessful } }
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
        leafCount = entry.optInt("leafCount"),
        viewedLeafCount = entry.optInt("viewedLeafCount"),
        addedAt = entry.optLong("addedAt"),
    )

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
        val request = Request.Builder()
            .url(url)
            .header("accept", "application/json")
            .header("X-Plex-Token", token)
            .build()
        Http.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            require(response.isSuccessful) { "Plex server returned ${response.code}" }
            return JSONObject(body).optJSONObject("MediaContainer") ?: JSONObject()
        }
    }
}
