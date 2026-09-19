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
import tv.reely.ui.PlayerPrefs
import tv.reely.ui.Playback
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
    upNext: PlexItem?,
    onExit: (Long) -> Unit,
    onEnded: () -> Unit,
    onPlayUpNext: () -> Unit,
    onDismissUpNext: () -> Unit,
    onStepChannel: (Int) -> Unit,
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
            .build()
            .apply { setWakeMode(C.WAKE_MODE_NETWORK) }
    }

    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tracksVersion by remember { mutableIntStateOf(0) }

    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var panel by remember { mutableStateOf(Panel.NONE) }

    // Nothing else tells the system the screen is in use, so Fire OS starts its screensaver
    // over a playing film. This is what stops that.
    DisposableEffect(playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
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

    val scrubberFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }

    LaunchedEffect(controlsVisible, panel, playback.isLive) {
        runCatching {
            when {
                panel != Panel.NONE -> panelFocus.requestFocus()
                controlsVisible && !playback.isLive -> scrubberFocus.requestFocus()
                controlsVisible -> rootFocus.requestFocus()
                else -> rootFocus.requestFocus()
            }
        }
    }

    BackHandler {
        when {
            panel != Panel.NONE -> panel = Panel.NONE
            upNext != null -> onDismissUpNext()
            controlsVisible && playing -> controlsVisible = false
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
                if (panel != Panel.NONE) return@onPreviewKeyEvent false
                interaction++
                when (event.key) {
                    Key.DirectionUp -> if (playback.isLive) {
                        onStepChannel(-1); true
                    } else {
                        controlsVisible = true; false
                    }

                    Key.DirectionDown -> if (playback.isLive) {
                        onStepChannel(1); true
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

        if (controlsVisible) {
            Controls(
                playback = playback,
                playing = playing,
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                scrubberFocus = scrubberFocus,
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
    scrubberFocus: FocusRequester,
    onSeek: (Long) -> Unit,
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
            .padding(horizontal = 44.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = playback.title,
            color = Parchment,
            fontSize = 25.sp,
            lineHeight = 31.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        playback.subtitle?.let {
            Text(
                text = it,
                color = Muted,
                fontSize = 14.sp,
                lineHeight = 18.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (playback.isLive) {
            Text(text = "LIVE", color = Accent, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.6.sp)
        } else {
            Scrubber(
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                focusRequester = scrubberFocus,
                onSeek = onSeek,
                onTogglePlay = onTogglePlay,
            )
        }

        Row(
            modifier = Modifier.focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TvActionButton(label = if (playing) "Pause" else "Play", onClick = onTogglePlay)
            TvActionButton(label = "Subtitles", onClick = onOpenSubtitles)
            TvActionButton(label = "Audio", onClick = onOpenAudio)
            if (playback.isLive) {
                TvActionButton(
                    label = "Switch to ${if (playback.format.label == "MPEG-TS") "HLS" else "MPEG-TS"}",
                    onClick = onToggleFormat,
                )
            }
        }

        Text(
            text = if (playback.isLive)
                "Up/Down changes channel · Back leaves"
            else
                "Left/Right seeks 10s · Down for buttons · Back leaves",
            color = Faint,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
    }
}

/** A focusable progress bar: left and right scrub, OK plays or pauses. */
@Composable
private fun Scrubber(
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    focusRequester: FocusRequester,
    onSeek: (Long) -> Unit,
    onTogglePlay: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val total = durationMs.coerceAtLeast(1)

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (focused) 10.dp else 6.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(Parchment.copy(alpha = 0.22f))
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
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
                    .background(Parchment.copy(alpha = 0.32f)),
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth((positionMs.toFloat() / total).coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(Accent),
            )
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = clock(positionMs), color = Parchment, fontSize = 13.sp, lineHeight = 17.sp)
            Box(modifier = Modifier.weight(1f))
            Text(
                text = "-" + clock((durationMs - positionMs).coerceAtLeast(0)),
                color = Muted,
                fontSize = 13.sp,
                lineHeight = 17.sp,
            )
            Text(
                text = "   /   " + clock(durationMs),
                color = Faint,
                fontSize = 13.sp,
                lineHeight = 17.sp,
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
