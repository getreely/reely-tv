package tv.reely.xtream

import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.reely.core.Http
import java.text.SimpleDateFormat
import java.util.Locale

data class XtreamCredentials(
    val base: String,
    val username: String,
    val password: String,
    /** Set when live TV comes from an M3U playlist rather than an Xtream Codes login. */
    val playlistUrl: String? = null,
    /** An XMLTV guide entered alongside a playlist, over the one the playlist names. */
    val guideUrl: String? = null,
) {
    val isPlaylist: Boolean get() = playlistUrl != null

    companion object {
        fun playlist(url: String, guideUrl: String?) =
            XtreamCredentials(base = url, username = "", password = "", playlistUrl = url, guideUrl = guideUrl)
    }
}

data class XtreamAccount(
    val status: String,
    val maxConnections: String,
    val activeConnections: String,
    val expiresAt: String?,
)

data class XtreamCategory(val id: String, val name: String)

data class XtreamChannel(
    val streamId: Int,
    val number: Int,
    val name: String,
    val icon: String?,
    /** Ties this channel to the XMLTV guide. Panels are inconsistent about case. */
    val epgChannelId: String?,
    /** A playlist channel's own address. Panel channels are addressed by their id. */
    val url: String? = null,
    /** A playlist channel's group, which stands in for a panel's category. */
    val group: String? = null,
)

enum class StreamFormat(val extension: String, val label: String) {
    TS("ts", "MPEG-TS"),
    HLS("m3u8", "HLS"),
}

/**
 * One programme from the panel's own short EPG. This is the cheap guide: a handful of
 * entries for a single channel, asked for when somebody looks at that channel. The full
 * XMLTV dump for a large provider runs to tens of megabytes and cannot be held in memory
 * on a stick, so it is not what feeds this.
 */
data class XtreamProgramme(
    val title: String,
    val description: String?,
    val startEpochSeconds: Long,
    val endEpochSeconds: Long,
) {
    /** How far through the programme we are now, or null when it is not on yet. */
    fun progressAt(nowEpochSeconds: Long): Float? {
        if (nowEpochSeconds < startEpochSeconds || nowEpochSeconds > endEpochSeconds) return null
        val span = (endEpochSeconds - startEpochSeconds).toFloat()
        if (span <= 0f) return null
        return ((nowEpochSeconds - startEpochSeconds) / span).coerceIn(0f, 1f)
    }
}

/**
 * The provider's own panel API — the same thing the provider's own app logs into.
 * Nothing is baked in: host, username and password are whatever this install was given.
 */
object XtreamApi {

    /** Accepts `panel.example.com:8080`, `http://…`, trailing slashes and all. */
    fun normalizeBase(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return when {
            trimmed.isEmpty() -> trimmed
            trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true) -> trimmed
            else -> "http://$trimmed"
        }
    }

    /** The playlist last downloaded, so categories and channels don't fetch it again. */
    @Volatile
    private var playlist: Pair<String, M3uPlaylist>? = null

    private suspend fun playlistFor(credentials: XtreamCredentials, fresh: Boolean = false): M3uPlaylist {
        val url = credentials.playlistUrl ?: error("Not a playlist.")
        playlist?.takeIf { !fresh && it.first == url }?.let { return it.second }
        return M3uPlaylist.download(url).also { playlist = url to it }
    }

    /**
     * Fetches the channel list again next time it is asked for. A panel is asked every
     * time anyway; a playlist is kept after the first download until this.
     */
    suspend fun reload(credentials: XtreamCredentials) {
        if (credentials.isPlaylist) playlistFor(credentials, fresh = true)
    }

    /** A playlist has no account to speak of: this only checks it has channels in it. */
    private suspend fun openPlaylist(credentials: XtreamCredentials): XtreamAccount {
        val list = playlistFor(credentials, fresh = true)
        if (list.channels.isEmpty()) error("There are no live channels in that playlist.")
        return XtreamAccount(status = "Active", maxConnections = "?", activeConnections = "0", expiresAt = null)
    }

    suspend fun login(credentials: XtreamCredentials): XtreamAccount = withContext(Dispatchers.IO) {
        if (credentials.isPlaylist) return@withContext openPlaylist(credentials)
        val json = get(credentials, action = null)
        val root = JSONObject(json)
        val user = root.optJSONObject("user_info")
            ?: error("Couldn't connect. Check the server address and port.")

        // Panels word this differently; treat anything but an explicit 1 as a rejection.
        if (user.optInt("auth", 0) != 1) {
            error("Incorrect username or password.")
        }
        val status = user.optString("status").ifEmpty { "Unknown" }
        if (!status.equals("Active", ignoreCase = true)) {
            error("This account is $status. Contact your provider.")
        }
        XtreamAccount(
            status = status,
            maxConnections = user.optString("max_connections").ifEmpty { "?" },
            activeConnections = user.optString("active_cons").ifEmpty { "0" },
            expiresAt = user.optString("exp_date").takeIf { it.isNotEmpty() && it != "null" },
        )
    }

    suspend fun liveCategories(credentials: XtreamCredentials): List<XtreamCategory> =
        withContext(Dispatchers.IO) {
            if (credentials.isPlaylist) {
                return@withContext playlistFor(credentials).groups.map { XtreamCategory(id = it, name = it) }
            }
            val array = JSONArray(get(credentials, "get_live_categories"))
            (0 until array.length()).map { array.getJSONObject(it) }.map {
                XtreamCategory(
                    id = it.optString("category_id"),
                    name = it.optString("category_name").ifEmpty { "Unnamed" },
                )
            }.filter { it.id.isNotEmpty() }
                // Same reasoning as the channels below: the id is a lazy list's key, and
                // a repeated one brings the app down when it scrolls into view.
                .distinctBy { it.id }
        }

    /**
     * One category's channels, or — with no category — every channel the account carries.
     * The whole list is what search needs; a panel with tens of thousands of channels
     * makes that a slow call, so it is asked for once and kept.
     */
    suspend fun liveChannels(
        credentials: XtreamCredentials,
        categoryId: String? = null,
    ): List<XtreamChannel> = withContext(Dispatchers.IO) {
        if (credentials.isPlaylist) {
            val channels = playlistFor(credentials).channels
            return@withContext if (categoryId == null) channels
            else channels.filter { (it.group ?: M3uPlaylist.OTHER) == categoryId }
        }
        val extras = categoryId?.let { arrayOf("category_id" to it) } ?: emptyArray()
        val array = JSONArray(get(credentials, "get_live_streams", *extras))
        (0 until array.length()).map { array.getJSONObject(it) }.mapNotNull {
            val streamId = it.optInt("stream_id", -1)
            if (streamId < 0) return@mapNotNull null
            XtreamChannel(
                streamId = streamId,
                number = it.optInt("num"),
                name = it.optString("name").ifEmpty { "Channel $streamId" },
                icon = it.optString("stream_icon").takeIf { icon -> icon.startsWith("http") },
                epgChannelId = it.optString("epg_channel_id")
                    .takeIf(String::isNotBlank)?.lowercase(),
            )
        }
            /*
             * A panel lists the same stream in as many categories as it likes, and asking
             * for the whole lineup — which is what search does — returns it once per
             * category. Two entries with one id is a repeated key in a lazy row, and a
             * lazy row only checks its keys as items come into view: holding right to
             * scroll through search results brought the app down the moment it reached
             * the duplicate. It also handed one focus requester to two nodes, which is
             * fatal on its own. PlexApi has done the same on ratingKey for a while.
             */
            .distinctBy { it.streamId }
    }

    /**
     * Where the provider serves its whole XMLTV guide. For a playlist, the guide entered
     * with it, else the one it names itself; null when there is neither.
     */
    fun xmltvUrl(credentials: XtreamCredentials): String? =
        if (credentials.isPlaylist) {
            credentials.guideUrl ?: playlist?.takeIf { it.first == credentials.playlistUrl }?.second?.guideUrl
        } else Uri.parse("${credentials.base}/xmltv.php").buildUpon()
            .appendQueryParameter("username", credentials.username)
            .appendQueryParameter("password", credentials.password)
            .build()
            .toString()

    fun streamUrl(
        credentials: XtreamCredentials,
        channel: XtreamChannel,
        format: StreamFormat,
    ): String = channel.url ?: with(credentials) {
        "$base/live/${Uri.encode(username)}/${Uri.encode(password)}/${channel.streamId}.${format.extension}"
    }

    private fun get(
        credentials: XtreamCredentials,
        action: String?,
        vararg extras: Pair<String, String>,
    ): String {
        val url = Uri.parse("${credentials.base}/player_api.php").buildUpon()
            .appendQueryParameter("username", credentials.username)
            .appendQueryParameter("password", credentials.password)
            .apply {
                if (action != null) appendQueryParameter("action", action)
                extras.forEach { (key, value) -> appendQueryParameter(key, value) }
            }
            .build()
            .toString()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "ReelyTV/${tv.reely.BuildConfig.VERSION_NAME} (Android TV)")
            .get()
            .build()

        Http.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            require(response.isSuccessful) { "Your provider returned an error (${response.code})." }
            require(body.isNotBlank()) { "Your provider didn't respond. Try again." }
            return body
        }
    }

    /**
     * Now and next for one channel. Panels base64-encode the title and description and
     * are inconsistent about which timestamp fields they send, so both are handled.
     */
    suspend fun shortEpg(
        credentials: XtreamCredentials,
        streamId: Int,
        limit: Int = 4,
    ): List<XtreamProgramme> = withContext(Dispatchers.IO) {
        // A playlist has no panel to ask; its guide is the XMLTV one or nothing.
        if (credentials.isPlaylist) return@withContext emptyList()
        val body = get(
            credentials,
            "get_short_epg",
            "stream_id" to streamId.toString(),
            "limit" to limit.toString(),
        )
        val listings = JSONObject(body).optJSONArray("epg_listings") ?: return@withContext emptyList()
        (0 until listings.length())
            .map { listings.getJSONObject(it) }
            .mapNotNull { entry ->
                val start = entry.optString("start_timestamp").toLongOrNull()
                    ?: parsePanelTime(entry.optString("start"))
                    ?: return@mapNotNull null
                val end = entry.optString("stop_timestamp").toLongOrNull()
                    ?: parsePanelTime(entry.optString("end"))
                    ?: return@mapNotNull null
                XtreamProgramme(
                    title = decodeField(entry.optString("title")).ifEmpty { "Untitled" },
                    description = decodeField(entry.optString("description")).takeIf { it.isNotBlank() },
                    startEpochSeconds = start,
                    endEpochSeconds = end,
                )
            }
            .sortedBy { it.startEpochSeconds }
    }

    /** Panel fields are usually base64; a panel that sends plain text should still work. */
    private fun decodeField(raw: String): String {
        if (raw.isEmpty()) return ""
        return runCatching {
            String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault(raw).trim()
    }

    private fun parsePanelTime(raw: String): Long? {
        if (raw.isBlank()) return null
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(raw)?.time?.div(1000)
        }.getOrNull()
    }
}

