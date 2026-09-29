package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.core.HomeCache
import tv.reely.plex.PlexItem

/** Home from last time, back the moment the app starts, and only for the same person. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeCacheTest {
    @get:Rule val folder = TemporaryFolder()

    private fun item(key: String, type: String = "movie") = PlexItem(
        ratingKey = key, title = "Title $key", type = type, thumb = "/t/$key", art = null, summary = "About $key",
        year = 2024, index = if (type == "episode") 3 else null, parentIndex = if (type == "episode") 1 else null,
        parentRatingKey = null, parentTitle = null,
        grandparentRatingKey = if (type == "episode") "show$key" else null,
        grandparentTitle = if (type == "episode") "Show $key" else null, grandparentThumb = null,
        durationMs = 3_600_000, viewOffsetMs = 1_200_000, leafCount = 0, viewedLeafCount = 0, viewCount = 0,
        addedAt = 1_700_000_000, lastViewedAt = 1_700_000_100, logo = "/logo/$key",
        qualities = listOf("4K", "5.1"), librarySectionId = "1", serverBase = "http://plex:32400",
    )

    @Test fun `the rows come back as they were kept`() {
        val rows = HomeCache.Rows(
            continueWatching = listOf(item("1", "episode"), item("2")),
            recentMovies = listOf(item("3")),
            recentEpisodes = listOf(item("4", "episode") to 3),
        )
        HomeCache.save(folder.root, "token|http://plex:32400", rows)
        assertEquals(rows, HomeCache.load(folder.root, "token|http://plex:32400"))
    }

    @Test fun `somebody else's rows are never shown`() {
        HomeCache.save(folder.root, "one|http://plex:32400", HomeCache.Rows(listOf(item("1")), emptyList(), emptyList()))
        assertNull(HomeCache.load(folder.root, "two|http://plex:32400"))
        assertNull(HomeCache.load(folder.root, "one|http://other:32400"))
        HomeCache.clear(folder.root)
        assertNull(HomeCache.load(folder.root, "one|http://plex:32400"))
    }
}
