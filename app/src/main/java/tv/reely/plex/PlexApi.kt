package tv.reely.plex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.reely.core.Http

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
 * into the video by the server, which is a transcode decision this spike does not make.
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

data class PlexItem(
    val ratingKey: String,
    val title: String,
    val type: String,
    val thumb: String?,
    val subtitle: String?,
) {
    val isPlayable: Boolean get() = type == "movie" || type == "episode"

    /** Path to drill into, for the container types that have children. */
    val childPath: String?
        get() = if (type == "show" || type == "season") "/library/metadata/$ratingKey/children" else null
}

/**
 * The plex.tv PIN flow plus the handful of server endpoints the spike needs.
 * Everything here is a plain JSON read: the hard streaming work stays on the server.
 */
object PlexApi {

    private const val PLEX_TV = "https://plex.tv"
    private const val PRODUCT = "Reely TV"
    private const val VERSION = "0.1.0"
    private const val SUBTITLE_STREAM = 3

    fun linkUrl(): String = "https://plex.tv/link"

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
    ): List<PlexItem> = withContext(Dispatchers.IO) {
        val separator = if (path.contains('?')) "&" else "?"
        val url = "$base$path${separator}X-Plex-Container-Start=0&X-Plex-Container-Size=$limit"
        val metadata = container(url, token).optJSONArray("Metadata") ?: JSONArray()
        (0 until metadata.length()).map { index ->
            val entry = metadata.getJSONObject(index)
            val type = entry.optString("type")
            PlexItem(
                ratingKey = entry.optString("ratingKey"),
                title = entry.optString("title"),
                type = type,
                thumb = entry.optString("thumb").takeIf(String::isNotEmpty),
                subtitle = when (type) {
                    "episode" -> "S${entry.optInt("parentIndex")} · E${entry.optInt("index")}"
                    "season" -> entry.optInt("leafCount").takeIf { it > 0 }?.let { "$it episodes" }
                    else -> entry.optInt("year").takeIf { it > 0 }?.toString()
                },
            )
        }
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

    fun imageUrl(base: String, token: String, thumb: String?): String? =
        thumb?.let { "$base$it?X-Plex-Token=$token" }

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
