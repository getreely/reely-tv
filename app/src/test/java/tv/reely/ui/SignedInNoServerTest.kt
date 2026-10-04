package tv.reely.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.screens.Shots
import tv.reely.ui.screens.PlexSignInPanel

/**
 * Signed in, but no server answered. It showed the sign-in button again, which read as
 * though signing in hadn't worked; now it says so, with a way to look again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class SignedInNoServerTest {

    @get:Rule val compose = createComposeRule()

    @Test fun `looking for the server doesn't offer to sign in again`() {
        compose.setContent {
            PlexSignInPanel(plex = PlexState(token = "t", busy = true), onStartLink = {}, onCancelLink = {}, onDismissError = {})
        }
        compose.onNodeWithText("Finding a Plex server").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTextCount("Sign in with Plex"))
        assertEquals(0, compose.onAllNodesWithTextCount("Getting a code…"))
    }

    @Test fun `no server answered, try again or use another account`() {
        var retried = 0
        var signedOut = 0
        compose.setContent {
            PlexSignInPanel(
                plex = PlexState(token = "t", error = "Couldn't reach your Plex server. Make sure it's on and connected."),
                onStartLink = {}, onCancelLink = {}, onDismissError = {},
                onRetryConnect = { retried++ }, onSignOut = { signedOut++ },
            )
        }
        compose.onNodeWithText("Couldn't reach a Plex server").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.onNodeWithText("Use a different Plex account").performClick()
        compose.runOnIdle {
            assertEquals(1, retried)
            assertEquals(1, signedOut)
        }
    }

    @Test fun `not signed in, it's the sign-in button`() {
        compose.setContent {
            PlexSignInPanel(plex = PlexState(), onStartLink = {}, onCancelLink = {}, onDismissError = {})
        }
        compose.onNodeWithText("Sign in with Plex").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size
}
