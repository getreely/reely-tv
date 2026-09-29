package tv.reely.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.screens.Shots
import tv.reely.ui.screens.GuideScreen
import tv.reely.ui.theme.ReelyTheme
import tv.reely.xtream.EpgProgramme
import tv.reely.xtream.XtreamAccount
import tv.reely.xtream.XtreamCategory
import tv.reely.xtream.XtreamChannel
import tv.reely.xtream.XtreamCredentials

/** Hold OK in the Live TV guide: the channel's menu, with Favorites and a reminder. */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class GuideMenuTest {
    @get:Rule val compose = createComposeRule()

    private val now = System.currentTimeMillis() / 1000
    private val channel = XtreamChannel(100, 1, "News One", null, "news")
    private val later = EpgProgramme("news", now + 3_600, now + 7_200, "The Late Show", null)
    private val favorited = mutableListOf<XtreamChannel>()
    private val reminded = mutableListOf<EpgProgramme>()
    private var played = 0

    private fun guide(focusTime: Long) {
        val live = LiveState(
            credentials = XtreamCredentials("http://panel", "u", "p"),
            account = XtreamAccount("Active", "2", "0", null),
            categories = listOf(XtreamCategory("c0", "News")),
            selectedCategory = XtreamCategory("c0", "News"),
            channels = listOf(channel),
        )
        val guide = GuideState(
            programmes = mapOf("news" to listOf(EpgProgramme("news", now - 600, now + 3_600, "Now", null), later)),
            windowStart = now - 3_600, windowEnd = now + 6 * 3_600, focusTime = focusTime,
        )
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Box(Modifier.fillMaxSize()) {
                    GuideScreen(
                        live = live, guide = guide, previewEnabled = false,
                        onMoveChannel = {}, onMoveTime = {}, onJumpToNow = {}, onRefresh = {},
                        onPlaySelected = { played++ }, onBackToCategories = {},
                        livePlayer = tv.reely.core.LivePlayer(context),
                        onToggleReminder = { _, programme -> reminded += programme },
                        onToggleFavorite = { favorited += it },
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    /** OK held down: a press, then the key repeating, then let go. */
    private fun holdOk() {
        fun event(action: Int, repeat: Int) = androidx.compose.ui.input.key.KeyEvent(
            android.view.KeyEvent(0L, 0L, action, android.view.KeyEvent.KEYCODE_DPAD_CENTER, repeat)
        )
        compose.onRoot().performKeyPress(event(android.view.KeyEvent.ACTION_DOWN, 0))
        compose.onRoot().performKeyPress(event(android.view.KeyEvent.ACTION_DOWN, 1))
        // As a remote does it: the menu is up and has the cursor while OK is still held,
        // repeating, and only then let go. Those have to go nowhere.
        compose.waitForIdle()
        compose.onRoot().performKeyPress(event(android.view.KeyEvent.ACTION_DOWN, 2))
        compose.onRoot().performKeyPress(event(android.view.KeyEvent.ACTION_DOWN, 3))
        compose.onRoot().performKeyPress(event(android.view.KeyEvent.ACTION_UP, 0))
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test fun `hold OK opens the channel's menu, and a favorite can be made there`() {
        guide(focusTime = now)
        holdOk()
        assertEquals("the hold didn't also play it", 0, played)
        compose.onNode(isFocused() and hasText("Watch")).assertExists()
        press(Key.DirectionDown)
        compose.onNode(isFocused() and hasText("Add to Favorites")).assertExists()
        press(Key.DirectionCenter)
        assertEquals(listOf(channel), favorited)
        compose.onNodeWithText("Add to Favorites").assertDoesNotExist()
    }

    @Test fun `on something still to come, the menu offers a reminder`() {
        guide(focusTime = later.start + 60)
        holdOk()
        press(Key.DirectionDown)
        press(Key.DirectionDown)
        compose.onNode(isFocused() and hasText("Remind me") and hasText("The Late Show", substring = true)).assertExists()
        press(Key.DirectionCenter)
        assertEquals(listOf(later), reminded)
    }

    @Test fun `a quick press still plays`() {
        guide(focusTime = now)
        press(Key.DirectionCenter)
        assertEquals(1, played)
    }
}
