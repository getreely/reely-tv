package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.reely.core.AudioPlan
import tv.reely.core.audioPlan
import tv.reely.core.conversionTargets
import tv.reely.plex.PlexApi
import java.net.URI
import java.net.URLDecoder

/**
 * Deciding before playback what to do with the sound, from what the file says it is and
 * what the device says it plays. The device is faked here as the set of codecs it plays.
 */
class AudioPlanTest {

    private fun device(vararg plays: String): (String, Int) -> Boolean =
        { codec, _ -> codec in plays || codec == "aac" }

    /** The reported case: Dolby Digital Plus 5.1 on a stick and TV that play no Dolby. */
    @Test
    fun `eac3 5_1 with no Dolby at all converts to AAC`() {
        assertEquals(AudioPlan.Convert(listOf("aac")), audioPlan("eac3", 6, device()))
    }

    /** Keep the surround: a TV that takes plain Dolby Digital gets 5.1 AC3, not stereo AAC. */
    @Test
    fun `eac3 5_1 on a device that plays AC3 converts to AC3 first`() {
        assertEquals(AudioPlan.Convert(listOf("ac3", "aac")), audioPlan("eac3", 6, device("ac3")))
    }

    @Test
    fun `sound the device plays is left alone`() {
        assertEquals(AudioPlan.PlayAsIs, audioPlan("eac3", 6, device("eac3")))
        assertEquals(AudioPlan.PlayAsIs, audioPlan("aac", 2, device()))
    }

    /** TrueHD down to the best Dolby the device takes, with AAC still under it. */
    @Test
    fun `truehd prefers Dolby Digital Plus, then Dolby Digital`() {
        assertEquals(
            AudioPlan.Convert(listOf("eac3", "ac3", "aac")),
            audioPlan("truehd", 8, device("eac3", "ac3")),
        )
    }

    /** No extra channels to keep, so nothing to gain from Dolby. */
    @Test
    fun `stereo goes straight to AAC even when Dolby would play`() {
        assertEquals(AudioPlan.Convert(listOf("aac")), audioPlan("dca", 2, device("ac3", "eac3")))
    }

    /** The file did not say. The check during playback is still there. */
    @Test
    fun `an unknown source plays as it is`() {
        assertEquals(AudioPlan.PlayAsIs, audioPlan(null, 0, device()))
        assertEquals(AudioPlan.PlayAsIs, audioPlan("  ", 0, device()))
    }

    @Test
    fun `plex's codec names are matched whatever their case`() {
        assertEquals(AudioPlan.PlayAsIs, audioPlan("EAC3", 6, device("eac3")))
    }

    /** The safety the whole thing rests on. */
    @Test
    fun `the targets always end in AAC and never offer what the device refuses`() {
        for (channels in listOf(1, 2, 6, 8)) {
            val targets = conversionTargets(channels, device("ac3"))
            assertEquals("aac", targets.last())
            assertFalse(targets.contains("eac3"))
        }
    }

    /** Neither Dolby format carries more than 5.1, so 7.1 is asked about as 5.1. */
    @Test
    fun `surround is asked about at no more than six channels`() {
        val asked = mutableListOf<Int>()
        conversionTargets(8) { _, channels -> asked += channels; true }
        assertTrue(asked.all { it == 6 })
    }

    // ------------------------------------------------------------- The request

    /** The list reaches the server in order, still comma-encoded inside the clause. */
    @Test
    fun `the conversion request carries the targets in order`() {
        val url = PlexApi.audioConvertUrl(
            base = "http://s:32400",
            token = "t",
            clientId = "c",
            ratingKey = "1",
            sessionId = "s",
            audioCodecs = listOf("ac3", "aac"),
        )
        val extra = URI(url).rawQuery.split('&')
            .first { it.startsWith("X-Plex-Client-Profile-Extra=") }
            .substringAfter('=')
            .let { URLDecoder.decode(it, "UTF-8") }
        assertTrue(extra, extra.endsWith("&audioCodec=ac3%2Caac)"))
    }
}
