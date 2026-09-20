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

/** One genre a library can be narrowed to. The id is what the filter takes. */
data class PlexGenre(val id: String, val title: String)

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

data class PlexPlayback(
    val url: String,
    val subtitles: List<PlexSubtitle>,
    val markers: List<PlexMarker> = emptyList(),
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
    val viewCount: Int,
    val addedAt: Long,
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
    val audienceRating: Double?,
    val airDate: String?,
    val viewCount: Int,
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

    val isWatched: Boolean
        get() = when (type) {
            "movie", "episode" -> viewCount > 0
            "show", "season" -> leafCount > 0 && childCount >= 0 && viewCount > 0
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

    /**
     * The genres this library actually contains, as the server counts them. Offering a
     * fixed list would show genres nothing in the library matches.
     */
    suspend fun genres(
        base: String,
        token: String,
        sectionKey: String,
        type: Int,
    ): List<PlexGenre> = withContext(Dispatchers.IO) {
        val directories = container("$base/library/sections/$sectionKey/genre?type=$type", token)
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
                audienceRating = entry.optDouble("audienceRating").takeIf { !it.isNaN() && it > 0 },
                airDate = entry.optString("originallyAvailableAt").takeIf(String::isNotBlank),
                viewCount = entry.optInt("viewCount"),
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
            // includeMarkers asks the server for its intro and credits detection.
            val metadata = container(
                "$base/library/metadata/$ratingKey?includeMarkers=1&includeChapters=1",
                token,
            )
                .optJSONArray("Metadata")?.optJSONObject(0) ?: return@withContext null
            val media = metadata.optJSONArray("Media")?.optJSONObject(0) ?: return@withContext null
            val part = media.optJSONArray("Part")?.optJSONObject(0) ?: return@withContext null
            val key = part.optString("key").takeIf(String::isNotEmpty) ?: return@withContext null

            PlexPlayback(
                url = "$base$key?X-Plex-Token=$token",
                subtitles = subtitlesOf(part, base, token),
                markers = markersOf(metadata),
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
        offsetMs: Long,
        maxBitrateKbps: Int,
        resolution: String,
    ): String {
        val path = URLEncoder.encode("/library/metadata/$ratingKey", "UTF-8")
        val bitrate = if (maxBitrateKbps > 0) "&maxVideoBitrate=$maxBitrateKbps" else ""
        return "$base/video/:/transcode/universal/start.m3u8" +
            "?path=$path&mediaIndex=0&partIndex=0" +
            "&protocol=hls&fastSeek=1&directPlay=0&directStream=1" +
            // Burned in, because a transcode is exactly when the picture subtitles that
            // cannot be sideloaded become playable.
            "&subtitles=burn&audioBoost=100&videoQuality=100" +
            "&videoResolution=$resolution$bitrate" +
            "&offset=${offsetMs / 1000}" +
            "&session=$sessionId" +
            "&X-Plex-Client-Identifier=$clientId" +
            "&X-Plex-Platform=Android&X-Plex-Product=" + URLEncoder.encode(PRODUCT, "UTF-8") +
            "&X-Plex-Token=$token"
    }

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
        withContext(Dispatchers.IO) {
            if (query.isBlank()) return@withContext emptyList()
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val hubs = container("$base/hubs/search?query=$encoded&limit=30", token)
                .optJSONArray("Hub") ?: JSONArray()

            val wanted = setOf("movie", "show", "episode")
            buildList {
                for (index in 0 until hubs.length()) {
                    val metadata = hubs.getJSONObject(index).optJSONArray("Metadata") ?: continue
                    for (entry in 0 until metadata.length()) {
                        val item = parseItem(metadata.getJSONObject(entry))
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
            require(response.isSuccessful) { "Plex returned ${response.code} marking that watched" }
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
        viewCount = entry.optInt("viewCount"),
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

    /** Plex has been known to collapse a one-element collection to a bare object. */
    private fun markerArray(metadata: JSONObject): JSONArray? =
        metadata.optJSONArray("Marker")
            ?: metadata.optJSONObject("Marker")?.let { JSONArray().put(it) }

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
            .header("X-Plex-Device", "Android TV")
            .header("X-Plex-Device-Name", "Reely TV")
            .apply { if (clientId.isNotEmpty()) header("X-Plex-Client-Identifier", clientId) }
            .header("X-Plex-Token", token)
            .build()
        Http.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            require(response.isSuccessful) { "Plex server returned ${response.code}" }
            return JSONObject(body).optJSONObject("MediaContainer") ?: JSONObject()
        }
    }
}
