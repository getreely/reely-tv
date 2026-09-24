package tv.reely.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.focus.focusRequester
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

    /** Paused with Skip Intro up: it sits in the title's row, and keeps the focus. */
    @Test fun skipInControls() = shot("player-skip", skip = true) { _, _ -> skipFocus.requestFocus() }

    /** Skip Intro with the controls away: on its own, low in the corner. */
    @Test fun skipAlone() {
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
                    TvActionButton(
                        label = "Skip Intro",
                        onClick = {},
                        emphasised = true,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 48.dp, bottom = 27.dp)
                            .focusRequester(skipFocus),
                    )
                }
            }
        }
        compose.runOnIdle { skipFocus.requestFocus() }
        Shots.save(compose, "player-skip-alone")
    }

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
                        skipLabel = if (skip) "Skip Intro" else null,
                        skipFocus = skipFocus,
                        modifier = Modifier.align(Alignment.BottomStart),
                    )
                }
            }
        }
        compose.runOnIdle { focus(play, scrubber) }
        Shots.save(compose, name)
    }
}
