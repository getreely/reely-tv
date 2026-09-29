package tv.reely.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.screens.Shots
import tv.reely.ui.components.requestWhenReady
import tv.reely.ui.screens.LiveCategoriesScreen
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.ReelyTheme
import tv.reely.xtream.XtreamCategory

/**
 * Back out of the guide lands on the category it was opened from, not the search tab;
 * and Back from a tab's page goes up to its tab, then asks before closing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class LiveBackTest {
    @get:Rule val compose = createComposeRule()

    private val live = LiveState(
        credentials = tv.reely.xtream.XtreamCredentials("http://line.example.tv:8080", "u", "p"),
        account = tv.reely.xtream.XtreamAccount("Active", "2", "1", "1798761600"),
        categories = List(40) { XtreamCategory("c$it", "Category $it") },
    )
    private var selected by mutableStateOf<XtreamCategory?>(null)
    private var returnTo by mutableStateOf<String?>(null)

    private fun focused(): String =
        compose.onAllNodes(isFocused()).fetchSemanticsNodes().joinToString { n ->
            (n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }?.joinToString() ?: "") +
                (n.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null } ?: "")
        }

    @OptIn(ExperimentalTestApi::class)
    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    private fun backFromGuide(steps: Int) {
        compose.setContent {
            ReelyTheme {
                Column(Modifier.fillMaxSize().background(Ink)) {
                    // Stands in for the tab row, whose search button is the first thing in the window.
                    Box(Modifier.fillMaxWidth().height(40.dp).testTag("search").focusable())
                    val category = selected
                    if (category == null) {
                        LiveCategoriesScreen(
                            live = live,
                            onSignIn = { _, _, _ -> }, onSignInPlaylist = { _, _ -> },
                            onSelectCategory = { selected = it },
                            onDismissError = {},
                            returnTo = returnTo,
                            onReturned = { returnTo = null },
                        )
                    } else {
                        // The guide, which takes the cursor when it opens.
                        val guide = remember { FocusRequester() }
                        LaunchedEffect(Unit) { guide.requestWhenReady() }
                        Box(Modifier.fillMaxSize().testTag("guide").focusRequester(guide).focusable())
                    }
                }
            }
        }
        compose.onNodeWithTag("search").requestFocus()
        press(Key.DirectionDown)
        repeat(steps) { press(Key.DirectionDown) }
        press(Key.DirectionRight)
        val opened = focused()
        assertTrue("on a category further in: $opened", opened.startsWith("Category ") && opened != "Category 0")
        press(Key.DirectionCenter)
        assertEquals("guide", focused())
        // What the guide's Back does.
        returnTo = selected?.id
        selected = null
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertEquals("the cursor is back on the category", opened, focused())
    }

    @Test fun `back from the guide lands on the category it came from`() = backFromGuide(steps = 1)

    // Far enough down that the grid, built again from the top, has to scroll to it.
    @Test fun `back from the guide finds a category further down`() = backFromGuide(steps = 8)

    @Test fun `back from a page goes up to its tab, and from the tab asks before closing`() {
        assertEquals(BackStep.UP_TO_TAB, backStep(false, false, onTabs = false, stackSize = 1, route = Route.Home))
        assertEquals(BackStep.ASK_EXIT, backStep(false, false, onTabs = true, stackSize = 1, route = Route.Home))
        assertEquals(BackStep.UP_TO_TAB, backStep(false, false, onTabs = false, stackSize = 1, route = Route.Live))
        assertEquals(BackStep.UP_TO_TAB, backStep(false, false, onTabs = false, stackSize = 1, route = Route.Search))
        assertEquals(
            BackStep.UP_TO_TAB,
            backStep(false, false, onTabs = false, stackSize = 1, route = Route.Library(LibraryKind.MOVIES)),
        )
        // Settings is a tab, even on top of a title's page.
        assertEquals(BackStep.UP_TO_TAB, backStep(false, false, onTabs = false, stackSize = 2, route = Route.Settings))
        assertEquals(BackStep.ASK_EXIT, backStep(false, false, onTabs = true, stackSize = 2, route = Route.Settings))
        // A title's page goes back to where it was opened from, from anywhere on it.
        val title = Route.Detail("1")
        assertEquals(BackStep.WALK_BACK, backStep(false, false, onTabs = false, stackSize = 2, route = title))
        assertEquals(BackStep.WALK_BACK, backStep(false, false, onTabs = true, stackSize = 2, route = title))
        // A library's grid goes to its home first.
        assertEquals(
            BackStep.LIBRARY_HOME,
            backStep(false, false, onTabs = false, stackSize = 1, route = Route.Library(LibraryKind.SHOWS, LibraryView.GRID)),
        )
        // Anything open closes first.
        assertEquals(BackStep.CLOSE_EXIT, backStep(true, false, onTabs = false, stackSize = 1, route = Route.Home))
        assertEquals(BackStep.CLOSE_MENU, backStep(false, true, onTabs = true, stackSize = 1, route = Route.Home))
    }
}
