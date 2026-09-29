package tv.reely.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The server's scrubbing pictures for one file, all of them, in hand.
 *
 * Plex keeps them as one BIF file per file — the format Roku drew up for exactly this —
 * and its own apps fetch that once and read pictures out of it as the bar moves. Asking
 * for each picture on its own, as this did, meant a round trip per step, and while the
 * scrub was moving the picture never caught up. The file is saved to the cache and read
 * a picture at a time, so a film's worth costs a few megabytes of disk and nothing held.
 *
 * BIF: an eight-byte signature, version, the count of pictures, and how many milliseconds
 * a unit of time is; then from byte 64 a table of (time, offset) pairs, one per picture
 * plus a last one whose offset is the end of the data; then the JPEGs.
 */
class PreviewIndex internal constructor(
    private val file: File,
    private val timesMs: LongArray,
    private val offsets: LongArray,
) {
    val size: Int get() = timesMs.size

    /** Which picture belongs to [positionMs]: the last one taken at or before it. */
    fun indexAt(positionMs: Long): Int {
        if (timesMs.isEmpty()) return -1
        var low = 0
        var high = timesMs.size - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (timesMs[mid] <= positionMs) low = mid else high = mid - 1
        }
        return low
    }

    /** The JPEG for picture [index], read straight from the file. */
    fun jpeg(index: Int): ByteArray? {
        if (index !in timesMs.indices) return null
        val start = offsets[index]
        val length = (offsets[index + 1] - start).toInt()
        if (length <= 0 || length > MAX_PICTURE) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                ByteArray(length).also { raf.seek(start); raf.readFully(it) }
            }
        }.getOrNull()
    }

    companion object {
        private val SIGNATURE = byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a)
        private const val TABLE_START = 64
        private const val MAX_PICTURE = 1 shl 20

        /**
         * The pictures for [url] (the BIF itself), from the cache when it has them. Null
         * when the server has none to give, or what it gave wasn't a BIF.
         */
        suspend fun load(url: String, cacheDir: File): PreviewIndex? = fetch(url, cacheDir).index

        /**
         * What a 404 means here: the file hasn't had its pictures made. Plex makes them
         * only for libraries with video preview thumbnails turned on, and only once its
         * scheduled task has got to the file, so a new episode often has none yet.
         */
        const val NOT_MADE = "Plex hasn't made them for this file yet"

        /** What asking for the pictures came to: them, or why not, said plainly. */
        data class Fetched(val index: PreviewIndex?, val problem: String?)

        /** As [load], saying why when there are none. */
        suspend fun fetch(url: String, cacheDir: File): Fetched = withContext(Dispatchers.IO) {
            var problem: String? = null
            val index = loadNow(url, cacheDir) { problem = it }
            Fetched(index, if (index == null) problem ?: "the server's file couldn't be read" else null)
        }

        /**
         * Whether the server hands out single pictures at [frameUrl], one moment's: some
         * don't give the whole file but do give these, which the bar can show one by one.
         */
        suspend fun hasFrames(frameUrl: String): Boolean = withContext(Dispatchers.IO) {
            runCatching {
                Http.client.newCall(Request.Builder().url(frameUrl).get().build()).execute().use { response ->
                    response.isSuccessful && response.body?.contentType()?.type == "image"
                }
            }.getOrDefault(false)
        }

        private fun loadNow(url: String, cacheDir: File, why: (String) -> Unit): PreviewIndex? {
            val dir = File(cacheDir, "previews").apply { mkdirs() }
            // Named for the file's address less the token, so it survives signing in again.
            val name = url.substringBefore('?').hashCode().toUInt().toString(16) + ".bif"
            val file = File(dir, name)
            if (!file.exists() || file.length() == 0L) {
                val partial = File(dir, "$name.part")
                val ok = runCatching {
                    val request = Request.Builder().url(url).get().build()
                    Http.bulk.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            why(if (response.code == 404) NOT_MADE else "the server said ${response.code}")
                            return@use false
                        }
                        val body = response.body ?: return@use false
                        partial.outputStream().use { out -> body.byteStream().copyTo(out) }
                        true
                    }
                }.getOrElse {
                    why("couldn't reach the server for them")
                    false
                }
                if (!ok || !partial.renameTo(file)) {
                    partial.delete()
                    return null
                }
                trim(dir, keep = file)
            }
            return runCatching { read(file) }.getOrNull() ?: run {
                why("the server's file wasn't one of preview pictures")
                file.delete()
                null
            }
        }

        /** Reads the table; the pictures stay in the file until asked for. */
        internal fun read(file: File): PreviewIndex? = RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(TABLE_START)
            if (raf.length() < TABLE_START) return null
            raf.readFully(header)
            if (!header.copyOfRange(0, 8).contentEquals(SIGNATURE)) return null
            val head = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val count = head.getInt(12).toLong() and 0xffffffffL
            if (count <= 0 || count > 100_000) return null
            val unit = (head.getInt(16).toLong() and 0xffffffffL).takeIf { it > 0 } ?: 1000L
            val table = ByteArray(((count + 1) * 8).toInt())
            raf.seek(TABLE_START.toLong())
            raf.readFully(table)
            val entries = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)
            val times = LongArray(count.toInt())
            val offsets = LongArray(count.toInt() + 1)
            for (i in 0..count.toInt()) {
                val time = entries.getInt(i * 8).toLong() and 0xffffffffL
                offsets[i] = entries.getInt(i * 8 + 4).toLong() and 0xffffffffL
                if (i < count) times[i] = time * unit
            }
            PreviewIndex(file, times, offsets)
        }

        /** Keeps the cache to the last few films' pictures. */
        private fun trim(dir: File, keep: File) {
            dir.listFiles { f -> f.name.endsWith(".bif") && f != keep }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(KEEP_FILES - 1)
                ?.forEach { it.delete() }
        }

        private const val KEEP_FILES = 8
    }
}
