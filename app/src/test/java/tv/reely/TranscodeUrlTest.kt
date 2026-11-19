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
    fun `a full transcode burns in subtitles unless they're to start off`() {
        fun url(subtitles: Boolean) = PlexApi.transcodeUrl(
            base = "http://s:32400", token = "t", clientId = "c", ratingKey = "1", sessionId = "s",
            maxBitrateKbps = 0, resolution = "1920x1080", subtitles = subtitles,
        )
        assertEquals("burn", paramsOf(url(true))["subtitles"])
        assertEquals("none", paramsOf(url(false))["subtitles"])
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

    /**
     * A picture the device has just failed to play must be made again, not copied: copied
     * into the stream it is the same picture, and an old MPEG-4 file came back green.
     */
    @Test
    fun `a transcode after a failed picture has the server make it again`() {
        fun url(copyPicture: Boolean) = PlexApi.transcodeUrl(
            base = "http://s:32400", token = "t", clientId = "c", ratingKey = "1", sessionId = "s",
            maxBitrateKbps = 0, resolution = "1920x1080", copyPicture = copyPicture,
        )
        assertEquals("1", paramsOf(url(true))["directStream"])
        assertEquals("0", paramsOf(url(false))["directStream"])
        assertFalse(PlexApi.copiesIntoStream("mpeg4"))
        assertFalse(PlexApi.copiesIntoStream("mpeg2video"))
        assertFalse(PlexApi.copiesIntoStream(null))
        assertEquals(true, PlexApi.copiesIntoStream("h264"))
        assertEquals(true, PlexApi.copiesIntoStream("HEVC"))
    }

    /** The name the timeline reports under, so the server knows them for one sitting. */
    @Test
    fun `a linked conversion names its session, and a trailer's doesn't`() {
        val linked = PlexApi.transcodeUrl(
            base = "http://s:32400", token = "t", clientId = "c", ratingKey = "1", sessionId = "abc",
            maxBitrateKbps = 0, resolution = "1920x1080", linked = true,
        )
        assertEquals("abc", paramsOf(linked)["X-Plex-Session-Identifier"])
        assertEquals("abc", paramsOf(linked)["session"])
        val sound = PlexApi.audioConvertUrl(base = "http://s:32400", token = "t", clientId = "c", ratingKey = "1", sessionId = "abc", linked = true)
        assertEquals("abc", paramsOf(sound)["X-Plex-Session-Identifier"])
        val trailer = PlexApi.transcodeUrl(
            base = "http://s:32400", token = "t", clientId = "c", ratingKey = "1", sessionId = "abc",
            maxBitrateKbps = 0, resolution = "1920x1080",
        )
        assertFalse(paramsOf(trailer).containsKey("X-Plex-Session-Identifier"))
    }
}
