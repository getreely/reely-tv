package tv.reely.ui.screens

import tv.reely.ui.components.FocusReturn
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.gestures.animateScrollBy
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import tv.reely.ui.components.requestWhenReady
import tv.reely.ui.components.pillColors
import tv.reely.ui.components.ChoiceRequest
import tv.reely.ui.components.ChoicePanel
import tv.reely.ui.theme.ReelyType
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Accent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import tv.reely.xtream.StreamFormat
import tv.reely.xtream.XtreamApi
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.reely.BuildConfig
import tv.reely.core.Settings
import tv.reely.plex.PlexServer
import tv.reely.ui.LibraryChoice
import tv.reely.ui.GuideState
import tv.reely.ui.GuideStatus
import tv.reely.ui.LiveState
import tv.reely.ui.PlayerPrefs
import tv.reely.ui.PlexState
import tv.reely.ui.UpdateStatus
import tv.reely.ui.components.ErrorNote
import tv.reely.ui.components.TvChip
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.SurfaceRaised
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private enum class Section(val title: String) {
    PLAYBACK("Playback"),
    HOME("Home"),
    LIVE_TV("Live TV"),
    REQUESTS("Requests"),
    PLEX("Plex"),
    UPDATES("Updates"),
    ABOUT("About"),
}

/**
 * A rail of sections down the left and their contents on the right, which is how a
 * television expects settings to be laid out: one press to change subject rather than a
 * single column somebody has to scroll to the bottom of.
 */
@Composable
fun SettingsScreen(
    plex: PlexState,
    live: LiveState,
    guide: GuideState,
    prefs: PlayerPrefs,
    onSignOutPlex: () -> Unit,
    onSignOutXtream: () -> Unit,
    onSwitchServer: (PlexServer) -> Unit,
    onToggleFavourite: (LibraryChoice) -> Unit,
    onToggleFormat: () -> Unit,
    onNudgeSubtitleScale: (Float) -> Unit,
    onToggleSubtitleBackground: () -> Unit,
    onNudgeUpNext: (Int) -> Unit,
    onToggleGuidePreview: () -> Unit,
    onSetPlaybackMode: (String) -> Unit,
    onSetMaxBitrate: (Int) -> Unit,
    onToggleMultiviewLayout: () -> Unit,
    onToggleThemeMusic: () -> Unit,
    onToggleMatchFrameRate: () -> Unit,
    onToggleLargerBuffer: () -> Unit,
    onToggleSkipIntros: () -> Unit,
    onToggleSkipCredits: () -> Unit = {},
    onSetAudioOutput: (String?) -> Unit = {},
    onSetScreensaver: (Int) -> Unit = {},
    onNudgeThemeVolume: (Float) -> Unit,
    onRefreshChannels: () -> Unit,
    onRefreshGuide: () -> Unit,
    update: UpdateStatus,
    updateUrl: String,
    onCheckForUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onOpenInstaller: () -> Unit = {},
    onTakeTour: () -> Unit = {},
    modifier: Modifier = Modifier,
    /** Opens "Who's watching?" to switch Plex Home profiles. */
    onSwitchProfile: () -> Unit = {},
    requests: tv.reely.ui.RequestsState = tv.reely.ui.RequestsState(),
    onDisconnectReely: () -> Unit = {},
    onToggleHomeRow: (tv.reely.ui.HomeRow) -> Unit = {},
    /** The IPTV provider's films and series in the tabs; see Settings.iptvLibrary. */
    iptv: tv.reely.ui.IptvState = tv.reely.ui.IptvState(),
    onSetIptvLibrary: (Boolean) -> Unit = {},
    onSetIptvWins: (Boolean) -> Unit = {},
    onRefreshIptv: () -> Unit = {},
    /**
     * A phone: one column, the sections as a list and each opening full width, rather
     * than the television's two side by side. The same rows either way.
     */
    compact: Boolean = false,
) {
    var section by remember { mutableStateOf(Section.PLAYBACK) }
    val sectionFocus = remember { Section.entries.associateWith { FocusRequester() } }
    var inOptions by remember { mutableStateOf(false) }
    // The first thing to change on each page, where right from the sections lands —
    // rather than whatever row happens to sit level with the section, which on two
    // pages was Sign out.
    val firstOption = remember { FocusRequester() }

    // Back from the options goes back to the section they belong to. Back from the
    // sections goes up to the gear, as from any tab's page.
    BackHandler(enabled = inOptions) { sectionFocus.getValue(section).requestFocus() }

    /*
     * Settings is entered at its sections, on the one that is showing: arriving from the
     * gear up in the corner used to land in the options, the nearest thing below it, with
     * no obvious way back out to the sections. Coming back left from the options lands on
     * that section too, rather than on whichever one happened to sit level with the cursor.
     */
    val toSection = Modifier.focusProperties {
        onEnter = { if (!FocusReturn.active) sectionFocus.getValue(section).requestFocus() }
    }

    var choosing by remember { mutableStateOf<ChoiceRequest?>(null) }

    val sectionContent: @Composable () -> Unit = {
            CompositionLocalProvider(LocalFirstOption provides firstOption) {
            when (section) {
                Section.PLAYBACK -> PlaybackSection(
                    prefs = prefs,
                    onNudgeSubtitleScale = onNudgeSubtitleScale,
                    onToggleSubtitleBackground = onToggleSubtitleBackground,
                    onNudgeUpNext = onNudgeUpNext,
                    onSetPlaybackMode = onSetPlaybackMode,
                    onSetMaxBitrate = onSetMaxBitrate,
                    onToggleThemeMusic = onToggleThemeMusic,
                    onToggleMatchFrameRate = onToggleMatchFrameRate,
                    onToggleLargerBuffer = onToggleLargerBuffer,
                    onToggleSkipIntros = onToggleSkipIntros,
                    onToggleSkipCredits = onToggleSkipCredits,
                    onSetAudioOutput = onSetAudioOutput,
                    onNudgeThemeVolume = onNudgeThemeVolume,
                )

                Section.LIVE_TV -> LiveSection(
                    live = live,
                    guide = guide,
                    prefs = prefs,
                    onToggleMultiviewLayout = onToggleMultiviewLayout,
                    onRefreshChannels = onRefreshChannels,
                    onRefreshGuide = onRefreshGuide,
                    onToggleFormat = onToggleFormat,
                    onToggleGuidePreview = onToggleGuidePreview,
                    onSignOutXtream = onSignOutXtream,
                    iptv = iptv,
                    onSetIptvLibrary = onSetIptvLibrary,
                    onSetIptvWins = onSetIptvWins,
                    onRefreshIptv = onRefreshIptv,
                )

                Section.HOME -> HomeSection(
                    prefs, reelyConnected = requests.server != null, onToggleHomeRow, onSetScreensaver,
                    iptvOn = iptv.on,
                )

                Section.REQUESTS -> RequestsSection(requests, onDisconnectReely)

                Section.PLEX -> PlexPanel(
                    plex = plex,
                    onSwitchProfile = onSwitchProfile,
                    onSwitchServer = onSwitchServer,
                    onToggleFavourite = onToggleFavourite,
                    onSignOutPlex = onSignOutPlex,
                )

                Section.UPDATES -> UpdatesSection(
                    update = update,
                    onCheck = onCheckForUpdate,
                    onInstall = onInstallUpdate,
                    onOpenInstaller = onOpenInstaller,
                )

                Section.ABOUT -> AboutSection(onTakeTour = onTakeTour)
            }
            }
    }

    Box(modifier = modifier.fillMaxSize()) {
    CompositionLocalProvider(LocalChoices provides { choosing = it }) {
    if (compact) {
        CompactSettings(section = section, onSection = { section = it }, content = sectionContent)
    } else Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 36.dp, vertical = 14.dp)
            .then(toSection)
            .focusGroup(),
    ) {
        Column(
            modifier = Modifier.width(180.dp).fillMaxHeight().then(toSection).focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Settings",
                color = Chalk,
                style = ReelyType.Headline,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Section.entries.forEach { entry ->
                TvChip(
                    label = entry.title,
                    selected = section == entry,
                    onClick = { section = entry },
                    // Moving down the sections shows each one, as a television's own
                    // settings do; right then goes into what is on screen.
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(sectionFocus.getValue(entry))
                        .onFocusChanged { if (it.isFocused) section = entry },
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 26.dp)
                .verticalScroll(rememberScrollState())
                .onFocusChanged { inOptions = it.hasFocus }
                .focusProperties {
                    onEnter = { if (!FocusReturn.active) runCatching { firstOption.requestFocus() } }
                }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            sectionContent()
        }
    }
    }
        // Kept by the screen, so the way back outlives the list that asked for it.
        val returnScope = androidx.compose.runtime.rememberCoroutineScope()
        choosing?.let { request ->
            ChoicePanel(
                request = request,
                onClose = {
                    choosing = null
                    returnScope.launch { FocusReturn.to(request.returnTo) }
                },
            )
        }
    }
}

@Composable
private fun PlaybackSection(
    prefs: PlayerPrefs,
    onNudgeSubtitleScale: (Float) -> Unit,
    onToggleSubtitleBackground: () -> Unit,
    onNudgeUpNext: (Int) -> Unit,
    onSetPlaybackMode: (String) -> Unit,
    onSetMaxBitrate: (Int) -> Unit,
    onToggleThemeMusic: () -> Unit,
    onToggleMatchFrameRate: () -> Unit,
    onToggleLargerBuffer: () -> Unit,
    onToggleSkipIntros: () -> Unit,
    onToggleSkipCredits: () -> Unit,
    onNudgeThemeVolume: (Float) -> Unit,
    onSetAudioOutput: (String?) -> Unit,
) {
    SettingGroup("Video") {
        ChoiceRow(
            title = "Playback mode",
            first = true,
            options = listOf(
                Option(Settings.MODE_AUTO, "Automatic", "Plays the original file. Plex converts it only when needed."),
                Option(Settings.MODE_DIRECT, "Original only", "Always plays the original file. Some may not play."),
                Option(Settings.MODE_TRANSCODE, "Always convert", "Plex converts everything. Uses more of your server."),
            ),
            selected = prefs.playbackMode.takeIf {
                it == Settings.MODE_DIRECT || it == Settings.MODE_TRANSCODE
            } ?: Settings.MODE_AUTO,
            onSelect = onSetPlaybackMode,
        )
        ChoiceRow(
            title = "Conversion quality",
            description = "The highest quality Plex uses when it converts a video.",
            options = Settings.BITRATE_CHOICES.map { kbps ->
                Option(
                    kbps,
                    if (kbps <= 0) "Original" else "Up to ${kbps / 1_000} Mbps",
                    when (kbps) {
                        0 -> "As good as the file itself."
                        20_000 -> "4K"
                        12_000 -> "1080p, very high"
                        8_000 -> "1080p"
                        4_000 -> "720p"
                        else -> "For a slow connection"
                    },
                )
            },
            selected = prefs.maxBitrateKbps.coerceAtLeast(0),
            onSelect = onSetMaxBitrate,
        )
        SettingRow(
            title = "Match frame rate",
            switch = prefs.matchFrameRate,
            description = "Smoother motion. The screen may blink as it switches.",
            onClick = onToggleMatchFrameRate,
        )
        ChoiceRow(
            title = "Buffer",
            description = "Larger helps on a slow or unsteady connection.",
            options = listOf(
                Option(false, "Normal", "Keeps about 50 seconds ahead. Starts quickly."),
                Option(true, "Larger", "Keeps up to two minutes ahead. Takes a moment longer to start."),
            ),
            selected = prefs.largerBuffer,
            onSelect = { onToggleLargerBuffer() },
        )
    }

    SettingGroup("Sound") {
        // What's connected or paired now, plus what was chosen if it's neither: it's still
        // the choice, and used again as soon as it's back.
        val outputs = tv.reely.ui.components.rememberAudioOutputs()
        val chosen = prefs.audioOutput
        val gone = chosen != null && outputs.none { it.key == chosen }
        val device = deviceName()
        val options = buildList<Option<String?>> {
            add(Option(null, "Automatic", "Wherever $device sends sound."))
            outputs.forEach { output ->
                add(
                    Option(
                        output.key,
                        output.label,
                        when {
                            !output.connected -> "Paired. Connects when something plays; the TV if it can't."
                            output.kind == tv.reely.core.AudioOutput.Kind.BLUETOOTH -> "Bluetooth"
                            output.kind == tv.reely.core.AudioOutput.Kind.USB -> "USB"
                            else -> null
                        },
                    )
                )
            }
            if (gone) add(Option(chosen, "Your last choice", "Not found. The TV is used instead."))
        }
        ChoiceRow(
            title = "Play sound on",
            description = "Headphones and speakers paired with ${deviceName()}. When the one chosen isn't there, " +
                if (LocalCompactSettings.current) "its own speaker." else "the TV.",
            options = options,
            selected = chosen,
            onSelect = onSetAudioOutput,
        )
    }

    SettingGroup("Subtitles") {
        ChoiceRow(
            title = "Size",
            options = SUBTITLE_SIZES.map { size ->
                Option(size, "${(size * 100).roundToInt()}%", if (size == 1.0f) "Standard" else null)
            },
            // The nearest step, so a value saved before there were steps still shows as one.
            selected = SUBTITLE_SIZES.minBy { kotlin.math.abs(it - prefs.subtitleScale) },
            onSelect = { size -> onNudgeSubtitleScale(size - prefs.subtitleScale) },
        )
        SettingRow(
            title = "Background",
            switch = prefs.subtitleBackground,
            description = "A dark box behind the text, instead of an outline.",
            onClick = onToggleSubtitleBackground,
        )
    }

    SettingGroup("Episodes") {
        SettingRow(
            title = "Skip intros",
            switch = prefs.skipIntros,
            description = "Goes straight past an episode's intro when Plex has found it.",
            onClick = onToggleSkipIntros,
        )
        SettingRow(
            title = "Skip credits",
            switch = prefs.skipCredits,
            description = "Goes straight to the next episode when Plex finds the credits.",
            onClick = onToggleSkipCredits,
        )
        ChoiceRow(
            title = "Up Next",
            description = "How long before the next episode starts by itself.",
            options = UP_NEXT_SECONDS.map { seconds ->
                Option(
                    seconds,
                    if (seconds > 0) "$seconds seconds" else "Off",
                    if (seconds == 0) "Up Next waits for you to choose." else null,
                )
            },
            selected = UP_NEXT_SECONDS.minBy { kotlin.math.abs(it - prefs.upNextSeconds) },
            onSelect = { seconds -> onNudgeUpNext(seconds - prefs.upNextSeconds) },
        )
    }

    SettingGroup("Show pages") {
        val level = themeLevel(prefs)
        ChoiceRow(
            title = "Theme music",
            description = "Plays a show's theme song on its page.",
            options = listOf(Option(-1, "Off")) +
                THEME_LEVELS.mapIndexed { index, (label, _) -> Option(index, label) },
            selected = level,
            onSelect = { chosen ->
                when {
                    chosen < 0 -> if (prefs.themeMusic) onToggleThemeMusic()
                    else -> {
                        if (!prefs.themeMusic) onToggleThemeMusic()
                        onNudgeThemeVolume(THEME_LEVELS[chosen].second - prefs.themeVolume)
                    }
                }
            },
        )
    }
}

private val SUBTITLE_SIZES = listOf(0.7f, 0.8f, 0.9f, 1.0f, 1.2f, 1.4f)
private val UP_NEXT_SECONDS = listOf(0, 5, 10, 15, 20, 30)
private val THEME_LEVELS = listOf("Quiet" to 0.05f, "Medium" to 0.10f, "Loud" to 0.20f)

/** Which of [THEME_LEVELS] the theme music is at, or -1 when it is off. */
private fun themeLevel(prefs: PlayerPrefs): Int {
    if (!prefs.themeMusic) return -1
    return THEME_LEVELS.indices.minBy { kotlin.math.abs(THEME_LEVELS[it].second - prefs.themeVolume) }
}

private fun onOff(on: Boolean) = if (on) "On" else "Off"

@Composable
private fun LiveSection(
    live: LiveState,
    guide: GuideState,
    prefs: PlayerPrefs,
    onToggleMultiviewLayout: () -> Unit,
    onRefreshChannels: () -> Unit,
    onRefreshGuide: () -> Unit,
    onToggleFormat: () -> Unit,
    onToggleGuidePreview: () -> Unit,
    onSignOutXtream: () -> Unit,
    iptv: tv.reely.ui.IptvState,
    onSetIptvLibrary: (Boolean) -> Unit,
    onSetIptvWins: (Boolean) -> Unit,
    onRefreshIptv: () -> Unit,
) {
    if (!live.isConnected) {
        SettingGroup("Live TV") {
            SettingRow(title = "Not signed in", description = "Sign in from the Live TV tab.")
        }
        return
    }

    if (live.error != null) ErrorNote(live.error)

    SettingGroup("Channels and guide") {
        SettingRow(
            title = "Refresh channels",
            first = true,
            value = if (live.busy) "Refreshing…" else "${live.categories.size} categories",
            onClick = onRefreshChannels,
        )
        val credentials = live.credentials
        if (credentials != null && XtreamApi.xmltvUrl(credentials) == null) {
            SettingRow(
                title = "TV guide",
                value = "None",
                description = "Your playlist doesn't name one. To add one, sign out and sign in " +
                    "again with a TV guide address.",
            )
        } else {
            SettingRow(
                title = "Refresh TV guide",
                value = guide.status.describe(guide.importedAt),
                onClick = onRefreshGuide,
            )
        }
    }

    val playlist = live.credentials?.isPlaylist == true
    SettingGroup("Watching") {
        // A playlist gives each channel's address as it is; there is no choice of type.
        if (!playlist) ChoiceRow(
            title = "Stream type",
            description = "Try the other if channels stutter or won't start.",
            options = listOf(
                Option(StreamFormat.HLS, StreamFormat.HLS.label, "Works with most providers."),
                Option(StreamFormat.TS, StreamFormat.TS.label, "Starts faster with some providers."),
            ),
            selected = live.format,
            onSelect = { onToggleFormat() },
        )
        SettingRow(
            title = "Guide preview",
            switch = prefs.guidePreview,
            description = "Plays the highlighted channel in the guide.",
            onClick = onToggleGuidePreview,
        )
        ChoiceRow(
            title = "Multiview layout",
            options = listOf(
                Option(Settings.LAYOUT_GRID, "Grid", "Every channel gets an equal share of the screen."),
                Option(Settings.LAYOUT_FOCUS, "Focus", "The channel you're hearing gets most of the screen."),
            ),
            selected = if (prefs.multiviewLayout == Settings.LAYOUT_FOCUS) Settings.LAYOUT_FOCUS else Settings.LAYOUT_GRID,
            onSelect = { onToggleMultiviewLayout() },
        )
    }

    SettingGroup(
        "Movies and shows",
        note = if (playlist) "Your provider's movies and shows need an Xtream login rather than a playlist. " +
            "Sign out and sign in with your server, username and password to use them."
        else "Your provider's movies and shows in the Movies and TV Shows tabs, on Home and in search, " +
            "marked IPTV. Off, only Plex's are shown.",
    ) {
        if (!playlist) {
            SettingRow(
                title = "Show IPTV movies and shows",
                switch = prefs.iptvLibrary,
                onClick = { onSetIptvLibrary(!prefs.iptvLibrary) },
            )
            if (prefs.iptvLibrary) {
                ChoiceRow(
                    title = "When a title is in both",
                    description = "Which copy shows on Home and in search, and which the IPTV library leaves out.",
                    options = listOf(
                        Option(false, "Plex", "Plex's copy. The IPTV library only has what Plex doesn't."),
                        Option(true, "IPTV", "The provider's copy, in place of Plex's on Home and in search."),
                    ),
                    selected = prefs.iptvWins,
                    onSelect = onSetIptvWins,
                )
                SettingRow(
                    title = "Refresh movies and shows",
                    value = when {
                        iptv.loading -> "Refreshing…"
                        iptv.error != null && iptv.loadedAt == 0L -> "Couldn't load"
                        iptv.loadedAt == 0L -> "Not loaded"
                        else -> "${iptv.movieCount} movies · ${iptv.showCount} shows"
                    },
                    description = iptv.error ?: iptv.loadedAt.takeIf { it > 0 }?.let { "Updated ${relativeTime(it / 1000)}" },
                    onClick = onRefreshIptv,
                )
            }
        }
    }

    SettingGroup("Provider") {
        SettingRow(title = if (playlist) "Playlist" else "Server", value = live.credentials?.base?.let(::hostOf) ?: "—")
        if (!playlist) SettingRow(
            title = "Account",
            value = listOfNotNull(
                live.account?.status?.replaceFirstChar { it.uppercase() },
                live.account?.expiresAt?.let { "until ${epochLabel(it)}" },
            ).joinToString(" · ").ifEmpty { "—" },
        )
        if (!playlist) SettingRow(
            title = "Connections",
            value = "${live.account?.activeConnections ?: "?"} of ${live.account?.maxConnections ?: "?"} in use",
            description = "Each channel on screen uses one, including the guide preview.",
        )
        SettingRow(title = "Sign out of live TV", onClick = onSignOutXtream)
    }
}

/** Which rows Home shows. */
@Composable
private fun HomeSection(
    prefs: PlayerPrefs,
    reelyConnected: Boolean,
    onToggle: (tv.reely.ui.HomeRow) -> Unit,
    onSetScreensaver: (Int) -> Unit,
    iptvOn: Boolean = false,
) {
    // IPTV's rows only while its movies and shows are switched on.
    val iptvRows = setOf(tv.reely.ui.HomeRow.IPTV_MOVIES, tv.reely.ui.HomeRow.IPTV_SHOWS)
    val (yours, reely) = tv.reely.ui.HomeRow.entries
        .filter { iptvOn || it !in iptvRows }
        .partition { !it.fromReely }
    SettingGroup("Rows on Home") {
        yours.forEachIndexed { index, row ->
            SettingRow(
                title = row.title,
                switch = row.id !in prefs.hiddenHomeRows,
                first = index == 0,
                onClick = { onToggle(row) },
            )
        }
    }
    SettingGroup(
        "From Reely",
        note = if (reelyConnected) "What's trending and popular that you can ask for." else
            "Connect to Reely from the Request tab to show these.",
    ) {
        reely.forEach { row ->
            SettingRow(
                title = row.title,
                switch = row.id !in prefs.hiddenHomeRows,
                onClick = { onToggle(row) },
            )
        }
    }
    // The phone has its own; this one is the television's.
    if (!LocalCompactSettings.current) SettingGroup("Screensaver") {
        ChoiceRow(
            title = "Screensaver",
            description = "Your library's artwork and the time, when the remote's been put down.",
            options = listOf(
                Option(0, "Off", "Fire TV's own screensaver comes on instead."),
                Option(3, "After 3 minutes"),
                Option(5, "After 5 minutes", "Only if Fire TV's own is set to longer."),
                Option(10, "After 10 minutes", "Only if Fire TV's own is set to longer."),
            ),
            selected = prefs.screensaverMinutes,
            onSelect = onSetScreensaver,
        )
    }
}

/** Reely, where the Request tab sends what's asked for: where it is, and the way out. */
@Composable
private fun RequestsSection(requests: tv.reely.ui.RequestsState, onDisconnect: () -> Unit) {
    val server = requests.server
    if (server == null) {
        SettingGroup("Requests") {
            SettingRow(title = "Not connected", description = "Connect to Reely from the Request tab.")
        }
        return
    }
    SettingGroup(
        "Reely",
        note = "Requests go to Reely, signed in with your Plex account.",
    ) {
        SettingRow(title = "Server", value = hostOf(server))
        SettingRow(title = "Disconnect", first = true, onClick = onDisconnect)
    }
}

@Composable
private fun PlexPanel(
    plex: PlexState,
    onSwitchProfile: () -> Unit,
    onSwitchServer: (PlexServer) -> Unit,
    onToggleFavourite: (LibraryChoice) -> Unit,
    onSignOutPlex: () -> Unit,
) {
    if (!plex.isConnected) {
        SettingGroup("Plex") {
            SettingRow(title = "Not signed in", description = "Sign in from the Home tab.")
        }
        return
    }

    if (plex.error != null) ErrorNote(plex.error)

    val user = plex.user
    if (user != null) {
        SettingGroup(
            "Profile",
            note = if (plex.canSwitchUser) "Each profile in your Plex Home has its own libraries, " +
                "watch history and Continue Watching." else null,
        ) {
            SettingRow(
                title = user.title,
                value = if (plex.canSwitchUser) "Switch" else null,
                description = when {
                    user.admin -> "Plex Home owner"
                    user.restricted -> "Managed profile"
                    plex.canSwitchUser -> "Plex Home member"
                    else -> "Signed in to Plex"
                },
                first = plex.canSwitchUser,
                onClick = if (plex.canSwitchUser) onSwitchProfile else null,
            )
        }
    }

    SettingGroup("Server") {
        SettingRow(title = "Server", value = plex.serverName ?: "—")
        SettingRow(title = "Connection", value = connectionKind(plex.baseUrl))
    }

    if (plex.libraryChoices.size > 1) {
        SettingGroup(
            "Libraries",
            note = "Pinned libraries are the only ones shown in the Movies and TV Shows " +
                "menus. With none pinned, all of them are.",
        ) {
            plex.libraryChoices.forEachIndexed { index, choice ->
                val pinned = choice.id in plex.favouriteSections
                SettingRow(
                    first = index == 0,
                    title = if (plex.namesNeedServer) "${choice.section.title} · ${choice.serverName}"
                    else choice.section.title,
                    switch = pinned,
                    onClick = { onToggleFavourite(choice) },
                )
            }
        }
    }

    if (plex.servers.size > 1) {
        SettingGroup("Servers") {
            plex.servers.forEach { server ->
                val current = server.name == plex.serverName
                SettingRow(
                    title = server.name,
                    value = if (current) "In use" else "Switch",
                    onClick = { if (!current) onSwitchServer(server) },
                )
            }
        }
    }

    SettingGroup("Account") {
        SettingRow(
            title = "Sign out of Plex",
            first = plex.libraryChoices.size <= 1,
            onClick = onSignOutPlex,
        )
    }
}

/** Whether the server is being reached on the home network, which is what matters. */
private fun connectionKind(base: String?): String {
    val host = base?.let(::hostOf) ?: return "—"
    val local = Regex("""^(10|127|192\.168|172\.(1[6-9]|2\d|3[01]))[.-]""")
    return if (local.containsMatchIn(host.replace('-', '.'))) "Home network" else "Internet"
}

private fun hostOf(url: String): String =
    url.substringAfter("://").substringBefore('/').substringBefore(':')

@Composable
private fun UpdatesSection(
    update: UpdateStatus,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onOpenInstaller: () -> Unit,
) {
    SettingGroup("Reely") {
        SettingRow(title = "Version", value = BuildConfig.VERSION_NAME)
        /*
         * One row that changes what it says, not a different row for each state. Pressing
         * Check for updates turned it into Checking…, a new row, and the one pressed went
         * with the cursor on it, which then turned up on Search. It stays pressable while
         * it's busy, doing nothing, so the cursor has something to stay on.
         */
        val size = { bytes: Long -> bytes.takeIf { it > 0 }?.let { "${it / 1_048_576} MB" } }
        val (title, value, description) = when (update) {
            is UpdateStatus.Idle, is UpdateStatus.Failed -> Triple("Check for updates", null, null)
            is UpdateStatus.Checking -> Triple("Check for updates", "Checking…", null)
            is UpdateStatus.UpToDate -> Triple("Check for updates", "Up to date", null)
            is UpdateStatus.Available -> Triple(
                "Download and install",
                listOfNotNull(update.info.versionName?.let { "Version $it" }, size(update.info.sizeBytes))
                    .joinToString(" · "),
                "A new version is available.",
            )
            is UpdateStatus.Unlabelled -> Triple(
                "Download and install",
                size(update.info.sizeBytes) ?: "",
                "A version is available, but its number couldn't be read.",
            )
            is UpdateStatus.Downloading -> {
                val total = update.total.takeIf { it > 0 } ?: 1
                Triple("Downloading", "${update.read * 100 / total}%", null)
            }
            is UpdateStatus.Handed -> Triple(
                "Install",
                "Downloaded",
                update.note ?: "Confirm on the screen that appears. The first time, allow installs from Reely when asked.",
            )
        }
        SettingRow(
            title = title,
            value = value,
            description = description,
            first = true,
            onClick = when (update) {
                is UpdateStatus.Available, is UpdateStatus.Unlabelled -> onInstall
                is UpdateStatus.Idle, is UpdateStatus.UpToDate, is UpdateStatus.Failed -> onCheck
                is UpdateStatus.Handed -> onOpenInstaller
                else -> ({})
            },
        )
        if (update is UpdateStatus.Failed) ErrorNote(update.message)
    }
}

/** Licences shipped in assets/licenses, and what they cover. */
private enum class Licence(val title: String, val kind: String, val file: String, val covers: String?) {
    FFMPEG("FFmpeg", "LGPL 2.1", "ffmpeg-LGPL-2.1.txt", "Decodes Dolby and DTS sound."),
    GEIST("Geist", "SIL Open Font License", "geist-OFL.txt", "The typeface."),
    APACHE(
        "Open-source components",
        "Apache 2.0",
        "apache-2.0.txt",
        "Android Jetpack, Media3, Kotlin, OkHttp, Coil and ZXing.",
    ),
}

@Composable
private fun AboutSection(onTakeTour: () -> Unit = {}) {
    val context = LocalContext.current
    var reading by remember { mutableStateOf<Licence?>(null) }
    var crash by remember { mutableStateOf(tv.reely.core.CrashLog.read(context)) }
    var readingCrash by remember { mutableStateOf(false) }
    val open = reading
    if (open != null) {
        val text = remember(open) {
            runCatching {
                context.assets.open("licenses/${open.file}").bufferedReader().use { it.readText() }
            }.getOrDefault("")
        }
        TextViewer(title = "${open.title} · ${open.kind}", text = text, onClose = { reading = null })
        return
    }
    val report = crash
    if (readingCrash && report != null) {
        TextViewer(title = "Problem report", text = report, onClose = { readingCrash = false })
        return
    }

    Column(
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = "reely", color = Accent, fontSize = 40.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold)
        Text(text = "Your Plex library and live TV, in one place.", color = Chalk, style = ReelyType.Meta)
        Text(
            text = "Version ${BuildConfig.VERSION_NAME}",
            color = Muted,
            style = ReelyType.Label,
        )
    }

    // The tour is of the remote: nothing to show on a phone.
    if (!LocalCompactSettings.current) SettingGroup("Help") {
        SettingRow(
            title = "Take the tour",
            description = "How to get around with the remote.",
            first = true,
            onClick = onTakeTour,
        )
    }

    SettingGroup("Licenses") {
        Licence.entries.forEach { licence ->
            SettingRow(
                title = licence.title,
                value = licence.kind,
                description = licence.covers,
                onClick = { reading = licence },
            )
        }
    }
    Text(
        text = "FFmpeg is used unmodified. Its source is at github.com/FFmpeg/FFmpeg (n6.0.1).",
        color = Faint,
        style = ReelyType.Label,
        modifier = Modifier.padding(start = 4.dp),
    )
    if (report != null) {
        SettingGroup(
            "Problem report",
            note = "Kept on this device only. Nothing is sent anywhere.",
        ) {
            SettingRow(
                title = "Reely closed unexpectedly",
                description = report.lineSequence().drop(2).firstOrNull(),
                value = "View",
                onClick = { readingCrash = true },
            )
            SettingRow(
                title = "Clear the report",
                onClick = {
                    tv.reely.core.CrashLog.clear(context)
                    crash = null
                },
            )
        }
    }
}

/** A long text — a licence, a problem report — scrolled with up and down; back returns. */
@Composable
private fun TextViewer(title: String, text: String, onClose: () -> Unit) {
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)
    LaunchedEffect(title) { focus.requestWhenReady() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = title, color = Chalk, style = ReelyType.RowTitle)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceRaised)
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = when (event.key) {
                        Key.DirectionDown -> 240
                        Key.DirectionUp -> -240
                        else -> return@onPreviewKeyEvent false
                    }
                    scope.launch { scroll.animateScrollBy(step.toFloat()) }
                    true
                }
                .verticalScroll(scroll)
                .padding(18.dp),
        ) {
            Text(text = text, color = Muted, style = ReelyType.Label)
        }
        Text(text = "Up and down to scroll · Back to return", color = Faint, style = ReelyType.Label)
    }
}

private val LocalFirstOption = staticCompositionLocalOf<FocusRequester?> { null }

/** A titled block of setting rows, with an optional line of explanation under it. */
@Composable
private fun SettingGroup(title: String, note: String? = null, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title.uppercase(),
            color = Faint,
            style = ReelyType.Label,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceRaised)
                .padding(4.dp)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) { content() }
        if (note != null) {
            Text(text = note, color = Faint, style = ReelyType.Label, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/**
 * One setting: its name, a line of explanation, and its value on the right. OK changes it.
 * Without [onClick] it only reports, and focus passes over it.
 */
@Composable
private fun SettingRow(
    title: String,
    value: String? = null,
    description: String? = null,
    /** The page's first option: where focus lands coming in from the sections. */
    first: Boolean = false,
    /** An on/off setting: drawn as a switch in place of [value]; OK flips it. */
    switch: Boolean? = null,
    /** A setting that opens a list of its choices: a chevron after the value says so. */
    opens: Boolean = false,
    /** For coming back to this row, as a list it opened closes. */
    requester: FocusRequester? = null,
    onClick: (() -> Unit)? = null,
) {
    val firstOption = LocalFirstOption.current
    var focused by remember { mutableStateOf(false) }
    val colors = pillColors(focused)
    val base = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
    val row = if (onClick != null) {
        base
            .then(if (first && firstOption != null) Modifier.focusRequester(firstOption) else Modifier)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) colors.fill else Color.Transparent)
            .clickable(onClick = onClick)
    } else {
        base
    }
    Row(
        modifier = row.padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = if (focused) Ink else Chalk,
                style = ReelyType.Meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Text(
                    text = description,
                    color = if (focused) Ink.copy(alpha = 0.7f) else Muted,
                    style = ReelyType.Label,
                )
            }
        }
        if (switch != null) {
            Switch(on = switch, focused = focused)
        } else if (!value.isNullOrEmpty()) {
            Text(
                text = value,
                color = if (focused) Ink else Muted,
                style = ReelyType.Meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 300.dp),
            )
        }
        if (opens) {
            Text(text = "›", color = if (focused) Ink else Faint, fontSize = 22.sp, lineHeight = 22.sp)
        }
    }
}

/** On or off at a glance: a track with its knob at one end, coral when on. */
@Composable
private fun Switch(on: Boolean, focused: Boolean) {
    val travel by animateFloatAsState(if (on) 1f else 0f, tween(160), label = "switch")
    val track = when {
        on -> Accent
        focused -> Ink.copy(alpha = 0.22f)
        else -> Chalk.copy(alpha = 0.18f)
    }
    Box(
        modifier = Modifier
            .size(width = 44.dp, height = 26.dp)
            .clip(RoundedCornerShape(50))
            .background(track)
            .padding(3.dp),
    ) {
        Box(
            modifier = Modifier
                .offset(x = 18.dp * travel)
                .size(20.dp)
                .clip(CircleShape)
                .background(if (on || !focused) Chalk else Ink.copy(alpha = 0.55f)),
        )
    }
}

/** How a [ChoiceRow] asks the screen to open its list. */
private val LocalChoices = staticCompositionLocalOf<(ChoiceRequest) -> Unit> { {} }

/** One of the values a setting can take, with a line on what it does if it needs one. */
private data class Option<T>(val value: T, val label: String, val description: String? = null)

/**
 * A setting with several values. OK opens the whole list, the current one ticked and
 * under the cursor; OK on another picks it, Back leaves it as it was. Pressing OK over and
 * over to cycle round to the value wanted, as this was, is not how a setting is chosen.
 */
@Composable
private fun <T> ChoiceRow(
    title: String,
    options: List<Option<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    description: String? = null,
    first: Boolean = false,
) {
    val openChoices = LocalChoices.current
    val row = remember { FocusRequester() }
    val current = options.firstOrNull { it.value == selected }
    SettingRow(
        title = title,
        value = current?.label,
        description = description ?: current?.description,
        first = first,
        opens = true,
        requester = row,
        onClick = {
            openChoices(
                ChoiceRequest(
                    title = title,
                    options = options.map { it.label to it.description },
                    selected = options.indexOfFirst { it.value == selected }.coerceAtLeast(0),
                    onPick = { index ->
                        val value = options[index].value
                        if (value != selected) onSelect(value)
                    },
                    returnTo = row,
                ),
            )
        },
    )
}

private fun GuideStatus.describe(importedAt: Long): String = when (this) {
    is GuideStatus.Idle -> "Not loaded"
    is GuideStatus.Importing -> "Updating…"
    is GuideStatus.Ready -> "Updated ${relativeTime(importedAt)}"
    is GuideStatus.Failed -> "Couldn't update"
}

/** A date the way the television's language writes one: "Sep 16, 2026" in American English. */
private val dayFormat = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, Locale.getDefault())

private fun epochLabel(raw: String): String {
    val seconds = raw.toLongOrNull() ?: return raw
    return dayFormat.format(Date(seconds * 1_000))
}

private fun relativeTime(epochSeconds: Long): String {
    if (epochSeconds <= 0) return "never"
    val ago = System.currentTimeMillis() / 1_000 - epochSeconds
    return when {
        ago < 90 -> "just now"
        ago < 3_600 -> plural(ago / 60, "minute") + " ago"
        ago < 86_400 -> plural(ago / 3_600, "hour") + " ago"
        else -> plural(ago / 86_400, "day") + " ago"
    }
}

private fun plural(count: Long, unit: String) = if (count == 1L) "1 $unit" else "$count ${unit}s"

/** True on a phone's Settings: what only a television has (the screensaver, the remote's tour) is left out. */
internal val LocalCompactSettings = androidx.compose.runtime.staticCompositionLocalOf { false }

/**
 * Settings in one column, for a phone: the sections listed, and the one opened taking
 * the whole width. Back, or the arrow, goes back to the list.
 */
@Composable
private fun CompactSettings(section: Section, onSection: (Section) -> Unit, content: @Composable () -> Unit) {
    var open by remember { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = open) { open = false }
    CompositionLocalProvider(LocalCompactSettings provides true) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            if (!open) {
                Text(text = "Settings", color = Chalk, style = ReelyType.Headline, modifier = Modifier.padding(vertical = 12.dp))
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Section.entries.forEach { entry ->
                        SettingRow(title = entry.title, opens = true, onClick = {
                            onSection(entry)
                            open = true
                        })
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
                    Box(
                        modifier = Modifier.size(44.dp).clip(CircleShape).clickable { open = false },
                        contentAlignment = Alignment.Center,
                    ) { tv.reely.ui.components.ArrowGlyph(Chalk, left = true, size = 22.dp) }
                    Text(text = section.title, color = Chalk, style = ReelyType.Headline, modifier = Modifier.padding(start = 4.dp))
                }
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) { content() }
            }
        }
    }
}

/** What to call the device in a setting: the television's own name for it, or a phone's. */
@Composable
private fun deviceName(): String = if (LocalCompactSettings.current) "this device" else "the Fire TV"
