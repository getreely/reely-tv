package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.reely.ui.LiveState
import tv.reely.xtream.XtreamCategory

class FavoritesTest {
    private val sport = XtreamCategory("1", "UK | Sport")
    private val news = XtreamCategory("2", "UK | News")

    @Test fun `no favorites, just the provider's categories`() {
        assertEquals(listOf(sport, news), LiveState(categories = listOf(sport, news)).shownCategories)
    }

    @Test fun `with favorites, Favorites comes first`() {
        val live = LiveState(categories = listOf(sport, news), favorites = setOf(101))
        assertEquals(listOf(LiveState.FAVORITES, sport, news), live.shownCategories)
    }
}
