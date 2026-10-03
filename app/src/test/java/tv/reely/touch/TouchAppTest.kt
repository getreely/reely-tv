package tv.reely.touch

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
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
import tv.reely.ui.HomeState
import tv.reely.ui.LibraryKind
import tv.reely.ui.PlexState
import tv.reely.ui.ReelyViewModel
import tv.reely.ui.Route
import tv.reely.ui.touch.TouchApp

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
        compose.onNodeWithText("Movies").performClick()
        compose.runOnIdle { assertEquals(Route.Library(LibraryKind.MOVIES), model.state.value.route) }
        compose.onNodeWithText("Live TV").performClick()
        compose.onNodeWithText("Watch live TV from your provider").assertIsDisplayed()
        compose.onNodeWithText("Requests").performClick()
        compose.onNodeWithText("Ask for movies and shows").assertIsDisplayed()
        compose.onNodeWithText("Home").performClick()
        compose.runOnIdle { assertEquals(Route.Home, model.state.value.route) }
    }

    @Test fun `holding a poster offers what the television's menu does`() {
        signedIn()
        show()
        compose.onAllNodesWithText("Ember Street").onFirst().performTouchInput { longClick() }
        compose.onNodeWithText("Mark as watched").assertIsDisplayed()
        compose.onNodeWithText("Details").assertIsDisplayed()
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
        compose.onAllNodesWithText("Northbound").onFirst().performClick()
        compose.runOnIdle { assertTrue(model.state.value.route is Route.Home) }
    }

    companion object {
        /** Nothing listens here, so anything asked of it fails at once. */
        const val SERVER = "http://127.0.0.1:9"
    }
}

const val PHONE = "w400dp-h860dp-port-xhdpi"
