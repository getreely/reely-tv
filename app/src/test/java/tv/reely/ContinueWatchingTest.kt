package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.reely.core.continueWatchingOrder
import tv.reely.plex.PlexItem

class ContinueWatchingTest {

    private fun item(
        key: String,
        type: String = "episode",
        show: String? = null,
        addedAt: Long = 0,
        lastViewedAt: Long = 0,
        viewOffsetMs: Long = 0,
        server: String = "http://a",
    ) = PlexItem(
        ratingKey = key,
        title = key,
        type = type,
        thumb = null,
        art = null,
        summary = null,
        year = null,
        index = null,
        parentIndex = null,
        parentRatingKey = null,
        parentTitle = null,
        grandparentRatingKey = show,
        grandparentTitle = null,
        grandparentThumb = null,
        durationMs = 1_000_000,
        viewOffsetMs = viewOffsetMs,
        leafCount = 0,
        viewedLeafCount = 0,
        viewCount = 0,
        addedAt = addedAt,
        lastViewedAt = lastViewedAt,
        librarySectionId = null,
        serverBase = server,
    )

    private fun List<PlexItem>.keys() = map { it.ratingKey }

    /** The reported bug: a film added last week sat ahead of the show watched just now. */
    @Test
    fun `most recently watched comes first, not most recently added`() {
        val row = continueWatchingOrder(
            listOf(
                item("newly-added-film", type = "movie", addedAt = 9_000, lastViewedAt = 1_000),
                item("watched-just-now", show = "kong", addedAt = 100, lastViewedAt = 5_000),
            )
        )
        assertEquals(listOf("watched-just-now", "newly-added-film"), row.keys())
    }

    /** The two hubs overlap; the part-watched episode is the one "continue" means. */
    @Test
    fun `one entry per show, preferring the one in progress`() {
        val row = continueWatchingOrder(
            listOf(
                item("s1e5-next-up", show = "kong", addedAt = 50),
                item("s1e4-halfway", show = "kong", lastViewedAt = 4_000, viewOffsetMs = 600_000),
            )
        )
        assertEquals(listOf("s1e4-halfway"), row.keys())
    }

    @Test
    fun `a next-up episode with no viewing yet falls back to when it was added`() {
        val row = continueWatchingOrder(
            listOf(
                item("film-watched", type = "movie", lastViewedAt = 3_000),
                item("next-up", show = "kong", addedAt = 7_000),
            )
        )
        assertEquals(listOf("next-up", "film-watched"), row.keys())
    }

    /** Rating keys are only unique within a server, so the same show key on two isn't one show. */
    @Test
    fun `shows on different servers are not merged`() {
        val row = continueWatchingOrder(
            listOf(
                item("a-ep", show = "10", lastViewedAt = 2_000, server = "http://a"),
                item("b-ep", show = "10", lastViewedAt = 1_000, server = "http://b"),
            )
        )
        assertEquals(listOf("a-ep", "b-ep"), row.keys())
    }

    @Test
    fun `the same film listed by both hubs appears once`() {
        val film = item("film", type = "movie", lastViewedAt = 2_000, viewOffsetMs = 10)
        assertEquals(listOf("film"), continueWatchingOrder(listOf(film, film)).keys())
    }

    @Test
    fun `ties keep the server's order`() {
        val row = continueWatchingOrder(
            listOf(
                item("first", type = "movie", lastViewedAt = 1_000),
                item("second", type = "movie", lastViewedAt = 1_000),
            )
        )
        assertEquals(listOf("first", "second"), row.keys())
    }
}
