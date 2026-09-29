package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.reely.plex.PlexApi
import tv.reely.plex.PlexItem

/** Which episode "Play next episode" on a show starts. */
class NextEpisodeTest {
    private fun ep(key: String, watched: Boolean = false, offset: Long = 0) = PlexItem(
        ratingKey = key, title = key, type = "episode", thumb = null, art = null, summary = null,
        year = null, index = null, parentIndex = null, parentRatingKey = null, parentTitle = null,
        grandparentRatingKey = "show", grandparentTitle = "Show", grandparentThumb = null,
        durationMs = 1_000_000, viewOffsetMs = offset, leafCount = 0, viewedLeafCount = 0,
        viewCount = if (watched) 1 else 0, addedAt = 0, librarySectionId = null,
    )

    @Test fun `the one part watched comes first`() {
        val list = listOf(ep("1", watched = true), ep("2"), ep("3", offset = 500_000))
        assertEquals("3", PlexApi.nextEpisode(list)?.ratingKey)
    }

    @Test fun `otherwise the one after the last watched`() {
        val list = listOf(ep("1", watched = true), ep("2"), ep("3", watched = true), ep("4"))
        assertEquals("4", PlexApi.nextEpisode(list)?.ratingKey)
    }

    @Test fun `nothing watched starts at the beginning`() {
        assertEquals("1", PlexApi.nextEpisode(listOf(ep("1"), ep("2")))?.ratingKey)
    }

    @Test fun `all watched, or a gap left behind`() {
        // The last one watched was the finale, but one was skipped: that one.
        assertEquals("2", PlexApi.nextEpisode(listOf(ep("1", true), ep("2"), ep("3", true)))?.ratingKey)
        // Every one watched: from the start.
        assertEquals("1", PlexApi.nextEpisode(listOf(ep("1", true), ep("2", true)))?.ratingKey)
        assertEquals(null, PlexApi.nextEpisode(emptyList()))
    }
}
