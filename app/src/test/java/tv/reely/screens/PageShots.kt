package tv.reely.screens

import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.onRoot
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import tv.reely.plex.PlexDetail
import tv.reely.plex.PlexRole
import tv.reely.ui.DetailState
import tv.reely.ui.GuideState
import tv.reely.ui.LiveState
import tv.reely.ui.PlayerPrefs
import tv.reely.ui.PlexState
import tv.reely.ui.UpdateStatus
import tv.reely.ui.screens.DetailScreen
import tv.reely.ui.screens.SettingsScreen
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.ReelyTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class PageShots {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() { Shots.onlyWhenAsked(); Shots.syncImages() }

    private val connectedLive = LiveState(
        credentials = tv.reely.xtream.XtreamCredentials("http://line.example.tv:8080", "u", "p"),
        account = tv.reely.xtream.XtreamAccount("Active", "2", "1", "1798761600"),
        categories = List(24) { tv.reely.xtream.XtreamCategory("c$it", "Category $it") },
    )
    private val readyGuide = GuideState(
        status = tv.reely.ui.GuideStatus.Ready(182_000),
        importedAt = System.currentTimeMillis() / 1_000 - 3 * 3_600,
    )

    private fun settings(update: UpdateStatus = UpdateStatus.Idle) = compose.setContent {
        ReelyTheme {
            Shots.RemoteInput()
            Box(Modifier.fillMaxSize().background(Ink)) {
                SettingsScreen(
                    plex = PlexState(baseUrl = "http://server", serverToken = "t", token = "t", serverName = "Living Room"),
                    live = connectedLive, guide = readyGuide, prefs = PlayerPrefs(themeMusic = true),
                    onSignOutPlex = {}, onSignOutXtream = {}, onSwitchServer = {}, onToggleFavourite = {},
                    onToggleFormat = {}, onNudgeSubtitleScale = {}, onToggleSubtitleBackground = {},
                    onNudgeUpNext = {}, onToggleGuidePreview = {}, onCyclePlaybackMode = {},
                    onCycleMaxBitrate = {}, onToggleMultiviewLayout = {}, onToggleThemeMusic = {},
                    onToggleMatchFrameRate = {}, onToggleLargerBuffer = {}, onNudgeThemeVolume = {}, onRefreshChannels = {},
                    onRefreshGuide = {}, update = update,
                    updateUrl = "https://github.com/getreely/reely-tv/releases/latest/download/reely-tv.apk",
                    onCheckForUpdate = {}, onInstallUpdate = {},
                )
            }
        }
    }

    /** Onto a section, then right into its options, as the remote does it. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    private fun openSection(name: String, @Suppress("UNUSED_PARAMETER") focusOn: String = "") {
        compose.onNodeWithText(name).performClick()
        compose.waitForIdle()
        compose.onNodeWithText(name).requestFocus()
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionRight) }
        compose.waitForIdle()
    }

    @Test fun settingsPlayback() {
        settings()
        openSection("Playback")
        Shots.save(compose, "settings-playback")
    }

    @Test fun settingsLive() {
        settings()
        openSection("Live TV", "Refresh TV guide")
        Shots.save(compose, "settings-live")
    }

    @Test fun settingsPlex() {
        settings()
        openSection("Plex", "Sign out of Plex")
        Shots.save(compose, "settings-plex")
    }

    @Test fun settingsUpdates() {
        settings(
            UpdateStatus.Available(
                tv.reely.core.UpdateInfo(url = "", versionCode = 1111, versionName = "0.35.0", notes = null, sizeBytes = 14_400_000, published = null),
            ),
        )
        openSection("Updates", "Download and install")
        Shots.save(compose, "settings-updates")
    }

    @Test fun settingsAbout() {
        settings()
        openSection("About", "FFmpeg")
        Shots.save(compose, "settings-about")
    }

    @Test fun settingsLicence() {
        settings()
        openSection("About")
        compose.onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        compose.waitForIdle()
        Shots.save(compose, "settings-licence")
    }

    @Test fun detailShow() {
        detail()
        compose.onAllNodesWithText("3. Dead Air").onFirst().requestFocus()
        Shots.save(compose, "detail-show")
    }

    private val longSummary =
        "A long-haul driver takes the jobs nobody else will, on roads that don't appear on any map, and starts to " +
            "notice who keeps booking her. Every run comes with a sealed envelope and a delivery window measured to " +
            "the minute, and the clients never meet her at the dock. When a load goes missing outside a weigh station " +
            "in the mountains, Maren has three days to find it before the people who hired her decide she took it. " +
            "Her dispatcher stops answering, her truck is flagged at every border crossing, and the only person " +
            "willing to help is a mechanic who seems to know far more about the cargo than he should. Season two " +
            "follows her south into the desert, where the roads are emptier and the clients more dangerous."

    /** The summary selected: cut at three lines, with the focus panel around it. */
    @Test fun detailSummaryFocused() {
        detail(summary = longSummary)
        compose.onNodeWithText(longSummary).requestFocus()
        compose.waitForIdle()
        Shots.save(compose, "detail-summary-focused")
    }

    /** And opened, a size down so it all fits. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test fun detailSummaryOpen() {
        detail(summary = longSummary)
        compose.onNodeWithText(longSummary).requestFocus()
        compose.waitForIdle()
        compose.onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        compose.waitForIdle()
        Shots.save(compose, "detail-summary-open")
    }

    private fun detail(
        summary: String = "A long-haul driver takes the jobs nobody else will, on roads that don't appear on any map, and starts to notice who keeps booking her.",
    ) {
        val episodes = (1..8).map { i ->
            Shots.item("north").copy(ratingKey = "ep$i", title = listOf("Pilot", "Mile Marker", "Dead Air", "Crosswind", "The Weigh Station", "Chain Control", "Jackknife", "Last Exit")[i - 1], index = i)
        }
        val seasons = (1..3).map { Shots.item("north").copy(ratingKey = "s$it", title = "Season $it", type = "season", index = it) }
        val north = Shots.titles.getValue("north")
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Box(Modifier.fillMaxSize().background(Ink)) {
                    DetailScreen(
                        state = DetailState(
                            ratingKey = "show-north", serverBase = "http://server", busy = false,
                            detail = PlexDetail(
                                ratingKey = "show-north", type = "show", title = "Northbound",
                                summary = summary,
                                tagline = null, year = 2024, durationMs = 0, viewOffsetMs = 0, contentRating = "TV-14",
                                rating = 8.1, audienceRating = 8.6, airDate = null, viewCount = 0, studio = "Harbourside",
                                thumb = "poster/north", art = "backdrop/north", theme = null,
                                genres = listOf("Drama", "Thriller"), directors = emptyList(),
                                roles = listOf("Rae Collins" to "Maren Hale", "Tom Okafor" to "Jude Mercer", "Ines Vidal" to "Carla Ruiz", "Sam Park" to "Owen Pike", "Lena Morse" to "Dee Hart", "Ari Stone" to "Cal Ward")
                                    .map { (who, as_) -> PlexRole(name = who, role = as_, thumb = null) },
                                childCount = 3, leafCount = 24, grandparentTitle = null, index = null, parentIndex = null,
                                logo = "logo/north",
                                qualities = listOf("4K", "Dolby Vision", "5.1"),
                            ),
                            seasons = seasons, selectedSeason = seasons[1], episodes = episodes,
                            focusedEpisode = null,
                        ),
                        imageUrl = { _, path, w, h -> Shots.imageUrl(path, w, h) },
                        backdropUrl = { _, path -> Shots.imageUrl(path, 1280, 720) },
                        logoUrl = { _, path -> Shots.imageUrl(path, 0, 0) },
                        onPlay = {}, onPlayFromStart = {}, onPlayDetail = {}, onPlayDetailFromStart = {},
                        onPlayTrailer = {}, onToggleWatched = {}, onToggleWatchedDetail = {},
                        onFocusEpisode = {}, onSelectSeason = {},
                    )
                }
            }
        }
        check(north.show == "Northbound")
    }
}
