package tv.reely

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.plex.PlexApi
import tv.reely.plex.PlexChapter
import tv.reely.ui.screens.currentChapter

/** Chapters as Plex lists them, and which one is playing. org.json is Android's, hence Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChaptersTest {
    @Test fun `reads a file's chapters in order`() {
        val metadata = JSONObject(
            """
            {"Chapter":[
              {"tag":"The Pass","index":2,"startTimeOffset":360000,"endTimeOffset":720000,"thumb":"/library/media/9/chapterImages/2"},
              {"index":1,"startTimeOffset":0,"endTimeOffset":360000}
            ]}
            """.trimIndent()
        )
        assertEquals(
            listOf(
                PlexChapter("Chapter 1", 0, 360_000, null),
                PlexChapter("The Pass", 360_000, 720_000, "http://s/library/media/9/chapterImages/2?X-Plex-Token=t"),
            ),
            PlexApi.chaptersOf(metadata, "http://s", "t"),
        )
    }

    @Test fun `one chapter for the whole film is no chapters`() {
        val metadata = JSONObject("""{"Chapter":{"tag":"Chapter 1","startTimeOffset":0,"endTimeOffset":6000000}}""")
        assertTrue(PlexApi.chaptersOf(metadata, "http://s", "t").isEmpty())
        assertTrue(PlexApi.chaptersOf(JSONObject("{}"), "http://s", "t").isEmpty())
    }

    @Test fun `the one playing is the last to have started`() {
        val chapters = listOf(0L, 60_000L, 120_000L).map { PlexChapter("c", it, it + 60_000) }
        assertEquals(0, currentChapter(chapters, 0))
        assertEquals(1, currentChapter(chapters, 90_000))
        assertEquals(2, currentChapter(chapters, 500_000))
    }
}
