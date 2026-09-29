package tv.reely.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.requests.RequestRow
import tv.reely.requests.RequestTitle
import tv.reely.screens.Shots
import tv.reely.ui.components.LocalScreenFocus
import tv.reely.ui.components.ScreenFocus
import tv.reely.ui.screens.RequestsScreen
import tv.reely.ui.theme.ReelyTheme

/** Back from a title opened in Requests puts the cursor on that title's poster again. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class RequestsBackTest {
    @get:Rule val compose = createComposeRule()
    private val screens = mutableMapOf<String, ScreenFocus>()
    private var route by mutableStateOf("requests")

    private fun films(row: String) = List(8) { RequestTitle("movie", (row.length * 100) + it + 1, 0, "$row $it", 2024, null, null, null) }

    @Test fun `back lands on the poster that was opened`() {
        val rows = listOf("Trending", "Popular", "Top").map { RequestRow(it, it, films(it)) }
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                val saved = rememberSaveableStateHolder()
                saved.SaveableStateProvider(route) {
                    CompositionLocalProvider(LocalScreenFocus provides screens.getOrPut(route) { ScreenFocus() }) {
                        if (route == "requests") {
                            RequestsScreen(
                                requests = RequestsState(server = "http://reely", connected = true, rows = rows),
                                onConnect = {}, onDismissError = {}, onQueryChange = {},
                                onOpen = { route = "title" },
                            )
                        } else {
                            Box(Modifier.size(10.dp).focusable())
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        fun press(key: Key) {
            compose.onRoot().performKeyInput { pressKey(key) }
            compose.waitForIdle()
        }
        // Into the second row, three along, and open it.
        compose.onNode(hasText("Popular 3", substring = true) and androidx.compose.ui.test.hasClickAction()).requestFocus()
        compose.waitForIdle()
        val opened = compose.onNode(isFocused()).fetchSemanticsNode().config.toString()
        println("OPENED: " + opened.take(300))
        press(Key.DirectionCenter)
        compose.waitForIdle()
        route = "requests"
        compose.waitForIdle()
        val restored = compose.runOnIdle { runBlocking { screens.getValue("requests").restore() } }
        compose.waitForIdle()
        println("RESTORED: $restored")
        val now = runCatching { compose.onNode(isFocused()).fetchSemanticsNode().config.toString() }.getOrDefault("nothing")
        println("FOCUSED: " + now.take(300))
        assertTrue(restored)
        compose.onNode(isFocused() and hasText("Popular 3", substring = true)).assertExists()
    }
}
