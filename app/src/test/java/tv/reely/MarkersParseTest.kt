package tv.reely

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.plex.PlexApi
import java.net.InetSocketAddress

/**
 * The episode request and its markers, end to end against a server that answers the way
 * a real Plex server does — including the shape of the Marker entries, which carry an
 * Attributes object of their own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MarkersParseTest {
    private lateinit var server: HttpServer
    private var requested: String? = null

    private val body = """
        {"MediaContainer":{"size":1,"allowSync":true,"identifier":"com.plexapp.plugins.library",
        "Metadata":[{"ratingKey":"2119","key":"/library/metadata/2119","type":"episode",
        "title":"Pilot","grandparentTitle":"The King of Queens","index":1,"parentIndex":1,"duration":1320000,
        "Media":[{"id":501,"duration":1320000,"audioCodec":"aac","audioChannels":2,"videoCodec":"h264",
          "Part":[{"id":601,"key":"/library/parts/601/1600000000/file.mkv","duration":1320000,
            "Stream":[{"id":1,"streamType":1,"codec":"h264"},{"id":2,"streamType":2,"codec":"aac","channels":2,"selected":true}]}]}],
        "Chapter":[{"id":9,"filter":"","index":1,"startTimeOffset":0,"endTimeOffset":300000}],
        "Marker":[
          {"id":2370,"type":"intro","startTimeOffset":63014,"endTimeOffset":94302,"final":false,"Attributes":{"id":2370,"version":5}},
          {"id":2371,"type":"credits","startTimeOffset":1290000,"endTimeOffset":1320000,"final":true,"Attributes":{"id":2371,"version":4}}
        ]}]}}
    """.trimIndent()

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requested = exchange.requestURI.toString()
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After fun stop() = server.stop(0)

    @Test fun `markers in a real-shaped reply reach the playback`() = runBlocking {
        val base = "http://127.0.0.1:${server.address.port}"
        val playback = PlexApi.playback(base, "token", "2119")!!
        assertEquals("/library/metadata/2119?includeMarkers=1&includeChapters=1", requested)
        assertEquals(listOf("intro", "credits"), playback.markers.map { it.type })
        assertEquals(63014L, playback.markers.first().startMs)
        assertEquals(94302L, playback.markers.first().endMs)
    }
}
