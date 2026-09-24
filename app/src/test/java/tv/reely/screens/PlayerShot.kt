package tv.reely.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import tv.reely.ui.components.TvActionButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.junit4.createComposeRule
import coil.compose.AsyncImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.reely.ui.Playback
import tv.reely.ui.screens.Controls
import tv.reely.ui.theme.ReelyTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class PlayerShot {
    @get:Rule val compose = createComposeRule()

    @Before fun setUp() { Shots.onlyWhenAsked(); Shots.syncImages() }

    /** The transport over a frame of the film, with focus on play, as it arrives. */
    @Test fun controls() = shot("player-controls") { play, _ -> play.requestFocus() }

    /**
     * Paused with Skip Intro up: the button sits on top of the transport, at the height
     * the player measures it to be, and keeps the focus.
     */
    @Test fun skipOverControls() = shot("player-skip", skip = true) { _, _ -> skipFocus.requestFocus() }

    private val skipFocus = FocusRequester()

    /** The same with focus moved up onto the bar, as it is while scrubbing. */
    @Test fun scrubbing() = shot("player-scrubbing") { _, scrubber -> scrubber.requestFocus() }

    private fun shot(
        name: String,
        skip: Boolean = false,
        focus: (play: FocusRequester, scrubber: FocusRequester) -> Unit,
    ) {
        val play = FocusRequester()
        val scrubber = FocusRequester()
        compose.setContent {
            var height by remember { mutableIntStateOf(0) }
            ReelyTheme {
                Shots.RemoteInput()
                Box(Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = Shots.imageUrl("backdrop/north", 1280, 720),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Controls(
                        playback = Playback(title = "The Weigh Station", subtitle = "Northbound  ·  S2 · E5", url = "", isLive = false, durationMs = 42 * 60_000L),
                        playing = !skip,
                        positionMs = 24 * 60_000L + 7_000,
                        durationMs = 42 * 60_000L,
                        bufferedMs = 27 * 60_000L,
                        canSkipBack = true,
                        canSkipForward = true,
                        playFocus = play,
                        scrubberFocus = scrubber,
                        onScrubberFocus = {},
                        onSeek = {}, onSkip = {}, onTogglePlay = {}, onAddChannel = {},
                        onOpenSubtitles = {}, onOpenAudio = {}, onOpenStats = {}, onToggleFormat = {},
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .onSizeChanged { height = it.height },
                    )
                    if (skip) {
                        TvActionButton(
                            label = "Skip Intro",
                            onClick = {},
                            emphasised = true,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 48.dp, bottom = with(LocalDensity.current) { height.toDp() })
                                .focusRequester(skipFocus),
                        )
                    }
                }
            }
        }
        compose.runOnIdle { focus(play, scrubber) }
        Shots.save(compose, name)
    }
}
