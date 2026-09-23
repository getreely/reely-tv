package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.reely.core.SilentAudio
import tv.reely.core.silentAudio
import tv.reely.plex.PlexApi
import java.net.URI
import java.net.URLDecoder

/**
 * Dolby Digital Plus on a device that cannot play it. The player selects no audio track,
 * raises no error, and plays the picture in silence — so this is decided from the tracks.
 */
class SilentAudioTest {

    private fun decide(
        hasAudio: Boolean = true,
        audioSelected: Boolean = false,
        isLive: Boolean = false,
        fromPlex: Boolean = true,
        alreadyTranscoding: Boolean = false,
        directOnly: Boolean = false,
    ) = silentAudio(hasAudio, audioSelected, isLive, fromPlex, alreadyTranscoding, directOnly)

    @Test
    fun `sound that plays needs nothing`() {
        assertEquals(SilentAudio.FINE, decide(audioSelected = true))
    }

    @Test
    fun `a file with no sound at all is not a fault`() {
        assertEquals(SilentAudio.FINE, decide(hasAudio = false))
    }

    /** The reported case: the server converts the audio, as Plex's own apps do. */
    @Test
    fun `unplayable sound from Plex is converted on the server`() {
        assertEquals(SilentAudio.CONVERT_ON_SERVER, decide())
    }

    /** A live channel comes from the provider; there is no server to ask. */
    @Test
    fun `live television can only be explained`() {
        assertEquals(SilentAudio.EXPLAIN, decide(isLive = true))
    }

    /** Asking again after the server has had its go would loop. */
    @Test
    fun `a stream already being converted is not converted again`() {
        assertEquals(SilentAudio.EXPLAIN, decide(alreadyTranscoding = true))
    }

    @Test
    fun `direct-only means do not ask the server`() {
        assertEquals(SilentAudio.EXPLAIN, decide(directOnly = true))
    }

    @Test
    fun `something not from Plex has nobody to convert it`() {
        assertEquals(SilentAudio.EXPLAIN, decide(fromPlex = false))
    }

    // ------------------------------------------------------------- The request itself

    private val url = PlexApi.audioConvertUrl(
        base = "http://192.168.1.2:32400",
        token = "tok",
        clientId = "client",
        ratingKey = "4242",
        sessionId = "sess",
    )

    private val params: Map<String, String> = URI(url).rawQuery.split('&').associate { pair ->
        val (key, value) = pair.split('=', limit = 2)
        key to URLDecoder.decode(value, "UTF-8")
    }

    @Test
    fun `only the audio is converted and the picture is passed through`() {
        assertEquals("0", params["directStreamAudio"])
        assertEquals("1", params["directStream"])
        assertEquals("0", params["directPlay"])
    }

    /** `burn` burns whatever subtitle the server has selected, and a burn re-encodes video. */
    @Test
    fun `no subtitle is burned in`() {
        assertEquals("none", params["subtitles"])
    }

    /**
     * No offset: the timeline stays in the file's own time, so the player resumes by
     * seeking and subtitles loaded alongside still line up.
     */
    @Test
    fun `the transcode starts at the beginning of the file`() {
        assertFalse(params.containsKey("offset"))
        assertEquals("/library/metadata/4242", params["path"])
    }

    /** The built-in Android profile allows E-AC3, so it could convert E-AC3 to E-AC3. */
    @Test
    fun `the generic profile is used so the target below decides`() {
        assertEquals("Generic", params["X-Plex-Client-Profile-Name"])
        assertEquals("Generic", params["X-Plex-Platform"])
    }

    /**
     * The server decodes the parameter once, then parses the clause as a query of its own
     * — so after one decode the commas inside the clause must still be encoded, or they
     * would split the codec lists.
     */
    @Test
    fun `the profile offers AAC alone, with the clause's commas still encoded`() {
        val extra = params.getValue("X-Plex-Client-Profile-Extra")
        assertTrue(extra, extra.contains("audioCodec=aac)"))
        assertTrue(extra, extra.contains("videoCodec=h264%2Chevc"))
        assertFalse("AC3 or E-AC3 on offer could be chosen again", extra.contains("ac3"))
        assertTrue(extra, extra.startsWith("add-settings(DirectPlayStreamSelection=true)+"))
    }
}
