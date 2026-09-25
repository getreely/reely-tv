package tv.reely.ui.screens

import androidx.media3.exoplayer.analytics.AnalyticsListener
import tv.reely.core.renderersFor
import tv.reely.ui.components.LoadingRing
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import tv.reely.core.GuideRequest
import tv.reely.core.SkipPrompt
import tv.reely.core.skipPromptAt
import tv.reely.core.LivePlayer
import tv.reely.core.DeviceAudio
import tv.reely.core.Settings
import tv.reely.core.SilentAudio
import tv.reely.core.silentAudio
import tv.reely.plex.PlexItem
import tv.reely.ui.GuideState
import tv.reely.ui.LiveState
import tv.reely.ui.PlayerPrefs
import tv.reely.ui.Playback
import tv.reely.ui.components.MatchFrameRate
import tv.reely.ui.components.InfoGlyph
import tv.reely.ui.components.PauseGlyph
import tv.reely.ui.components.PlayGlyph
import tv.reely.ui.components.PlusGlyph
import tv.reely.ui.components.SkipGlyph
import tv.reely.ui.components.isSelect
import tv.reely.ui.components.SpeakerGlyph
import tv.reely.ui.components.SubtitleGlyph
import tv.reely.ui.components.TransportButton
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.components.TvListRow
import tv.reely.ui.components.sheet
import tv.reely.ui.components.rememberSelectPress
import tv.reely.ui.components.requestWhenReady
import tv.reely.xtream.XtreamApi
import tv.reely.xtream.XtreamCategory
import tv.reely.xtream.XtreamChannel
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.ReelyType

private const val SEEK_STEP_MS = 10_000L
private const val CONTROLS_TIMEOUT_MS = 6_000L

/** Long enough to read twice from across a room, short enough not to sit on the picture. */
private const val AUDIO_NOTICE_MS = 9_000L

internal enum class Panel { NONE, SUBTITLES, AUDIO, STATS }

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
    /** The credits have started: find what comes next, for the Next Episode button. */
    onCredits: () -> Unit,
    onPlayUpNext: () -> Unit,
    onDismissUpNext: () -> Unit,
    /** What comes next, once the credits have started and it has been found. */
    nextEpisode: PlexItem? = null,
    /** The Next Episode button: bring up the Up Next screen. */
    onShowUpNext: () -> Unit = {},
    onStepChannel: (Int) -> Unit,
    onSelectChannel: (Int) -> Unit,
    onOpenCategory: (XtreamCategory) -> Unit,
    livePlayer: LivePlayer,
    multiview: List<XtreamChannel>,
    onAddToMultiview: (XtreamChannel) -> Unit,
    onRemoveTile: (Int) -> Unit,
    onClearTiles: () -> Unit,
    onReplaceTile: (Int, XtreamChannel) -> Unit,
    onCollapseToChannel: (XtreamChannel) -> Unit,
    onStepEpisode: (Int) -> Unit,
    onDecodeFailure: (Long) -> Unit,
    /** The file's sound cannot be played here; ask the server to convert just that. */
    onConvertAudio: (Long) -> Unit,
    onToggleFormat: () -> Unit,
    onReportProgress: (Long, Boolean) -> Unit,
    onNudgeSubtitleScale: (Float) -> Unit,
    onToggleSubtitleBackground: () -> Unit,
    imageUrl: (String?, String?, Int, Int) -> String?,
    logoUrl: (String?, String?) -> String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current

    val ownPlayer = remember {
        ExoPlayer.Builder(context, renderersFor(context))
            // Quick to start by default; more in hand when asked for — see bufferFor.
            .setLoadControl(bufferFor(prefs.largerBuffer))
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

    // Live television plays on the shared player the guide was already previewing on, so
    // arriving here from the guide does not restart the stream. Everything else gets its
    // own, which is released with this screen.
    val exoPlayer = if (playback.isLive) livePlayer.player else ownPlayer
    DisposableEffect(Unit) { onDispose { ownPlayer.release() } }

    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    // Which playback positionMs belongs to. It is polled, so for a moment after the next
    // episode takes over it still holds the last one's — deep in its credits — and read
    // as the new one's, that put Up Next straight back up over the new episode's intro.
    var positionFor by remember { mutableStateOf<String?>(null) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    /*
     * Why there is no sound, when nothing can be done about it. Separate from `error`
     * because this is found while the picture plays: reaching the ready state clears
     * errors, and would wipe this the moment it was set.
     */
    var audioNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(audioNotice) {
        if (audioNotice != null) {
            delay(AUDIO_NOTICE_MS)
            audioNotice = null
        }
    }
    var tracksVersion by remember { mutableIntStateOf(0) }
    // Which decoder took the sound — the device's, or the app's FFmpeg — for the stats
    // panel. Null while nothing has been decoded, which with sound playing means it is
    // going out untouched to whatever is on the other end of the HDMI.
    var audioDecoder by remember { mutableStateOf<String?>(null) }

    // A film or episode opening is worth showing the transport for; a channel is not,
    // and raising it there cost six seconds of a dead d-pad before the timeout cleared it.
    var controlsVisible by remember { mutableStateOf(!playback.isLive) }
    // Past the end of the file. Up Next then offers to leave rather than to watch the
    // credits, which have been and gone.
    var ended by remember { mutableStateOf(false) }
    // The scrubber is the top of the control bar, so this is "there is nothing above
    // here to move to" — which is what makes another press of up mean "put these away".
    var atTopOfControls by remember { mutableStateOf(false) }
    // The skip prompt, when the controls are up, is the top of them instead.
    var skipFocused by remember { mutableStateOf(false) }
    // The guide, raised over a playing channel. Mutually exclusive with the controls.
    // One value rather than three flags, so opening it has to say what it is for and
    // closing it cannot half-forget — see GuideRequest.
    var guideRequest by remember { mutableStateOf(GuideRequest.Closed) }
    val guideOpen = guideRequest.open

    // Which tile the held-OK menu is open over, if any.
    var tileMenu by remember { mutableStateOf<Int?>(null) }
    val tilePress = rememberSelectPress()

    // Tile 0 is the channel in `playback`, drawn by the player that is already running.
    // With nothing beside it this is all inert and the screen behaves exactly as before.
    val tiles = if (playback.isLive) multiview else emptyList()
    val tileCount = tiles.size + 1
    val focusLayout = prefs.multiviewLayout == Settings.LAYOUT_FOCUS
    // A grid with room left over offers the spare cell as somewhere to put another
    // channel. Two side by side is a deliberate exception: filling half the screen with
    // an invitation is worse than not having one.
    val hasSpare = hasSpareCell(tileCount, focusLayout)
    val slotCount = tileCount + if (hasSpare) 1 else 0
    val addSlot = if (hasSpare) tileCount else -1
    var focusedTile by remember { mutableIntStateOf(0) }
    if (focusedTile >= slotCount) focusedTile = 0

    // One tile filling the screen without the others being torn down. Choosing a tile
    // used to collapse the grid to that channel, which meant the only way back was
    // building it again — and rebuilding it costs the provider connections it had
    // already granted.
    var zoomed by remember { mutableStateOf<Int?>(null) }
    if (zoomed != null && (tileCount == 1 || zoomed!! > tiles.size)) zoomed = null

    // The extra channels' players, kept by the screen rather than by the tiles that draw
    // them, so a tile can move between the grid and full screen without its stream being
    // torn down and dialled again.
    val extraUrls = live.credentials?.let { credentials ->
        tiles.map { XtreamApi.streamUrl(credentials, it, live.format) }
    }.orEmpty()
    val extraPlayers = remember { mutableStateMapOf<String, ExoPlayer>() }
    LaunchedEffect(extraUrls) {
        extraUrls.forEach { url ->
            if (url !in extraPlayers) extraPlayers[url] = buildExtraPlayer(context, url)
        }
        (extraPlayers.keys - extraUrls.toSet()).forEach { extraPlayers.remove(it)?.release() }
    }
    DisposableEffect(Unit) {
        onDispose {
            extraPlayers.values.forEach { it.release() }
            extraPlayers.clear()
        }
    }

    // Only the tile with the cursor on it is heard.
    LaunchedEffect(focusedTile, tileCount) {
        exoPlayer.volume = if (focusedTile == 0) 1f else 0f
    }
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

    /*
     * The skip prompt, and only while the marker it is for is under the playhead.
     *
     * This was computed and then thrown away: the rework that took the transport out of
     * a split took the prompt with it, and putting the transport back missed it, so the
     * markers were fetched from the server on every episode and never shown. The half
     * second off the end of the intro keeps the button from flashing away under a thumb
     * already on its way to OK.
     */
    // Markers are this episode's; a position still left over from the last one is not.
    val positionIsCurrent = positionFor == playback.url
    val prompt = if (!positionIsCurrent) null else skipPromptAt(
        positionMs = positionMs,
        introStartMs = intro?.startMs,
        introEndMs = intro?.endMs,
        creditsStartMs = credits?.startMs,
        canSkipForward = nextEpisode != null,
        upNextShowing = upNext != null,
    )
    val skipLabel = when (prompt) {
        SkipPrompt.INTRO -> "Skip Intro"
        SkipPrompt.NEXT_EPISODE -> "Next Episode"
        null -> null
    }

    /*
     * The credits starting looks up what comes next, and offers it as a Next Episode
     * button in the corner, like Skip Intro. The Up Next screen only comes up when that
     * is pressed, or when the file runs out: taking the picture over the moment the
     * credits began was too soon.
     */
    val inCredits = !playback.isLive && positionIsCurrent && credits != null && positionMs >= credits.startMs
    var creditsOffered by remember(playback.url) { mutableStateOf(false) }
    LaunchedEffect(inCredits, playback.url) {
        if (inCredits && !creditsOffered) {
            creditsOffered = true
            onCredits()
        }
    }
    // The Up Next screen: the picture shrunk into a corner and the next episode offered
    // across the rest. One picture only; a grid is live television, which has no next.
    val postPlay = upNext != null && tileCount == 1 && !playback.isLive
    val upNextFocus = remember { FocusRequester() }

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
                    // The other tiles are players too, and a player nobody stopped keeps
                    // playing over the launcher. Twice bitten.
                    extraPlayers.values.forEach { it.playWhenReady = false }
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
                    // Each of these is as far behind live as the main one, for the same
                    // reason, and rejoins the same way.
                    extraPlayers.values.forEach {
                        it.seekToDefaultPosition()
                        it.prepare()
                        it.playWhenReady = true
                    }
                }

                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /*
     * The listener below lives as long as the player, so anything it reads from the
     * composition has to be read live. It used to capture `playback` from the first
     * composition, which meant its "already transcoding" test was false forever.
     */
    val currentPlayback by rememberUpdatedState(playback)
    val currentPrefs by rememberUpdatedState(prefs)

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                // Something is playing again, so whatever the last complaint was, it is
                // no longer true. Live recovers itself; the message should not outlive it.
                if (state == Player.STATE_READY) error = null
                ended = state == Player.STATE_ENDED
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
                val now = currentPlayback
                when (
                    silentAudio(
                        hasAudio = tracks.containsType(C.TRACK_TYPE_AUDIO),
                        audioSelected = tracks.isTypeSelected(C.TRACK_TYPE_AUDIO),
                        isLive = now.isLive,
                        fromPlex = now.ratingKey != null,
                        alreadyTranscoding = now.transcoding,
                        directOnly = currentPrefs.playbackMode == Settings.MODE_DIRECT,
                    )
                ) {
                    SilentAudio.FINE -> Unit
                    SilentAudio.CONVERT_ON_SERVER ->
                        onConvertAudio(exoPlayer.currentPosition.coerceAtLeast(0))
                    SilentAudio.EXPLAIN -> audioNotice = explainSilence(tracks, now, currentPrefs)
                }
            }

            override fun onPlayerError(playbackError: PlaybackException) {
                // The live player rejoins the stream by itself when it falls off the end
                // of the playlist, which is the normal fate of a long-running channel.
                // Saying so on screen would be reporting a fault that is already fixed.
                if (playbackError.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    return
                }
                // Anything in the parsing, decoding or audio-output ranges means this
                // device could not handle the file — which is what the server's
                // transcoder is for. Network errors are not that, and stay errors.
                val deviceCannotPlay = playbackError.errorCode in 3_000..5_999
                if (deviceCannotPlay && !currentPlayback.transcoding) {
                    onDecodeFailure(exoPlayer.currentPosition.coerceAtLeast(0))
                } else {
                    error = describe(playbackError)
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : AnalyticsListener {
            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long,
            ) {
                audioDecoder = decoderName
            }
        }
        exoPlayer.addAnalyticsListener(listener)
        onDispose { exoPlayer.removeAnalyticsListener(listener) }
    }

    LaunchedEffect(playback.url) {
        audioDecoder = null
        error = null
        audioNotice = null
        panel = Panel.NONE
        // A film or episode starting is worth showing the controls for. A channel
        // starting is not: this fires on every channel change, and it was the third and
        // last way the controls kept reappearing while somebody was surfing.
        if (!playback.isLive) interaction++
        if (playback.isLive) {
            // No-op when this is the channel already running, which is the whole point.
            livePlayer.play(playback.url)
        } else {
            exoPlayer.setMediaItem(buildMediaItem(playback), playback.startPositionMs)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }

    LaunchedEffect(exoPlayer) {
        while (true) {
            val current = currentPlayback
            val loaded = exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
            positionFor = if (current.isLive || loaded == current.url) current.url else null
            positionMs = exoPlayer.currentPosition.coerceAtLeast(0)
            bufferedMs = exoPlayer.bufferedPosition.coerceAtLeast(0)
            durationMs = exoPlayer.duration.takeIf { it > 0 } ?: playback.durationMs
            delay(500)
        }
    }

    /*
     * Tell Plex where we are, starting at once.
     *
     * The delay used to come first, so nothing at all was sent for the first ten seconds
     * and the server had no idea the item had been opened — start something, look at Plex
     * on another device, and it still showed as unwatched. Reporting up front also means
     * a pause or a resume is sent the moment it happens, because this restarts on
     * `playing`, rather than up to ten seconds later.
     */
    LaunchedEffect(playback.ratingKey, playing) {
        if (playback.isLive || playback.ratingKey == null) return@LaunchedEffect
        while (true) {
            onReportProgress(exoPlayer.currentPosition.coerceAtLeast(0), playing)
            delay(10_000)
        }
    }

    /*
     * Pressing something shows the controls. Nothing else does — and in particular not
     * playback starting, which is what made them appear on every channel change: this
     * used to be keyed on `playing`, which flips each time a new stream comes up.
     */
    LaunchedEffect(interaction) {
        if (interaction > 0 && tileCount == 1) controlsVisible = true
    }

    /*
     * The transport belongs to one picture, so a grid never has it. It is not enough to
     * stop drawing it: every direction key is gated on it being down, so leaving the flag
     * set left the d-pad dead until the timeout cleared it. Walking between tiles counts
     * as interaction, which set it again on every press.
     */
    LaunchedEffect(tileCount) {
        if (tileCount > 1) controlsVisible = false
    }

    /* They go away again after a quiet spell, but only while something is playing. */
    LaunchedEffect(interaction, controlsVisible, playing, panel) {
        if (!controlsVisible || panel != Panel.NONE || !playing) return@LaunchedEffect
        delay(CONTROLS_TIMEOUT_MS)
        controlsVisible = false
    }

    // The screen's refresh rate, matched to what is playing. Live television is left
    // alone: a channel is fifty or sixty already, and a mode change mid-surf would blank
    // the picture on every press of left or right.
    MatchFrameRate(
        player = exoPlayer,
        activity = LocalActivity.current,
        enabled = prefs.matchFrameRate && !playback.isLive,
    )

    val playFocus = remember { FocusRequester() }
    val scrubberFocus = remember { FocusRequester() }
    val rootFocus = remember { FocusRequester() }
    val panelFocus = remember { FocusRequester() }
    val tileMenuFocus = remember { FocusRequester() }

    /*
     * A FocusRequester throws until the node it is attached to has been laid out, and on
     * the player's first frame none of them have been. Swallowing that left the controls
     * on screen with nothing focused: they were visible but dead, and only came to life
     * after they timed out and were summoned back, which re-ran this against nodes that
     * existed by then. So keep asking for a few frames instead of giving up on the first.
     */
    LaunchedEffect(
        controlsVisible, panel, guideOpen, tileMenu, skipLabel, playback.isLive, playback.url,
        postPlay,
    ) {
        if (guideOpen) return@LaunchedEffect
        when {
            postPlay -> upNextFocus.requestWhenReady()
            tileMenu != null -> tileMenuFocus.requestWhenReady()
            panel != Panel.NONE -> panelFocus.requestWhenReady()
            // Above the transport: a prompt that is only up for a few seconds is no use
            // if reaching it means hunting for it first. When it goes, focus has to land
            // somewhere or the remote does nothing at all.
            skipLabel != null -> skipFocus.requestWhenReady()
            controlsVisible -> playFocus.requestWhenReady()
            else -> rootFocus.requestWhenReady()
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
                /*
                 * A press of OK on a tile, and a hold of it, both halves. This has to run
                 * before the key-up bail below, because a press is not a press until the
                 * key comes up — see SelectPress.
                 */
                // The release belongs to the press that started it, whatever the hold
                // has since put on screen — see SelectPress.awaitingRelease.
                /*
                 * A hold opens the tile menu with one channel up as readily as with four.
                 *
                 * This used to require a split already on screen, so the menu — the one
                 * route to another channel that does not depend on the layout — was
                 * unreachable from exactly the state everybody starts in. A single
                 * channel has nothing to maximise and cannot be closed, so its menu is
                 * Add, Replace and Cancel, which is what a hold there is wanted for.
                 *
                 * Films are left alone: OK on one belongs to the transport.
                 */
                val tilePressOwnsOk = tilePress.awaitingRelease || (
                    playback.isLive && !controlsVisible &&
                        tileMenu == null && panel == Panel.NONE && !guideOpen && upNext == null
                    )
                if (tilePressOwnsOk) {
                    val handled = tilePress.handle(
                        event,
                        onPress = {
                            when {
                                // One picture: OK still means "show me the transport".
                                slotCount == 1 -> {
                                    interaction++
                                    controlsVisible = true
                                }

                                focusedTile == addSlot ->
                                    guideRequest = GuideRequest.add(live.channels.isNotEmpty())

                                // Fills the screen with this one and leaves the rest
                                // running behind it. Again, or back, returns to the grid.
                                else -> zoomed = if (zoomed == focusedTile) null else focusedTile
                            }
                        },
                        onHold = { if (focusedTile != addSlot) tileMenu = focusedTile },
                    )
                    if (handled) return@onPreviewKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                // This is the first thing in the composition to see a key, so closing a
                // panel here is the one way to be sure it takes a single press. Leaving
                // it to the back dispatcher took two, because something between the two
                // was eating the first. Consuming the key-down also stops the activity
                // tracking the press, so the key-up cannot then exit the player as well.
                if (event.key == Key.Back) {
                    return@onPreviewKeyEvent when {
                        tileMenu != null -> {
                            tileMenu = null
                            true
                        }
                        panel != Panel.NONE -> {
                            panel = Panel.NONE
                            interaction++
                            true
                        }
                        // Back closes the guide, full stop. The categories used to be a
                        // second level reached by backing out of the grid, which made
                        // back mean two different things; they are now a row above the
                        // channels that up walks into, so this can be what it looks like.
                        guideOpen -> {
                            guideRequest = GuideRequest.Closed
                            true
                        }
                        // Coming out of a zoomed tile returns to the grid it came from.
                        zoomed != null -> {
                            zoomed = null
                            true
                        }
                        // A tile beside the main one is the first thing back takes away.
                        focusedTile in 1..tiles.size -> {
                            val index = focusedTile - 1
                            focusedTile = 0
                            onRemoveTile(index)
                            true
                        }
                        // Back from Up Next is the button beside Play: during the
                        // credits it goes back to them, after them it leaves.
                        postPlay -> {
                            if (ended) onExit(exoPlayer.currentPosition.coerceAtLeast(0))
                            else onDismissUpNext()
                            true
                        }
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
                if (tileMenu != null) return@onPreviewKeyEvent false
                if (panel != Panel.NONE) return@onPreviewKeyEvent false
                // Up Next is a screen of its own, and its two buttons are all there is.
                if (postPlay) return@onPreviewKeyEvent false
                // The guide owns every key while it is up, bar the Back handled above.
                if (guideOpen) return@onPreviewKeyEvent false
                // Up from the top of the controls dismisses them, rather than leaving
                // somebody to wait out the timeout. Same reason for not counting it.
                if (
                    event.key == Key.DirectionUp &&
                    !playback.isLive &&
                    controlsVisible &&
                    ((atTopOfControls && skipLabel == null) || skipFocused)
                ) {
                    controlsVisible = false
                    return@onPreviewKeyEvent true
                }
                // With a grid up, the direction keys walk it. Only a press that runs off
                // the edge falls through to what that key means with one channel on
                // screen, which is why a single tile behaves exactly as it always has.
                // Zoomed: the grid is not on screen to move around, and stepping channel
                // would retune the main tile rather than the one being looked at.
                if (slotCount > 1 && zoomed != null && !controlsVisible) {
                    val directional = event.key == Key.DirectionUp || event.key == Key.DirectionDown ||
                        event.key == Key.DirectionLeft || event.key == Key.DirectionRight
                    if (directional) return@onPreviewKeyEvent true
                }
                if (slotCount > 1 && !controlsVisible && zoomed == null) {
                    val dx = when (event.key) {
                        Key.DirectionLeft -> -1
                        Key.DirectionRight -> 1
                        else -> 0
                    }
                    val dy = when (event.key) {
                        Key.DirectionUp -> -1
                        Key.DirectionDown -> 1
                        else -> 0
                    }
                    if (dx != 0 || dy != 0) {
                        // In the focus layout the tiles are a line rather than a grid,
                        // because the one with the cursor on it is always the big one and
                        // the rest shuffle up beside it.
                        val next = if (focusLayout) {
                            (focusedTile + dy + dx).takeIf { it in 0 until slotCount }
                        } else {
                            tileNeighbour(slotCount, focusedTile, dx, dy)
                        }
                        if (next != null) {
                            interaction++
                            focusedTile = next
                            return@onPreviewKeyEvent true
                        }
                        // Off the edge: down still opens the guide. Up does nothing,
                        // because the transport belongs to one picture and there is no
                        // one picture here. Sideways does nothing rather than surprising
                        // somebody by retuning a tile they were only walking past.
                        interaction++
                        if (dy > 0) guideRequest = GuideRequest.browse(live.channels.isNotEmpty())
                        return@onPreviewKeyEvent true
                    }
                }
                // Steering live television is not an interaction. Counting it raised the
                // controls on every channel change, and with them up the next press of
                // left or right went to the transport instead of the next channel — so
                // changing channel twice in a row was impossible.
                if (playback.isLive && !controlsVisible && slotCount == 1) {
                    when (event.key) {
                        Key.DirectionLeft -> {
                            onStepChannel(-1)
                            return@onPreviewKeyEvent true
                        }

                        Key.DirectionRight -> {
                            onStepChannel(1)
                            return@onPreviewKeyEvent true
                        }

                        Key.DirectionDown -> {
                            guideRequest = GuideRequest.browse(live.channels.isNotEmpty())
                            return@onPreviewKeyEvent true
                        }

                        else -> Unit
                    }
                }
                // The skip prompt owns OK while it is up, and this must not count as
                // activity: that raises the transport over the thing being skipped, and
                // the button jumps as its padding moves to clear it. Declining rather
                // than consuming, so the press still reaches the button that has focus.
                if (skipLabel != null && event.isSelect()) return@onPreviewKeyEvent false
                interaction++
                when (event.key) {
                    Key.DirectionUp -> { controlsVisible = true; false }

                    Key.DirectionDown -> { controlsVisible = true; false }

                    Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> {
                        if (playback.isLive && !exoPlayer.isPlaying) livePlayer.rejoin()
                        else togglePlay(exoPlayer)
                        true
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
        val mainSurface: @Composable () -> Unit = {
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
        }

        // One description of a tile, drawn either into the grid or over the whole screen.
        val tileAt: @Composable (Int) -> Unit = { index ->
            when {
                index == addSlot -> AddTile(focused = focusedTile == index)

                index == 0 -> TileFrame(
                    name = playback.title,
                    focused = focusedTile == 0,
                    aspectRatio = rememberVideoAspect(exoPlayer),
                    content = mainSurface,
                )

                else -> {
                    val extra = tiles[index - 1]
                    val player = extraUrls.getOrNull(index - 1)?.let { extraPlayers[it] }
                    if (player == null) {
                        TileFrame(name = extra.name, focused = focusedTile == index) {}
                    } else {
                        ExtraTile(
                            player = player,
                            name = extra.name,
                            focused = focusedTile == index,
                        )
                    }
                }
            }
        }

        if (tileCount == 1) {
            PostPlay(
                item = upNext.takeIf { postPlay },
                stillUrl = upNext?.let { imageUrl(it.serverBase, it.thumb ?: it.art, 1280, 720) },
                logoUrl = upNext?.let { logoUrl(it.serverBase, it.logo) },
                nowTitle = playback.title,
                ended = ended,
                countdownSeconds = prefs.upNextSeconds,
                focusRequester = upNextFocus,
                onPlay = onPlayUpNext,
                onDecline = {
                    if (ended) onExit(exoPlayer.currentPosition.coerceAtLeast(0)) else onDismissUpNext()
                },
                video = mainSurface,
            )
        } else if (zoomed != null) {
            Box(modifier = Modifier.fillMaxSize()) { tileAt(zoomed!!) }
        } else if (focusLayout) {
            FocusLayout(
                slots = slotCount,
                focused = focusedTile,
                modifier = Modifier.fillMaxSize(),
            ) { tileAt(it) }
        } else {
            MultiViewGrid(slots = slotCount, modifier = Modifier.fillMaxSize()) { tileAt(it) }
        }

        /*
         * Loading and errors. Both were deleted with the transport by the split-view
         * rework and missed when it was put back — so every playback failure since then
         * has been swallowed without a word, and a stalled stream looked like a frozen
         * picture. One picture only: in a grid this would sit across the join between
         * tiles and describe only the first of them.
         */
        if (buffering && error == null && tileCount == 1) {
            LoadingRing(modifier = Modifier.align(Alignment.Center))
        }

        error?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .widthIn(max = 720.dp)
                    .sheet()
                    .padding(horizontal = 28.dp, vertical = 22.dp),
            ) {
                Text(text = message, color = Chalk, style = ReelyType.Body)
            }
        }

        // At the top, away from the transport and the skip prompt, and gone on its own:
        // the picture is still playing and this is only saying why it is quiet.
        audioNotice?.let { message ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 27.dp)
                    .widthIn(max = 720.dp)
                    .sheet(radius = 16)
                    .padding(horizontal = 22.dp, vertical = 14.dp),
            ) {
                Text(text = message, color = Chalk, style = ReelyType.Meta)
            }
        }

        if (guideOpen) {
            GuideOverlay(
                channels = live.channels,
                programmes = guide.programmes,
                windowStart = guide.windowStart,
                windowEnd = guide.windowEnd,
                playingIndex = playback.channelIndex,
                categories = live.categories,
                selectedCategory = live.selectedCategory,
                onSelectCategory = onOpenCategory,
                addMode = guideRequest.adds,
                pickVerb = guideRequest.verb,
                onSelect = { index ->
                    guideRequest = GuideRequest.Closed
                    focusedTile = 0
                    onSelectChannel(index)
                },
                onAddToMultiview = { channel ->
                    val slot = guideRequest.replaces
                    guideRequest = GuideRequest.Closed
                    if (slot != null) onReplaceTile(slot, channel) else onAddToMultiview(channel)
                },
                canAddTile = tileCount < 4,
                onDismiss = { guideRequest = GuideRequest.Closed },
                modifier = Modifier.fillMaxSize(),
            )
        }

        /*
         * The transport. This block was deleted wholesale by the split-view rework, which
         * meant to suppress it in a grid and instead stopped it ever being drawn at all —
         * so a film had no controls, and the flag that gates the direction keys ran on
         * regardless. Hence the tileCount test rather than another deletion.
         */
        val controlsShowing = controlsVisible && !guideOpen && tileCount == 1 && !postPlay
        val skip = {
            if (prompt == SkipPrompt.INTRO && intro != null) exoPlayer.seekTo(intro.endMs)
            else onShowUpNext()
        }
        if (controlsShowing) {
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
                onTogglePlay = {
                    interaction++
                    // Coming back from a pause on live television means coming back to
                    // now, not to the moment it was paused — which is behind the live
                    // window by definition and would only fail.
                    if (playback.isLive && !exoPlayer.isPlaying) livePlayer.rejoin()
                    else togglePlay(exoPlayer)
                },
                onAddChannel = { guideRequest = GuideRequest.add(live.channels.isNotEmpty()) },
                onOpenSubtitles = { panel = Panel.SUBTITLES },
                onOpenAudio = { panel = Panel.AUDIO },
                onOpenStats = { panel = Panel.STATS },
                onToggleFormat = onToggleFormat,
                skipLabel = skipLabel,
                skipFocus = skipFocus,
                onSkipPrompt = skip,
                onSkipFocus = { skipFocused = it },
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }

        // With the controls up the prompt is part of them, in the title's row. Without
        // them it sits low in the corner on its own. Either way the same requester, so
        // focus follows it from one to the other when the controls come and go.
        if (skipLabel != null && !controlsShowing) {
            TvActionButton(
                label = skipLabel,
                onClick = skip,
                emphasised = true,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = 27.dp)
                    .onFocusChanged { skipFocused = it.isFocused }
                    .focusRequester(skipFocus),
            )
        }

        /*
         * The track menus. Deleted by the same rework that took the transport, and
         * missed when that was put back — so choosing subtitles or audio set the state
         * that gates every key and drew nothing, leaving the remote dead until back.
         */
        if (panel == Panel.SUBTITLES || panel == Panel.AUDIO) {
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

        if (panel == Panel.STATS) {
            StatsPanel(
                playback = playback,
                player = exoPlayer,
                audioDecoder = audioDecoder,
                focusRequester = panelFocus,
                onClose = { panel = Panel.NONE },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        tileMenu?.let { slot ->
            TileMenu(
                name = if (slot == 0) playback.title
                else tiles.getOrNull(slot - 1)?.name.orEmpty(),
                focusRequester = tileMenuFocus,
                canClose = slot in 1..tiles.size,
                canAdd = tileCount < 4,
                // Nothing to maximise when the tile already is the screen.
                canMaximize = slotCount > 1,
                onMaximize = {
                    tileMenu = null
                    zoomed = slot
                },
                onAdd = {
                    tileMenu = null
                    guideRequest = GuideRequest.add(live.channels.isNotEmpty())
                },
                onReplace = {
                    tileMenu = null
                    guideRequest = GuideRequest.replace(slot, live.channels.isNotEmpty())
                },
                onClose = {
                    tileMenu = null
                    val index = slot - 1
                    focusedTile = 0
                    onRemoveTile(index)
                },
                onCancel = { tileMenu = null },
                modifier = Modifier.align(Alignment.Center),
            )
        }

    }
}

// ---------------------------------------------------------------- Controls

@Composable
internal fun Controls(
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
    onAddChannel: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenStats: () -> Unit,
    onToggleFormat: () -> Unit,
    /** Skip Intro or Next Episode, when one is being offered; it sits in the title's row. */
    skipLabel: String? = null,
    skipFocus: FocusRequester = remember { FocusRequester() },
    onSkipPrompt: () -> Unit = {},
    onSkipFocus: (Boolean) -> Unit = {},
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
            // Inside the television's safe area: 48 dp at the sides, 27 dp at the bottom.
            // It sat 10 dp off the bottom edge, where a set that crops the picture could
            // cut the buttons off.
            .padding(start = 48.dp, end = 48.dp, top = 20.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        /*
         * Two rows: the bar, then one row holding everything else — what is playing on
         * the left, the transport in the middle, the options on the right. The first
         * version stacked title, subtitle, bar, times and buttons, and at 56 dp buttons
         * took two fifths of the screen, well over the picture it was there to control.
         */

        // Skip Intro, right-aligned above the bar, when there is one to offer.
        if (skipLabel != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TvActionButton(
                    label = skipLabel,
                    onClick = onSkipPrompt,
                    emphasised = true,
                    modifier = Modifier
                        .onFocusChanged { onSkipFocus(it.isFocused) }
                        .focusRequester(skipFocus),
                )
            }
        }

        if (!playback.isLive) {
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

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // What is playing. Weighted the same as the options opposite, so the
            // transport between them sits in the middle of the screen.
            Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (playback.isLive) {
                        Text(
                            text = "LIVE",
                            color = Ink,
                            style = ReelyType.Label.copy(fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp),
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Accent)
                                .padding(horizontal = 7.dp, vertical = 1.dp),
                        )
                    }
                    Text(
                        text = playback.title,
                        color = Chalk,
                        style = ReelyType.Body,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                playback.subtitle?.let {
                    Text(
                        text = it,
                        color = Muted,
                        style = ReelyType.Label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier.focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TransportButton(
                    onClick = { onSkip(-1) },
                    enabled = canSkipBack,
                    diameter = SMALL_BUTTON,
                    glyph = { SkipGlyph(it, forward = false, size = 16.dp) },
                )
                TransportButton(
                    onClick = onTogglePlay,
                    filled = true,
                    diameter = 44.dp,
                    modifier = Modifier.focusRequester(playFocus),
                    glyph = {
                        if (playing) PauseGlyph(it, 20.dp) else PlayGlyph(it, 20.dp)
                    },
                )
                TransportButton(
                    onClick = { onSkip(1) },
                    enabled = canSkipForward,
                    diameter = SMALL_BUTTON,
                    glyph = { SkipGlyph(it, forward = true, size = 16.dp) },
                )
            }

            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Row(
                    modifier = Modifier.focusGroup(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (playback.isLive) {
                        TransportButton(
                            onClick = onAddChannel,
                            diameter = SMALL_BUTTON,
                            glyph = { PlusGlyph(it, 16.dp) },
                        )
                    }
                    TransportButton(
                        onClick = onOpenSubtitles,
                        diameter = SMALL_BUTTON,
                        glyph = { SubtitleGlyph(it, 16.dp) },
                    )
                    TransportButton(
                        onClick = onOpenAudio,
                        diameter = SMALL_BUTTON,
                        glyph = { SpeakerGlyph(it, 16.dp) },
                    )
                    TransportButton(
                        onClick = onOpenStats,
                        diameter = SMALL_BUTTON,
                        glyph = { InfoGlyph(it, 16.dp) },
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
}

/** The transport's smaller buttons: the skips and the options. */
private val SMALL_BUTTON = 36.dp

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
    val played = (positionMs.toFloat() / total).coerceIn(0f, 1f)
    val buffered = (bufferedMs.toFloat() / total).coerceIn(0f, 1f)
    // Times sit in a column that ticks every second; fixed-width digits stop them jiggling.
    val times = ReelyType.Label.copy(fontFeatureSettings = "tnum")

    // The times sit either side of the bar, on its line, rather than on a row of their own.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(text = clock(positionMs), color = Chalk, style = times)
        // The box is as tall as the thumb, so the track can thicken on focus without
        // pushing the times or the buttons about.
        BoxWithConstraints(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .weight(1f)
                .height(SCRUB_THUMB)
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
            val track = if (focused) 8.dp else 5.dp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(track)
                    .clip(CircleShape)
                    .background(Chalk.copy(alpha = if (focused) 0.28f else 0.2f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(buffered)
                        .fillMaxHeight()
                        .background(Chalk.copy(alpha = 0.22f)),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(played)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(Accent),
                )
            }
            if (focused) {
                // Centred on the playhead, and kept inside the bar at either end.
                val x = (maxWidth * played - SCRUB_THUMB / 2).coerceIn(0.dp, maxWidth - SCRUB_THUMB)
                Box(
                    modifier = Modifier
                        .offset(x = x)
                        .size(SCRUB_THUMB)
                        .clip(CircleShape)
                        .background(Chalk),
                )
            }
        }
        Text(
            text = "\u2212" + clock((durationMs - positionMs).coerceAtLeast(0)),
            color = Muted,
            style = times,
        )
    }
}

private val SCRUB_THUMB = 14.dp

// ---------------------------------------------------------------- Track panel

/**
 * What is actually happening to this stream.
 *
 * The one thing worth knowing is whether the server is handing over the file or
 * re-encoding it, because a transcode costs the server and loses quality, and nothing
 * else in the app says which is happening. The codecs and the decoder names are here
 * because when something will not play, they are the first question.
 */
@Composable
internal fun StatsPanel(
    playback: Playback,
    player: ExoPlayer,
    audioDecoder: String? = null,
    focusRequester: FocusRequester,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Formats arrive after the first frame and change on a transcode's quality switch,
    // so this is read again rather than sampled once.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick++
        }
    }

    val video = remember(tick) { player.videoFormat }
    val audio = remember(tick) { player.audioFormat }
    val context = LocalContext.current
    val deviceSound = remember { DeviceAudio(context).summary() }
    // The file's own sound, which is what to describe when none could be selected —
    // otherwise the panel shows a dash exactly when the audio is the question.
    val fileAudio = remember(tick) {
        player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }?.getTrackFormat(0)
    }
    val unplayable = audio == null && fileAudio != null &&
        !player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)
    val dropped = remember(tick) { player.videoDecoderCounters?.droppedBufferCount ?: 0 }

    Column(
        modifier = modifier
            .padding(end = 48.dp, top = 27.dp, bottom = 27.dp)
            .width(420.dp)
            .sheet()
            .padding(24.dp)
            .focusGroup()
            .focusRequester(focusRequester),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Close sits beside the heading: at the foot of the panel it fell below the
        // bottom of the screen once every line here was filled in.
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "Playback info", color = Chalk, style = ReelyType.Headline, modifier = Modifier.weight(1f))
            TvActionButton(label = "Close", onClick = onClose)
        }
        StatLine(
            "Method",
            when {
                playback.isLive -> "Direct play  ·  ${playback.format.label}"
                playback.audioConverted -> "Direct stream  ·  audio converted"
                playback.transcoding -> "Transcoding"
                else -> "Direct play"
            },
        )
        // Whether the server sent intro and credits markers at all, which is the only
        // way to tell a server that has not detected them from a button that failed.
        if (!playback.isLive) StatLine("Intro & credits", describeMarkers(playback.markers))
        StatLine("Source", playback.serverBase?.removePrefix("http://")?.removePrefix("https://")
            ?: "—")

        SectionLabel("VIDEO")
        StatLine("Codec", video?.sampleMimeType?.let(::codecName) ?: "—")
        StatLine(
            "Size",
            video?.takeIf { it.width > 0 }?.let { format ->
                val fps = format.frameRate.takeIf { it > 0f }?.let { "  ·  %.3g fps".format(it) }.orEmpty()
                "${format.width} × ${format.height}$fps"
            } ?: "—",
        )
        StatLine("Bitrate", bitrate(video?.bitrate ?: -1))
        StatLine("Dropped frames", dropped.toString())

        SectionLabel("AUDIO")
        StatLine("Codec", (audio ?: fileAudio)?.sampleMimeType?.let(::codecName) ?: "—")
        StatLine(
            "Decoder",
            when {
                audioDecoder?.startsWith("ffmpeg") == true -> "In app (FFmpeg)"
                audioDecoder != null -> "Device"
                audio != null -> "Passed through"
                else -> "—"
            },
        )
        if (unplayable) StatLine("Status", "Not supported on this device")
        StatLine(
            "Channels",
            (audio ?: fileAudio)?.channelCount?.takeIf { it > 0 }?.let { count ->
                when (count) {
                    1 -> "Mono"
                    2 -> "Stereo"
                    6 -> "5.1"
                    8 -> "7.1"
                    else -> "$count channels"
                }
            } ?: "—",
        )
        StatLine("Rate", audio?.sampleRate?.takeIf { it > 0 }?.let { "$it Hz" } ?: "—")
        StatLine("Bitrate", bitrate(audio?.bitrate ?: -1))
        // What this device said it plays — by decoder, or passed over HDMI to whatever
        // is plugged in — which is what decided whether the sound above was converted.
        StatLine("Supported audio", deviceSound.ifEmpty { "—" })
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = Muted, style = ReelyType.Label)
        Text(
            text = value,
            color = Chalk,
            style = ReelyType.Label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** The small capitals over a group of lines in a panel. */
@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = Faint,
        style = ReelyType.Label,
        letterSpacing = 1.4.sp,
        modifier = modifier.padding(top = 6.dp),
    )
}

/** The part of a mime type anybody says out loud. */
private fun codecName(mime: String): String = when (mime.substringAfter('/')) {
    "avc", "avc1", "h264" -> "H.264"
    "hevc", "hvc1", "hev1" -> "HEVC"
    "av01" -> "AV1"
    "mp4v-es" -> "MPEG-4"
    "mpeg2" -> "MPEG-2"
    "x-vnd.on2.vp9" -> "VP9"
    "mp4a-latm" -> "AAC"
    "ac3" -> "Dolby Digital"
    "eac3", "eac3-joc" -> "Dolby Digital Plus"
    "true-hd" -> "Dolby TrueHD"
    "vnd.dts" -> "DTS"
    "vnd.dts.hd" -> "DTS-HD"
    else -> mime.substringAfter('/').uppercase()
}

private fun bitrate(bits: Int): String = when {
    bits <= 0 -> "—"
    bits >= 1_000_000 -> "%.1f Mbps".format(bits / 1_000_000f)
    else -> "${bits / 1_000} kbps"
}

@Composable
internal fun TrackPanel(
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
            .padding(end = 48.dp, top = 27.dp, bottom = 27.dp)
            .width(420.dp)
            .sheet()
            .padding(24.dp)
            .focusGroup()
            .focusRequester(focusRequester),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = if (panel == Panel.SUBTITLES) "Subtitles" else "Audio",
            color = Chalk,
            style = ReelyType.Headline,
        )

        if (choices.isEmpty()) {
            Text(
                text = if (panel == Panel.SUBTITLES)
                    "No subtitles are available for this video."
                else
                    "There's only one audio track.",
                color = Muted,
                style = ReelyType.Meta,
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
            SectionLabel("Appearance")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvActionButton(label = "Smaller", onClick = { onNudgeScale(-Settings.SCALE_STEP) })
                TvActionButton(label = "Bigger", onClick = { onNudgeScale(Settings.SCALE_STEP) })
            }
            Text(
                text = "Size ${(prefs.subtitleScale * 100).toInt()}%",
                color = Muted,
                style = ReelyType.Label,
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
/**
 * Why there is no sound, in terms of what somebody can do about it. Only reached when the
 * server cannot be asked, or already has been — see [silentAudio].
 */
private fun explainSilence(tracks: Tracks, playback: Playback, prefs: PlayerPrefs): String {
    val sound = tracks.groups
        .firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
        ?.getTrackFormat(0)
        ?.let(::describeAudio)
        ?: "This sound"
    return when {
        playback.isLive ->
            "No sound: this channel's audio ($sound) isn't supported on this device."
        playback.transcoding ->
            "No sound: Plex couldn't convert this audio ($sound)."
        prefs.playbackMode == Settings.MODE_DIRECT ->
            "No sound: this audio ($sound) isn't supported on this device. " +
                "Set Playback mode to Automatic in Settings to have Plex convert it."
        else -> "No sound: this audio ($sound) isn't supported on this device."
    }
}

/** "Dolby Digital Plus 5.1", or as much of that as the format says. */
private fun describeAudio(format: androidx.media3.common.Format): String {
    val codec = format.sampleMimeType?.let(::codecName) ?: "an unknown format"
    val layout = channelLayout(format.channelCount)
    return if (layout == null) codec else "$codec $layout"
}

private fun channelLayout(count: Int): String? = when (count) {
    1 -> "mono"
    2 -> "stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> if (count > 0) "$count-channel" else null
}

/** The server's intro and credits markers, or a plain statement that it sent none. */
private fun describeMarkers(markers: List<tv.reely.plex.PlexMarker>): String {
    if (markers.isEmpty()) return "Not detected"
    return markers.joinToString("  ·  ") { marker ->
        val kind = when {
            marker.isIntro -> "Intro"
            marker.isCredits -> "Credits"
            else -> marker.type.replaceFirstChar { it.uppercase() }
        }
        "$kind ${clock(marker.startMs)}–${clock(marker.endMs)}"
    }
}

private fun describe(error: PlaybackException): String = when (val cause = error.cause) {
    is HttpDataSource.InvalidResponseCodeException -> when (cause.responseCode) {
        401, 403 -> "This couldn't be opened. If it's a live channel, your subscription may " +
            "already be using all its connections — close another stream and try again."

        404 -> "This isn't available right now."
        else -> "This couldn't be played (error ${cause.responseCode})."
    }

    is HttpDataSource.HttpDataSourceException -> "Couldn't connect. Check your network and try again."

    // Anything else is the player's own; its code is kept for anybody reporting it.
    else -> "This couldn't be played (${error.errorCodeName})."
}

/**
 * How much of a film or episode to keep loaded ahead.
 *
 * Normal: a second to start, a ceiling of thirty seconds — channel-switch speed, which is
 * what separates good from bad on live television, and fine on a good home network.
 * Larger: the same start, then it keeps loading to a minute before easing off, holds up
 * to two, and after a stall waits for five seconds in hand rather than two, so an uneven
 * connection does not stutter through a string of short stops. Both are also bounded by
 * the player's memory budget, which a very high bitrate file reaches first.
 */
private fun bufferFor(larger: Boolean): DefaultLoadControl =
    if (larger) {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(60_000, 120_000, 1_500, 5_000)
            .build()
    } else {
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(2_000, 30_000, 1_000, 2_000)
            .build()
    }
