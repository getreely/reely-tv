package tv.reely

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.reely.core.PreviewIndex
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reading pictures out of a BIF, the file Plex keeps a film's scrubbing previews in. */
class PreviewIndexTest {
    private val pictures = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5), byteArrayOf(6, 7, 8, 9))

    /** A BIF of [pictures] taken every two seconds, times in units of [unit] ms. */
    private fun bif(unit: Int = 1000): File {
        val count = pictures.size
        val tableEnd = 64 + (count + 1) * 8
        val out = ByteBuffer.allocate(tableEnd + pictures.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
        out.put(byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a))
        out.putInt(0).putInt(count).putInt(unit)
        out.position(64)
        var offset = tableEnd
        pictures.forEachIndexed { i, p ->
            out.putInt(i * 2000 / unit).putInt(offset)
            offset += p.size
        }
        out.putInt(-1).putInt(offset)
        pictures.forEach { out.put(it) }
        return File.createTempFile("previews", ".bif").apply { writeBytes(out.array()); deleteOnExit() }
    }

    @Test fun `each moment finds the picture taken at or before it`() {
        val index = PreviewIndex.read(bif())!!
        assertEquals(3, index.size)
        assertEquals(0, index.indexAt(0))
        assertEquals(0, index.indexAt(1_999))
        assertEquals(1, index.indexAt(2_000))
        assertEquals(2, index.indexAt(90_000))
    }

    @Test fun `the picture is the bytes between its offset and the next`() {
        val index = PreviewIndex.read(bif())!!
        pictures.forEachIndexed { i, p -> assertArrayEquals(p, index.jpeg(i)) }
        assertNull(index.jpeg(3))
    }

    @Test fun `a time unit of nought means seconds`() {
        assertEquals(1, PreviewIndex.read(bif(unit = 1000))!!.indexAt(2_500))
    }

    @Test fun `anything that isn't a BIF is refused`() {
        val junk = File.createTempFile("junk", ".bif").apply { writeBytes(ByteArray(200) { 7 }); deleteOnExit() }
        assertNull(PreviewIndex.read(junk))
    }
}
