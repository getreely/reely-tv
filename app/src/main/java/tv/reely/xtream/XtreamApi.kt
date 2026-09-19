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
)

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

    suspend fun login(credentials: XtreamCredentials): XtreamAccount = withContext(Dispatchers.IO) {
        val json = get(credentials, action = null)
        val root = JSONObject(json)
        val user = root.optJSONObject("user_info")
            ?: error("No Xtream panel answered at that address — check the host and port.")

        // Panels word this differently; treat anything but an explicit 1 as a rejection.
        if (user.optInt("auth", 0) != 1) {
            error("The panel rejected those credentials.")
        }
        val status = user.optString("status").ifEmpty { "Unknown" }
        if (!status.equals("Active", ignoreCase = true)) {
            error("The panel reports this account as \"$status\".")
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
            val array = JSONArray(get(credentials, "get_live_categories"))
            (0 until array.length()).map { array.getJSONObject(it) }.map {
                XtreamCategory(
                    id = it.optString("category_id"),
                    name = it.optString("category_name").ifEmpty { "Unnamed" },
                )
            }.filter { it.id.isNotEmpty() }
        }

    suspend fun liveChannels(
        credentials: XtreamCredentials,
        categoryId: String,
    ): List<XtreamChannel> = withContext(Dispatchers.IO) {
        val array = JSONArray(get(credentials, "get_live_streams", "category_id" to categoryId))
        (0 until array.length()).map { array.getJSONObject(it) }.mapNotNull {
            val streamId = it.optInt("stream_id", -1)
            if (streamId < 0) return@mapNotNull null
            XtreamChannel(
                streamId = streamId,
                number = it.optInt("num"),
                name = it.optString("name").ifEmpty { "Channel $streamId" },
                icon = it.optString("stream_icon").takeIf { icon -> icon.startsWith("http") },
            )
        }
    }

    fun streamUrl(
        credentials: XtreamCredentials,
        channel: XtreamChannel,
        format: StreamFormat,
    ): String = with(credentials) {
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
            .header("User-Agent", "ReelyTV/0.1 (Android TV)")
            .get()
            .build()

        Http.client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            require(response.isSuccessful) { "The panel returned HTTP ${response.code}." }
            require(body.isNotBlank()) { "The panel returned an empty response." }
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

