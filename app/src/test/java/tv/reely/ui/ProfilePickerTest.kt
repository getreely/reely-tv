package tv.reely.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.reely.plex.PlexHomeUser
import tv.reely.screens.Shots
import tv.reely.ui.screens.ProfilePicker
import tv.reely.ui.theme.ReelyTheme

/**
 * "Who's watching?" driven with the remote: a profile without a PIN switches straight
 * away, one with a PIN asks for it on the keypad and switches once four digits are in.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class ProfilePickerTest {
    @get:Rule val compose = createComposeRule()

    private val owner = PlexHomeUser("a1", "Tommy", null, protected = false, admin = true, restricted = false)
    private val partner = PlexHomeUser("b2", "Jess", null, protected = true, admin = false, restricted = false)
    private val kids = PlexHomeUser("c3", "Kids", null, protected = false, admin = false, restricted = true)
    private val picked = mutableListOf<Pair<String, String?>>()

    private fun open(error: String? = null) {
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                ProfilePicker(
                    users = listOf(owner, partner, kids),
                    current = owner,
                    switchingTo = null,
                    error = error,
                    onPick = { user, pin -> picked += user.title to pin },
                    onDismissError = {},
                    onClose = {},
                )
            }
        }
        compose.waitForIdle()
        // The picker keeps asking for focus until it lands; let it.
        compose.mainClock.advanceTimeBy(3_000)
        compose.waitForIdle()
    }

    private fun press(key: Key) {
        compose.onRoot().performKeyInput { pressKey(key) }
        compose.waitForIdle()
    }

    @Test fun `a profile without a PIN switches straight away`() {
        open()
        press(Key.DirectionRight)
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        assertEquals(listOf("Kids" to null), picked)
    }

    @Test fun `a PIN is typed on the keypad`() {
        open()
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        compose.onNodeWithText("Enter the PIN for this profile").assertExists()
        Shots.saveIfAsked(compose, "profile-pin")
        // Focus starts on 5: 5, then 2 above it, 8 below twice, 0 at the bottom.
        press(Key.DirectionCenter)
        press(Key.DirectionUp); press(Key.DirectionCenter)
        press(Key.DirectionDown); press(Key.DirectionDown); press(Key.DirectionCenter)
        press(Key.DirectionDown); press(Key.DirectionCenter)
        assertEquals(listOf("Jess" to "5280"), picked)
    }

    @Test fun `or with the number keys`() {
        open()
        press(Key.DirectionRight)
        press(Key.DirectionCenter)
        listOf(Key.One, Key.Nine, Key.Eight, Key.Four).forEach(::press)
        assertEquals(listOf("Jess" to "1984"), picked)
    }

    @Test fun chooser() {
        open()
        Shots.saveIfAsked(compose, "profile-chooser")
    }
}
