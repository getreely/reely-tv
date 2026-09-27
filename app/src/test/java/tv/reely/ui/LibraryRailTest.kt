package tv.reely.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocusable
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.reely.plex.PlexGenre
import tv.reely.plex.PlexLetter
import tv.reely.plex.PlexSection
import tv.reely.screens.Shots
import tv.reely.ui.screens.LibraryScreen
import tv.reely.ui.screens.letterOf
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.ReelyTheme

/** The A–Z rail down a library in title order, and the sort list that replaced cycling. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class LibraryRailTest {
    @get:Rule val compose = createComposeRule()

    private val section = PlexSection("1", "Films", "movie")
    private val looks = listOf("orbit", "ember", "field", "glass", "ferry", "cardinal")
    private val words = listOf("Alder", "Birch", "Cedar", "Dogwood", "Elm", "Fir")
    private val items = words.flatMapIndexed { w, word ->
        List(10) { i ->
            Shots.item(looks[(w + i) % looks.size]).copy(ratingKey = "m$w$i", title = "$word $i", year = 2000 + i)
        }
    }
    private var browse by mutableStateOf(
        BrowseState(
            section = section,
            items = items,
            letters = words.map { PlexLetter(it.take(1), 10) },
            genres = listOf(PlexGenre("1", "Drama"), PlexGenre("2", "Comedy"), PlexGenre("3", "Thriller")),
            decades = listOf(PlexGenre("2020", "2020s"), PlexGenre("2010", "2010s"), PlexGenre("2000", "2000s")),
        )
    )
    private val jumped = mutableListOf<String>()
    private val sorted = mutableListOf<LibrarySort>()

    private fun grid() {
        val tabFocus = List(5) { FocusRequester() }
        val settingsFocus = FocusRequester()
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Column(Modifier.fillMaxSize().background(Ink)) {
                    TopBar(
                        current = Route.Library(LibraryKind.MOVIES), onNavigate = {}, onActivate = {},
                        onTabFocused = {}, onTabPositioned = { _, _ -> }, tabFocus = tabFocus,
                        settingsFocus = settingsFocus, canSelectOnFocus = { false },
                        serverName = "Living Room",
                    )
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        LibraryScreen(
                            kind = LibraryKind.MOVIES,
                            view = LibraryView.GRID,
                            plex = PlexState(
                                baseUrl = "http://server", serverToken = "t", token = "t", serverName = "Living Room",
                                sections = listOf(section),
                                browse = LibraryKind.entries.associateWith {
                                    if (it == LibraryKind.MOVIES) browse else BrowseState()
                                },
                            ),
                            home = HomeState(),
                            focused = items[12],
                            imageUrl = { _, path, w, h -> Shots.imageUrl(path, w, h) },
                            backdropUrl = { _, path -> Shots.imageUrl(path, 1280, 720) },
                            onFocusItem = {}, onOpenItem = {}, onStartLink = {}, onCancelLink = {},
                            onDismissPlexError = {}, onSetSort = { sorted += it }, onToggleUnwatched = {},
                            onSelectGenre = {}, onDismissBrowseError = {},
                            onJumpToLetter = { jumped += it },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun focusedTitle(title: String) =
        compose.onNode(hasText(title) and isFocused()).assertExists()

    @OptIn(ExperimentalTestApi::class)
    @Test fun `OK on a letter asks for it`() {
        grid()
        compose.onNode(hasText("D") and isFocusable()).requestFocus()
        compose.onNode(hasText("D") and isFocusable()).performKeyInput { pressKey(Key.DirectionCenter) }
        compose.waitForIdle()
        assertEquals(listOf("D"), jumped)
        Shots.saveIfAsked(compose, "library-rail")
    }

    @Test fun `letters with nothing under them can't be chosen`() {
        grid()
        compose.onNode(hasText("Q") and isFocusable()).assertDoesNotExist()
        compose.onNodeWithText("Q").assertExists()
    }

    @Test fun `a jump puts the cursor on the letter's first title`() {
        grid()
        compose.onAllNodesWithText("Alder 0").onFirst().requestFocus()
        compose.waitForIdle()
        browse = browse.copy(jump = GridJump(index = 40, serial = 1))
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        focusedTitle("Elm 0")
    }

    @Test fun `a letter starts after every title before it`() {
        assertEquals(0, browse.letterStart("A"))
        assertEquals(30, browse.letterStart("D"))
        assertEquals(null, browse.letterStart("Q"))
    }

    @Test fun `titles file under their sort title, anything else under #`() {
        val film = items[0]
        assertEquals("M", letterOf(film.copy(title = "The Matrix", titleSort = "Matrix")))
        assertEquals("#", letterOf(film.copy(title = "2001: A Space Odyssey")))
    }

    @Test fun `sort opens a list, and picking from it sorts`() {
        grid()
        compose.onNodeWithText("Sort · A–Z").performClick()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(300)
        Shots.saveIfAsked(compose, "library-sort")
        compose.onNodeWithText("Recently watched").performClick()
        compose.waitForIdle()
        assertEquals(listOf(LibrarySort.WATCHED), sorted)
        compose.onNodeWithText("Sort by").assertDoesNotExist()
    }
}
