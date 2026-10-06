package tv.reely.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlin.math.roundToInt
import tv.reely.core.AudioOutputs
import tv.reely.ui.components.FollowAudioOutput
import tv.reely.ui.components.focusFirstOf
import tv.reely.ui.components.rememberOutputPicker
import tv.reely.ui.components.ConnectChosenOutput
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
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.asImageBitmap
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import tv.reely.ui.theme.SurfaceRaised
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
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
import androidx.media3.common.TrackSelectionParameters
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
import tv.reely.core.startsWithServerSubtitles
import tv.reely.core.SilentAudio
import tv.reely.core.silentAudio
import tv.reely.core.PlayerTrack
import tv.reely.core.PlexStream
import tv.reely.core.playerTrackFor
import tv.reely.core.plexStreamFor
import tv.reely.core.sidecarId
import tv.reely.ui.components.digitOf
import tv.reely.plex.PlexItem
import tv.reely.ui.GuideState
import tv.reely.ui.LiveState
import tv.reely.ui.PlayerPrefs
import tv.reely.ui.Playback
import tv.reely.ui.components.MatchFrameRate
import tv.reely.ui.components.ChaptersGlyph
import tv.reely.ui.components.InfoGlyph
import tv.reely.ui.components.PauseGlyph
import tv.reely.ui.components.PlayGlyph
import tv.reely.ui.components.MinusGlyph
import tv.reely.ui.components.PlusGlyph
import tv.reely.ui.components.RestartGlyph
import tv.reely.ui.components.SkipGlyph
import tv.reely.ui.components.isSelect
import tv.reely.ui.components.SpeakerGlyph
import tv.reely.ui.components.SubtitleGlyph
import tv.reely.ui.components.TransportButton
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.components.TvListRow
import tv.reely.ui.components.MenuPanel
import tv.reely.ui.components.MenuHeading
import tv.reely.ui.components.MenuItem
import tv.reely.ui.components.MenuSection
import tv.reely.ui.components.MenuScrim
import tv.reely.ui.components.SearchGlyph
import tv.reely.ui.components.MoonGlyph
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

/** A press that starts a scrub from outside the bar: which way, how far, and which press. */
internal data class ScrubNudge(val direction: Int, val stepMs: Long, val serial: Int)
private const val CONTROLS_TIMEOUT_MS = 6_000L

/** Long enough to read twice from across a room, short enough not to sit on the picture. */
private const val AUDIO_NOTICE_MS = 9_000L

/** Attempts at picking a film up again after the connection drops, and the wait before the first. */
private const val RECONNECT_TRIES = 3
private const val RECONNECT_WAIT_MS = 3_000L
/** Half a minute of starting again, every two seconds, while the Fire TV notices headphones have gone. */
private const val SOUND_RETRIES = 15
private const val SOUND_RETRY_MS = 2_000L

/** What Play does: see [livePlayAction]. */
internal enum class LivePlay { TOGGLE, REJOIN, FROM_PAUSE }

/**
 * Play or pause. Coming back from a pause on live television is coming back to now —
 * unless the channel keeps an archive and the pause was long enough ago to be behind
 * now, when it carries on from the pause, out of the archive.
 */
internal fun livePlayAction(isLive: Boolean, isPlaying: Boolean, rewindable: Boolean, pausedAt: Long?, now: Long): LivePlay =
    when {
        !isLive || isPlaying -> LivePlay.TOGGLE
        rewindable && pausedAt != null && pausedAt < now - LIVE_SLACK_MS -> LivePlay.FROM_PAUSE
        else -> LivePlay.REJOIN
    }

/** How close to now counts as live: a scrub let go this near the live point stays live. */
private const val LIVE_SLACK_MS = 20_000L

/** How long nothing in the player may have the cursor before it's put back. */
private const val PLAYER_RESCUE_DELAY_MS = 150L
private const val NOTICE_MS = 2_500L
/** A moment to ask the server for a single preview picture of, to see if it gives them. */
private const val PROBE_FRAME_MS = 60_000L
private const val CHANNEL_ENTRY_MS = 2_000L
private const val CHANNEL_DIGITS = 5

internal enum class Panel { NONE, SUBTITLES, AUDIO, STATS, CHAPTERS, SLEEP, FIND_SUBTITLES }

private data class TrackChoice(
    val label: String,
    val group: Tracks.Group?,
    val selected: Boolean,
    /** Where the track is among all of its type, as [playerTracks] lists them; null for Off. */
    val trackIndex: Int? = null,
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
    /** The credits have started: find what comes next, and offer it. */
    onCredits: () -> Unit,
    onPlayUpNext: () -> Unit,
    onDismissUpNext: () -> Unit,
    /** Holding OK on a channel in the guide: in or out of Favorites. */
    onToggleFavoriteChannel: (XtreamChannel) -> Unit = {},
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
    /** Saves the channels up now, in their places (tiles left to right; 0 is the main one). */
    onSaveMultiview: (List<Int>) -> Unit = {},
    /** The saved set, named for the menu, or null when it's what's up already. */
    savedMultiview: String? = null,
    onOpenSavedMultiview: () -> Unit = {},
    onStepEpisode: (Int) -> Unit,
    onDecodeFailure: (Long) -> Unit,
    /** The connection to the Plex server dropped: ask for the title again, afresh, from here. */
    onReopen: (Long) -> Unit = {},
    /** The file's sound cannot be played here; ask the server to convert just that. */
    onConvertAudio: (Long) -> Unit,
    onToggleFormat: () -> Unit,
    /** Where it's up to, whether it's playing, and how long the file runs as far as the player knows (0 when it doesn't). */
    onReportProgress: (Long, Boolean, Long) -> Unit,
    onNudgeSubtitleScale: (Float) -> Unit,
    onToggleSubtitleBackground: () -> Unit,
    /** Where sound goes, by AudioOutputs' key; null for wherever the system sends it. */
    onSetAudioOutput: (String?) -> Unit = {},
    /**
     * The programme the live bar spans, where the channel's archive can go back into it;
     * null for a channel that can't be rewound. See ReelyViewModel.liveWindow.
     */
    liveWindow: tv.reely.ui.Timeshift? = null,
    /** Back to this moment, in epoch milliseconds, out of the channel's archive. */
    onTimeshift: (Long) -> Unit = {},
    /** From behind, back to the channel as it is now. */
    onGoLive: () -> Unit = {},
    /** A sound or subtitle choice to keep with Plex: stream ids, "0" for subtitles off. */
    onSaveStreamChoice: (audio: String?, subtitle: String?) -> Unit = { _, _ -> },
    /** Subtitles being looked for online, and looking, choosing and giving up. */
    subtitleSearch: tv.reely.ui.SubtitleSearch? = null,
    onFindSubtitles: () -> Unit = {},
    onAddSubtitle: (tv.reely.plex.PlexOnlineSubtitle) -> Unit = {},
    onCloseSubtitleSearch: () -> Unit = {},
    /** Programmes with a reminder, for the guide over the picture, and setting one there. */
    reminders: List<tv.reely.core.Reminder> = emptyList(),
    onToggleReminder: (XtreamChannel, tv.reely.xtream.EpgProgramme) -> Unit = { _, _ -> },
    /** A programme starting that somebody asked to be reminded of, and the answers to it. */
    reminder: tv.reely.core.Reminder? = null,
    onWatchReminder: () -> Unit = {},
    onDismissReminder: () -> Unit = {},
    /** The sleep timer, if one is set, and setting it: minutes, 0 for the episode's end, null off. */
    sleep: tv.reely.ui.SleepTimer? = null,
    onSetSleep: (Int?) -> Unit = {},
    /** The programme on now, from its beginning, when the channel keeps an archive; else null. */
    onStartOver: (() -> Unit)? = null,
    /** A programme from a channel's archive, chosen in the guide over the picture. */
    onCatchUp: (XtreamChannel, tv.reely.xtream.EpgProgramme) -> Unit = { _, _ -> },
    /** The channel with a number typed on the remote, if there is one. */
    findChannel: (Int) -> XtreamChannel? = { null },
    onTuneChannel: (XtreamChannel) -> Unit = {},
    imageUrl: (String?, String?, Int, Int) -> String?,
    logoUrl: (String?, String?) -> String?,
    modifier: Modifier = Modifier,
    /**
     * On a phone or tablet: a tap shows or hides the controls, a double tap either side
     * skips ten seconds, a swipe up or down changes channel, and the bar takes a drag.
     * A television never sends a touch, so there this changes nothing.
     */
    touch: Boolean = false,
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
     * The connection to the server dropped mid-way. Picked up again where it stopped, a
     * few times with a growing wait, as Plex's own app does; before, it said to try again
     * and left nothing that would, short of leaving and resuming. After the last attempt
     * it waits for OK.
     */
    var reconnects by remember { mutableIntStateOf(0) }
    // Starting again after the sound lost its output; see onPlayerError.
    var soundRetries by remember { mutableIntStateOf(0) }
    var reconnectAt by remember { mutableLongStateOf(0L) }
    // The next try is for a dropped connection rather than lost sound; see reconnect.
    var reconnectFresh by remember { mutableStateOf(false) }
    // Which of the playback's fresh starts has been loaded; see LaunchedEffect(playback.url).
    var reopenedSeen by remember { mutableIntStateOf(playback.reopened) }
    /*
     * A Plex title whose connection dropped is asked for again as a new stream: the old
     * address can belong to a session the server has already ended, and then every try at
     * it fails the same way. Anything else — lost sound, an IPTV film — starts the same
     * stream again.
     */
    fun reconnect() {
        if (reconnectFresh && playback.onPlex) {
            error = "Reconnecting…"
            onReopen(exoPlayer.currentPosition.coerceAtLeast(0))
        } else {
            error = null
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
        }
    }
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
    // Go live has the cursor: it sits above the bar, so up from the bar goes to it rather
    // than putting the controls away, and up from it does that instead.
    var goLiveFocused by remember { mutableStateOf(false) }
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
    // focusedTile is a place on screen. Which tile is in each place is `places`: tiles can
    // be moved, and each keeps its own picture as it goes (see TileLayout).
    var focusedTile by remember { mutableIntStateOf(0) }
    if (focusedTile >= slotCount) focusedTile = 0
    var order by remember { mutableStateOf(listOf(0)) }
    val places = tileOrder(order, tileCount) + (if (hasSpare) listOf(addSlot) else emptyList())
    /** The tile in [place]. */
    fun tileIn(place: Int): Int = places.getOrElse(place) { place }
    val focusedId = tileIn(focusedTile)

    // One tile filling the screen without the others being torn down. Choosing a tile
    // used to collapse the grid to that channel, which meant the only way back was
    // building it again — and rebuilding it costs the provider connections it had
    // already granted.
    var zoomed by remember { mutableStateOf<Int?>(null) }
    // Held by which tile it is, not where: moving tiles about doesn't change what's zoomed.
    if (zoomed != null && (tileCount == 1 || zoomed!! > tiles.size)) zoomed = null

    // The extra channels' players, kept by the screen rather than by the tiles that draw
    // them, so a tile can move between the grid and full screen without its stream being
    // torn down and dialled again.
    val extraUrls = live.credentials?.let { credentials ->
        tiles.map { XtreamApi.streamUrl(credentials, it, live.format) }
    }.orEmpty()
    val extraPlayers = remember { mutableStateMapOf<String, ExoPlayer>() }
    // Sound where it was sent. Live TV's own player is kept there by the app, guide and all.
    FollowAudioOutput(listOf(ownPlayer) + extraPlayers.values, prefs.audioOutput)
    ConnectChosenOutput(prefs.audioOutput)
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
    LaunchedEffect(focusedId, tileCount) {
        exoPlayer.volume = if (focusedId == 0) 1f else 0f
    }
    var interaction by remember { mutableIntStateOf(0) }
    /*
     * Left or right with the controls down, or fast-forward and rewind, start a scrub on
     * the bar, preview and all, as Plex's own player does. They used to raise the controls
     * with the cursor on Play, or jump thirty seconds blind, and the preview was only ever
     * seen by somebody who knew to go up to the bar first.
     */
    var nudge by remember { mutableStateOf<ScrubNudge?>(null) }
    var scrubFirst by remember { mutableStateOf(false) }
    var scrubRequests by remember { mutableIntStateOf(0) }
    // The server's scrubbing pictures for this file, fetched once as it starts.
    var previews by remember(playback.previewUrl) { mutableStateOf<tv.reely.core.PreviewIndex?>(null) }
    // Whether asking found none, which is when the bar shows the time alone, and why.
    var noPreviews by remember(playback.previewUrl) { mutableStateOf(false) }
    var previewProblem by remember(playback.previewUrl) { mutableStateOf<String?>(null) }
    // The whole file wouldn't come, but single pictures do: the bar shows those one by one.
    var framesOnly by remember(playback.previewUrl) { mutableStateOf(false) }
    LaunchedEffect(playback.previewUrl) {
        val url = playback.previewUrl ?: return@LaunchedEffect
        val fetched = tv.reely.core.PreviewIndex.fetch(url.replace("/{ms}", ""), context.cacheDir)
        previews = fetched.index
        if (fetched.index == null) {
            framesOnly = tv.reely.core.PreviewIndex.hasFrames(url.replace("{ms}", PROBE_FRAME_MS.toString()))
            noPreviews = !framesOnly
            previewProblem = fetched.problem
        }
    }
    val previewUrl = playback.previewUrl.takeIf { !noPreviews }
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
        canSkipForward = canSkipForward,
        upNextShowing = upNext != null,
    )
    /*
     * Past the intro without being asked, when that's what Settings says. Once per
     * episode: going back into the intro afterwards is somebody wanting to hear it.
     */
    var introSkipped by remember(playback.url) { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(prompt == SkipPrompt.INTRO, prefs.skipIntros) {
        if (prompt == SkipPrompt.INTRO && prefs.skipIntros && !introSkipped && intro != null) {
            introSkipped = true
            exoPlayer.seekTo(intro.endMs)
            notice = "Intro skipped"
        }
    }
    /*
     * A channel number typed on the remote. It tunes a moment after the last digit, or
     * straight away on OK, as a cable box does.
     */
    var typedChannel by remember { mutableStateOf("") }
    val typedMatch = typedChannel.toIntOrNull()?.let(findChannel)
    fun tuneTyped() {
        val number = typedChannel.toIntOrNull()
        typedChannel = ""
        val channel = number?.let(findChannel)
        if (channel != null) onTuneChannel(channel) else if (number != null) notice = "There's no channel $number."
    }
    LaunchedEffect(typedChannel) {
        if (typedChannel.isNotEmpty()) {
            delay(CHANNEL_ENTRY_MS)
            tuneTyped()
        }
    }
    // The sleep timer running out: out of the player, where it was saved, as Back does.
    LaunchedEffect(sleep?.atMs) {
        val at = sleep?.atMs ?: return@LaunchedEffect
        delay((at - System.currentTimeMillis()).coerceAtLeast(0))
        onExit(exoPlayer.currentPosition.coerceAtLeast(0))
    }
    // Its own effect: the one above is cancelled by the seek it makes.
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MS)
            notice = null
        }
    }
    val skipLabel = when (prompt) {
        SkipPrompt.INTRO -> "Skip Intro"
        SkipPrompt.NEXT_EPISODE -> "Next Episode"
        null -> null
    }

    /*
     * Up Next comes up when the credits start, as it does in Plex, rather than only once
     * the file has run out. Once per episode: somebody who chose to watch the credits
     * is not asked again until they are over, when the end of the file asks.
     */
    val inCredits = !playback.isLive && positionIsCurrent && credits != null && positionMs >= credits.startMs
    var creditsOffered by remember(playback.url) { mutableStateOf(false) }
    LaunchedEffect(inCredits, playback.url) {
        if (inCredits && !creditsOffered) {
            creditsOffered = true
            // Skip credits, in Settings: straight on to the next episode, where there is one.
            // The last episode, and films, still end with Up Next as before.
            // Not with the sleep timer set for this episode's end: it runs out instead.
            val lastOne = sleep?.endOfEpisode == true
            if (prefs.skipCredits && canSkipForward && !lastOne) onStepEpisode(1) else onCredits()
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
                    // playing over the launcher. Twice bitten. Stopped, not paused: each
                    // holds one of the provider's few streams for as long as it's loaded.
                    extraPlayers.values.forEach { it.stop() }
                    if (!playback.isLive && playback.ratingKey != null) {
                        onReportProgress(exoPlayer.currentPosition.coerceAtLeast(0), false, exoPlayer.duration.coerceAtLeast(0))
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
    // The file whose server-chosen tracks have been applied, so it happens once.
    var serverChoiceFor by remember { mutableStateOf<String?>(null) }
    val currentPrefs by rememberUpdatedState(prefs)

    /*
     * The listener only hears changes. Live TV shares one player with the guide's preview,
     * so a channel chosen from the guide can already be playing when this screen opens:
     * no change ever arrives, and the loading ring, which starts out on, stayed on over a
     * channel that was playing fine. So read where the player actually is, on opening and
     * on each new stream, and let the listener take it from there.
     */
    LaunchedEffect(exoPlayer, playback.url) {
        val state = exoPlayer.playbackState
        // Idle is a stream not started yet, which is loading as far as anybody watching
        // can tell; only ready (or the end) takes the ring away.
        buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
        playing = exoPlayer.isPlaying
        if (state == Player.STATE_READY) error = null
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
                ended = state == Player.STATE_ENDED
                if (state == Player.STATE_ENDED) onEnded()
            }

            override fun onTracksChanged(tracks: Tracks) {
                tracksVersion++
                val now = currentPlayback
                // Once per file, the sound and subtitles Plex has chosen for this account.
                // Not for a conversion, where the server has already applied them.
                if (!now.isLive && !now.transcoding && serverChoiceFor != now.url && !tracks.isEmpty) {
                    serverChoiceFor = now.url
                    applyServerChoice(exoPlayer, now, currentPrefs.subtitlesAtStart)
                }
                when (
                    silentAudio(
                        hasAudio = tracks.containsType(C.TRACK_TYPE_AUDIO),
                        audioSelected = tracks.isTypeSelected(C.TRACK_TYPE_AUDIO),
                        isLive = now.isLive,
                        fromPlex = now.onPlex,
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
                // The sound had nowhere to go — headphones died or were switched off.
                // Nothing wrong with what's playing, and nothing Plex converting it would
                // help: it starts again on whatever the sound goes to now, the TV, once the
                // Fire TV has noticed they've gone. Live TV does that itself (LivePlayer).
                if (tv.reely.core.AudioOutputs.lostOutput(playbackError.errorCode)) {
                    if (soundRetries < SOUND_RETRIES) {
                        soundRetries++
                        error = "Headphones or speaker disconnected. Carrying on with the TV…"
                        reconnectFresh = false
                        if (!currentPlayback.isLive) reconnectAt = System.currentTimeMillis() + SOUND_RETRY_MS
                    } else {
                        error = "The sound has nowhere to play. Check the TV or your headphones, and press OK to try again."
                    }
                    return
                }
                // Anything in the parsing, decoding or audio-output ranges means this
                // device could not handle the file — which is what the server's
                // transcoder is for. Network errors are not that, and stay errors.
                val deviceCannotPlay = playbackError.errorCode in 3_000..5_999
                val connection = playbackError.errorCode in 2_000..2_999
                // Only a Plex server can convert; an IPTV provider's file plays as it is or not at all.
                if (deviceCannotPlay && !currentPlayback.transcoding && currentPlayback.onPlex) {
                    onDecodeFailure(exoPlayer.currentPosition.coerceAtLeast(0))
                } else if (connection && !currentPlayback.isLive && reconnects < RECONNECT_TRIES) {
                    // Live channels have their own; see LivePlayer.
                    reconnects++
                    reconnectFresh = true
                    error = "Reconnecting…"
                    reconnectAt = System.currentTimeMillis() + RECONNECT_WAIT_MS * reconnects
                } else {
                    reconnectFresh = connection
                    // The player's own number for what went wrong, for anyone helping find out why.
                    val code = " (Error ${playbackError.errorCode})"
                    error = if (connection && currentPlayback.fromIptv) "Lost the connection to your IPTV provider. Press OK to try again.$code"
                        else if (connection && !currentPlayback.isLive) "Lost the connection to your Plex server. Press OK to try again.$code"
                        else describe(playbackError)
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

    LaunchedEffect(reconnectAt) {
        if (reconnectAt == 0L) return@LaunchedEffect
        delay((reconnectAt - System.currentTimeMillis()).coerceAtLeast(0))
        reconnect()
    }
    // Playing again: the next drop gets its full set of attempts.
    LaunchedEffect(playing) {
        if (playing) {
            reconnects = 0
            soundRetries = 0
        }
    }

    LaunchedEffect(playback.url, playback.reopened) {
        // Asked for afresh after a drop, the tries carry on counting; otherwise a server
        // that never came back would be asked forever. Anything new starts from none.
        if (playback.reopened <= reopenedSeen) reconnects = 0
        reopenedSeen = playback.reopened
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

    /*
     * New subtitles added to the file while it plays, found online: the file is loaded
     * again where it was, with them beside it, and the server's choice — the new one —
     * applied afresh.
     */
    var loadedSubtitles by remember(playback.url) { mutableStateOf(playback.subtitles) }
    LaunchedEffect(playback.subtitles) {
        if (playback.isLive || playback.subtitles == loadedSubtitles) return@LaunchedEffect
        loadedSubtitles = playback.subtitles
        val at = exoPlayer.currentPosition.coerceAtLeast(0)
        serverChoiceFor = null
        exoPlayer.setMediaItem(buildMediaItem(playback), at)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
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
    LaunchedEffect(playback.ratingKey, playback.serverBase, playing) {
        if (playback.isLive || playback.ratingKey == null) return@LaunchedEffect
        while (true) {
            onReportProgress(exoPlayer.currentPosition.coerceAtLeast(0), playing, exoPlayer.duration.coerceAtLeast(0))
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
    val panelButtons = remember { Panel.entries.associateWith { FocusRequester() } }
    val tileMenuFocus = remember { FocusRequester() }

    /*
     * A FocusRequester throws until the node it is attached to has been laid out, and on
     * the player's first frame none of them have been. Swallowing that left the controls
     * on screen with nothing focused: they were visible but dead, and only came to life
     * after they timed out and were summoned back, which re-ran this against nodes that
     * existed by then. So keep asking for a few frames instead of giving up on the first.
     */
    /*
     * Rewinding live television. The provider only ever sends now, so going back comes
     * out of the channel's archive: the bar spans the programme on now, with the live
     * point moving along it, and letting go behind it plays from there. Behind, it plays
     * like a film, with Go live to come back — or going forward to the live point does.
     */
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(liveWindow != null) {
        while (liveWindow != null) {
            nowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val liveRewind = playback.isLive && liveWindow != null && slotCount == 1
    val behindLive = !playback.isLive && playback.timeshift != null
    val liveSpanMs = liveWindow?.let { (it.stop - it.start) * 1000 } ?: 0L
    val liveEdgeMs = liveWindow?.let { (nowMs - it.start * 1000).coerceIn(0, liveSpanMs) } ?: 0L

    /** When live television was paused, by the clock, to carry on from there. */
    var livePausedAt by remember(playback.url) { mutableStateOf<Long?>(null) }

    /*
     * Play and pause, from the button or the remote. Coming back from a pause on live
     * television means coming back to now, not to the moment it was paused — which is
     * behind the live window by definition and would only fail. Unless the channel keeps
     * an archive: then it carries on from the pause, out of that, as a recorder would.
     */
    fun playPause() {
        val now = System.currentTimeMillis()
        when (livePlayAction(playback.isLive, exoPlayer.isPlaying, liveRewind, livePausedAt, now)) {
            LivePlay.FROM_PAUSE -> livePausedAt?.let(onTimeshift)
            LivePlay.REJOIN -> livePlayer.rejoin()
            LivePlay.TOGGLE -> {
                if (playback.isLive && liveRewind) livePausedAt = now
                togglePlay(exoPlayer)
            }
        }
    }

    fun startScrub(direction: Int, stepMs: Long) {
        scrubFirst = true
        controlsVisible = true
        nudge = ScrubNudge(direction, stepMs, (nudge?.serial ?: 0) + 1)
        scrubRequests++
    }

    /*
     * The cursor onto the bar for a scrub started from outside it. An effect of its own,
     * keyed on nothing the bar changes: this used to ride on the one below, keyed on the
     * press itself, and the bar spending the press cancelled the move half-way, so the
     * cursor landed on Play and the preview, which shows only on the bar, never came up.
     */
    LaunchedEffect(scrubRequests) {
        if (scrubRequests == 0 || (playback.isLive && !liveRewind)) return@LaunchedEffect
        focusFirstOf(scrubberFocus, playFocus, rootFocus)
        // Held for a frame: the effect below starts in the same frame as this one, and
        // seeing the flag already down it put the cursor straight back on Play.
        withFrameNanos { }
        scrubFirst = false
    }

    // The panel last open, so closing it puts the cursor back on the button that opened
    // it. Going to Play instead meant finding Subtitles again to change your mind.
    var closedPanel by remember { mutableStateOf<Panel?>(null) }

    /*
     * The net under the player, as the app has under its pages. When whatever has the
     * cursor is taken away — a list's rows replaced by what it was loading, a device
     * disconnected from the sound menu — it has nowhere to be, and the remote goes dead
     * until Back. The player sits outside the app's own net, so it keeps one of its own:
     * the cursor gone, or left on the bare picture behind a menu that's open, is put back
     * where it belongs.
     */
    var hasFocus by remember { mutableStateOf(true) }
    var bareFocus by remember { mutableStateOf(false) }
    var refocus by remember { mutableIntStateOf(0) }
    // Lost: nowhere at all, or on the bare picture while there's something up that should
    // have it — a menu, the tile menu, Up Next, or the controls themselves.
    val lostCursor = !hasFocus ||
        (bareFocus && (panel != Panel.NONE || tileMenu != null || postPlay || (controlsVisible && !guideOpen && slotCount == 1)))
    LaunchedEffect(lostCursor) {
        if (!lostCursor) return@LaunchedEffect
        // Anything moving the cursor on purpose has a moment to do it first.
        delay(PLAYER_RESCUE_DELAY_MS)
        refocus++
    }
    val trackFocus = Modifier.onFocusChanged {
        hasFocus = it.hasFocus
        bareFocus = it.isFocused
    }

    LaunchedEffect(
        controlsVisible, panel, guideOpen, tileMenu, playback.isLive, playback.url,
        postPlay, refocus,
    ) {
        if (guideOpen) return@LaunchedEffect
        val cameFrom = closedPanel
        closedPanel = null
        // Each with the picture itself to fall back on, which is always there and takes
        // the keys: see focusFirstOf.
        when {
            postPlay -> focusFirstOf(upNextFocus, rootFocus)
            tileMenu != null -> focusFirstOf(tileMenuFocus, rootFocus)
            panel != Panel.NONE -> {
                closedPanel = panel
                focusFirstOf(panelFocus, rootFocus)
            }
            // Above the transport: a prompt that is only up for a few seconds is no use
            // if reaching it means hunting for it first.
            // The effect above is putting the cursor on the bar; don't take it to Play.
            scrubFirst && controlsVisible && (!playback.isLive || liveRewind) -> Unit
            // Finding subtitles is reached from the Subtitles menu and has no button of
            // its own: back to Subtitles. Asking for a button that wasn't there left the
            // cursor behind the controls, and nothing on them worked until they'd gone.
            cameFrom != null && controlsVisible -> focusFirstOf(
                panelButtons.getValue(if (cameFrom == Panel.FIND_SUBTITLES) Panel.SUBTITLES else cameFrom),
                playFocus,
                rootFocus,
            )
            skipLabel != null -> focusFirstOf(skipFocus, playFocus, rootFocus)
            controlsVisible -> focusFirstOf(playFocus, rootFocus)
            else -> rootFocus.requestWhenReady()
        }
    }

    /*
     * The skip prompt coming and going on its own, as the intro or the credits pass under
     * the playhead. It takes the cursor when nothing is being done with the controls, so
     * one press of OK skips. It doesn't while the bar has it: that is somebody scrubbing,
     * and the prompt appearing sent the cursor off the bar mid-scrub. When the prompt goes
     * with the cursor on it, the cursor goes to Play, or with the controls down, to the
     * picture, so the remote still does something.
     */
    LaunchedEffect(skipLabel != null) {
        if (guideOpen || postPlay || tileMenu != null || panel != Panel.NONE) return@LaunchedEffect
        if (skipLabel != null) {
            if (!(controlsVisible && atTopOfControls)) focusFirstOf(skipFocus, rootFocus)
        } else if (skipFocused) {
            skipFocused = false
            if (controlsVisible) focusFirstOf(playFocus, rootFocus) else rootFocus.requestWhenReady()
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
            .then(trackFocus)
            .focusRequester(rootFocus)
            .focusable()
            .then(
                if (!touch) Modifier
                else Modifier
                    .pointerInput(playback.url) {
                        detectTapGestures(
                            onTap = {
                                if (controlsVisible) controlsVisible = false else interaction++
                            },
                            onDoubleTap = { at ->
                                // Not live: there, the bar is the programme and the archive's.
                                if (playback.isLive) return@detectTapGestures
                                val forward = at.x > size.width / 2
                                val to = (exoPlayer.currentPosition + if (forward) TOUCH_SKIP_MS else -TOUCH_SKIP_MS)
                                    .coerceIn(0, exoPlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE)
                                exoPlayer.seekTo(to)
                                interaction++
                            },
                        )
                    }
                    .pointerInput(playback.isLive) {
                        if (!playback.isLive) return@pointerInput
                        var travelled = 0f
                        detectVerticalDragGestures(
                            onDragStart = { travelled = 0f },
                            onDragEnd = {
                                // Up for the next channel, as on a remote's Channel Up.
                                if (kotlin.math.abs(travelled) > TOUCH_SWIPE_DP.toPx()) {
                                    onStepChannel(if (travelled < 0) 1 else -1)
                                }
                            },
                        ) { _, dy -> travelled += dy }
                    },
            )
            .onPreviewKeyEvent { event ->
                // A reminder up has the cursor on its buttons; the keys are its.
                if (reminder != null) return@onPreviewKeyEvent false
                // Stuck after the connection dropped: OK, or Play, tries again from there.
                if (error != null && !playback.isLive && reconnects >= RECONNECT_TRIES &&
                    exoPlayer.playbackState == androidx.media3.common.Player.STATE_IDLE &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter ||
                        event.key == Key.MediaPlay || event.key == Key.MediaPlayPause)
                ) {
                    if (event.type == KeyEventType.KeyUp) {
                        reconnects = 0
                        reconnect()
                    }
                    return@onPreviewKeyEvent true
                }
                // Number keys type a channel, one picture on screen and nothing open over it.
                if (playback.isLive && slotCount == 1 && !guideOpen && panel == Panel.NONE && tileMenu == null) {
                    val digit = digitOf(event.key)
                    if (digit != null) {
                        if (event.type == KeyEventType.KeyDown) {
                            typedChannel = (typedChannel + digit).take(CHANNEL_DIGITS)
                        }
                        return@onPreviewKeyEvent true
                    }
                    if (typedChannel.isNotEmpty()) {
                        when (event.key) {
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                if (event.type == KeyEventType.KeyDown) tuneTyped()
                                return@onPreviewKeyEvent true
                            }
                            Key.Back -> {
                                if (event.type == KeyEventType.KeyDown) typedChannel = ""
                                return@onPreviewKeyEvent true
                            }
                        }
                    }
                }
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
                                else -> zoomed = if (zoomed == focusedId) null else focusedId
                            }
                        },
                        onHold = { if (focusedId != addSlot) tileMenu = focusedId },
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
                        focusedId in 1..tiles.size -> {
                            order = withoutTile(places.filter { it < tileCount }, focusedId)
                            // Onto the main channel, wherever it has been moved to.
                            focusedTile = order.indexOf(0).coerceAtLeast(0)
                            onRemoveTile(focusedId - 1)
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
                    ((atTopOfControls && skipLabel == null && !behindLive) || skipFocused || goLiveFocused)
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
                        // In the focus layout the tiles are a line, left to right: the
                        // one the cursor is on is the big one, in its own place in the line.
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
                        playPause()
                        true
                    }

                    Key.MediaFastForward, Key.MediaRewind -> {
                        val direction = if (event.key == Key.MediaFastForward) 1 else -1
                        // Live, back into the archive where there is one; forward from
                        // live is nowhere, and a channel with no archive can't go back.
                        if (!playback.isLive || liveRewind) startScrub(direction, 30_000)
                        true
                    }

                    Key.DirectionLeft, Key.DirectionRight -> {
                        if (playback.isLive || controlsVisible) false
                        else {
                            startScrub(if (event.key == Key.DirectionRight) 1 else -1, SEEK_STEP_MS)
                            true
                        }
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
                    // The picture follows whichever player is in use. Start over, or a
                    // programme again from the guide over a channel, moves from the live
                    // player to this screen's own; set only once, the picture stayed on
                    // the stopped live one and went black while the sound played on.
                    if (playerView.player !== exoPlayer) playerView.player = exoPlayer
                    playerView.subtitleView?.applyStyle(prefs)
                },
                // A view that has gone lets go of the player, which would otherwise keep
                // it and its surface alive for as long as the player lives.
                onRelease = { it.player = null },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // One description of a tile, drawn either into the grid or over the whole screen.
        val tileAt: @Composable (Int) -> Unit = { index ->
            when {
                index == addSlot -> AddTile(focused = focusedId == index)

                index == 0 -> TileFrame(
                    name = playback.title,
                    focused = focusedId == 0,
                    aspectRatio = rememberVideoAspect(exoPlayer),
                    content = mainSurface,
                )

                else -> {
                    val extra = tiles[index - 1]
                    val player = extraUrls.getOrNull(index - 1)?.let { extraPlayers[it] }
                    if (player == null) {
                        TileFrame(name = extra.name, focused = focusedId == index) {}
                    } else {
                        ExtraTile(
                            player = player,
                            name = extra.name,
                            focused = focusedId == index,
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
                order = places,
                modifier = Modifier.fillMaxSize(),
            ) { tileAt(it) }
        } else {
            MultiViewGrid(slots = slotCount, order = places, modifier = Modifier.fillMaxSize()) { tileAt(it) }
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
        if (typedChannel.isNotEmpty()) {
            ChannelEntry(
                typed = typedChannel,
                match = typedMatch,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 27.dp),
            )
        }

        (audioNotice ?: notice)?.let { message ->
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
                categories = live.shownCategories,
                selectedCategory = live.selectedCategory,
                favorites = live.favorites,
                onToggleFavorite = onToggleFavoriteChannel,
                reminders = reminders,
                onToggleReminder = onToggleReminder,
                onCatchUp = { channel, programme ->
                    guideRequest = GuideRequest.Closed
                    onCatchUp(channel, programme)
                },
                onSelectCategory = onOpenCategory,
                addMode = guideRequest.adds,
                pickVerb = guideRequest.verb,
                onSelect = { index ->
                    guideRequest = GuideRequest.Closed
                    focusedTile = places.indexOf(0).coerceAtLeast(0)
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
        // In picture in picture the window is the picture alone, too small for anything over it.
        val controlsShowing = controlsVisible && !guideOpen && tileCount == 1 && !postPlay &&
            !tv.reely.core.PictureInPicture.active.value
        val skip = {
            if (prompt == SkipPrompt.INTRO && intro != null) exoPlayer.seekTo(intro.endMs)
            else onStepEpisode(1)
        }
        if (controlsShowing && touch) {
            // No remote to press Back on: the way out is on the screen too.
            TouchBack(
                onClick = { onExit(exoPlayer.currentPosition.coerceAtLeast(0)) },
                // Clear of a phone's camera cutout, which sideways sits at this edge.
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.displayCutout)
                    .padding(16.dp),
            )
        }
        // As on the iPhone: back ten seconds, play or pause, and on ten, large in the middle
        // of the picture where a thumb finds them. Live television has the bar's own.
        if (controlsShowing && touch && !playback.isLive) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TouchRound(size = 60.dp, description = "Back 10 seconds", onClick = {
                    exoPlayer.seekTo((exoPlayer.currentPosition - TOUCH_SKIP_MS).coerceAtLeast(0)); interaction++
                }) { TenSeconds(forward = false) }
                TouchRound(size = 78.dp, description = if (playing) "Pause" else "Play", onClick = { togglePlay(exoPlayer); interaction++ }) {
                    if (playing) tv.reely.ui.components.PauseGlyph(Chalk, 30.dp) else tv.reely.ui.components.PlayGlyph(Chalk, 30.dp)
                }
                TouchRound(size = 60.dp, description = "Forward 10 seconds", onClick = {
                    val end = exoPlayer.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                    exoPlayer.seekTo((exoPlayer.currentPosition + TOUCH_SKIP_MS).coerceAtMost(end)); interaction++
                }) { TenSeconds(forward = true) }
            }
        }
        if (controlsShowing) {
            PlayerClock(
                endsAtMs = if (!playback.isLive && durationMs > 0) {
                    (durationMs - positionMs).coerceAtLeast(0)
                } else {
                    null
                },
                modifier = Modifier.align(Alignment.TopEnd),
            )
            Controls(
                // Without the server's pictures, the bar shows the time alone.
                playback = playback.copy(previewUrl = previewUrl),
                playing = playing,
                // Live, the bar is the programme on now, and where it's got to is now.
                positionMs = if (liveRewind) liveEdgeMs else positionMs,
                durationMs = if (liveRewind) liveSpanMs else durationMs,
                bufferedMs = if (liveRewind) liveEdgeMs else bufferedMs,
                showBar = !playback.isLive || liveRewind,
                // Nothing past now to go to, live or behind it.
                scrubLimitMs = if (liveRewind || behindLive) liveEdgeMs else null,
                onGoLive = if (behindLive) ({ interaction++; onGoLive() }) else null,
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
                onSeekTo = { to ->
                    interaction++
                    when {
                        // Let go behind now: out of the archive from there. At now: stays live.
                        liveRewind -> if (to < liveEdgeMs - LIVE_SLACK_MS) {
                            liveWindow?.let { window -> onTimeshift(window.start * 1000 + to) }
                        }
                        // Caught up with now from behind: the channel live again.
                        behindLive && to >= liveEdgeMs - LIVE_SLACK_MS -> onGoLive()
                        else -> {
                            val target = to.coerceIn(0, (durationMs - 1_000).coerceAtLeast(0))
                            exoPlayer.seekTo(target)
                            positionMs = target
                        }
                    }
                },
                onScrub = { interaction++ },
                nudge = nudge,
                onNudgeUsed = { nudge = null },
                previews = previews,
                onTogglePlay = {
                    interaction++
                    playPause()
                },
                onAddChannel = { guideRequest = GuideRequest.add(live.channels.isNotEmpty()) },
                onOpenSubtitles = { panel = Panel.SUBTITLES },
                onOpenAudio = { panel = Panel.AUDIO },
                onOpenStats = { panel = Panel.STATS },
                onOpenChapters = if (playback.chapters.isNotEmpty()) ({ panel = Panel.CHAPTERS }) else null,
                onOpenSleep = { panel = Panel.SLEEP },
                sleeping = sleep != null,
                panelButtons = panelButtons,
                onToggleFormat = onToggleFormat,
                onStartOver = onStartOver,
                skipLabel = skipLabel,
                skipFocus = skipFocus,
                onSkipPrompt = skip,
                onSkipFocus = { skipFocused = it },
                onGoLiveFocus = { goLiveFocused = it },
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

        // The shade every menu has behind it, darkest by the panel; see Menus.kt.
        if (panel != Panel.NONE) MenuScrim(strength = 0.8f)

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
                // Found online by the server, for a file it plays as it is; a conversion
                // burns subtitles in, and a channel has none to find.
                onFindSubtitles = if (playback.onPlex && !playback.transcoding) {
                    {
                        onFindSubtitles()
                        panel = Panel.FIND_SUBTITLES
                    }
                } else null,
                onPicked = { trackType, trackIndex ->
                    if (!playback.transcoding) {
                        val tracks = playerTracks(exoPlayer, trackType).map { it.second }
                        if (trackType == C.TRACK_TYPE_AUDIO) {
                            trackIndex?.let { plexStreamFor(playback.audioStreams, tracks, it) }
                                ?.let { onSaveStreamChoice(it.id, null) }
                        } else {
                            val stream = trackIndex?.let { plexStreamFor(playback.subtitleStreams, tracks, it) }
                            when {
                                trackIndex == null -> onSaveStreamChoice(null, "0")
                                stream != null -> onSaveStreamChoice(null, stream.id)
                            }
                        }
                    }
                },
                onClose = { panel = Panel.NONE },
                audioOutput = prefs.audioOutput,
                onPickOutput = onSetAudioOutput,
                onNudgeScale = onNudgeSubtitleScale,
                onToggleBackground = onToggleSubtitleBackground,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        // A reminder, top right, over whatever is playing: the cursor goes to Watch, and
        // comes back where it belongs once it's answered or has gone.
        val reminderFocus = remember { FocusRequester() }
        var hadReminder by remember { mutableStateOf(false) }
        LaunchedEffect(reminder) {
            if (reminder != null) {
                hadReminder = true
                focusFirstOf(reminderFocus, rootFocus)
            } else if (hadReminder) {
                hadReminder = false
                if (controlsVisible && !playback.isLive) focusFirstOf(playFocus, rootFocus) else rootFocus.requestWhenReady()
            }
        }
        reminder?.let { due ->
            ReminderCard(
                reminder = due,
                focusRequester = reminderFocus,
                onWatch = onWatchReminder,
                onDismiss = onDismissReminder,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 27.dp, end = 48.dp),
            )
        }

        if (panel == Panel.FIND_SUBTITLES) {
            subtitleSearch?.let { search ->
                SubtitleSearchPanel(
                    search = search,
                    focusRequester = panelFocus,
                    onPick = onAddSubtitle,
                    onClose = {
                        onCloseSubtitleSearch()
                        panel = Panel.NONE
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
        // Found and added: the search goes, and so does its panel.
        LaunchedEffect(subtitleSearch == null) {
            if (subtitleSearch == null && panel == Panel.FIND_SUBTITLES) panel = Panel.NONE
        }

        if (panel == Panel.SLEEP) {
            SleepPanel(
                sleep = sleep,
                isLive = playback.isLive,
                focusRequester = panelFocus,
                onPick = { minutes ->
                    onSetSleep(minutes)
                    panel = Panel.NONE
                },
                onClose = { panel = Panel.NONE },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        if (panel == Panel.CHAPTERS) {
            ChapterPanel(
                chapters = playback.chapters,
                positionMs = positionMs,
                focusRequester = panelFocus,
                onPick = { chapter ->
                    exoPlayer.seekTo(chapter.startMs)
                    positionMs = chapter.startMs
                    panel = Panel.NONE
                },
                onClose = { panel = Panel.NONE },
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
            val place = places.indexOf(slot)
            MenuScrim(strength = 0.8f)
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
                    order = withoutTile(places.filter { it < tileCount }, slot)
                    focusedTile = order.indexOf(0).coerceAtLeast(0)
                    onRemoveTile(slot - 1)
                },
                // Never into the spare cell: that stays last, where it's looked for.
                canMoveBack = tileCount > 1 && place > 0,
                canMoveOn = tileCount > 1 && place in 0 until tileCount - 1,
                onMove = { delta ->
                    tileMenu = null
                    order = movedTile(places.filter { it < tileCount }, place, delta)
                    // The cursor goes with the tile, so moving it again is one more hold.
                    focusedTile = place + delta
                },
                canSave = tileCount > 1,
                onSave = {
                    tileMenu = null
                    onSaveMultiview(places.filter { it < tileCount })
                },
                saved = savedMultiview,
                onOpenSaved = {
                    tileMenu = null
                    order = listOf(0)
                    focusedTile = 0
                    zoomed = null
                    onOpenSavedMultiview()
                },
                onCancel = { tileMenu = null },
                modifier = Modifier.align(Alignment.CenterEnd),
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
    /** Go to this point in the file: the end of a scrub. */
    onSeekTo: (Long) -> Unit,
    /** A press while scrubbing, which is using the controls. */
    onScrub: () -> Unit = {},
    /** A scrub started from outside the bar; see ScrubNudge. */
    nudge: ScrubNudge? = null,
    onNudgeUsed: () -> Unit = {},
    previews: tv.reely.core.PreviewIndex? = null,
    onSkip: (Int) -> Unit,
    onTogglePlay: () -> Unit,
    onAddChannel: () -> Unit,
    onOpenSubtitles: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenStats: () -> Unit,
    onToggleFormat: () -> Unit,
    /** Live: the programme on now from its beginning, where the channel keeps an archive. */
    onStartOver: (() -> Unit)? = null,
    /** Whether there's a bar: always but for a live channel that can't be rewound. */
    showBar: Boolean = !playback.isLive,
    /** The furthest the bar can be taken: now, live or behind it. */
    scrubLimitMs: Long? = null,
    /** Behind live: back to the channel as it is now. */
    onGoLive: (() -> Unit)? = null,
    onGoLiveFocus: (Boolean) -> Unit = {},
    onOpenSleep: () -> Unit = {},
    /** A sleep timer is set: its button is lit. */
    sleeping: Boolean = false,
    /** The film's chapters, when it has some. */
    onOpenChapters: (() -> Unit)? = null,
    /** Skip Intro or Next Episode, when one is being offered; it sits in the title's row. */
    skipLabel: String? = null,
    skipFocus: FocusRequester = remember { FocusRequester() },
    onSkipPrompt: () -> Unit = {},
    onSkipFocus: (Boolean) -> Unit = {},
    /** The buttons that open each panel, for the cursor to go back to when it closes. */
    panelButtons: Map<Panel, FocusRequester> = remember { Panel.entries.associateWith { FocusRequester() } },
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

        // Behind live: the way back to now, where Skip Intro would sit above the bar.
        if (onGoLive != null && skipLabel == null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TvActionButton(
                    label = "Go live",
                    onClick = onGoLive,
                    emphasised = true,
                    modifier = Modifier.onFocusChanged { onGoLiveFocus(it.isFocused) },
                )
            }
        }

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

        if (showBar) {
            Scrubber(
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedMs = bufferedMs,
                limitMs = scrubLimitMs,
                focusRequester = scrubberFocus,
                onFocusState = onScrubberFocus,
                onSeekTo = onSeekTo,
                onTogglePlay = onTogglePlay,
                onScrub = onScrub,
                previewUrl = playback.previewUrl,
                nudge = nudge,
                onNudgeUsed = onNudgeUsed,
                previews = previews,
                chapters = playback.chapters.filter { it.thumbUrl != null },
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
                    if (onOpenChapters != null) {
                        TransportButton(
                            onClick = onOpenChapters,
                            diameter = SMALL_BUTTON,
                            modifier = Modifier.focusRequester(panelButtons.getValue(Panel.CHAPTERS)),
                            glyph = { ChaptersGlyph(it, 16.dp) },
                        )
                    }
                    TransportButton(
                        onClick = onOpenSubtitles,
                        diameter = SMALL_BUTTON,
                        modifier = Modifier.focusRequester(panelButtons.getValue(Panel.SUBTITLES)),
                        glyph = { SubtitleGlyph(it, 16.dp) },
                    )
                    TransportButton(
                        onClick = onOpenAudio,
                        diameter = SMALL_BUTTON,
                        modifier = Modifier.focusRequester(panelButtons.getValue(Panel.AUDIO)),
                        glyph = { SpeakerGlyph(it, 16.dp) },
                    )
                    TransportButton(
                        onClick = onOpenSleep,
                        diameter = SMALL_BUTTON,
                        filled = sleeping,
                        modifier = Modifier.focusRequester(panelButtons.getValue(Panel.SLEEP)),
                        glyph = { tv.reely.ui.components.MoonGlyph(it, 16.dp) },
                    )
                    TransportButton(
                        onClick = onOpenStats,
                        diameter = SMALL_BUTTON,
                        modifier = Modifier.focusRequester(panelButtons.getValue(Panel.STATS)),
                        glyph = { InfoGlyph(it, 16.dp) },
                    )
                    // Round, like the rest of the row. Words here made one button twice the
                    // size of the others and the row looked crammed; the stream format that
                    // sat beside it is a setting, and lives in Settings, Live TV.
                    if (playback.isLive && onStartOver != null) {
                        TransportButton(
                            onClick = onStartOver,
                            diameter = SMALL_BUTTON,
                            glyph = { RestartGlyph(it, 16.dp) },
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
    onSeekTo: (Long) -> Unit,
    onTogglePlay: () -> Unit,
    /** Each press, so the controls stay up while somebody is scrubbing. */
    onScrub: () -> Unit = {},
    previewUrl: String? = null,
    nudge: ScrubNudge? = null,
    onNudgeUsed: () -> Unit = {},
    previews: tv.reely.core.PreviewIndex? = null,
    /** The file's chapters with pictures, for a server that made those and not previews. */
    chapters: List<tv.reely.plex.PlexChapter> = emptyList(),
    /** The furthest a scrub can go, when that's short of the end: now, on live television. */
    limitMs: Long? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val total = durationMs.coerceAtLeast(1)
    val furthest = limitMs?.coerceAtMost(durationMs) ?: (durationMs - 1_000).coerceAtLeast(0)
    /*
     * Where the scrub has got to, before it is committed. Left and right move this, not
     * the picture: seeking on every press made the player throw away its buffer and
     * start again at each step, so holding the button was a stutter of half-loaded
     * frames. It seeks once, when the presses stop or OK is pressed.
     */
    var target by remember { mutableStateOf<Long?>(null) }
    val scope = rememberCoroutineScope()
    var commit by remember { mutableStateOf<Job?>(null) }
    fun settle(to: Long) {
        commit?.cancel()
        commit = scope.launch {
            delay(SCRUB_SETTLE_MS)
            onSeekTo(to)
            target = null
        }
    }

    // The press that brought the controls up is the first step of the scrub. Used once:
    // the controls coming back later must not take the same step again.
    LaunchedEffect(nudge?.serial) {
        val press = nudge ?: return@LaunchedEffect
        onNudgeUsed()
        onScrub()
        val next = ((target ?: positionMs) + press.direction * press.stepMs)
            .coerceIn(0, furthest)
        target = next
        settle(next)
    }

    val shown = target ?: positionMs
    val played = (shown.toFloat() / total).coerceIn(0f, 1f)
    val buffered = (bufferedMs.toFloat() / total).coerceIn(0f, 1f)
    // Times sit in a column that ticks every second; fixed-width digits stop them jiggling.
    val times = ReelyType.Label.copy(fontFeatureSettings = "tnum")

    // The times sit either side of the bar, on its line, rather than on a row of their own.
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(text = clock(shown), color = Chalk, style = times)
        // The box is as tall as the thumb, so the track can thicken on focus without
        // pushing the times or the buttons about.
        BoxWithConstraints(
            contentAlignment = Alignment.CenterStart,
            modifier = Modifier
                .weight(1f)
                .height(SCRUB_THUMB)
                .focusRequester(focusRequester)
                .onFocusChanged {
                    // Leaving the bar mid-scrub still goes where it was taken. Only leaving:
                    // the bar reports "not focused" when it first appears, which is before
                    // the cursor reaches it on a scrub started from outside.
                    val left = focused && !it.isFocused
                    focused = it.isFocused
                    onFocusState(it.isFocused)
                    if (left) target?.let { to -> commit?.cancel(); onSeekTo(to); target = null }
                }
                .focusable()
                .then(
                    if (!LocalTouchPlayer.current) Modifier
                    else Modifier
                        // A tap on the bar goes there; a drag goes where it's let go.
                        .pointerInput(total, furthest) {
                            detectTapGestures { at ->
                                onScrub()
                                commit?.cancel()
                                target = null
                                onSeekTo((at.x / size.width * total).toLong().coerceIn(0, furthest))
                            }
                        }
                        .pointerInput(total, furthest) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    target?.let { to -> commit?.cancel(); onSeekTo(to); target = null }
                                },
                                onDragCancel = { target = null },
                            ) { change, _ ->
                                onScrub()
                                target = (change.position.x / size.width * total).toLong().coerceIn(0, furthest)
                            }
                        },
                )
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val direction = when (event.key) {
                        Key.DirectionLeft -> -1
                        Key.DirectionRight -> 1
                        else -> 0
                    }
                    if (direction != 0) {
                        onScrub()
                        // Held down, it goes further each step: a minute a step by the time
                        // somebody is crossing a whole film.
                        val step = scrubStep(event.nativeKeyEvent.repeatCount)
                        val next = ((target ?: positionMs) + direction * step)
                            .coerceIn(0, furthest)
                        target = next
                        settle(next)
                        return@onPreviewKeyEvent true
                    }
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter -> {
                            val to = target
                            if (to != null) {
                                commit?.cancel()
                                onSeekTo(to)
                                target = null
                            } else {
                                onTogglePlay()
                            }
                            true
                        }
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
            val scrubbing = target
            if (focused && scrubbing != null) {
                ScrubPreview(
                    atMs = scrubbing,
                    previewUrl = previewUrl,
                    previews = previews,
                    chapters = chapters,
                    centreX = maxWidth * played,
                    barWidth = maxWidth,
                )
            }
        }
        Text(
            text = "\u2212" + clock((durationMs - shown).coerceAtLeast(0)),
            color = Muted,
            style = times,
        )
    }
}

/**
 * The picture at the scrub point, above the bar, as Plex's own player shows it: the
 * server's preview for that moment and the time under it. Just the time, in a pill, when
 * the server has made no previews. Takes no room of its own; it is drawn over the picture.
 */
@Composable
private fun ScrubPreview(
    atMs: Long,
    previewUrl: String?,
    centreX: Dp,
    barWidth: Dp,
    previews: tv.reely.core.PreviewIndex? = null,
    chapters: List<tv.reely.plex.PlexChapter> = emptyList(),
) {
    // The last resort for a picture: the chapter's, where the server has none of its own.
    val chapterPicture = if (previewUrl == null && previews == null && chapters.isNotEmpty()) {
        chapters.getOrNull(currentChapter(chapters, atMs).coerceAtLeast(0))?.thumbUrl
    } else null
    val pictured = previewUrl != null || chapterPicture != null
    val width = if (pictured) PREVIEW_WIDTH else 84.dp
    val height = if (pictured) PREVIEW_WIDTH * 9 / 16 + 30.dp else 30.dp
    val left = (centreX - width / 2).coerceIn(0.dp, (barWidth - width).coerceAtLeast(0.dp))
    Column(
        modifier = Modifier
            .layout { measurable, _ ->
                val placeable = measurable.measure(Constraints.fixed(width.roundToPx(), height.roundToPx()))
                // No size in the bar's layout: placed above it, clear of the thumb.
                layout(0, 0) { placeable.place(left.roundToPx(), -(height + 18.dp).roundToPx()) }
            }
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceRaised.copy(alpha = 0.95f))
            .border(1.dp, Chalk.copy(alpha = 0.18f), RoundedCornerShape(10.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (previews != null) {
            // Read from the file already in hand. The last picture stays up while the
            // next is read, so holding the button is a moving picture, not a flicker.
            val index = previews.indexAt(atMs)
            var picture by remember(previews) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
            LaunchedEffect(previews, index) {
                val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) { previews.jpeg(index) } ?: return@LaunchedEffect
                picture = withContext(kotlinx.coroutines.Dispatchers.Default) {
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                } ?: picture
            }
            Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Ink)) {
                picture?.let {
                    androidx.compose.foundation.Image(
                        bitmap = it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        } else if (chapterPicture != null) {
            AsyncImage(
                model = chapterPicture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Ink),
            )
        } else if (previewUrl != null) {
            // To the nearest two seconds, the spacing Plex makes them at, so a scrub that
            // comes back past the same moment finds the picture already loaded.
            val moment = (atMs / 2_000) * 2_000
            AsyncImage(
                model = previewUrl.replace("{ms}", moment.toString()),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Ink),
            )
        }
        Box(modifier = Modifier.fillMaxWidth().height(30.dp), contentAlignment = Alignment.Center) {
            Text(
                text = clock(atMs),
                color = Chalk,
                style = ReelyType.Label.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** How far one press of left or right moves the scrub, by how long it has been held. */
internal fun scrubStep(repeatCount: Int): Long = when {
    repeatCount < 6 -> SEEK_STEP_MS
    repeatCount < 20 -> 30_000L
    else -> 60_000L
}

/** How long after the last press the scrub is taken as where to go. */
private const val SCRUB_SETTLE_MS = 700L

private val PREVIEW_WIDTH = 240.dp

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
    // The file's own sound, which is what to describe when none could be selected —
    // otherwise the panel shows a dash exactly when the audio is the question.
    val fileAudio = remember(tick) {
        player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_AUDIO }?.getTrackFormat(0)
    }
    val unplayable = audio == null && fileAudio != null &&
        !player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)

    // Nothing in here to press: the panel holds the cursor itself, and Back closes it.
    MenuPanel(modifier = modifier.focusRequester(focusRequester).focusable()) {
        MenuHeading(title = "Playback info")
        /*
         * What somebody watching might want to know, said the way a streaming app says
         * it: the quality, the picture, the sound and the rate. Decoder names, dropped
         * frames and what the server has or hasn't made used to be here behind More
         * details; that's a workbench, not a player.
         */
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(start = 4.dp)) {
            StatLine(
                "Quality",
                when {
                    playback.isLive -> "Live"
                    playback.transcoding || playback.audioConverted -> "Adjusted for this TV"
                    else -> "Original"
                },
            )
            StatLine("Picture", video?.let(::pictureSummary) ?: "—")
            StatLine(
                "Sound",
                when {
                    unplayable -> "Not supported on this TV"
                    else -> (audio ?: fileAudio)?.let(::soundSummary) ?: "—"
                },
            )
            StatLine("Bitrate", bitrate(video?.bitrate?.takeIf { it > 0 }?.plus(audio?.bitrate?.coerceAtLeast(0) ?: 0) ?: -1))
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = Muted, style = ReelyType.Meta)
        Text(
            text = value,
            color = Chalk,
            style = ReelyType.Meta,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/** The small capitals over a group of lines in a panel. */
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

/** "4K  ·  HDR10  ·  24 fps", from what the player is actually showing. */
internal fun pictureSummary(format: androidx.media3.common.Format): String? {
    if (format.height <= 0) return null
    val size = when {
        format.height >= 2000 || format.width >= 3800 -> "4K"
        format.height >= 1000 || format.width >= 1900 -> "1080p"
        format.height >= 700 || format.width >= 1260 -> "720p"
        else -> "SD"
    }
    val range = when {
        format.sampleMimeType == androidx.media3.common.MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
        format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
        else -> null
    }
    val fps = format.frameRate.takeIf { it > 0f }?.let { "%.3g fps".format(it) }
    return listOfNotNull(size, range, fps).joinToString("  ·  ")
}

/** "Dolby Digital Plus  ·  5.1". */
internal fun soundSummary(format: androidx.media3.common.Format): String? {
    val codec = format.sampleMimeType?.let(::codecName) ?: return null
    val layout = when (format.channelCount) {
        1 -> "Mono"
        2 -> "Stereo"
        6 -> "5.1"
        8 -> "7.1"
        in 3..Int.MAX_VALUE -> "${format.channelCount} channels"
        else -> null
    }
    return listOfNotNull(codec, layout).joinToString("  ·  ")
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
    /** Subtitles online, when they can be looked for; null when they can't. */
    onFindSubtitles: (() -> Unit)? = null,
    /** After a track is chosen: its type, and where it is among them (null for Off). */
    onPicked: (Int, Int?) -> Unit = { _, _ -> },
    onToggleBackground: () -> Unit,
    modifier: Modifier = Modifier,
    /** Where the sound is going, by AudioOutputs' key; null for Automatic. */
    audioOutput: String? = null,
    onPickOutput: (String?) -> Unit = {},
) {
    val trackType = if (panel == Panel.SUBTITLES) C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO
    val picker = rememberOutputPicker(onPickOutput)
    val outputs = picker.outputs
    val context = LocalContext.current
    val pairing = remember { AudioOutputs.pairingIntent(context) }
    val choices = remember(tracksVersion, panel) { trackChoices(player, trackType) }

    MenuPanel(modifier = modifier.focusGroup().focusRequester(focusRequester)) {
        MenuHeading(title = if (panel == Panel.SUBTITLES) "Subtitles" else "Audio")

        if (choices.isEmpty()) {
            Text(
                text = if (panel == Panel.SUBTITLES)
                    "No subtitles are available for this video."
                else
                    "There's only one audio track.",
                color = Muted,
                style = ReelyType.Meta,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(choices) { _, choice ->
                MenuItem(
                    label = choice.label,
                    checked = choice.selected,
                    onClick = {
                        applyTrack(player, trackType, choice)
                        onPicked(trackType, choice.trackIndex)
                    },
                )
            }
            if (panel == Panel.SUBTITLES && onFindSubtitles != null) {
                item {
                    MenuItem(
                        label = "Find subtitles online",
                        icon = { SearchGlyph(it, size = 20.dp) },
                        onClick = onFindSubtitles,
                    )
                }
            }
            if (panel == Panel.AUDIO) {
                // Somewhere else for the sound: headphones, a speaker. What's chosen is kept
                // for next time, and used whenever it's there; when it isn't, the TV.
                item { MenuSection("Play sound on") }
                val chosen = outputs.firstOrNull { it.key == audioOutput && it.connected }
                item {
                    MenuItem(
                        label = "Automatic",
                        detail = if (audioOutput != null && chosen == null && picker.connecting == null)
                            "Using the TV until yours is connected" else null,
                        checked = chosen == null && picker.connecting == null,
                        onClick = { picker.pick(null) },
                    )
                }
                itemsIndexed(outputs, key = { _, output -> output.key }) { _, output ->
                    MenuItem(
                        label = output.label,
                        detail = when {
                            picker.connecting == output.key -> "Connecting…"
                            picker.failed == output.key -> "Couldn't connect. Is it on and nearby?"
                            !output.connected -> "Paired · select to connect"
                            output.kind == tv.reely.core.AudioOutput.Kind.BLUETOOTH -> "Bluetooth"
                            output.kind == tv.reely.core.AudioOutput.Kind.USB -> "USB"
                            else -> null
                        },
                        checked = output == chosen,
                        onClick = { picker.pick(output) },
                    )
                }
                if (pairing != null) {
                    item {
                        MenuItem(
                            label = "Pair something new",
                            icon = { PlusGlyph(it, size = 18.dp) },
                            onClick = { runCatching { context.startActivity(pairing) } },
                        )
                    }
                }
            }
            if (panel == Panel.SUBTITLES) {
                // The size it is now heads the two that change it, rather than sitting on one of them.
                item { MenuSection("Size  ·  ${(prefs.subtitleScale * 100).roundToInt()}%") }
                item {
                    MenuItem(
                        label = "Increase size",
                        icon = { PlusGlyph(it, size = 18.dp) },
                        onClick = { onNudgeScale(Settings.SCALE_STEP) },
                    )
                }
                item {
                    MenuItem(
                        label = "Decrease size",
                        icon = { MinusGlyph(it, size = 18.dp) },
                        onClick = { onNudgeScale(-Settings.SCALE_STEP) },
                    )
                }
                item { MenuSection("Appearance") }
                item {
                    MenuItem(
                        label = "Background",
                        value = if (prefs.subtitleBackground) "On" else "Off",
                        onClick = onToggleBackground,
                    )
                }
            }
        }
    }
}

private fun trackChoices(player: ExoPlayer, trackType: Int): List<TrackChoice> {
    val all = player.currentTracks.groups.filter { it.type == trackType }
    val groups = all.withIndex().filter { it.value.isSupported }
    if (groups.isEmpty()) return emptyList()

    val anySelected = groups.any { it.value.isSelected }
    val options = mutableListOf<TrackChoice>()
    if (trackType == C.TRACK_TYPE_TEXT) {
        options += TrackChoice(label = "Off", group = null, selected = !anySelected)
    }
    groups.forEachIndexed { index, (trackIndex, group) ->
        val format = group.getTrackFormat(0)
        val label = format.label
            ?: format.language
            ?: "Track ${index + 1}"
        options += TrackChoice(label = label, group = group, selected = group.isSelected, trackIndex = trackIndex)
    }
    return options
}

/** Every track of a type, supported or not, in the player's order, as [playerTrackFor] reads them. */
private fun playerTracks(player: ExoPlayer, trackType: Int): List<Pair<Tracks.Group, PlayerTrack>> =
    player.currentTracks.groups.filter { it.type == trackType }.map { group ->
        val format = group.getTrackFormat(0)
        group to PlayerTrack(
            id = format.id,
            language = format.language,
            sidecar = format.id?.contains(sidecarId("")) == true,
        )
    }

/**
 * Plays the sound and subtitles the server has selected for this account: what its
 * language settings ask for, or what was last picked for this title in any Plex app.
 * Without a subtitle chosen there, the player's own choice stands, which is none unless
 * one was picked earlier in this sitting. With subtitles set to start off, the server's
 * subtitle is passed over unless it's forced.
 */
private fun applyServerChoice(player: ExoPlayer, playback: Playback, subtitlesAtStart: String) {
    var builder: TrackSelectionParameters.Builder? = null
    fun choose(trackType: Int, streams: List<PlexStream>) {
        val tracks = playerTracks(player, trackType)
        val index = playerTrackFor(streams, tracks.map { it.second }) ?: return
        val group = tracks[index].first
        if (!group.isSupported || group.isSelected) return
        builder = (builder ?: player.trackSelectionParameters.buildUpon())
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
    }
    choose(C.TRACK_TYPE_AUDIO, playback.audioStreams)
    if (startsWithServerSubtitles(playback.subtitleStreams, subtitlesAtStart)) {
        choose(C.TRACK_TYPE_TEXT, playback.subtitleStreams)
    }
    builder?.let { player.trackSelectionParameters = it.build() }
}

/**
 * A track chosen in the panel. The choice is of this file's track, which the next
 * episode doesn't have; its language is kept too, so the next episode starts with the
 * same subtitles or sound. Before, subtitles turned on for one episode were off again for
 * the next unless the server happened to pick them.
 */
private fun applyTrack(player: ExoPlayer, trackType: Int, choice: TrackChoice) {
    val builder = player.trackSelectionParameters.buildUpon()
    val group = choice.group
    val language = group?.getTrackFormat(0)?.language?.takeIf { it.isNotBlank() && it != C.LANGUAGE_UNDETERMINED }
    player.trackSelectionParameters = if (group == null) {
        builder.clearOverridesOfType(trackType).setTrackTypeDisabled(trackType, true)
            .apply { if (trackType == C.TRACK_TYPE_TEXT) setPreferredTextLanguage(null) }
            .build()
    } else {
        builder
            .setTrackTypeDisabled(trackType, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
            .apply {
                if (language != null) {
                    if (trackType == C.TRACK_TYPE_TEXT) setPreferredTextLanguage(language)
                    else if (trackType == C.TRACK_TYPE_AUDIO) setPreferredAudioLanguage(language)
                }
            }
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
                    // Tells the track apart from the file's own, and says which stream it is.
                    .setId(sidecarId(subtitle.id))
                    .setMimeType(subtitle.mimeType)
                    .setLanguage(subtitle.language)
                    .setLabel(subtitle.label)
                    .build()
            }
        )
        .build()

/** The number being typed, and the channel it will tune to, or that there isn't one. */
@Composable
internal fun ChannelEntry(typed: String, match: XtreamChannel?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .widthIn(min = 200.dp, max = 460.dp)
            .sheet(radius = 16)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = typed, color = Chalk, style = ReelyType.Display)
        Text(
            text = match?.name ?: "No channel with this number",
            color = if (match != null) Chalk else Muted,
            style = ReelyType.Body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The film's chapters, each with the server's picture of it and where it starts. Opens on
 * the one playing; OK on another goes there.
 */
@Composable
internal fun ChapterPanel(
    chapters: List<tv.reely.plex.PlexChapter>,
    positionMs: Long,
    focusRequester: FocusRequester,
    onPick: (tv.reely.plex.PlexChapter) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = remember(chapters) { currentChapter(chapters, positionMs) }
    val list = androidx.compose.foundation.lazy.rememberLazyListState(
        initialFirstVisibleItemIndex = (current - 1).coerceAtLeast(0),
    )
    MenuPanel(modifier = modifier.focusGroup(), width = 500.dp) {
        MenuHeading(title = "Chapters")
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(chapters) { index, chapter ->
                MenuItem(
                    label = chapter.title,
                    detail = clock(chapter.startMs),
                    checked = index == current,
                    // The chapter's own picture, where the server made one.
                    icon = chapter.thumbUrl?.let { url ->
                        {
                            coil.compose.AsyncImage(
                                model = url,
                                contentDescription = null,
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.width(64.dp).height(36.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp)),
                            )
                        }
                    },
                    onClick = { onPick(chapter) },
                    modifier = if (index == current) Modifier.focusRequester(focusRequester) else Modifier,
                )
            }
        }
    }
}

/**
 * Subtitles the server found online, in the television's language, to choose from. The
 * one chosen is fetched and added to the file, and plays straight away.
 */
@Composable
internal fun SubtitleSearchPanel(
    search: tv.reely.ui.SubtitleSearch,
    focusRequester: FocusRequester,
    onPick: (tv.reely.plex.PlexOnlineSubtitle) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val languageName = java.util.Locale(search.language).displayLanguage.ifBlank { search.language }
    // The cursor waits on Close while the server looks. When the results come, Close goes,
    // and the cursor has to be put on the first of them: left alone it went with Close,
    // and there was nothing in the list that could be chosen.
    val found = search.results.isNotEmpty()
    LaunchedEffect(found) {
        if (found) focusRequester.requestWhenReady()
    }
    MenuPanel(modifier = modifier.focusGroup(), width = 500.dp) {
        MenuHeading(title = "Find subtitles")
        Text(
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            text = when {
                search.busy -> "Looking for $languageName subtitles…"
                search.error != null -> search.error
                search.results.isEmpty() -> "No $languageName subtitles were found for this."
                search.adding != null -> "Adding them…"
                else -> "$languageName, found by your Plex server."
            },
            color = Muted,
            style = ReelyType.Meta,
        )
        LazyColumn(
            modifier = Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexed(search.results, key = { _, it -> it.key }) { index, result ->
                MenuItem(
                    label = result.title,
                    detail = listOfNotNull(
                        result.provider,
                        "For the hard of hearing".takeIf { result.hearingImpaired },
                        "Forced".takeIf { result.forced },
                    ).joinToString("  ·  ").ifBlank { null },
                    checked = result.key == search.adding,
                    onClick = { if (search.adding == null) onPick(result) },
                    modifier = if (index == 0) Modifier.focusRequester(focusRequester) else Modifier,
                )
            }
        }
        // Something for the cursor to be on while it looks, or when it found nothing.
        if (search.results.isEmpty()) {
            MenuItem(label = "Close", onClick = onClose, modifier = Modifier.focusRequester(focusRequester))
        }
    }
}

/**
 * The sleep timer: off, a time from now, or the end of what's playing. A channel has no
 * end to stop at, so it isn't offered there. The choice already made is marked, with when
 * it will stop.
 */
@Composable
internal fun SleepPanel(
    sleep: tv.reely.ui.SleepTimer?,
    isLive: Boolean,
    focusRequester: FocusRequester,
    onPick: (Int?) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val options = buildList<Pair<Int?, String>> {
        add(null to "Off")
        SLEEP_MINUTES.forEach { add(it to "In $it minutes") }
        if (!isLive) add(0 to "At the end of this episode")
    }
    val chosen: Int? = when {
        sleep == null -> null
        sleep.endOfEpisode -> 0
        else -> -1
    }
    MenuPanel(modifier = modifier.focusGroup()) {
        MenuHeading(
            title = "Sleep timer",
            subtitle = sleep?.atMs?.let { "Stops at ${tv.reely.ui.components.clockTime(context, it)}." },
        )
        options.forEachIndexed { index, (minutes, label) ->
            MenuItem(
                label = label,
                checked = minutes == chosen,
                icon = if (minutes == null) null else ({ MoonGlyph(it, size = 20.dp) }),
                onClick = { onPick(minutes) },
                modifier = if (index == 0) Modifier.focusRequester(focusRequester) else Modifier,
            )
        }
    }
}

private val SLEEP_MINUTES = listOf(30, 60, 90)

/**
 * The time, top right while the controls are up, and for a film or an episode when it
 * will finish: the two things somebody glancing at the controls late at night wants.
 * A soft shade behind it keeps it readable over a bright picture.
 */
@Composable
internal fun PlayerClock(endsAtMs: Long?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val now = tv.reely.ui.components.rememberNow()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Ink.copy(alpha = 0.6f), Color.Transparent)))
            .padding(top = 24.dp, end = 48.dp, bottom = 56.dp, start = 48.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = tv.reely.ui.components.clockTime(context, now),
                color = Chalk,
                fontSize = 26.sp,
                lineHeight = 30.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (endsAtMs != null) {
                Text(
                    text = "Ends at " + tv.reely.ui.components.clockTime(context, now + endsAtMs),
                    color = Muted,
                    style = ReelyType.Label,
                )
            }
        }
    }
}

/** Which chapter [positionMs] falls in: the last to have started by then. */
internal fun currentChapter(chapters: List<tv.reely.plex.PlexChapter>, positionMs: Long): Int =
    chapters.indexOfLast { it.startMs <= positionMs }.coerceAtLeast(0)

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
        // Nothing converts an IPTV provider's file: it plays here as it is, or not at all.
        playback.fromIptv ->
            "No sound: this audio ($sound) isn't supported on this device."
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

private fun describe(error: PlaybackException): String = when (val cause = error.cause) {
    is HttpDataSource.InvalidResponseCodeException -> when (cause.responseCode) {
        401, 403 -> "This couldn't be opened. If it's a live channel, your subscription may " +
            "already be using all its connections. Close another stream and try again."

        404 -> "This isn't available right now."
        else -> "This couldn't be played. Try again."
    }

    is HttpDataSource.HttpDataSourceException -> tv.reely.core.Friendly.OFFLINE

    else -> "This couldn't be played. Try again."
}

/**
 * How much of a film or episode to keep loaded ahead. Live television has its own, much
 * smaller, buffer: channel-change speed is what matters there.
 *
 * Normal: a second to start, then fifty seconds kept ahead, the player's own standard. It
 * used to be thirty, live television's ceiling, which left a film less in hand than the
 * player it is built on would keep by default.
 * Larger: the same start, then it keeps loading to a minute before easing off, holds up
 * to two, and after a stall waits for five seconds in hand rather than two, so an uneven
 * connection does not stutter through a string of short stops.
 *
 * Both are bounded by memory as well as time: see [playbackBufferBytes].
 */
private fun bufferFor(larger: Boolean): DefaultLoadControl =
    DefaultLoadControl.Builder()
        .apply {
            if (larger) setBufferDurationsMs(60_000, 120_000, 1_500, 5_000)
            else setBufferDurationsMs(50_000, 50_000, 1_000, 2_000)
        }
        .setTargetBufferBytes(playbackBufferBytes(Runtime.getRuntime().maxMemory()))
        .build()

/**
 * How many bytes of video the player may hold, from how much memory the app has.
 *
 * The player's own figure is about 130 MB whatever the device, which is a minute of an
 * ordinary 1080p file and only a quarter of one of a 4K remux at 70 Mbps, so on a 4K
 * file the time above was never reached. Where the device gives the app room (a Shield,
 * a Fire TV Cube), up to four tenths of it goes to the buffer. Where it doesn't, the
 * player's own figure stands: a buffer that runs a stick out of memory stops everything.
 */
internal fun playbackBufferBytes(maxHeapBytes: Long): Int {
    val mb = 1024L * 1024
    if (maxHeapBytes < 320 * mb) return C.LENGTH_UNSET
    return (maxHeapBytes * 4 / 10).coerceIn(128 * mb, 384 * mb).toInt()
}


/** Whether the player is on a touch screen; see PlayerScreen's `touch`. */
internal val LocalTouchPlayer = androidx.compose.runtime.staticCompositionLocalOf { false }

/** How far a double tap skips, either way. */
private const val TOUCH_SKIP_MS = 10_000L

/** How far a swipe has to travel to change channel. */
private val TOUCH_SWIPE_DP = 72.dp

/** A round frosted button over the picture, for a finger. */
@Composable
private fun TouchRound(size: androidx.compose.ui.unit.Dp, description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Ink.copy(alpha = 0.45f))
            .border(0.5.dp, Color.White.copy(alpha = 0.15f), CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** A turning arrow with 10 in it: ten seconds back, or (mirrored) on. */
@Composable
private fun TenSeconds(forward: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        tv.reely.ui.components.RestartGlyph(
            Chalk, 30.dp,
            modifier = if (forward) Modifier.graphicsLayer { scaleX = -1f } else Modifier,
        )
        Text("10", color = Chalk, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 2.dp))
    }
}

/** The way out of the player on a touch screen, where there's no remote to press Back on. */
@Composable
private fun TouchBack(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(Ink.copy(alpha = 0.6f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        tv.reely.ui.components.ArrowGlyph(Chalk, left = true, size = 22.dp)
    }
}
