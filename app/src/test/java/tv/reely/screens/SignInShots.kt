package tv.reely.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.reely.ui.LiveState
import tv.reely.ui.Route
import tv.reely.ui.TopBar
import tv.reely.ui.screens.XtreamSignInPanel
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.ReelyTheme

/** The live TV sign-in, under the tab row as it appears, with and without an error. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class SignInShots {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() { Shots.onlyWhenAsked() }

    private fun signIn(live: LiveState, name: String) {
        val tabFocus = List(5) { FocusRequester() }
        val settingsFocus = FocusRequester()
        compose.setContent {
            ReelyTheme {
                Column(Modifier.fillMaxSize().background(Ink)) {
                    TopBar(
                        current = Route.Live, onNavigate = {}, onActivate = {}, onTabFocused = {},
                        onTabPositioned = { _, _ -> }, tabFocus = tabFocus, settingsFocus = settingsFocus,
                        canSelectOnFocus = { false }, serverName = "Living Room",
                    )
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        XtreamSignInPanel(live = live, onSignIn = { _, _, _ -> }, onDismissError = {}, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
        Shots.save(compose, name)
    }

    private fun plex(state: tv.reely.ui.PlexState, name: String) {
        val tabFocus = List(5) { FocusRequester() }
        val settingsFocus = FocusRequester()
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Column(Modifier.fillMaxSize().background(Ink)) {
                    TopBar(
                        current = Route.Home, onNavigate = {}, onActivate = {}, onTabFocused = {},
                        onTabPositioned = { _, _ -> }, tabFocus = tabFocus, settingsFocus = settingsFocus,
                        canSelectOnFocus = { false }, serverName = null,
                    )
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        tv.reely.ui.screens.PlexSignInPanel(
                            plex = state, onStartLink = {}, onCancelLink = {}, onDismissError = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        Shots.save(compose, name)
    }

    @Test fun plexSignIn() = plex(tv.reely.ui.PlexState(), "plex-sign-in")

    @Test fun plexSignInCode() = plex(
        tv.reely.ui.PlexState(linkCode = "K7QM", error = "That code has expired. Try signing in again."),
        "plex-sign-in-code",
    )

    @Test fun plexSignInScan() = plex(
        tv.reely.ui.PlexState(
            linkCode = "K7QM",
            linkUrl = tv.reely.plex.PlexApi.authUrl("0f3c9a2e-reely-tv", "4k7xq2m9vj3p8wz6rt5ynb1hd"),
        ),
        "plex-sign-in-scan",
    )

    @Test fun liveSignIn() = signIn(LiveState(), "live-sign-in")

    @Test fun liveSignInError() =
        signIn(LiveState(error = "Couldn't connect. Check the server address and port."), "live-sign-in-error")
}
