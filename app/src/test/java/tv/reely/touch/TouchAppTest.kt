package tv.reely.touch

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.core.FormFactor
import tv.reely.screens.Shots
import tv.reely.plex.PlexLetter
import tv.reely.plex.PlexSection
import tv.reely.ui.BrowseState
import tv.reely.ui.HomeState
import tv.reely.ui.LibraryKind
import tv.reely.ui.LibraryView
import tv.reely.ui.PlexState
import tv.reely.ui.ReelyViewModel
import tv.reely.ui.Route
import tv.reely.ui.touch.TouchApp
import tv.reely.ui.touch.countLabel

/** The phone and tablet app, on the real view model: the way round it a finger takes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = PHONE)
class TouchAppTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var model: ReelyViewModel

    @Before fun setUp() {
        FakeKeyStore.install()
        model = ReelyViewModel(ApplicationProvider.getApplicationContext())
    }

    private fun signedIn() = model.setStateForTest {
        it.copy(
            plex = PlexState(token = "t", baseUrl = SERVER, serverToken = "t", serverName = "Living Room"),
            home = HomeState(
                continueWatching = listOf(Shots.item("north").copy(serverBase = SERVER)),
                recentMovies = listOf("ember", "field", "orbit").map { key -> Shots.item(key).copy(serverBase = SERVER) },
            ),
            restoring = false,
        )
    }

    private fun show() = compose.setContent { TouchApp(model, imageUrl = { _, path, w, h -> Shots.imageUrl(path, w, h) }) }

    @Test fun `a phone or tablet gets the touch app, a television or anything without touch the remote one`() {
        assertTrue(FormFactor.isTelevision(leanback = true, fireTv = false, touchscreen = true))
        assertTrue(FormFactor.isTelevision(leanback = false, fireTv = true, touchscreen = false))
        assertTrue(FormFactor.isTelevision(leanback = false, fireTv = false, touchscreen = false))
        assertEquals(false, FormFactor.isTelevision(leanback = false, fireTv = false, touchscreen = true))
    }

    @Test fun `not signed in, Home asks to sign in with Plex`() {
        show()
        compose.onNodeWithText("Sign in to watch your library").assertIsDisplayed()
        compose.onNodeWithText("Sign in with Plex").assertIsDisplayed()
    }

    @Test fun `the bottom bar goes to each tab`() {
        signedIn()
        show()
        compose.onNodeWithText("Recently Added Movies").assertIsDisplayed()
        // The floating tabs show only the chosen one's name; each is found by what it's called.
        compose.onNodeWithContentDescription("Movies").performClick()
        compose.runOnIdle { assertEquals(Route.Library(LibraryKind.MOVIES), model.state.value.route) }
        compose.onNodeWithContentDescription("Live TV").performClick()
        compose.onNodeWithText("Watch live TV from your provider").assertIsDisplayed()
        compose.onNodeWithContentDescription("Requests").performClick()
        compose.onNodeWithText("Ask for movies and shows").assertIsDisplayed()
        compose.onNodeWithContentDescription("Home").performClick()
        compose.runOnIdle { assertEquals(Route.Home, model.state.value.route) }
    }

    @Test fun `holding a poster offers what the television's menu does`() {
        signedIn()
        show()
        compose.onAllNodesWithText("Ember Street").onFirst().performTouchInput { longClick() }
        compose.onNodeWithText("Mark as watched").assertIsDisplayed()
        // Home's picture has a Details of its own; the menu's is the last drawn.
        compose.onAllNodesWithText("Details").onLast().assertIsDisplayed()
    }

    @Test fun `back from a page goes back, and from a tab to Home`() {
        signedIn()
        show()
        compose.runOnIdle { model.navigate(Route.Library(LibraryKind.SHOWS)) }
        compose.runOnIdle { model.navigate(Route.Detail("ember", serverBase = SERVER)) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(Route.Library(LibraryKind.SHOWS), model.state.value.route) }
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(Route.Home, model.state.value.route) }
    }

    @Test fun `settings on a phone list their sections and open one full width`() {
        signedIn()
        show()
        compose.runOnIdle { model.navigate(Route.Settings) }
        compose.onNodeWithText("Playback").performClick()
        compose.onNodeWithText("Playback mode").assertIsDisplayed()
        // Nothing of the television's own: no screensaver, no tour of the remote.
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("About").performClick()
        assertTrue(compose.onAllNodesWithText("Take the tour").fetchSemanticsNodes().isEmpty())
    }

    @Test fun `search takes what's typed`() {
        signedIn()
        show()
        compose.runOnIdle { model.navigate(Route.Search) }
        compose.onNodeWithText("Movies, shows, people, channels").performTextInput("dune")
        compose.runOnIdle { assertEquals("dune", model.state.value.search.query) }
    }

    @Test fun `Continue Watching plays from a press, as a phone's Plex does`() {
        signedIn()
        show()
        // Nowhere to play it from here; what matters is that it's asked to play, not opened.
        // The first Northbound is Home's picture, which opens the show; the second is the card.
        compose.onAllNodesWithText("Northbound")[1].performClick()
        compose.runOnIdle { assertTrue(model.state.value.route is Route.Home) }
    }

    @Test fun `every profile in a big household can be reached, and a PIN asks on a keypad`() {
        val people = listOf("Taylor", "Sam", "Kids", "Grandma", "Jordan", "Alex").mapIndexed { i, name ->
            tv.reely.plex.PlexHomeUser("u$i", name, null, protected = name == "Grandma", admin = i == 0, restricted = name == "Kids")
        }
        signedIn()
        model.setStateForTest { it.copy(plex = it.plex.copy(homeUsers = people, user = people[1], askWho = true)) }
        show()
        compose.onNodeWithText("Who's watching?").assertIsDisplayed()
        compose.onNodeWithText("Grandma").assertIsDisplayed().performClick()
        compose.onNodeWithText("Enter the PIN for this profile").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Alex").performScrollTo().assertIsDisplayed()
        // The profile already in use just closes it.
        compose.onNodeWithText("Sam").performScrollTo().performClick()
        compose.onNodeWithText("Recently Added Movies").assertIsDisplayed()
    }

    @Test fun `a show's Play starts the episode it's up to, not the season's first`() {
        val episodes = (1..4).map { i ->
            Shots.item("north").copy(ratingKey = "ep$i", title = "Episode $i", index = i, parentIndex = 1, serverBase = SERVER, viewCount = if (i < 3) 1 else 0, viewOffsetMs = 0)
        }
        signedIn()
        model.setStateForTest {
            it.copy(
                stack = listOf(Route.Home, Route.Detail("show", serverBase = SERVER)),
                detail = tv.reely.ui.DetailState(
                    ratingKey = "show", serverBase = SERVER, busy = false,
                    detail = tv.reely.plex.PlexDetail(
                        ratingKey = "show", type = "show", title = "Northbound", summary = null, tagline = null, year = 2024,
                        durationMs = 0, viewOffsetMs = 0, contentRating = null, rating = null, audienceRating = null, airDate = null,
                        viewCount = 0, studio = null, thumb = null, art = null, theme = null, genres = emptyList(), directors = emptyList(),
                        roles = emptyList(), childCount = 1, leafCount = 4, grandparentTitle = null, index = null, parentIndex = null,
                    ),
                    episodes = episodes, focusedEpisode = episodes[2],
                ),
            )
        }
        show()
        compose.onNodeWithText("Play S1 · E3").assertIsDisplayed()
    }

    @Test fun `a phone's library says how many there are, and its A to Z rail goes to a letter`() {
        val films = PlexSection("1", "Films", "movie")
        val items = (0 until 50).map { i -> Shots.item("ember").copy(ratingKey = "m$i", title = "Film $i", serverBase = SERVER) }
        signedIn()
        model.setStateForTest {
            it.copy(
                stack = listOf(Route.Home, Route.Library(LibraryKind.MOVIES, LibraryView.GRID)),
                plex = it.plex.copy(
                    sections = listOf(films),
                    browse = mapOf(
                        LibraryKind.MOVIES to BrowseState(
                            section = films, items = items, total = 1212,
                            letters = listOf(PlexLetter("A", 20), PlexLetter("M", 25)),
                        ),
                    ),
                ),
            )
        }
        show()
        compose.onNodeWithText("1,212 movies").assertIsDisplayed()
        compose.onNodeWithText("M").performClick()
        compose.runOnIdle { assertEquals(20, model.state.value.plex.browseFor(LibraryKind.MOVIES).jump?.index) }
        compose.onNodeWithText("A").performClick()
        compose.runOnIdle { assertEquals(0, model.state.value.plex.browseFor(LibraryKind.MOVIES).jump?.index) }
    }

    @Test fun `the count reads right for one and for many`() {
        assertEquals("1 movie", countLabel(1, LibraryKind.MOVIES))
        assertEquals("48 shows", countLabel(48, LibraryKind.SHOWS))
        assertEquals("12,040 movies", countLabel(12040, LibraryKind.MOVIES))
    }

    @Test fun `a colour picked in Settings is used everywhere, and kept for next time`() {
        try {
            assertEquals(tv.reely.ui.theme.AccentChoice.BLUE.color, tv.reely.ui.theme.Accent)
            model.setAccent("gold")
            assertEquals(tv.reely.ui.theme.AccentChoice.GOLD.color, tv.reely.ui.theme.Accent)
            assertEquals(tv.reely.ui.theme.AccentChoice.GOLD.on, tv.reely.ui.theme.OnAccent)
            val again = ReelyViewModel(ApplicationProvider.getApplicationContext())
            assertEquals("gold", again.state.value.prefs.accent)
            assertEquals(tv.reely.ui.theme.AccentChoice.GOLD.color, tv.reely.ui.theme.Accent)
            // Anything unknown is the blue.
            model.setAccent("plaid")
            assertEquals(tv.reely.ui.theme.AccentChoice.BLUE.color, tv.reely.ui.theme.Accent)
        } finally {
            tv.reely.ui.theme.useAccent("blue")
        }
    }

    @Test fun `IPTV can be taken out of the Movies and TV Shows menus, and stays out`() {
        signedIn()
        model.setStateForTest {
            it.copy(
                iptv = it.iptv.copy(on = true),
                stack = listOf(Route.Home, Route.Library(LibraryKind.MOVIES, LibraryView.HOME)),
            )
        }
        show()
        compose.onNodeWithText("IPTV").assertIsDisplayed()
        compose.runOnIdle { model.toggleIptvInMenus() }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("IPTV").fetchSemanticsNodes().isEmpty())
        val again = ReelyViewModel(ApplicationProvider.getApplicationContext())
        assertEquals(false, again.state.value.prefs.iptvInMenus)
    }

    companion object {
        /** Nothing listens here, so anything asked of it fails at once. */
        const val SERVER = "http://127.0.0.1:9"
    }
}

const val PHONE = "w400dp-h860dp-port-xhdpi"
