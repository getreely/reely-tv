package tv.reely.xtream

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import tv.reely.core.Http
import java.io.BufferedInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlin.coroutines.coroutineContext

/**
 * Reads the provider's whole XMLTV guide and writes it to SQLite as it arrives.
 *
 * Nothing accumulates: the parser pulls one element at a time off the socket and each
 * programme is handed straight to the insert. A guide with several hundred thousand
 * programmes therefore costs a constant few hundred kilobytes of memory, which is the
 * only way this works on a stick.
 */
object XmltvImporter {

    /** Programmes outside this window are parsed and discarded rather than stored. */
    const val PAST_WINDOW_SECONDS = 6L * 3600
    const val FUTURE_WINDOW_SECONDS = 7L * 24 * 3600

    suspend fun import(
        credentials: XtreamCredentials,
        store: EpgStore,
        nowSeconds: Long,
        onProgress: (written: Int, scanned: Int) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val keepFrom = nowSeconds - PAST_WINDOW_SECONDS
        val keepTo = nowSeconds + FUTURE_WINDOW_SECONDS

        val request = Request.Builder()
            .url(XtreamApi.xmltvUrl(credentials))
            // Asking for gzip ourselves means OkHttp will not transparently unwrap it,
            // so the stream is sniffed below instead.
            .header("Accept-Encoding", "gzip")
            .header("User-Agent", "ReelyTV/0.4 (Android TV)")
            .get()
            .build()

        Http.bulk.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Couldn't download the TV guide (error ${response.code})." }
            val body = response.body ?: error("Your provider didn't send a TV guide.")

            val stream = maybeGunzip(BufferedInputStream(body.byteStream(), 64 * 1024))
            val import = store.beginImport()
            var scanned = 0
            try {
                parse(stream) { channelId, start, stop, title, description ->
                    scanned++
                    if (stop > keepFrom && start < keepTo && start < stop) {
                        import.add(channelId, start, stop, title, description)
                    }
                    if (scanned % 5_000 == 0) {
                        coroutineContext.ensureActive()
                        onProgress(import.written, scanned)
                    }
                }
                import.finish(nowSeconds)
            } catch (failure: Throwable) {
                import.abort()
                throw failure
            }
            onProgress(import.written, scanned)
            import.written
        }
    }

    /** Some panels serve gzip without saying so, and some say so without meaning it. */
    private fun maybeGunzip(stream: BufferedInputStream): InputStream {
        stream.mark(2)
        val first = stream.read()
        val second = stream.read()
        stream.reset()
        val isGzip = first == 0x1f && second == 0x8b
        return if (isGzip) GZIPInputStream(stream, 64 * 1024) else stream
    }

    private inline fun parse(
        stream: InputStream,
        emit: (channelId: String, start: Long, stop: Long, title: String, description: String?) -> Unit,
    ) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, null)

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                val channelId = parser.getAttributeValue(null, "channel")?.lowercase()
                val start = parseTime(parser.getAttributeValue(null, "start"))
                val stop = parseTime(parser.getAttributeValue(null, "stop"))

                var title: String? = null
                var description: String? = null
                while (true) {
                    val inner = parser.next()
                    if (inner == XmlPullParser.END_DOCUMENT) break
                    if (inner == XmlPullParser.END_TAG && parser.name == "programme") break
                    if (inner != XmlPullParser.START_TAG) continue
                    when (parser.name) {
                        // nextText leaves the parser on the closing tag, so the loop stays honest.
                        "title" -> if (title == null) title = parser.nextText() else skip(parser)
                        "desc" -> if (description == null) description = parser.nextText() else skip(parser)
                        else -> skip(parser)
                    }
                }

                if (channelId != null && start > 0 && stop > 0) {
                    emit(
                        channelId,
                        start,
                        stop,
                        title?.trim().orEmpty().ifEmpty { "No information" },
                        description?.trim()?.takeIf(String::isNotEmpty),
                    )
                }
            }
            event = parser.next()
        }
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /**
     * XMLTV time is "20240115143000 +0100". Parsed by hand rather than with a date
     * formatter, because this runs a few hundred thousand times per import.
     */
    fun parseTime(raw: String?): Long {
        if (raw == null || raw.length < 14) return 0
        val year = raw.digits(0, 4) ?: return 0
        val month = raw.digits(4, 6) ?: return 0
        val day = raw.digits(6, 8) ?: return 0
        val hour = raw.digits(8, 10) ?: return 0
        val minute = raw.digits(10, 12) ?: return 0
        val second = raw.digits(12, 14) ?: return 0
        if (month !in 1..12 || day !in 1..31) return 0

        var epoch = daysFromCivil(year, month, day) * 86_400L +
            hour * 3600L + minute * 60L + second

        // An offset is optional; without one the time is already UTC by convention.
        val rest = raw.substring(14).trim()
        if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-')) {
            val offsetHours = rest.digits(1, 3) ?: 0
            val offsetMinutes = rest.digits(3, 5) ?: 0
            val offset = offsetHours * 3600L + offsetMinutes * 60L
            epoch += if (rest[0] == '-') offset else -offset
        }
        return epoch
    }

    private fun String.digits(from: Int, to: Int): Int? {
        if (to > length) return null
        var value = 0
        for (index in from until to) {
            val digit = this[index] - '0'
            if (digit !in 0..9) return null
            value = value * 10 + digit
        }
        return value
    }

    /** Days since the epoch, by the civil-calendar algorithm. No Calendar allocation. */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val monthTerm = if (month > 2) month - 3 else month + 9
        val dayOfYear = (153 * monthTerm + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era.toLong() * 146_097L + dayOfEra.toLong() - 719_468L
    }
}
