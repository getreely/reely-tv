package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import tv.reely.plex.PlexApi
import java.net.URI

class TranscodeUrlTest {

    private fun paramsOf(url: String): Map<String, String> =
        URI(url).rawQuery.split('&').associate { pair ->
            val (key, value) = pair.split('=', limit = 2)
            key to value
        }

    /**
     * With an offset the player's clock restarts at nought part-way into the file, and
     * that clock is what gets reported to Plex — so resuming at twenty minutes wrote a
     * resume point near the start. Both kinds of transcode must keep the file's own time.
     */
    @Test
    fun `a full transcode keeps the file's own timeline`() {
        val url = PlexApi.transcodeUrl(
            base = "http://s:32400",
            token = "t",
            clientId = "c",
            ratingKey = "1",
            sessionId = "s",
            maxBitrateKbps = 0,
            resolution = "1920x1080",
        )
        assertFalse(paramsOf(url).containsKey("offset"))
        assertEquals("1", paramsOf(url)["fastSeek"])
    }

    @Test
    fun `an audio conversion keeps the file's own timeline`() {
        val url = PlexApi.audioConvertUrl(
            base = "http://s:32400",
            token = "t",
            clientId = "c",
            ratingKey = "1",
            sessionId = "s",
        )
        assertFalse(paramsOf(url).containsKey("offset"))
    }
}
