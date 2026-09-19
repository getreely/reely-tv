package tv.reely.ui.screens

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.reely.core.cycleWithOff
import tv.reely.ui.Playback
import tv.reely.ui.components.FactLine
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Parchment

private data class Diagnostics(
    val video: String? = null,
    val audio: String? = null,
    val bufferedMs: Long = 0,
    val state: String = "idle",
)

/**
 * The spike's instrument. Playback is the point, but the overlay is what answers the two
 * questions that cannot be reasoned about: whether these streams decode on this hardware,
 * and how long a channel change takes.
 */
@Composable
fun PlayerScreen(
    playback: Playback,
    onExit: () -> Unit,
    onStepChannel: (Int) -> Unit,
    onToggleFormat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setLoadControl(
                // A small start buffer: channel-switch latency is what separates good from bad here.
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(2_000, 30_000, 1_000, 2_000)
                    .build()
            )
            .build()
    }

    var startedAt by remember { mutableLongStateOf(0L) }
    var firstFrameMs by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var diagnostics by remember { mutableStateOf(Diagnostics()) }
    var overlayNonce by remember { mutableIntStateOf(0) }
    var overlayVisible by remember { mutableStateOf(true) }
    // -1 is "off"; anything else indexes the text track groups the player currently sees.
    var subtitleChoice by remember { mutableIntStateOf(-1) }
    var subtitleLabel by remember { mutableStateOf("Off") }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameMs = SystemClock.elapsedRealtime() - startedAt
            }

            override fun onPlayerError(playbackError: PlaybackException) {
                error = describe(playbackError)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    LaunchedEffect(playback.url) {
        error = null
        firstFrameMs = null
        subtitleChoice = -1
        startedAt = SystemClock.elapsedRealtime()
        overlayNonce++
        exoPlayer.setMediaItem(buildMediaItem(playback))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            diagnostics = Diagnostics(
                video = exoPlayer.videoFormat?.let { format ->
                    buildString {
                        append("${format.width}×${format.height}")
                        format.sampleMimeType?.substringAfter('/')?.let { append(" · $it") }
                        if (format.frameRate > 0f) append(" · ${format.frameRate.toInt()} fps")
                    }
                },
                audio = exoPlayer.audioFormat?.let { format ->
                    buildString {
                        format.sampleMimeType?.substringAfter('/')?.let { append(it) }
                        if (format.channelCount > 0) append(" · ${format.channelCount}ch")
                    }
                },
                bufferedMs = exoPlayer.totalBufferedDuration,
                state = when (exoPlayer.playbackState) {
                    Player.STATE_IDLE -> "idle"
                    Player.STATE_BUFFERING -> "buffering"
                    Player.STATE_READY -> if (exoPlayer.isPlaying) "playing" else "paused"
                    Player.STATE_ENDED -> "ended"
                    else -> "unknown"
                },
            )
            delay(500)
        }
    }

    // Any interaction re-shows the overlay; it then fades itself out.
    LaunchedEffect(overlayNonce) {
        overlayVisible = true
        delay(5_000)
        overlayVisible = false
    }

    BackHandler(onBack = onExit)

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                overlayNonce++
                when (event.key) {
                    // Live: up and down surf channels. On-demand: they cycle subtitle tracks.
                    Key.DirectionUp -> {
                        if (playback.isLive) {
                            onStepChannel(-1)
                        } else {
                            val (label, index) = applySubtitleChoice(exoPlayer, subtitleChoice - 1)
                            subtitleLabel = label
                            subtitleChoice = index
                        }
                        true
                    }

                    Key.DirectionDown -> {
                        if (playback.isLive) {
                            onStepChannel(1)
                        } else {
                            val (label, index) = applySubtitleChoice(exoPlayer, subtitleChoice + 1)
                            subtitleLabel = label
                            subtitleChoice = index
                        }
                        true
                    }

                    Key.DirectionRight -> {
                        if (playback.isLive) onToggleFormat() else exoPlayer.seekForward()
                        true
                    }

                    Key.DirectionLeft -> {
                        if (!playback.isLive) exoPlayer.seekBack()
                        !playback.isLive
                    }

                    Key.DirectionCenter, Key.Enter, Key.MediaPlayPause -> {
                        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        true
                    }

                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { viewContext ->
                PlayerView(viewContext).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    player = exoPlayer
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (overlayVisible || error != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 40.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = playback.title,
                    color = Parchment,
                    fontSize = 26.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                playback.subtitle?.let {
                    Text(text = it, color = Faint, fontSize = 14.sp, maxLines = 1)
                }

                error?.let { message ->
                    Box(
                        modifier = Modifier
                            .widthIn(max = 900.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Accent.copy(alpha = 0.22f))
                            .padding(12.dp),
                    ) {
                        Text(text = message, color = Parchment, fontSize = 14.sp)
                    }
                }

                FactLine("State", diagnostics.state)
                FactLine(
                    "Time to first frame",
                    firstFrameMs?.let { "$it ms" } ?: "—",
                )
                FactLine("Video", diagnostics.video ?: "—")
                FactLine("Audio", diagnostics.audio ?: "—")
                FactLine("Buffered", "${diagnostics.bufferedMs / 1000} s")
                if (playback.isLive) {
                    FactLine("Container", playback.format.label)
                } else {
                    FactLine("Subtitles", subtitleLabel)
                }

                Text(
                    text = if (playback.isLive)
                        "OK play/pause · up/down changes channel · right switches TS ↔ HLS · Back to leave"
                    else
                        "OK play/pause · left/right seeks · up/down cycles subtitles · Back to leave",
                    color = Faint,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * Sidecar subtitles ride alongside the video as separate tracks. Anything embedded in the
 * container that ExoPlayer can read shows up in the same track list for free.
 */
private fun buildMediaItem(playback: Playback): MediaItem =
    MediaItem.Builder()
        .setUri(playback.url)
        .setSubtitleConfigurations(
            playback.subtitles.map { subtitle ->
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(subtitle.url))
                    .setMimeType(subtitle.mimeType)
                    .setLanguage(subtitle.language)
                    .setLabel(subtitle.label)
                    .build()
            }
        )
        .build()

/**
 * Steps through the text tracks the player can actually see — sideloaded and embedded
 * alike — with "off" as one more position in the cycle. Returns the label to show and the
 * index that was settled on.
 */
private fun applySubtitleChoice(player: ExoPlayer, requested: Int): Pair<String, Int> {
    val groups = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
    if (groups.isEmpty()) return "None available" to -1

    val choice = cycleWithOff(requested, groups.size)

    val parameters = player.trackSelectionParameters.buildUpon()
    if (choice < 0) {
        player.trackSelectionParameters = parameters
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        return "Off" to -1
    }

    val group = groups[choice]
    player.trackSelectionParameters = parameters
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        .build()

    val format = group.getTrackFormat(0)
    val label = format.label ?: format.language ?: "Track ${choice + 1}"
    return label to choice
}

/**
 * A refused stream must say so. Providers cap simultaneous connections, and a dead player
 * looks exactly like a broken app.
 */
private fun describe(error: PlaybackException): String = when (val cause = error.cause) {
    is HttpDataSource.InvalidResponseCodeException -> when (cause.responseCode) {
        401, 403 -> "The provider refused this stream (HTTP ${cause.responseCode}). Either these " +
            "credentials aren't valid for it, or every connection your subscription allows is already in use."

        404 -> "The provider says this stream doesn't exist (HTTP 404)."
        else -> "The provider returned HTTP ${cause.responseCode}."
    }

    is HttpDataSource.HttpDataSourceException ->
        "Couldn't reach the stream: ${cause.message ?: "no response"}"

    else -> error.errorCodeName + (error.message?.let { " — $it" } ?: "")
}
