package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.reely.plex.PlexItem
import tv.reely.ui.DetailState
import tv.reely.ui.HomeState
import tv.reely.ui.ReelyState
import tv.reely.ui.withProgress

/** Back from the player, Resume picks up where it was stopped, not where it started. */
class ResumePointTest {

    private fun episode(key: String, offset: Long = 0) = PlexItem(
        ratingKey = key, title = key, type = "episode", thumb = null, art = null, summary = null,
        year = null, index = null, parentIndex = null, parentRatingKey = null, parentTitle = null,
        grandparentRatingKey = "show", grandparentTitle = null, grandparentThumb = null,
        durationMs = 1_000_000, viewOffsetMs = offset, leafCount = 0, viewedLeafCount = 0,
        viewCount = 0, addedAt = 0, lastViewedAt = 0, librarySectionId = null, serverBase = null,
    )

    private val before = ReelyState(
        detail = DetailState(
            ratingKey = "show",
            episodes = listOf(episode("e1"), episode("e2", offset = 100_000), episode("e3")),
            focusedEpisode = episode("e2", offset = 100_000),
        ),
        home = HomeState(continueWatching = listOf(episode("e2", offset = 100_000))),
    )

    @Test fun `the show's page and Continue Watching have where it stopped`() {
        val after = before.withProgress("e2", positionMs = 412_345, durationMs = 1_000_000)
        assertEquals(412_345L, after.detail!!.episodes[1].viewOffsetMs)
        assertEquals(412_345L, after.detail!!.focusedEpisode!!.viewOffsetMs)
        assertEquals(412_345L, after.home.continueWatching[0].viewOffsetMs)
        // The others are left as they were.
        assertEquals(0L, after.detail!!.episodes[0].viewOffsetMs)
    }

    @Test fun `stopped in the credits, it's watched and starts from the top`() {
        val after = before.withProgress("e2", positionMs = 950_000, durationMs = 1_000_000)
        val e2 = after.detail!!.episodes[1]
        assertEquals(0L, e2.viewOffsetMs)
        assertEquals(1, e2.viewCount)
    }
}
