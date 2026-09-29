package tv.reely.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.screens.Shots
import tv.reely.ui.screens.GuideScreen
import tv.reely.ui.theme.ReelyTheme
import tv.reely.xtream.XtreamAccount
import tv.reely.xtream.XtreamCategory
import tv.reely.xtream.XtreamChannel
import tv.reely.xtream.XtreamCredentials

/**
 * The guide takes the cursor when a category is opened, and not when the cursor is only
 * passing the Live TV tab on its way along the tabs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class GuideFocusTest {
    @get:Rule val compose = createComposeRule()

    private fun guide(takeFocus: Boolean) {
        val channels = List(6) { i -> XtreamChannel(100 + i, i + 1, "Channel $i", null, "ch$i") }
        val live = LiveState(
            credentials = XtreamCredentials("http://panel", "u", "p"),
            account = XtreamAccount("Active", "2", "0", null),
            categories = listOf(XtreamCategory("c0", "News")),
            selectedCategory = XtreamCategory("c0", "News"),
            channels = channels,
        )
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val livePlayer = tv.reely.core.LivePlayer(context)
        val tab = FocusRequester()
        // As in the app: the tab has the cursor, and the guide arrives under it.
        var showGuide by mutableStateOf(false)
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().height(40.dp).testTag("tab").focusRequester(tab).focusable())
                    if (showGuide) GuideScreen(
                        live = live, guide = GuideState(), previewEnabled = false,
                        onMoveChannel = {}, onMoveTime = {}, onJumpToNow = {}, onRefresh = {},
                        onPlaySelected = {}, onBackToCategories = {}, livePlayer = livePlayer,
                        takeFocus = takeFocus,
                    )
                }
            }
        }
        compose.runOnIdle { tab.requestFocus() }
        compose.waitForIdle()
        showGuide = true
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
    }

    @Test fun `passing along the tabs, the cursor stays on the tab`() {
        guide(takeFocus = false)
        compose.onNodeWithTag("tab").assertIsFocused()
    }

    @Test fun `opening a category, the guide takes it`() {
        guide(takeFocus = true)
        compose.onNodeWithTag("tab").assertIsNotFocused()
    }
}
