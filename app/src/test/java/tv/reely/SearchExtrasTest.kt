package tv.reely

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.core.rememberedSearches
import tv.reely.plex.PlexApi

/** Collections among the search results, and the searches kept to offer again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SearchExtrasTest {
    private val hubs = JSONArray(
        """
        [
          {"type": "movie", "Metadata": [
            {"ratingKey": "1", "type": "movie", "title": "Skyfall", "year": 2012}
          ]},
          {"type": "collection", "Metadata": [
            {"ratingKey": "90", "type": "collection", "title": "James Bond", "leafCount": 25}
          ]}
        ]
        """.trimIndent()
    )

    @Test fun `collections come apart from the titles`() {
        val titles = PlexApi.itemsFromHubs(hubs, "http://plex")
        val collections = PlexApi.itemsFromHubs(hubs, "http://plex", setOf("collection"))
        assertEquals(listOf("Skyfall"), titles.map { it.title })
        assertEquals(listOf("James Bond"), collections.map { it.title })
        assertEquals("25 titles", collections.single().caption)
    }

    @Test fun `a search is remembered first, once`() {
        val recent = listOf("bond", "nfl", "the office")
        // The newest spelling is the one kept.
        assertEquals(listOf("NFL", "bond", "the office"), rememberedSearches(recent, "NFL"))
        assertEquals(listOf("star wars", "bond", "nfl", "the office"), rememberedSearches(recent, "  star   wars "))
        assertEquals(recent, rememberedSearches(recent, "   "))
    }

    @Test fun `only the latest few are kept`() {
        val recent = (1..8).map { "search $it" }
        val next = rememberedSearches(recent, "new one")
        assertEquals(8, next.size)
        assertEquals("new one", next.first())
        assertEquals("search 7", next.last())
    }
}
