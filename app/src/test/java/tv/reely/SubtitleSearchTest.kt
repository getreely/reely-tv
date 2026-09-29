package tv.reely

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.plex.PlexApi
import java.net.InetSocketAddress
import java.net.URLDecoder

/** Subtitles found online by the server, and one of them added to the file. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SubtitleSearchTest {
    private lateinit var server: HttpServer
    private var added: Map<String, String>? = null

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/library/metadata/77/subtitles") { ex ->
            val query = ex.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }
                .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val body = if (ex.requestMethod == "PUT") {
                added = query
                ""
            } else {
                assertEquals("en", query["language"])
                """{"MediaContainer":{"Stream":[
                  {"key":"/sub/1","codec":"srt","languageCode":"eng","title":"Low.Orbit.2024.1080p.srt","providerTitle":"OpenSubtitles","hearingImpaired":false},
                  {"key":"/sub/2","codec":"srt","languageCode":"eng","displayTitle":"English (SDH)","providerTitle":"Podnapisi","hearingImpaired":true},
                  {"codec":"srt","title":"no key, can't be had"}]}}"""
            }
            val bytes = body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After fun stop() = server.stop(0)

    private val base get() = "http://127.0.0.1:${server.address.port}"

    @Test fun `what the server finds, less what it can't fetch`() = runBlocking {
        val found = PlexApi.searchSubtitles(base, "token", "77", "en")
        assertEquals(listOf("Low.Orbit.2024.1080p.srt", "English (SDH)"), found.map { it.title })
        assertEquals("OpenSubtitles", found[0].provider)
        assertTrue(found[1].hearingImpaired)
    }

    @Test fun `adding one asks the server for it by its key`() = runBlocking {
        val pick = PlexApi.searchSubtitles(base, "token", "77", "en")[1]
        assertTrue(PlexApi.addSubtitle(base, "token", "77", pick, "en"))
        val sent = added!!
        assertEquals("/sub/2", sent["key"])
        assertEquals("eng", sent["language"])
        assertEquals("1", sent["hearingImpaired"])
        assertEquals("Podnapisi", sent["providerTitle"])
    }
}
