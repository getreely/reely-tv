package tv.reely.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.requestFocus
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.reely.ui.EpisodeGroup
import tv.reely.ui.HomeState
import tv.reely.ui.PlexState
import tv.reely.ui.Route
import tv.reely.ui.TopBar
import tv.reely.ui.screens.HomeScreen
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.ReelyTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class HomeShot {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() { Shots.onlyWhenAsked(); Shots.syncImages() }

    @Test fun home() {
        val cw = listOf("north", "harbor", "shift", "quiet", "salt").map(Shots::item)
        val movies = listOf("ember", "field", "orbit", "glass", "ferry", "cardinal").map(Shots::item)
        val groups = listOf("north", "harbor", "shift").map { key ->
            val item = Shots.item(key)
            EpisodeGroup(
                showTitle = item.grandparentTitle!!, showRatingKey = item.grandparentRatingKey,
                thumb = item.grandparentThumb, newest = item, count = 2, addedAt = 0,
                librarySectionId = "1", serverBase = item.serverBase,
            )
        }
        // Made outside the composition: a focus handle belongs to whoever holds it.
        val tabFocus = List(5) { FocusRequester() }
        val settingsFocus = FocusRequester()
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Column(Modifier.fillMaxSize().background(Ink)) {
                    TopBar(
                        current = Route.Home, onNavigate = {}, onActivate = {}, onTabFocused = {},
                        onTabPositioned = { _, _ -> }, tabFocus = tabFocus,
                        settingsFocus = settingsFocus, canSelectOnFocus = { false },
                        serverName = "Living Room",
                    )
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        HomeScreen(
                            plex = PlexState(baseUrl = "http://server", serverToken = "t", token = "t", serverName = "Living Room"),
                            home = HomeState(continueWatching = cw, recentEpisodes = groups, recentMovies = movies),
                            focused = cw.first(),
                            imageUrl = { _, path, w, h -> Shots.imageUrl(path, w, h) },
                            backdropUrl = { _, path -> Shots.imageUrl(path, 1280, 720) },
                            onFocusItem = {}, onOpenItem = {}, onPlayItem = { _, _ -> }, onToggleWatched = {},
                            onStartLink = {}, onCancelLink = {}, onDismissPlexError = {},
                        )
                    }
                }
            }
        }
        compose.onAllNodesWithText("Harbor Lights").onFirst().requestFocus()
        Shots.save(compose, "home")
    }
}
