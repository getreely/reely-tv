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
import tv.reely.requests.RequestDetail
import tv.reely.requests.RequestRecord
import tv.reely.requests.RequestRow
import tv.reely.requests.RequestSeason
import tv.reely.requests.RequestTitle
import tv.reely.ui.RequestDetailState
import tv.reely.ui.RequestsState
import tv.reely.ui.Route
import tv.reely.ui.TopBar
import tv.reely.ui.screens.RequestTitleScreen
import tv.reely.ui.screens.RequestsScreen
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.ReelyTheme

/** The Request tab: connecting to Reely, its rows, and a show's page with seasons to pick. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class RequestShots {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() { Shots.onlyWhenAsked(); Shots.syncImages() }

    private fun title(key: String, kind: String = "movie", id: Int = key.hashCode() and 0xffff): RequestTitle {
        val t = Shots.titles.getValue(key)
        return RequestTitle(
            kind = kind, tmdbId = id, tvdbId = 0,
            title = t.show ?: t.title, year = 2024, overview = t.summary,
            poster = Shots.imageUrl("poster/$key", 300, 450), posterPath = "/$key.jpg",
        )
    }

    private fun framed(content: @androidx.compose.runtime.Composable () -> Unit) {
        val tabFocus = List(6) { FocusRequester() }
        val settingsFocus = FocusRequester()
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Column(Modifier.fillMaxSize().background(Ink)) {
                    TopBar(
                        current = Route.Requests, onNavigate = {}, onActivate = {}, onTabFocused = {},
                        onTabPositioned = { _, _ -> }, tabFocus = tabFocus, settingsFocus = settingsFocus,
                        canSelectOnFocus = { false },
                    )
                    Box(Modifier.fillMaxWidth().weight(1f)) { content() }
                }
            }
        }
    }

    @Test fun connect() {
        framed {
            RequestsScreen(RequestsState(), onConnect = {}, onDismissError = {}, onQueryChange = {}, onOpen = {})
        }
        Shots.save(compose, "requests-connect")
    }

    @Test fun rows() {
        val films = listOf("orbit", "ember", "field", "glass", "ferry", "cardinal").map { title(it) }
        val shows = listOf("north", "harbor", "shift", "quiet", "salt").map { title(it, "show") }
        framed {
            RequestsScreen(
                RequestsState(
                    server = "http://reely.local", connected = true,
                    rows = listOf(RequestRow("movies", "Trending Movies", films), RequestRow("shows", "Trending Shows", shows)),
                    mine = listOf(
                        RequestRecord(9, shows[1], "pending", listOf(1)),
                        RequestRecord(4, films[2], "approved", null),
                    ),
                ),
                onConnect = {}, onDismissError = {}, onQueryChange = {}, onOpen = {},
            )
        }
        Shots.save(compose, "requests-rows")
    }

    @Test fun showPage() {
        val show = title("north", "show")
        framed {
            RequestTitleScreen(
                page = RequestDetailState(
                    title = show, busy = false,
                    detail = RequestDetail(
                        title = show, backdrop = Shots.imageUrl("backdrop/north", 1280, 720),
                        genres = listOf("Drama", "Thriller"), runtime = null, status = "Returning Series",
                        seasons = (1..4).map { RequestSeason(it, "Season $it", 8) }, inLibrary = false,
                    ),
                    chosen = setOf(1, 3),
                    places = tv.reely.requests.RequestPlaces(
                        libraries = listOf(
                            tv.reely.requests.RequestLibrary(1, "TV Shows", "shows"),
                            tv.reely.requests.RequestLibrary(2, "Kids TV", "shows"),
                        ),
                        defaultLibraryId = 1,
                        adds = false,
                    ),
                    libraryId = 1,
                ),
                status = null, onToggleSeason = {}, onToggleAll = {}, onRequest = {},
            )
        }
        Shots.save(compose, "request-show")
    }
}
