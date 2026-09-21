package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.reely.core.Settings
import tv.reely.core.UpdateInfo
import tv.reely.xtream.XtreamApi

class UpdateCheckTest {

    private fun info(versionCode: Int?) = UpdateInfo(
        url = "https://example.invalid/reely-tv.apk",
        versionCode = versionCode,
        versionName = null,
        notes = null,
        sizeBytes = 0,
        published = null,
    )

    @Test
    fun `a published build is newer only when its number is higher`() {
        assertTrue(info(46).isNewerThan(45))
        assertFalse(info(45).isNewerThan(45))
        assertFalse(info(44).isNewerThan(45))
    }

    @Test
    fun `a build that does not say what it is is never offered`() {
        // Without a manifest there is no version to compare, and re-downloading the build
        // already installed is the whole thing this check exists to avoid.
        assertFalse(info(null).isNewerThan(1))
        assertFalse(info(null).describesItself)
        assertTrue(info(46).describesItself)
    }

    @Test
    fun `the default update address follows whatever release is newest`() {
        // Not a style preference: /releases/latest/download/ is the redirect that makes
        // the address permanent, and the manifest is found by swapping the extension, so
        // the asset has to be named exactly this.
        assertEquals(
            "https://github.com/getreely/reely-tv/releases/latest/download/reely-tv.apk",
            Settings.DEFAULT_UPDATE_URL,
        )
        assertTrue(Settings.DEFAULT_UPDATE_URL.endsWith("/reely-tv.apk"))
    }

    @Test
    fun `a panel address is usable however it was typed`() {
        assertEquals("http://panel.example.com:8080", XtreamApi.normalizeBase("panel.example.com:8080"))
        assertEquals("http://panel.example.com:8080", XtreamApi.normalizeBase("  panel.example.com:8080  "))
        assertEquals("http://panel.example.com:8080", XtreamApi.normalizeBase("http://panel.example.com:8080/"))
        assertEquals("https://panel.example.com", XtreamApi.normalizeBase("https://panel.example.com///"))
        // An upper-case scheme is left as it was typed rather than rewritten. OkHttp reads
        // a scheme case-insensitively, so there is nothing to fix and nothing to lose.
        assertEquals("HTTPS://panel.example.com", XtreamApi.normalizeBase("HTTPS://panel.example.com/"))
        assertEquals("", XtreamApi.normalizeBase("   "))
    }
}
