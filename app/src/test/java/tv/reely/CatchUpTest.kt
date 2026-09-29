package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.xtream.EpgProgramme
import tv.reely.xtream.XtreamApi
import tv.reely.xtream.XtreamChannel
import tv.reely.xtream.XtreamCredentials
import tv.reely.xtream.catchUpProgramme

/** Watching again what a channel has already shown, from its archive. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CatchUpTest {
    private val panel = XtreamCredentials("http://panel.example:8080", "me", "p@ss")
    private val archived = XtreamChannel(streamId = 42, number = 5, name = "News", icon = null, epgChannelId = "news", archiveDays = 3)
    private val live = archived.copy(archiveDays = 0)

    // 2024-03-10 20:00 UTC to 21:30 UTC.
    private val start = 1_710_100_800L
    private val stop = start + 90 * 60

    @Test fun `the address is the panel's timeshift one, in the panel's own time`() {
        assertEquals(
            "http://panel.example:8080/timeshift/me/p%40ss/90/2024-03-10:20-00/42.ts",
            XtreamApi.catchUpUrl(panel, archived, start, stop, "UTC"),
        )
        // The same moment, written as a panel in New York writes it.
        assertEquals(
            "http://panel.example:8080/timeshift/me/p%40ss/90/2024-03-10:16-00/42.ts",
            XtreamApi.catchUpUrl(panel, archived, start, stop, "America/New_York"),
        )
    }

    @Test fun `a part minute counts as a whole one`() {
        val url = XtreamApi.catchUpUrl(panel, archived, start, start + 61, "UTC")!!
        assertEquals("2", url.split('/')[6])
    }

    @Test fun `no archive, or no panel, no address`() {
        assertNull(XtreamApi.catchUpUrl(panel, live, start, stop, "UTC"))
        assertNull(XtreamApi.catchUpUrl(XtreamCredentials.playlist("http://x/list.m3u", null), archived, start, stop, "UTC"))
    }

    @Test fun `only what's over, and still in the archive, is watched again`() {
        val now = stop + 3_600
        val earlier = EpgProgramme("news", start, stop, "Evening News", null)
        val onNow = EpgProgramme("news", now - 600, now + 600, "Late News", null)
        val tooOld = EpgProgramme("news", now - 5 * 86_400, now - 5 * 86_400 + 1_800, "Old", null)
        val listing = listOf(tooOld, earlier, onNow)
        assertEquals(earlier, catchUpProgramme(archived, listing, start + 60, now))
        assertNull("on now plays live", catchUpProgramme(archived, listing, now, now))
        assertNull("older than the archive", catchUpProgramme(archived, listing, tooOld.start + 60, now))
        assertNull("a channel without one", catchUpProgramme(live, listing, start + 60, now))
    }
}
