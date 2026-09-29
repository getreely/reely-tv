package tv.reely.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.requests.RequestRow
import tv.reely.requests.RequestTitle
import tv.reely.screens.Shots
import tv.reely.ui.screens.RequestsScreen
import tv.reely.ui.theme.ReelyTheme

/** Down from the tab row lands on the search at the top of Requests, not the posters. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class RequestSearchFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `down from the tab is the search`() {
        val films = List(6) { RequestTitle("movie", it + 1, 0, "Film $it", 2024, null, null, null) }
        val tab = FocusRequester()
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(40.dp).testTag("tab").focusRequester(tab).focusable())
                    RequestsScreen(
                        requests = RequestsState(
                            server = "http://reely.local", connected = true,
                            rows = listOf(RequestRow("movies", "Trending Movies", films)),
                        ),
                        onConnect = {}, onDismissError = {}, onQueryChange = {}, onOpen = {},
                    )
                }
            }
        }
        compose.runOnIdle { tab.requestFocus() }
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        compose.onNode(isFocused() and hasText("Movies and shows to request", substring = true)).assertExists()
    }
}
