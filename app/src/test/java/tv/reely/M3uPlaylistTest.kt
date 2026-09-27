package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.ui.panelLoginIn
import tv.reely.xtream.M3uPlaylist
import tv.reely.xtream.XtreamApi
import tv.reely.xtream.XtreamCredentials
import tv.reely.xtream.StreamFormat

/** Playlists as providers really write them. Uri is Android's, hence Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class M3uPlaylistTest {
    // Starts with a byte-order mark, as some do.
    private val playlist = "\uFEFF" + """
        #EXTM3U url-tvg="http://guide.example.tv/epg.xml.gz,http://backup.example.tv/epg.xml"
        #EXTINF:-1 tvg-id="BBCOne.uk" tvg-name="UK: BBC One" tvg-logo="http://logos.example.tv/bbc1.png" group-title="UK | Entertainment",UK: BBC One HD
        http://line.example.tv/live/u/p/101.ts
        #EXTINF:-1 tvg-id="" tvg-logo="" group-title="News, Weather",Sky News
        #EXTVLCOPT:http-user-agent=Something
        http://line.example.tv/live/u/p/102.ts

        #EXTINF:-1 tvg-chno="7",Channel Seven
        #EXTGRP:Local
        https://cdn.example.tv/seven/index.m3u8
        #EXTINF:-1 tvg-name="The Film" group-title="Movies",The Film (2024)
        http://line.example.tv/movie/u/p/5001.mkv
        #EXTINF:-1 group-title="Series",Some Show S01 E01
        http://line.example.tv/series/u/p/7001.mp4
        #EXTINF:-1,
        http://line.example.tv/live/u/p/103.ts
    """.trimIndent()

    private val parsed = M3uPlaylist.parse(playlist.lineSequence())

    @Test fun `keeps the live channels and passes over films and series`() {
        assertEquals(
            listOf("UK: BBC One HD", "Sky News", "Channel Seven", "Channel 4"),
            parsed.channels.map { it.name },
        )
    }

    @Test fun `reads the guide id, logo, number and group`() {
        val bbc = parsed.channels[0]
        assertEquals("bbcone.uk", bbc.epgChannelId)
        assertEquals("http://logos.example.tv/bbc1.png", bbc.icon)
        assertEquals("UK | Entertainment", bbc.group)
        assertEquals(1, bbc.number)

        val news = parsed.channels[1]
        assertNull(news.epgChannelId)
        assertNull(news.icon)
        assertEquals("News, Weather", news.group)

        val seven = parsed.channels[2]
        assertEquals(7, seven.number)
        assertEquals("Local", seven.group)
        assertEquals("https://cdn.example.tv/seven/index.m3u8", seven.url)
    }

    @Test fun `groups come in the playlist's order, with the ungrouped under Other`() {
        assertEquals(listOf("UK | Entertainment", "News, Weather", "Local", "Other"), parsed.groups)
    }

    @Test fun `takes the first guide the header names`() {
        assertEquals("http://guide.example.tv/epg.xml.gz", parsed.guideUrl)
    }

    @Test fun `a channel keeps its id from one download to the next`() {
        val again = M3uPlaylist.parse(playlist.lineSequence())
        assertEquals(parsed.channels.map { it.streamId }, again.channels.map { it.streamId })
        assertEquals(parsed.channels.size, parsed.channels.map { it.streamId }.toSet().size)
    }

    @Test fun `a playlist channel plays from its own address`() {
        val credentials = XtreamCredentials.playlist("http://example.tv/list.m3u", guideUrl = null)
        val seven = parsed.channels[2]
        assertEquals(seven.url, XtreamApi.streamUrl(credentials, seven, StreamFormat.TS))
    }

    @Test fun `a guide entered with the playlist is the one used`() {
        val credentials = XtreamCredentials.playlist("http://example.tv/list.m3u", "http://example.tv/guide.xml")
        assertEquals("http://example.tv/guide.xml", XtreamApi.xmltvUrl(credentials))
    }

    @Test fun `a panel's playlist address signs in to the panel`() {
        val login = panelLoginIn("http://line.example.tv:8080/get.php?username=me&password=secret&type=m3u_plus&output=ts")
        assertEquals(XtreamCredentials("http://line.example.tv:8080", "me", "secret"), login)
        assertNull(panelLoginIn("http://example.tv/list.m3u"))
        assertNull(panelLoginIn("http://line.example.tv/get.php?username=me"))
        assertNotEquals(null, panelLoginIn("https://x.tv/sub/get.php?username=a&password=b"))
    }
}
