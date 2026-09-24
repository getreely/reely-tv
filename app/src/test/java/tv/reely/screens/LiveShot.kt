package tv.reely.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.requestFocus
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.reely.ui.LiveState
import tv.reely.ui.screens.LiveCategoriesScreen
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.ReelyTheme
import tv.reely.xtream.XtreamAccount
import tv.reely.xtream.XtreamCategory
import tv.reely.xtream.XtreamCredentials

/** The Live TV categories, one of them focused. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class LiveShot {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() { Shots.onlyWhenAsked() }

    @Test fun categories() {
        val names = listOf(
            "UK | Entertainment", "UK | Sport", "UK | News", "US | Entertainment",
            "US | Sports", "Movies 24/7", "Kids", "Documentaries", "Music",
            "Canada", "Ireland", "Australia | Sport",
        )
        val live = LiveState(
            credentials = XtreamCredentials("http://panel", "u", "p"),
            account = XtreamAccount(status = "Active", maxConnections = "2", activeConnections = "0", expiresAt = null),
            categories = names.mapIndexed { i, name -> XtreamCategory(id = "c$i", name = name) },
        )
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Box(Modifier.fillMaxSize().background(Ink)) {
                    LiveCategoriesScreen(live = live, onSignIn = { _, _, _ -> }, onSelectCategory = {}, onDismissError = {})
                }
            }
        }
        compose.onNodeWithText("US | Entertainment").requestFocus()
        Shots.save(compose, "live-categories")
    }
}
