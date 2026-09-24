package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.reely.core.qualityBadges

class QualityTest {

    @Test
    fun `a 4K Dolby Vision file with 5_1 sound`() {
        assertEquals(listOf("4K", "Dolby Vision", "5.1"), qualityBadges("4k", 6, dolbyVision = true, transfer = "smpte2084"))
    }

    /** Dolby Vision files usually carry HDR10 too; the better of the two is the one shown. */
    @Test
    fun `dolby vision wins over the hdr10 it carries`() {
        assertEquals("Dolby Vision", qualityBadges("4k", 0, dolbyVision = true, transfer = "smpte2084")[1])
    }

    @Test
    fun `transfer functions name the HDR format`() {
        assertEquals(listOf("4K", "HDR10"), qualityBadges("4k", 0, transfer = "smpte2084"))
        assertEquals(listOf("4K", "HLG"), qualityBadges("4k", 0, transfer = "arib-std-b67"))
        assertEquals(listOf("HD"), qualityBadges("1080", 0, transfer = "bt709"))
    }

    /** Viewers think in 4K, HD and SD, not Plex's own labels. */
    @Test
    fun `resolutions read the way a viewer says them`() {
        assertEquals(listOf("HD", "Stereo"), qualityBadges("720", 2))
        assertEquals(listOf("SD", "Mono"), qualityBadges("sd", 1))
        assertEquals(listOf("4K", "7.1"), qualityBadges("2160", 8))
    }

    /** A list entry often carries no stream details; it says nothing rather than guess. */
    @Test
    fun `nothing known, nothing shown`() {
        assertEquals(emptyList<String>(), qualityBadges(null, 0))
        assertEquals(emptyList<String>(), qualityBadges("weird", 3))
    }
}
