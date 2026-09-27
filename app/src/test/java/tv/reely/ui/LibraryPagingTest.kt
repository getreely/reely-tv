package tv.reely.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.requestFocus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.plex.PlexSection
import tv.reely.screens.Shots
import tv.reely.ui.screens.LibraryScreen
import tv.reely.ui.theme.ReelyTheme

/**
 * The library grid asks for its next page as the cursor nears the end, and not before —
 * it used to stop at the first 400 titles, whatever the library held.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class LibraryPagingTest {
    @get:Rule val compose = createComposeRule()

    private var asked = 0

    private fun grid(count: Int) {
        val section = PlexSection("1", "Films", "movie")
        val items = List(count) { i -> Shots.item("orbit").copy(ratingKey = "m$i", title = "Film $i") }
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Box(Modifier.fillMaxSize()) {
                    LibraryScreen(
                        kind = LibraryKind.MOVIES,
                        view = LibraryView.GRID,
                        plex = PlexState(
                            baseUrl = "http://server", serverToken = "t", token = "t", serverName = "Living Room",
                            sections = listOf(section),
                            browse = LibraryKind.entries.associateWith {
                                if (it == LibraryKind.MOVIES) BrowseState(section = section, items = items) else BrowseState()
                            },
                        ),
                        home = HomeState(),
                        focused = null,
                        imageUrl = { _, _, _, _ -> null },
                        backdropUrl = { _, _ -> null },
                        onFocusItem = {}, onOpenItem = {}, onStartLink = {}, onCancelLink = {},
                        onDismissPlexError = {}, onCycleSort = {}, onToggleUnwatched = {},
                        onSelectGenre = {}, onDismissBrowseError = {},
                        onLoadMore = { asked++ },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun focus(title: String) {
        compose.onAllNodesWithText(title).onFirst().requestFocus()
        compose.waitForIdle()
    }

    @Test fun `near the top, nothing more is asked for`() {
        grid(300)
        focus("Film 0")
        assertEquals(0, asked)
    }

    @Test fun `near the end, the next page is`() {
        // Twenty titles: the first is already within reach of the end.
        grid(20)
        focus("Film 0")
        assertEquals(1, asked)
    }
}
