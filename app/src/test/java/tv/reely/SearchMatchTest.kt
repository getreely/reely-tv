package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.reely.core.SearchMatch
import tv.reely.screens.Shots

/** Search results that are about what was typed, and nothing that only came close. */
class SearchMatchTest {
    private fun film(title: String) = Shots.item("orbit").copy(ratingKey = title, title = title, grandparentTitle = null)
    private fun episode(title: String, show: String) =
        Shots.item("north").copy(ratingKey = "$show/$title", title = title, grandparentTitle = show)

    private fun titles(query: String, vararg items: tv.reely.plex.PlexItem) =
        SearchMatch.relevant(query, items.toList()).map { it.title }

    @Test fun `only names with it in, not things that came close`() {
        assertEquals(
            listOf("NFL Films Presents"),
            titles("nfl", film("Friday Night Lights"), film("NFL Films Presents"), film("Inflation")),
        )
    }

    @Test fun `an exact name first, then names starting with it, then the rest`() {
        assertEquals(
            listOf("Dune", "Dune: Part Two", "The Dune Chronicles"),
            titles("dune", film("The Dune Chronicles"), film("Dune: Part Two"), film("Dune")),
        )
    }

    @Test fun `a show's episodes match on the show's name`() {
        assertEquals(listOf("Week 1"), titles("nfl game", episode("Week 1", "NFL Game Day"), episode("Pilot", "Suits")))
    }

    @Test fun `punctuation, case and accents don't count`() {
        assertEquals(listOf("Spider-Man"), titles("spiderman", film("Spider-Man")))
        assertEquals(listOf("Amélie"), titles("AMELIE", film("Amélie")))
    }

    @Test fun `words typed together can start at any word`() {
        assertEquals(listOf("Man of Steel"), titles("ofsteel", film("Man of Steel")))
    }

    @Test fun `the rest follow, not thrown away`() {
        val (matches, others) = SearchMatch.split(
            "nfl", listOf(film("Friday Night Lights"), film("NFL Films Presents"), film("Inflation")),
        )
        assertEquals(listOf("NFL Films Presents"), matches.map { it.title })
        assertEquals(listOf("Friday Night Lights", "Inflation"), others.map { it.title })
    }

    @Test fun `a person's name counts in any order and case, every word present`() {
        assertEquals(true, tv.reely.ui.namesAll("Tom Hanks", "hanks TOM"))
        assertEquals(true, tv.reely.ui.namesAll("Tom Hanks", "tom"))
        assertEquals(false, tv.reely.ui.namesAll("Tom Hanks", "tom cruise"))
        assertEquals(false, tv.reely.ui.namesAll("Tom Hanks", "  "))
    }
}
