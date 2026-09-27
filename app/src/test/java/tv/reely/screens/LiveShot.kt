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
                    LiveCategoriesScreen(live = live, onSignIn = { _, _, _ -> }, onSignInPlaylist = { _, _ -> }, onSelectCategory = {}, onDismissError = {})
                }
            }
        }
        compose.onNodeWithText("US | Entertainment").requestFocus()
        Shots.save(compose, "live-categories")
    }

    private val channelNames = listOf(
        "BBC One", "BBC Two", "ITV1", "Channel 4", "Sky News", "Sky Sports Main Event",
        "Discovery", "Nat Geo", "Comedy Central", "Film4",
    )
    private val channels = channelNames.mapIndexed { i, name ->
        tv.reely.xtream.XtreamChannel(streamId = 100 + i, number = i + 1, name = name, icon = null, epgChannelId = "ch$i")
    }

    /** The TV guide: channels down the side, their programmes across time. */
    @Test fun guide() {
        val now = 1_790_000_000L - (1_790_000_000L % 1_800)
        val shows = listOf(
            "Breakfast", "Homes Under the Hammer", "The Chase", "News at One", "Garden Rescue",
            "Countdown", "Pointless", "Match of the Day", "Grand Designs", "The Repair Shop",
        )
        val programmes = channels.mapIndexed { c, channel ->
            var start = now - 1_200 - c * 300L
            channel.epgChannelId!! to List(8) { p ->
                val length = listOf(1_800L, 3_600L, 2_700L)[(c + p) % 3]
                tv.reely.xtream.EpgProgramme(
                    channelId = channel.epgChannelId!!,
                    start = start, stop = start + length,
                    title = shows[(c * 3 + p) % shows.size],
                    description = "An hour of the best of it, with guests.",
                ).also { start += length }
            }
        }.toMap()
        val live = LiveState(
            credentials = XtreamCredentials("http://panel", "u", "p"),
            account = XtreamAccount(status = "Active", maxConnections = "2", activeConnections = "0", expiresAt = null),
            categories = listOf(XtreamCategory(id = "c0", name = "UK | Entertainment")),
            selectedCategory = XtreamCategory(id = "c0", name = "UK | Entertainment"),
            channels = channels,
        )
        val guide = tv.reely.ui.GuideState(
            status = tv.reely.ui.GuideStatus.Ready(programmes.values.sumOf { it.size }),
            programmes = programmes,
            windowStart = now - 1_800, windowEnd = now + 3 * 3_600,
            focusTime = now + 60, channelIndex = 2,
            importedAt = now - 3_600,
        )
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val livePlayer = tv.reely.core.LivePlayer(context)
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Box(Modifier.fillMaxSize().background(Ink)) {
                    tv.reely.ui.screens.GuideScreen(
                        live = live, guide = guide, previewEnabled = false,
                        onMoveChannel = {}, onMoveTime = {}, onJumpToNow = {}, onRefresh = {},
                        onPlaySelected = {}, onBackToCategories = {}, livePlayer = livePlayer,
                    )
                }
            }
        }
        Shots.save(compose, "live-guide")
    }

    /** Search: channels that match, then titles from the library. */
    @Test fun search() {
        val results = listOf("north", "harbor", "shift", "ember", "field", "orbit").map(Shots::item)
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Box(Modifier.fillMaxSize().background(Ink)) {
                    tv.reely.ui.screens.SearchScreen(
                        search = tv.reely.ui.SearchState(query = "the", results = results, channels = channels.take(4)),
                        focused = results[0],
                        imageUrl = { _, path, w, h -> Shots.imageUrl(path, w, h) },
                        backdropUrl = { _, path -> Shots.imageUrl(path, 1280, 720) },
                        onQueryChange = {}, onFocusItem = {}, onOpenItem = {}, onPlayChannel = {},
                    )
                }
            }
        }
        Shots.save(compose, "search")
    }
}
