package tv.reely.touch

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.reely.plex.PlexDetail
import tv.reely.plex.PlexRole
import tv.reely.screens.Shots
import tv.reely.ui.BrowseState
import tv.reely.ui.DetailState
import tv.reely.ui.HomeState
import tv.reely.ui.LibraryKind
import tv.reely.ui.LibraryView
import tv.reely.ui.LiveState
import tv.reely.ui.PlexState
import tv.reely.ui.ReelyViewModel
import tv.reely.ui.Route
import tv.reely.ui.touch.TouchApp
import tv.reely.xtream.XtreamAccount
import tv.reely.xtream.XtreamCategory
import tv.reely.xtream.XtreamChannel
import tv.reely.xtream.XtreamCredentials
import tv.reely.xtream.XtreamProgramme

/** The phone app's screens, drawn at a phone's size for looking over. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class TouchShots {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var model: ReelyViewModel

    @Before fun setUp() {
        Shots.onlyWhenAsked()
        Shots.syncImages()
        FakeKeyStore.install()
        model = ReelyViewModel(ApplicationProvider.getApplicationContext())
    }

    private val server = TouchAppTest.SERVER
    private fun item(key: String) = Shots.item(key).copy(serverBase = server)
    private fun show(key: String) = item(key).let { it.copy(type = "show", title = Shots.titles.getValue(key).show ?: it.title, viewOffsetMs = 0) }

    private fun signedIn(stack: List<Route> = listOf(Route.Home), change: (tv.reely.ui.ReelyState) -> tv.reely.ui.ReelyState = { it }) =
        model.setStateForTest {
            change(
                it.copy(
                    plex = PlexState(token = "t", baseUrl = server, serverToken = "t", serverName = "Living Room"),
                    home = HomeState(
                        continueWatching = listOf(item("north"), item("harbor"), item("ember")),
                        recentMovies = listOf("orbit", "glass", "ferry", "cardinal").map(::item),
                        recentEpisodes = emptyList(),
                        iptvMovies = listOf("field", "salt").map { key -> item(key).copy(serverBase = tv.reely.xtream.IPTV_SOURCE, viewOffsetMs = 0) },
                    ),
                    stack = stack,
                    restoring = false,
                ),
            )
        }

    private fun draw() = compose.setContent { TouchApp(model, imageUrl = { _, path, w, h -> Shots.imageUrl(path, w, h) }) }

    @Test fun home() {
        signedIn()
        draw()
        Shots.save(compose, "phone-home")
    }

    @Test fun signIn() {
        draw()
        Shots.save(compose, "phone-sign-in")
    }

    @Test fun moviesGrid() {
        signedIn(listOf(Route.Library(LibraryKind.MOVIES, LibraryView.GRID))) { state ->
            state.copy(
                plex = state.plex.copy(
                    sections = listOf(tv.reely.plex.PlexSection("1", "Movies", "movie")),
                    browse = mapOf(
                        LibraryKind.MOVIES to BrowseState(
                            section = tv.reely.plex.PlexSection("1", "Movies", "movie"),
                            items = listOf("ember", "field", "orbit", "glass", "ferry", "cardinal", "shift", "quiet", "salt").map(::item).map { it.copy(type = "movie", grandparentTitle = null) },
                            genres = listOf(tv.reely.plex.PlexGenre("1", "Drama"), tv.reely.plex.PlexGenre("2", "Thriller")),
                        ),
                        LibraryKind.SHOWS to BrowseState(),
                    ),
                ),
            )
        }
        draw()
        Shots.save(compose, "phone-movies-grid")
    }

    @Test @Config(qualifiers = PHONE_LANDSCAPE) fun showPageSideways() = showPage("phone-show-page-landscape")

    @Test @Config(qualifiers = PHONE_LANDSCAPE) fun homeSideways() {
        signedIn()
        draw()
        Shots.save(compose, "phone-home-landscape")
    }

    @Test fun showPage() = showPage("phone-show-page")

    private fun showPage(name: String) {
        val episodes = (1..6).map { i ->
            item("north").copy(ratingKey = "ep$i", title = listOf("Pilot", "Mile Marker", "Dead Air", "Crosswind", "The Weigh Station", "Chain Control")[i - 1], index = i, viewOffsetMs = if (i == 3) 1_200_000 else 0, viewCount = if (i < 3) 1 else 0)
        }
        val seasons = (1..3).map { item("north").copy(ratingKey = "s$it", title = "Season $it", type = "season", index = it) }
        signedIn(listOf(Route.Home, Route.Detail("show-north", serverBase = server))) { state ->
            state.copy(
                detail = DetailState(
                    ratingKey = "show-north", serverBase = server, busy = false,
                    detail = PlexDetail(
                        ratingKey = "show-north", type = "show", title = "Northbound",
                        summary = "A long-haul driver takes the jobs nobody else will, on roads that don't appear on any map.",
                        tagline = null, year = 2024, durationMs = 0, viewOffsetMs = 0, contentRating = "TV-14",
                        rating = 8.1, audienceRating = 8.6, airDate = "2024-03-03", viewCount = 0, studio = "Harbourside",
                        thumb = "poster/north", art = "backdrop/north", theme = null,
                        genres = listOf("Drama", "Thriller"), directors = listOf("Ana Reyes"),
                        roles = listOf("Rae Collins" to "Maren Hale", "Tom Okafor" to "Jude Mercer", "Ines Vidal" to "Carla Ruiz")
                            .map { (who, as_) -> PlexRole(name = who, role = as_, thumb = null, id = who) },
                        childCount = 3, leafCount = 24, grandparentTitle = null, index = null, parentIndex = null,
                        qualities = listOf("4K", "5.1"),
                    ),
                    seasons = seasons, selectedSeason = seasons[0], episodes = episodes,
                    related = listOf("harbor", "shift", "quiet").map(::show),
                ),
            )
        }
        draw()
        Shots.save(compose, name)
    }

    @Test fun liveChannels() {
        val now = System.currentTimeMillis() / 1000
        val channels = listOf("News 24", "Sports One", "Cinema Max", "Kids Zone", "Nature HD").mapIndexed { i, name ->
            XtreamChannel(streamId = i + 1, number = 101 + i, name = name, icon = null, epgChannelId = null, archiveDays = if (i == 0) 3 else 0)
        }
        signedIn(listOf(Route.Live)) { state ->
            state.copy(
                live = LiveState(
                    credentials = XtreamCredentials("http://provider", "u", "p"),
                    account = XtreamAccount("Active", "2", "0", null),
                    categories = listOf(XtreamCategory("1", "Entertainment")),
                    selectedCategory = XtreamCategory("1", "Entertainment"),
                    channels = channels,
                    guide = channels.associate { c ->
                        c.streamId to listOf(
                            XtreamProgramme("The Evening Report", null, now - 1_200, now + 600),
                            XtreamProgramme("Late Edition", null, now + 600, now + 4_200),
                        )
                    },
                    favorites = setOf(2),
                ),
            )
        }
        draw()
        Shots.save(compose, "phone-live-channels")
    }

    @Test fun settings() {
        signedIn(listOf(Route.Home, Route.Settings))
        draw()
        Shots.save(compose, "phone-settings")
        compose.onNodeWithText("Playback").performClick()
        Shots.save(compose, "phone-settings-playback")
    }
}
