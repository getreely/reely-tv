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
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.requestFocus
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.reely.plex.PlexGenre
import tv.reely.ui.HomeState
import tv.reely.ui.IptvLibrary
import tv.reely.ui.IptvState
import tv.reely.ui.LibraryKind
import tv.reely.ui.LibraryView
import tv.reely.ui.PlexState
import tv.reely.ui.Route
import tv.reely.ui.TopBar
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.ReelyTheme
import tv.reely.xtream.IPTV_SOURCE

/** The IPTV provider's films in the Movies tab: its own grid, and a row on the tab's home. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class IptvShots {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() { Shots.onlyWhenAsked(); Shots.syncImages() }

    private val films = listOf("ember", "field", "orbit", "glass", "ferry", "cardinal", "shift", "quiet", "salt")
        .map { key ->
            Shots.item(key).copy(
                ratingKey = "m$key.mkv", type = "movie", serverBase = IPTV_SOURCE,
                grandparentTitle = null, viewOffsetMs = 0, qualities = listOf("EN"),
            )
        }

    private fun show(view: LibraryView, name: String, home: HomeState = HomeState()) {
        val tabFocus = List(5) { FocusRequester() }
        val settingsFocus = FocusRequester()
        val grid = IptvLibrary.emptyBrowse(LibraryKind.MOVIES).copy(
            items = films.sortedBy { it.title },
            genres = listOf(PlexGenre("1", "Action"), PlexGenre("2", "Drama"), PlexGenre("3", "4K Movies")),
        )
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Column(Modifier.fillMaxSize().background(Ink)) {
                    TopBar(
                        current = Route.Library(LibraryKind.MOVIES, view), onNavigate = {}, onActivate = {},
                        onTabFocused = {}, onTabPositioned = { _, _ -> }, tabFocus = tabFocus,
                        settingsFocus = settingsFocus, canSelectOnFocus = { false },
                    )
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        tv.reely.ui.screens.LibraryScreen(
                            kind = LibraryKind.MOVIES,
                            view = view,
                            plex = PlexState(baseUrl = "http://server", serverToken = "t", token = "t", serverName = "Living Room"),
                            home = home,
                            focused = films[2],
                            imageUrl = { _, path, w, h -> Shots.imageUrl(path, w, h) },
                            backdropUrl = { _, path -> Shots.imageUrl(path, 1280, 720) },
                            onFocusItem = {}, onOpenItem = {}, onStartLink = {}, onCancelLink = {},
                            onDismissPlexError = {}, onSetSort = {}, onToggleUnwatched = {},
                            onSelectGenre = {}, onDismissBrowseError = {},
                            iptv = IptvState(on = true, movies = grid, movieCount = films.size),
                        )
                    }
                }
            }
        }
        compose.onAllNodesWithText(films[2].title).onLast().requestFocus()
        Shots.save(compose, name)
    }

    @Test fun grid() = show(LibraryView.IPTV, "iptv-movies-grid")

    @Test fun tabHome() = show(
        LibraryView.HOME,
        "iptv-movies-tab-home",
        home = HomeState(continueWatching = listOf(Shots.item("north"), films[4].copy(viewOffsetMs = 1_800_000)), iptvMovies = films),
    )
}
