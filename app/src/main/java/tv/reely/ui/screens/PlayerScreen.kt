package tv.reely.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
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
import androidx.compose.runtime.mutableStateMapOf
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
import tv.reely.core.GuideRequest
import tv.reely.core.SkipPrompt
import tv.reely.core.skipPromptAt
import tv.reely.core.LivePlayer
import tv.reely.core.Settings
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
import tv.reely.ui.components.rememberSelectPress
import tv.reely.ui.components.requestWhenReady
import tv.reely.xtream.XtreamApi
import tv.reely.xtream.XtreamCategory
import tv.reely.xtream.XtreamChannel
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Line
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Parchment
import tv.reely.ui.theme.SurfaceRaised

private const val SEEK_STEP_MS = 10_000L
private const val CONTROLS_TIMEOUT_MS = 6_000L

private enum class Panel { NONE, SUBTITLES, AUDIO, STATS }

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
    onToggleFormat: () -> Unit,
    onReportProgress: (Long, Boolean) -> Unit,
    onNudgeSubtitleScale: (Float) -> Unit,
    onToggleSubtitleBackground: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current

    val ownPlayer = remember {
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

    // Live television plays on the shared player the guide was already previewing on, so
    // arriving here from the guide does not restart the stream. Everything else gets its
    // own, which is released with this screen.
    val exoPlayer = if (playback.isLive) livePlayer.player else ownPlayer
    DisposableEffect(Unit) { onDispose { ownPlayer.release() } }

    var playing by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var bufferedMs by remember { mutableLongStateOf(0L) }
    var buffering by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tracksVersion by remember { mutableIntStateOf(0) }

    // A film or episode opening is worth showing the transport for; a channel is not,
    // and raising it there cost six seconds of a dead d-pad before the timeout cleared it.
    var controlsVisible by remember { mutableStateOf(!playback.isLive) }
    // The scrubber is the top of the control bar, so this is "there is nothing above
    // here to move to" — which is what makes another press of up mean "put these away".
    var atTopOfControls by remember { mutableStateOf(false) }
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
    val prompt = skipPromptAt(
        positionMs = positionMs,
        introStartMs = intro?.startMs,
        introEndMs = intro?.endMs,
        creditsStartMs = credits?.startMs,
        canSkipForward = canSkipForward,
        upNextShowing = upNext != null,
    )
    val skipLabel = when (prompt) {
        SkipPrompt.INTRO -> "Skip Intro"
        SkipPrompt.NEXT_EPISODE -> "Next Episode"
        null -> null
    }

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
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
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
                if (deviceCannotPlay && !playback.transcoding) {
                    onDecodeFailure(exoPlayer.currentPosition.coerceAtLeast(0))
                } else {
                    error = describe(playbackError)
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    LaunchedEffect(playback.url) {
        error = null
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
    ) {
        if (guideOpen) return@LaunchedEffect
        when {
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
                if (tileMenu != null) return@onPreviewKeyEvent false
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
            mainSurface()
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
        if (controlsVisible && !guideOpen && tileCount == 1) {
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
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }

        // Sits clear of the transport when that is up, and near the corner when it is
        // not. Drawn after it so it is never behind it.
        if (skipLabel != null) {
            TvActionButton(
                label = skipLabel,
                onClick = {
                    if (prompt == SkipPrompt.INTRO && intro != null) exoPlayer.seekTo(intro.endMs)
                    else onStepEpisode(1)
                },
                emphasised = true,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 40.dp, bottom = if (controlsVisible) 168.dp else 40.dp)
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
    onAddChannel: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenStats: () -> Unit,
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
                if (playback.isLive) {
                    TransportButton(
                        onClick = onAddChannel,
                        diameter = 36.dp,
                        glyph = { PlusGlyph(it, 17.dp) },
                    )
                }
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
                TransportButton(
                    onClick = onOpenStats,
                    diameter = 36.dp,
                    glyph = { InfoGlyph(it, 17.dp) },
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

/**
 * What is actually happening to this stream.
 *
 * The one thing worth knowing is whether the server is handing over the file or
 * re-encoding it, because a transcode costs the server and loses quality, and nothing
 * else in the app says which is happening. The codecs and the decoder names are here
 * because when something will not play, they are the first question.
 */
@Composable
private fun StatsPanel(
    playback: Playback,
    player: ExoPlayer,
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
    val dropped = remember(tick) { player.videoDecoderCounters?.droppedBufferCount ?: 0 }

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
            text = "Playback",
            color = Parchment,
            fontSize = 18.sp,
            lineHeight = 23.sp,
            fontWeight = FontWeight.SemiBold,
        )
        StatLine(
            "Method",
            when {
                playback.isLive -> "Direct play  ·  ${playback.format.label}"
                playback.transcoding -> "Transcoding"
                else -> "Direct play"
            },
        )
        StatLine("Source", playback.serverBase?.removePrefix("http://")?.removePrefix("https://")
            ?: "—")

        Text(
            text = "VIDEO",
            color = Faint,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            letterSpacing = 1.4.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
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

        Text(
            text = "AUDIO",
            color = Faint,
            fontSize = 11.sp,
            lineHeight = 14.sp,
            letterSpacing = 1.4.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
        StatLine("Codec", audio?.sampleMimeType?.let(::codecName) ?: "—")
        StatLine(
            "Channels",
            audio?.channelCount?.takeIf { it > 0 }?.let { count ->
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

        TvActionButton(label = "Close", onClick = onClose, emphasised = true)
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = Muted, fontSize = 13.sp, lineHeight = 18.sp)
        Text(
            text = value,
            color = Parchment,
            fontSize = 13.sp,
            lineHeight = 18.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
        focus.requestWhenReady()
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
