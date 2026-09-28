package tv.reely

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.core.versionDetail
import tv.reely.core.versionLabel
import tv.reely.plex.PlexApi
import tv.reely.plex.PlexVersion

/** A title in more than one file, as Plex describes each. org.json is Android's, hence Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VersionsTest {
    private val film = JSONObject(
        """
        {"ratingKey":"7","type":"movie","title":"Low Orbit","Media":[
          {"videoResolution":"4k","videoCodec":"hevc","audioCodec":"truehd","audioChannels":8,"bitrate":58400,
           "Part":[{"size":62400000000,"Stream":[{"streamType":1,"DOVIPresent":true}]}]},
          {"videoResolution":"1080","videoCodec":"h264","audioCodec":"ac3","audioChannels":6,"bitrate":9800,
           "Part":[{"size":9800000000,"Stream":[{"streamType":1}]}]}
        ]}
        """.trimIndent()
    )

    @Test fun `each file is named by what tells it apart`() {
        assertEquals(
            listOf(
                PlexVersion("4K Dolby Vision", "HEVC · TrueHD 7.1 · 58 Mbps · 62.4 GB"),
                PlexVersion("1080p", "H.264 · Dolby Digital 5.1 · 10 Mbps · 9.8 GB"),
            ),
            PlexApi.versionsOf(film),
        )
    }

    @Test fun `one file is no choice at all`() {
        val single = JSONObject(film.toString()).apply {
            put("Media", org.json.JSONArray().put(getJSONArray("Media").get(0)))
        }
        assertTrue(PlexApi.versionsOf(single).isEmpty())
    }

    @Test fun `labels`() {
        assertEquals("720p", versionLabel("720"))
        assertEquals("SD", versionLabel("sd"))
        assertEquals("4K HDR10", versionLabel("4k", transfer = "smpte2084"))
        assertEquals("Other", versionLabel(null))
        assertEquals(null, versionDetail(null, null, 0, 0, 0))
        assertEquals("AAC Stereo · 800 kbps · 450 MB", versionDetail("", "aac", 2, 800, 450_000_000))
    }
}
