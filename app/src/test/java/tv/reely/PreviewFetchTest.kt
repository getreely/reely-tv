package tv.reely

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.core.PreviewIndex
import java.io.File
import java.net.InetSocketAddress

/** A server without the preview file says why, and one with single pictures is found out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreviewFetchTest {
    private lateinit var server: HttpServer
    private val cache = kotlin.io.path.createTempDirectory("previews").toFile()

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/library/parts/5/indexes/sd") { ex ->
            if (ex.requestURI.path.endsWith("/sd")) {
                ex.sendResponseHeaders(404, -1)
                ex.close()
            } else {
                val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
                ex.responseHeaders.add("Content-Type", "image/jpeg")
                ex.sendResponseHeaders(200, jpeg.size.toLong())
                ex.responseBody.use { it.write(jpeg) }
            }
        }
        server.createContext("/library/parts/6/indexes/sd") { ex ->
            ex.sendResponseHeaders(404, -1)
            ex.close()
        }
        server.start()
    }

    @After fun stop() {
        server.stop(0)
        cache.deleteRecursively()
    }

    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Test fun `no file says what the server said`() = runBlocking {
        val fetched = PreviewIndex.fetch("$base/library/parts/5/indexes/sd?X-Plex-Token=t", File(cache, "a"))
        assertNull(fetched.index)
        assertEquals("the server said 404", fetched.problem)
    }

    @Test fun `single pictures are found when the file isn't there`() = runBlocking {
        assertTrue(PreviewIndex.hasFrames("$base/library/parts/5/indexes/sd/60000?X-Plex-Token=t"))
        assertFalse(PreviewIndex.hasFrames("$base/library/parts/6/indexes/sd/60000?X-Plex-Token=t"))
    }
}
