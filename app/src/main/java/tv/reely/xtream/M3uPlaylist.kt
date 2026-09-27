package tv.reely.xtream

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import tv.reely.core.Http
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

/**
 * An M3U playlist: the other way live TV providers hand out their channels, and the only
 * way for the ones that don't run an Xtream Codes panel. One address, a list of channels
 * with their groups, logos and guide ids, and often the address of an XMLTV guide.
 */
data class M3uPlaylist(
    val channels: List<XtreamChannel>,
    /** The guide the playlist names in its header, if it names one. */
    val guideUrl: String?,
) {
    /** The groups, in the order the playlist first mentions them. */
    val groups: List<String> get() = channels.map { it.group ?: OTHER }.distinct()

    companion object {
        /** Where channels the playlist doesn't put in a group are listed. */
        const val OTHER = "Other"

        /** Some playlists start with a byte-order mark, which would hide the header. */
        private const val BOM = '\uFEFF'
        private val ATTRIBUTE = Regex("""([A-Za-z0-9_-]+)="([^"]*)"""")
        private val VOD_EXTENSIONS = listOf(".mp4", ".mkv", ".avi", ".mov", ".m4v", ".wmv", ".flv", ".webm")

        suspend fun download(url: String): M3uPlaylist = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "ReelyTV/${tv.reely.BuildConfig.VERSION_NAME} (Android TV)")
                .get()
                .build()
            val call = runCatching { Http.bulk.newCall(request).execute() }.getOrElse {
                error("Couldn't reach the playlist. Check the address.")
            }
            call.use { response ->
                require(response.isSuccessful) { "Couldn't download the playlist (error ${response.code})." }
                val body = response.body ?: error("The playlist was empty.")
                val stream = body.byteStream().buffered(64 * 1024).let { raw ->
                    raw.mark(2)
                    val gzipped = raw.read() == 0x1f && raw.read() == 0x8b
                    raw.reset()
                    if (gzipped) GZIPInputStream(raw) else raw
                }
                val context = coroutineContext
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).useLines { lines ->
                    parse(lines.onEach { context.ensureActive() })
                }
            }
        }

        /**
         * Reads the playlist a line at a time. Playlists from big providers run to hundreds
         * of thousands of lines, most of them films and series, which are passed over:
         * only live channels are kept.
         */
        fun parse(lines: Sequence<String>): M3uPlaylist {
            var guideUrl: String? = null
            var pending: Pending? = null
            val channels = ArrayList<XtreamChannel>()
            val ids = HashSet<Int>()

            for (raw in lines) {
                val line = raw.trim().trimStart(BOM)
                when {
                    line.isEmpty() -> Unit
                    line.startsWith("#EXTM3U", ignoreCase = true) -> {
                        val attributes = attributesOf(line)
                        guideUrl = (attributes["url-tvg"] ?: attributes["x-tvg-url"])
                            ?.split(',')
                            ?.map { it.trim() }
                            ?.firstOrNull { it.startsWith("http", ignoreCase = true) }
                            ?: guideUrl
                    }
                    line.startsWith("#EXTINF", ignoreCase = true) -> pending = pendingFrom(line)
                    line.startsWith("#EXTGRP:", ignoreCase = true) ->
                        pending = pending?.let { it.copy(group = it.group ?: line.substringAfter(':').trim().ifEmpty { null }) }
                    line.startsWith("#") -> Unit
                    else -> {
                        val entry = pending ?: Pending(name = null, id = null, logo = null, number = null, group = null)
                        pending = null
                        if (isVod(line)) continue
                        var streamId = line.hashCode() and Int.MAX_VALUE
                        // Two channels whose addresses hash the same would share a key in
                        // the lists; the second is nudged along instead of dropped.
                        while (!ids.add(streamId)) streamId = (streamId + 1) and Int.MAX_VALUE
                        channels += XtreamChannel(
                            streamId = streamId,
                            number = entry.number ?: (channels.size + 1),
                            name = entry.name ?: "Channel ${channels.size + 1}",
                            icon = entry.logo,
                            epgChannelId = entry.id?.lowercase(),
                            url = line,
                            group = entry.group,
                        )
                    }
                }
            }
            return M3uPlaylist(channels, guideUrl)
        }

        private data class Pending(
            val name: String?,
            val id: String?,
            val logo: String?,
            val number: Int?,
            val group: String?,
        )

        private fun pendingFrom(line: String): Pending {
            val attributes = attributesOf(line)
            // The name follows the first comma that isn't inside a quoted attribute.
            var quoted = false
            var comma = -1
            for (i in line.indices) {
                when (line[i]) {
                    '"' -> quoted = !quoted
                    ',' -> if (!quoted) { comma = i; break }
                }
            }
            val name = if (comma >= 0) line.substring(comma + 1).trim() else ""
            return Pending(
                name = name.ifEmpty { attributes["tvg-name"]?.trim() }?.ifEmpty { null },
                id = attributes["tvg-id"]?.trim()?.ifEmpty { null },
                logo = attributes["tvg-logo"]?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) },
                number = attributes["tvg-chno"]?.trim()?.toIntOrNull(),
                group = attributes["group-title"]?.trim()?.ifEmpty { null },
            )
        }

        private fun attributesOf(line: String): Map<String, String> =
            ATTRIBUTE.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }

        /** Films and series episodes: playlists from Xtream panels carry them alongside the channels. */
        private fun isVod(url: String): Boolean {
            val path = url.substringBefore('?').lowercase()
            return "/movie/" in path || "/series/" in path || VOD_EXTENSIONS.any { path.endsWith(it) }
        }
    }
}
