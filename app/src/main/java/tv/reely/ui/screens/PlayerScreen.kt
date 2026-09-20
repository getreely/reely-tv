package tv.reely.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.reely.core.Settings
import tv.reely.plex.PlexItem
import tv.reely.ui.GuideState
import tv.reely.ui.LiveState
import tv.reely.ui.PlayerPrefs
import tv.reely.ui.Playback
import tv.reely.ui.components.PauseGlyph
import tv.reely.ui.components.PlayGlyph
import tv.reely.ui.components.SkipGlyph
import tv.reely.ui.components.SpeakerGlyph
import tv.reely.ui.components.SubtitleGlyph
import tv.reely.ui.components.TransportButton
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.components.TvListRow
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Line
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Parchment
import tv.reely.ui.theme.SurfaceRaised

private const val SEEK_STEP_MS = 10_000L
private const val CONTROLS_TIMEOUT_MS = 6_000L

/** Roughly half a second of frames, which is far longer than a layout pass needs. */
private const val FOCUS_ATTEMPTS = 16
private const val FOCUS_RETRY_MS = 32L

private enum class Panel { NONE, SUBTITLES, AUDIO }

private data class TrackChoice(
    val label: String,
    val group: Tracks.Group?,
    val selected: Boolean,
)

@Composable
fun PlayerScreen(
    playback: Playback,
    prefs: PlayerPrefs,
    live: LiveState,
    guide: GuideState,
    upNext: PlexItem?,
    onExit: (Long) -> Unit,
    onEnded: () -> Unit,
    onPlayUpNext: () -> Unit,
    onDismissUpNext: () -> Unit,
    onStepChannel: (Int) -> Unit,
    onSelectChannel: (Int) -> Unit,
    onStepEpisode: (Int) -> Unit,
    onDecodeFailure: (Long) -> Unit,
    onToggleFormat: () -> Unit,
    onReportProgress: (Long, Boolean) -> Unit,
    onNudgeSubtitleScale: (Float) -> Unit,
    onToggleSubtitleBackground: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context)
            .setLoadControl(
                // A small start buffer: channel-switch latency is what separates good from bad.
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(2_000, 30_000, 1_000, 2_000)
                    .build()
            )
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                // Hand audio focus to whatever takes it — an alarm, a voice assistant,
                // another app's video — instead of playing underneath it.
                /* handleAudioFocus = */ true,
            )
            .build()
            .apply {
                setWakeMode(C.WAKE_MODE_NETWORK)
                // Subtitles start off. Left alone, the track selector turns them on by
                // itself whenever a track matches the device language or is flagged
                // default or forced, which is not what anyone asked for. Choosing one in
                // the panel re-enables them, and that choice then carries to whatever is
                // played next, which is what a client should do.
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
    }

    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tracksVersion by remember { mutableIntStateOf(0) }

    var controlsVisible by remember { mutableStateOf(true) }
    // The scrubber is the top of the control bar, so this is "there is nothing above
    // here to move to" — which is what makes another press of up mean "put these away".
    var atTopOfControls by remember { mutableStateOf(false) }
    // The guide, raised over a playing channel. Mutually exclusive with the controls.
    var guideOpen by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf(Panel.NONE) }

    // Plex's own intro and credits detection, when the server has it.
    val intro = playback.markers.firstOrNull { it.isIntro }
    val credits = playback.markers.firstOrNull { it.isCredits }
    val skipFocus = remember { FocusRequester() }

    // Live skips a channel; on demand it skips an episode, when the queue has one.
    val canSkipBack = if (playback.isLive) true else playback.queueIndex > 0
    val canSkipForward = if (playback.isLive) true
    else playback.queueIndex >= 0 && playback.queueIndex < playback.queue.lastIndex

    // Nothing else tells the system the screen is in use, so Fire OS starts its screensaver
    // over a playing film. This is what stops that.
    DisposableEffect(playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }

    /*
     * Home on the remote hides the app but does not stop the player: the launcher comes
     * up and the show carries on playing underneath it. Stopping on ON_STOP is what fixes
     * that, and the position is reported while this still knows what it is.
     *
     * Coming back differs by kind. A film waits where it was left, paused with the
     * controls up. A live channel has moved on in the meantime, so it rejoins the stream
     * rather than resuming a buffer that is now minutes behind.
     */
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, playback.url) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    exoPlayer.playWhenReady = false
                    if (!playback.isLive && playback.ratingKey != null) {
                        onReportProgress(exoPlayer.currentPosition.coerceAtLeast(0), false)
                    }
                }

                // A fresh observer is handed the events that bring it up to date, so
                // ON_START also arrives on the very first composition — before anything
                // has been queued. Preparing an empty player would report it as ended.
                Lifecycle.Event.ON_START -> if (playback.isLive && exoPlayer.mediaItemCount > 0) {
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
            }

            override fun onPlayerError(playbackError: PlaybackException) {
                // Anything in the parsing, decoding or audio-output ranges means this
                // device could not handle the file — which is what the server's
                // transcoder is for. Network errors are not that, and stay errors.
                val deviceCannotPlay = playbackError.errorCode in 3_000..5_999
                if (deviceCannotPlay && !playback.transcoding) {
                    onDecodeFailure(exoPlayer.currentPosition.coerceAtLeast(0))
                } else {
                    error = describe(playbackError)
                }
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
        panel = Panel.NONE
        interaction++
        exoPlayer.setMediaItem(buildMediaItem(playback), playback.startPositionMs)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0)
            bufferedMs = exoPlayer.bufferedPosition.coerceAtLeast(0)
            durationMs = exoPlayer.duration.takeIf { it > 0 } ?: playback.durationMs
            delay(500)
        }
    }

    // Tell Plex where we are every so often, so Continue Watching is not a guess.
    LaunchedEffect(playback.ratingKey, playing) {
        if (playback.isLive || playback.ratingKey == null) return@LaunchedEffect
        while (true) {
            delay(10_000)
            onReportProgress(exoPlayer.currentPosition.coerceAtLeast(0), playing)
        }
    }

    LaunchedEffect(interaction, playing, panel) {
        controlsVisible = true
        if (panel != Panel.NONE || !playing) return@LaunchedEffect
        delay(CONTROLS_TIMEOUT_MS)
        controlsVisible = false
    }

    val playFocus = remember { FocusRequester() }
    val scrubberFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }

    /*
     * A FocusRequester throws until the node it is attached to has been laid out, and on
     * the player's first frame none of them have been. Swallowing that left the controls
     * on screen with nothing focused: they were visible but dead, and only came to life
     * after they timed out and were summoned back, which re-ran this against nodes that
     * existed by then. So keep asking for a few frames instead of giving up on the first.
     */
    LaunchedEffect(controlsVisible, panel, guideOpen, playback.isLive, playback.url) {
        if (guideOpen) return@LaunchedEffect
        repeat(FOCUS_ATTEMPTS) {
            val placed = runCatching {
                when {
                    panel != Panel.NONE -> panelFocus.requestFocus()
                    controlsVisible -> playFocus.requestFocus()
                    else -> rootFocus.requestFocus()
                }
            }.isSuccess
            if (placed) return@LaunchedEffect
            delay(FOCUS_RETRY_MS)
        }
    }

    // Only reached once the preview handler below has declined the press, which it does
    // when there is nothing left on screen to put away.
    BackHandler {
        when {
            upNext != null -> onDismissUpNext()
            else -> onExit(exoPlayer.currentPosition.coerceAtLeast(0))
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // This is the first thing in the composition to see a key, so closing a
                // panel here is the one way to be sure it takes a single press. Leaving
                // it to the back dispatcher took two, because something between the two
                // was eating the first. Consuming the key-down also stops the activity
                // tracking the press, so the key-up cannot then exit the player as well.
                if (event.key == Key.Back) {
                    return@onPreviewKeyEvent when {
                        panel != Panel.NONE -> {
                            panel = Panel.NONE
                            interaction++
                            true
                        }
                        guideOpen -> {
                            guideOpen = false
                            true
                        }
                        // Up Next owns the press while it is showing.
                        upNext != null -> false
                        // Back closes what is open before it closes the player. Note the
                        // absence of interaction++: counting this as activity would fire
                        // the timer that puts the controls straight back up.
                        controlsVisible -> {
                            controlsVisible = false
                            true
                        }

                        else -> false
                    }
                }
                if (panel != Panel.NONE) return@onPreviewKeyEvent false
                // The guide owns every key while it is up, bar the Back handled above.
                if (guideOpen) return@onPreviewKeyEvent false
                // Up from the top of the controls dismisses them, rather than leaving
                // somebody to wait out the timeout. Same reason for not counting it.
                if (
                    event.key == Key.DirectionUp &&
                    !playback.isLive &&
                    controlsVisible &&
                    atTopOfControls
                ) {
                    controlsVisible = false
                    return@onPreviewKeyEvent true
                }
                interaction++
                when (event.key) {
                    // Live steers sideways, the way a television does, and down opens
                    // the guide over the picture. Up is left to the controls.
                    Key.DirectionLeft -> if (playback.isLive && !controlsVisible) {
                        onStepChannel(-1); true
                    } else {
                        false
                    }

                    Key.DirectionRight -> if (playback.isLive && !controlsVisible) {
                        onStepChannel(1); true
                    } else {
                        false
                    }

                    Key.DirectionUp -> { controlsVisible = true; false }

                    Key.DirectionDown -> if (playback.isLive && !controlsVisible) {
                        guideOpen = live.channels.isNotEmpty(); true
                    } else {
                        controlsVisible = true; false
                    }

                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        togglePlay(exoPlayer); true
                    }

                    Key.MediaFastForward -> {
                        exoPlayer.seekTo(exoPlayer.currentPosition + 30_000); true
                    }

                    Key.MediaRewind -> {
                        exoPlayer.seekTo((exoPlayer.currentPosition - 30_000).coerceAtLeast(0)); true
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
            update = { playerView ->
                playerView.subtitleView?.applyStyle(prefs)
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (buffering && error == null) {
            Text(
                text = "Loading…",
                color = Parchment,
                fontSize = 15.sp,
                lineHeight = 20.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        error?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 820.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Ink.copy(alpha = 0.92f))
                    .border(1.dp, Accent.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(20.dp),
            ) {
                Text(text = message, color = Parchment, fontSize = 15.sp, lineHeight = 22.sp)
            }
        }

        if (controlsVisible && !guideOpen) {
            Controls(
                playback = playback,
                playing = playing,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                canSkipBack = canSkipBack,
                canSkipForward = canSkipForward,
                playFocus = playFocus,
                scrubberFocus = scrubberFocus,
                onScrubberFocus = { atTopOfControls = it },
                onSkip = { delta ->
                    interaction++
                    // The same pair of buttons: a channel when live, an episode when not.
                    if (playback.isLive) onStepChannel(delta) else onStepEpisode(delta)
                },
                onSeek = { delta ->
                    interaction++
                    val target = (exoPlayer.currentPosition + delta)
                        .coerceIn(0, (durationMs - 1_000).coerceAtLeast(0))
                    exoPlayer.seekTo(target)
                    positionMs = target
                },
                onTogglePlay = { interaction++; togglePlay(exoPlayer) },
                onOpenSubtitles = { panel = Panel.SUBTITLES },
                onOpenAudio = { panel = Panel.AUDIO },
                onToggleFormat = onToggleFormat,
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }

        if (panel != Panel.NONE) {
            TrackPanel(
                panel = panel,
                player = exoPlayer,
                prefs = prefs,
                tracksVersion = tracksVersion,
                focusRequester = panelFocus,
                onClose = { panel = Panel.NONE },
                onNudgeScale = onNudgeSubtitleScale,
                onToggleBackground = onToggleSubtitleBackground,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        // A skip prompt only while the marker is actually under the playhead. It takes
        // focus so OK reaches it without hunting, and hands focus back when it goes.
        val inIntro = intro != null && positionMs >= intro.startMs && positionMs < intro.endMs - 500
        val inCredits = credits != null && positionMs >= credits.startMs && canSkipForward
        val skipLabel = when {
            inIntro -> "Skip Intro"
            inCredits && upNext == null -> "Next Episode"
            else -> null
        }

        // Same trap as the transport controls: the button is composed in this pass and
        // its requester is not attached yet, so one attempt would fail silently and leave
        // a Skip Intro that cannot be pressed. When it goes, focus has to land somewhere
        // or the remote does nothing at all.
        LaunchedEffect(skipLabel, controlsVisible) {
            if (skipLabel != null) {
                repeat(FOCUS_ATTEMPTS) {
                    if (runCatching { skipFocus.requestFocus() }.isSuccess) return@LaunchedEffect
                    delay(FOCUS_RETRY_MS)
                }
            } else {
                runCatching {
                    if (controlsVisible) playFocus.requestFocus() else rootFocus.requestFocus()
                }
            }
        }

        if (skipLabel != null) {
            TvActionButton(
                label = skipLabel,
                onClick = {
                    interaction++
                    if (inIntro && intro != null) exoPlayer.seekTo(intro.endMs) else onStepEpisode(1)
                },
                emphasised = true,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 40.dp, bottom = if (controlsVisible) 168.dp else 40.dp)
                    .focusRequester(skipFocus),
            )
        }

        if (guideOpen) {
            GuideOverlay(
                channels = live.channels,
                programmes = guide.programmes,
                windowStart = guide.windowStart,
                windowEnd = guide.windowEnd,
                playingIndex = playback.channelIndex,
                onSelect = { index ->
                    guideOpen = false
                    onSelectChannel(index)
                },
                onDismiss = { guideOpen = false },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (upNext != null) {
            UpNextCard(
                item = upNext,
                countdownSeconds = prefs.upNextSeconds,
                onPlay = onPlayUpNext,
                onDismiss = onDismissUpNext,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
    }
}

// ---------------------------------------------------------------- Controls

@Composable
private fun Controls(
    playback: Playback,
    playing: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    canSkipBack: Boolean,
    canSkipForward: Boolean,
    playFocus: FocusRequester,
    scrubberFocus: FocusRequester,
    onScrubberFocus: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onSkip: (Int) -> Unit,
    onTogglePlay: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenAudio: () -> Unit,
    onToggleFormat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Ink.copy(alpha = 0.85f), Ink.copy(alpha = 0.97f))
                )
            )
            .padding(horizontal = 40.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = playback.title,
            color = Parchment,
            fontSize = 17.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        playback.subtitle?.let {
            Text(
                text = it,
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (playback.isLive) {
            Text(
                text = "LIVE",
                color = Accent,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                letterSpacing = 1.6.sp,
                fontWeight = FontWeight.Medium,
            )
        } else {
            Scrubber(
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                focusRequester = scrubberFocus,
                onFocusState = onScrubberFocus,
                onSeek = onSeek,
                onTogglePlay = onTogglePlay,
            )
        }

        Box(modifier = Modifier.fillMaxWidth()) {
            // The transport sits in the middle of the screen, where a player's controls
            // belong. Seeking is the progress bar's job, so there is nothing here for it.
            Row(
                modifier = Modifier.align(Alignment.Center).focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(
                    onClick = { onSkip(-1) },
                    enabled = canSkipBack,
                    diameter = 36.dp,
                    glyph = { SkipGlyph(it, forward = false, size = 17.dp) },
                )
                TransportButton(
                    onClick = onTogglePlay,
                    filled = true,
                    diameter = 46.dp,
                    modifier = Modifier.focusRequester(playFocus),
                    glyph = {
                        if (playing) PauseGlyph(it, 21.dp) else PlayGlyph(it, 21.dp)
                    },
                )
                TransportButton(
                    onClick = { onSkip(1) },
                    enabled = canSkipForward,
                    diameter = 36.dp,
                    glyph = { SkipGlyph(it, forward = true, size = 17.dp) },
                )
            }

            Row(
                modifier = Modifier.align(Alignment.CenterEnd).focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(
                    onClick = onOpenSubtitles,
                    diameter = 36.dp,
                    glyph = { SubtitleGlyph(it, 17.dp) },
                )
                TransportButton(
                    onClick = onOpenAudio,
                    diameter = 36.dp,
                    glyph = { SpeakerGlyph(it, 17.dp) },
                )
                if (playback.isLive) {
                    TvActionButton(
                        label = if (playback.format.label == "MPEG-TS") "HLS" else "TS",
                        onClick = onToggleFormat,
                    )
                }
            }
        }

    }
}

/**
 * Position, buffer and remaining time — and the only way to scrub. Left and right move
 * ten seconds while it holds focus; OK plays or pauses without leaving it.
 */
@Composable
private fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    focusRequester: FocusRequester,
    onFocusState: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val total = durationMs.coerceAtLeast(1)

    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (focused) 8.dp else 5.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Parchment.copy(alpha = if (focused) 0.3f else 0.22f))
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    onFocusState(it.isFocused)
                }
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionLeft -> { onSeek(-SEEK_STEP_MS); true }
                        Key.DirectionRight -> { onSeek(SEEK_STEP_MS); true }
                        Key.DirectionCenter, Key.Enter -> { onTogglePlay(); true }
                        else -> false
                    }
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((bufferedMs.toFloat() / total).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(Parchment.copy(alpha = 0.3f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth((positionMs.toFloat() / total).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(Accent),
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = clock(positionMs), color = Parchment, fontSize = 12.sp, lineHeight = 16.sp)
            Box(modifier = Modifier.weight(1f))
            Text(
                text = "-" + clock((durationMs - positionMs).coerceAtLeast(0)),
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
            Text(
                text = "   /   " + clock(durationMs),
                color = Faint,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            )
        }
    }
}

// ---------------------------------------------------------------- Track panel

@Composable
private fun TrackPanel(
    panel: Panel,
    player: ExoPlayer,
    prefs: PlayerPrefs,
    tracksVersion: Int,
    focusRequester: FocusRequester,
    onClose: () -> Unit,
    onNudgeScale: (Float) -> Unit,
    onToggleBackground: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val trackType = if (panel == Panel.SUBTITLES) C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO
    val choices = remember(tracksVersion, panel) { trackChoices(player, trackType) }

    Column(
        modifier = modifier
            .width(400.dp)
            .fillMaxHeight()
            .background(Ink.copy(alpha = 0.96f))
            .border(1.dp, Line, RoundedCornerShape(0.dp))
            .padding(24.dp)
            .focusGroup()
            .focusRequester(focusRequester),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = if (panel == Panel.SUBTITLES) "Subtitles" else "Audio",
            color = Parchment,
            fontSize = 21.sp,
            lineHeight = 27.sp,
            fontWeight = FontWeight.SemiBold,
        )

        if (choices.isEmpty()) {
            Text(
                text = if (panel == Panel.SUBTITLES)
                    "This file has no text subtitle tracks. Image subtitles would have to be burned in by the server."
                else
                    "This file has only one audio track.",
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(choices) { _, choice ->
                TvListRow(
                    title = choice.label,
                    subtitle = null,
                    imageUrl = null,
                    selected = choice.selected,
                    onClick = { applyTrack(player, trackType, choice) },
                )
            }
        }

        if (panel == Panel.SUBTITLES) {
            Text(
                text = "Appearance",
                color = Faint,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                letterSpacing = 1.4.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvActionButton(label = "Smaller", onClick = { onNudgeScale(-Settings.SCALE_STEP) })
                TvActionButton(label = "Bigger", onClick = { onNudgeScale(Settings.SCALE_STEP) })
            }
            Text(
                text = "Size ${(prefs.subtitleScale * 100).toInt()}%",
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 17.sp,
            )
            TvActionButton(
                label = if (prefs.subtitleBackground) "Background: on" else "Background: off",
                onClick = onToggleBackground,
            )
        }

        TvActionButton(label = "Close", onClick = onClose)
    }
}

private fun trackChoices(player: ExoPlayer, trackType: Int): List<TrackChoice> {
    val groups = player.currentTracks.groups.filter { it.type == trackType && it.isSupported }
    if (groups.isEmpty()) return emptyList()

    val anySelected = groups.any { it.isSelected }
    val options = mutableListOf<TrackChoice>()
    if (trackType == C.TRACK_TYPE_TEXT) {
        options += TrackChoice(label = "Off", group = null, selected = !anySelected)
    }
    groups.forEachIndexed { index, group ->
        val format = group.getTrackFormat(0)
        val label = format.label
            ?: format.language
            ?: "Track ${index + 1}"
        options += TrackChoice(label = label, group = group, selected = group.isSelected)
    }
    return options
}

private fun applyTrack(player: ExoPlayer, trackType: Int, choice: TrackChoice) {
    val builder = player.trackSelectionParameters.buildUpon()
    val group = choice.group
    player.trackSelectionParameters = if (group == null) {
        builder.clearOverridesOfType(trackType).setTrackTypeDisabled(trackType, true).build()
    } else {
        builder
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .build()
    }
}

// ---------------------------------------------------------------- Up next

@Composable
private fun UpNextCard(
    item: PlexItem,
    countdownSeconds: Int,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var remaining by remember(item.ratingKey) { mutableIntStateOf(countdownSeconds) }
    val focus = remember { FocusRequester() }

    LaunchedEffect(item.ratingKey, countdownSeconds) {
        runCatching { focus.requestFocus() }
        // Zero means the countdown is switched off: the card waits to be chosen.
        if (countdownSeconds <= 0) return@LaunchedEffect
        remaining = countdownSeconds
        while (remaining > 0) {
            delay(1_000)
            remaining--
        }
        onPlay()
    }

    Column(
        modifier = modifier
            .padding(40.dp)
            .width(420.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceRaised)
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(20.dp)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = if (countdownSeconds > 0) "UP NEXT IN $remaining" else "UP NEXT",
            color = Accent,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            letterSpacing = 1.5.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = item.title,
            color = Parchment,
            fontSize = 19.sp,
            lineHeight = 25.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        listOfNotNull(item.grandparentTitle, item.caption).joinToString("  ·  ")
            .takeIf { it.isNotBlank() }
            ?.let { Text(text = it, color = Muted, fontSize = 13.sp, lineHeight = 17.sp) }

        Row(
            modifier = Modifier.padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TvActionButton(
                label = "Play now",
                onClick = onPlay,
                emphasised = true,
                modifier = Modifier.focusRequester(focus),
            )
            TvActionButton(label = "Stop", onClick = onDismiss)
        }
    }
}

// ---------------------------------------------------------------- Helpers

private fun togglePlay(player: ExoPlayer) {
    if (player.isPlaying) player.pause() else player.play()
}

private fun SubtitleView.applyStyle(prefs: PlayerPrefs) {
    // The file's own styling and the system's caption settings both get ignored, so what
    // is on screen is what this app's settings say it should be.
    setApplyEmbeddedStyles(false)
    setApplyEmbeddedFontSizes(false)
    setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * prefs.subtitleScale)
    setStyle(
        CaptionStyleCompat(
            android.graphics.Color.WHITE,
            if (prefs.subtitleBackground) android.graphics.Color.argb(190, 0, 0, 0)
            else android.graphics.Color.TRANSPARENT,
            android.graphics.Color.TRANSPARENT,
            if (prefs.subtitleBackground) CaptionStyleCompat.EDGE_TYPE_NONE
            else CaptionStyleCompat.EDGE_TYPE_OUTLINE,
            android.graphics.Color.BLACK,
            null,
        )
    )
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

private fun clock(millis: Long): String {
    if (millis <= 0) return "0:00"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}

/**
 * A refused stream must say so. Providers cap simultaneous connections, and a dead player
 * looks exactly like a broken app.
 */
private fun describe(error: PlaybackException): String = when (val cause = error.cause) {
    is HttpDataSource.InvalidResponseCodeException -> when (cause.responseCode) {
        401, 403 -> "Refused this stream (HTTP ${cause.responseCode}). Either these credentials " +
            "aren't valid for it, or every connection your subscription allows is already in use."

        404 -> "That stream doesn't exist (HTTP 404)."
        else -> "The server returned HTTP ${cause.responseCode}."
    }

    is HttpDataSource.HttpDataSourceException ->
        "Couldn't reach the stream: ${cause.message ?: "no response"}"

    else -> error.errorCodeName + (error.message?.let { " — $it" } ?: "")
}
