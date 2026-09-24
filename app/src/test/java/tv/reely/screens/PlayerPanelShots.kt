package tv.reely.screens

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import coil.compose.AsyncImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.reely.ui.Playback
import tv.reely.ui.PlayerPrefs
import tv.reely.ui.screens.Panel
import tv.reely.ui.screens.StatsPanel
import tv.reely.ui.screens.TileMenu
import tv.reely.ui.screens.TrackPanel
import tv.reely.ui.screens.UpNextCard
import tv.reely.ui.theme.ReelyTheme

/** What opens over the picture: the track and stats panels, the tile menu, up next. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class PlayerPanelShots {
    @get:Rule val compose = createComposeRule()
    private lateinit var player: ExoPlayer

    @Before fun setUp() {
        Shots.onlyWhenAsked()
        Shots.syncImages()
        player = ExoPlayer.Builder(ApplicationProvider.getApplicationContext<Context>()).build()
    }

    @After fun tearDown() { if (::player.isInitialized) player.release() }

    private val playback = Playback(
        title = "The Weigh Station", subtitle = "Northbound  ·  S2 · E5", url = "",
        isLive = false, durationMs = 42 * 60_000L,
    )

    private fun overPicture(focus: FocusRequester, content: @Composable () -> Unit) {
        compose.setContent {
            ReelyTheme {
                Shots.RemoteInput()
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = Shots.imageUrl("backdrop/north", 1280, 720),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    content()
                }
            }
        }
        compose.runOnIdle { focus.requestFocus() }
    }

    @Test fun stats() {
        val focus = FocusRequester()
        overPicture(focus) {
            Box(Modifier.fillMaxSize()) {
                StatsPanel(playback, player, focus, onClose = {}, modifier = Modifier.align(Alignment.CenterEnd))
            }
        }
        Shots.save(compose, "player-stats")
    }

    @Test fun subtitles() {
        val focus = FocusRequester()
        overPicture(focus) {
            Box(Modifier.fillMaxSize()) {
                TrackPanel(
                    panel = Panel.SUBTITLES, player = player, prefs = PlayerPrefs(), tracksVersion = 0,
                    focusRequester = focus, onClose = {}, onNudgeScale = {}, onToggleBackground = {},
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
        Shots.save(compose, "player-subtitles")
    }

    @Test fun tileMenu() {
        val focus = FocusRequester()
        overPicture(focus) {
            Box(Modifier.fillMaxSize()) {
                TileMenu(
                    name = "BBC One", focusRequester = focus, canClose = true, canAdd = true,
                    canMaximize = true, onMaximize = {}, onAdd = {}, onReplace = {}, onClose = {},
                    onCancel = {}, modifier = Modifier.align(Alignment.Center),
                )
            }
        }
        Shots.save(compose, "player-tile-menu")
    }

    @Test fun upNext() {
        val focus = FocusRequester()
        overPicture(focus) {
            Box(Modifier.fillMaxSize()) {
                UpNextCard(
                    item = Shots.item("north"), countdownSeconds = 0, onPlay = {}, onDismiss = {},
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }
        Shots.save(compose, "player-up-next")
    }
}
