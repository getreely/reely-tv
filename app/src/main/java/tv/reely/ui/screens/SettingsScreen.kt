package tv.reely.ui.screens

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
    LIVE_TV("Live TV"),
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
    onCyclePlaybackMode: () -> Unit,
    onCycleMaxBitrate: () -> Unit,
    onToggleMultiviewLayout: () -> Unit,
    onToggleThemeMusic: () -> Unit,
    onToggleMatchFrameRate: () -> Unit,
    onToggleLargerBuffer: () -> Unit,
    onNudgeThemeVolume: (Float) -> Unit,
    onRefreshChannels: () -> Unit,
    onRefreshGuide: () -> Unit,
    update: UpdateStatus,
    updateUrl: String,
    onCheckForUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var section by remember { mutableStateOf(Section.PLAYBACK) }
    val sectionFocus = remember { Section.entries.associateWith { FocusRequester() } }
    var inOptions by remember { mutableStateOf(false) }
    // The first thing to change on each page, where right from the sections lands —
    // rather than whatever row happens to sit level with the section, which on two
    // pages was Sign out.
    val firstOption = remember { FocusRequester() }

    // Back from the options goes back to the section they belong to. Back from the
    // sections leaves Settings, as it always has.
    BackHandler(enabled = inOptions) { sectionFocus.getValue(section).requestFocus() }

    /*
     * Settings is entered at its sections, on the one that is showing: arriving from the
     * gear up in the corner used to land in the options, the nearest thing below it, with
     * no obvious way back out to the sections. Coming back left from the options lands on
     * that section too, rather than on whichever one happened to sit level with the cursor.
     */
    val toSection = Modifier.focusProperties {
        onEnter = { sectionFocus.getValue(section).requestFocus() }
    }

    Row(
        modifier = modifier
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
                    onEnter = { runCatching { firstOption.requestFocus() } }
                }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            CompositionLocalProvider(LocalFirstOption provides firstOption) {
            when (section) {
                Section.PLAYBACK -> PlaybackSection(
                    prefs = prefs,
                    onNudgeSubtitleScale = onNudgeSubtitleScale,
                    onToggleSubtitleBackground = onToggleSubtitleBackground,
                    onNudgeUpNext = onNudgeUpNext,
                    onCyclePlaybackMode = onCyclePlaybackMode,
                    onCycleMaxBitrate = onCycleMaxBitrate,
                    onToggleThemeMusic = onToggleThemeMusic,
                    onToggleMatchFrameRate = onToggleMatchFrameRate,
                    onToggleLargerBuffer = onToggleLargerBuffer,
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
                )

                Section.PLEX -> PlexPanel(
                    plex = plex,
                    onSwitchServer = onSwitchServer,
                    onToggleFavourite = onToggleFavourite,
                    onSignOutPlex = onSignOutPlex,
                )

                Section.UPDATES -> UpdatesSection(
                    update = update,
                    onCheck = onCheckForUpdate,
                    onInstall = onInstallUpdate,
                )

                Section.ABOUT -> AboutSection()
            }
            }
        }
    }
}

@Composable
private fun PlaybackSection(
    prefs: PlayerPrefs,
    onNudgeSubtitleScale: (Float) -> Unit,
    onToggleSubtitleBackground: () -> Unit,
    onNudgeUpNext: (Int) -> Unit,
    onCyclePlaybackMode: () -> Unit,
    onCycleMaxBitrate: () -> Unit,
    onToggleThemeMusic: () -> Unit,
    onToggleMatchFrameRate: () -> Unit,
    onToggleLargerBuffer: () -> Unit,
    onNudgeThemeVolume: (Float) -> Unit,
) {
    SettingGroup("Video") {
        SettingRow(
            title = "Playback mode",
            first = true,
            value = when (prefs.playbackMode) {
                Settings.MODE_DIRECT -> "Original only"
                Settings.MODE_TRANSCODE -> "Always convert"
                else -> "Automatic"
            },
            description = when (prefs.playbackMode) {
                Settings.MODE_DIRECT -> "Always plays the original file. Some may not play."
                Settings.MODE_TRANSCODE -> "Plex converts everything. Uses more of your server."
                else -> "Plays the original file. Plex converts it only when needed."
            },
            onClick = onCyclePlaybackMode,
        )
        SettingRow(
            title = "Conversion quality",
            value = if (prefs.maxBitrateKbps <= 0) "Original" else "Up to ${prefs.maxBitrateKbps / 1_000} Mbps",
            description = "The highest quality Plex uses when it converts a video.",
            onClick = onCycleMaxBitrate,
        )
        SettingRow(
            title = "Match frame rate",
            value = onOff(prefs.matchFrameRate),
            description = "Smoother motion. The screen may blink as it switches.",
            onClick = onToggleMatchFrameRate,
        )
        SettingRow(
            title = "Buffer",
            value = if (prefs.largerBuffer) "Larger" else "Normal",
            description = "Larger helps on a slow or unsteady connection.",
            onClick = onToggleLargerBuffer,
        )
    }

    SettingGroup("Subtitles") {
        SettingRow(
            title = "Size",
            value = "${(prefs.subtitleScale * 100).roundToInt()}%",
            onClick = { onNudgeSubtitleScale(nextStep(prefs.subtitleScale, SUBTITLE_SIZES)) },
        )
        SettingRow(
            title = "Background",
            value = onOff(prefs.subtitleBackground),
            description = "A dark box behind the text, instead of an outline.",
            onClick = onToggleSubtitleBackground,
        )
    }

    SettingGroup("Up Next") {
        SettingRow(
            title = "Countdown",
            value = if (prefs.upNextSeconds > 0) "${prefs.upNextSeconds} seconds" else "Off",
            description = "How long before the next episode starts by itself.",
            onClick = {
                val next = UP_NEXT_SECONDS.firstOrNull { it > prefs.upNextSeconds } ?: UP_NEXT_SECONDS.first()
                onNudgeUpNext(next - prefs.upNextSeconds)
            },
        )
    }

    SettingGroup("Show pages") {
        val level = themeLevel(prefs)
        SettingRow(
            title = "Theme music",
            value = THEME_LEVELS.getOrNull(level)?.first ?: "Off",
            description = "Plays a show's theme song on its page.",
            onClick = {
                // Off, then each volume in turn, then off again.
                val next = level + 1
                when {
                    level < 0 -> {
                        onToggleThemeMusic()
                        onNudgeThemeVolume(THEME_LEVELS.first().second - prefs.themeVolume)
                    }
                    next < THEME_LEVELS.size ->
                        onNudgeThemeVolume(THEME_LEVELS[next].second - prefs.themeVolume)
                    else -> onToggleThemeMusic()
                }
            },
        )
    }
}

private val SUBTITLE_SIZES = listOf(0.7f, 0.8f, 0.9f, 1.0f, 1.2f, 1.4f)
private val UP_NEXT_SECONDS = listOf(0, 5, 10, 15, 20, 30)
private val THEME_LEVELS = listOf("Quiet" to 0.05f, "Medium" to 0.10f, "Loud" to 0.20f)

/** The change that takes a value to the next of [steps], or back round to the first. */
private fun nextStep(current: Float, steps: List<Float>): Float {
    val next = steps.firstOrNull { it > current + 0.01f } ?: steps.first()
    return next - current
}

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
        SettingRow(
            title = "Refresh TV guide",
            value = guide.status.describe(guide.importedAt),
            onClick = onRefreshGuide,
        )
    }

    SettingGroup("Watching") {
        SettingRow(
            title = "Stream type",
            value = live.format.label,
            description = "Try the other if channels stutter or won't start.",
            onClick = onToggleFormat,
        )
        SettingRow(
            title = "Guide preview",
            value = onOff(prefs.guidePreview),
            description = "Plays the highlighted channel in the guide.",
            onClick = onToggleGuidePreview,
        )
        SettingRow(
            title = "Multiview layout",
            value = if (prefs.multiviewLayout == Settings.LAYOUT_FOCUS) "Focus" else "Grid",
            description = if (prefs.multiviewLayout == Settings.LAYOUT_FOCUS)
                "The channel you're hearing gets most of the screen."
            else
                "Every channel gets an equal share of the screen.",
            onClick = onToggleMultiviewLayout,
        )
    }

    SettingGroup("Provider") {
        SettingRow(title = "Server", value = live.credentials?.base?.let(::hostOf) ?: "—")
        SettingRow(
            title = "Account",
            value = listOfNotNull(
                live.account?.status?.replaceFirstChar { it.uppercase() },
                live.account?.expiresAt?.let { "until ${epochLabel(it)}" },
            ).joinToString(" · ").ifEmpty { "—" },
        )
        SettingRow(
            title = "Connections",
            value = "${live.account?.activeConnections ?: "?"} of ${live.account?.maxConnections ?: "?"} in use",
            description = "Each channel on screen uses one, including the guide preview.",
        )
        SettingRow(title = "Sign out of live TV", onClick = onSignOutXtream)
    }
}

@Composable
private fun PlexPanel(
    plex: PlexState,
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
                    value = if (pinned) "Pinned" else "",
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
) {
    SettingGroup("Reely") {
        SettingRow(title = "Version", value = BuildConfig.VERSION_NAME)
        when (update) {
            is UpdateStatus.Idle ->
                SettingRow(title = "Check for updates", first = true, onClick = onCheck)

            is UpdateStatus.Checking ->
                SettingRow(title = "Check for updates", value = "Checking…", first = true, onClick = {})

            is UpdateStatus.UpToDate ->
                SettingRow(title = "Check for updates", value = "Up to date", first = true, onClick = onCheck)

            is UpdateStatus.Available -> SettingRow(
                title = "Download and install",
                value = listOfNotNull(
                    update.info.versionName?.let { "Version $it" },
                    update.info.sizeBytes.takeIf { it > 0 }?.let { "${it / 1_048_576} MB" },
                ).joinToString(" · "),
                description = "A new version is available.",
                first = true,
                onClick = onInstall,
            )

            is UpdateStatus.Unlabelled -> SettingRow(
                title = "Download and install",
                value = update.info.sizeBytes.takeIf { it > 0 }?.let { "${it / 1_048_576} MB" } ?: "",
                description = "A version is available, but its number couldn't be read.",
                first = true,
                onClick = onInstall,
            )

            is UpdateStatus.Downloading -> {
                val total = update.total.takeIf { it > 0 } ?: 1
                SettingRow(title = "Downloading", value = "${update.read * 100 / total}%")
            }

            is UpdateStatus.Handed -> SettingRow(
                title = "Ready to install",
                description = "Confirm on the screen that appears. The first time, Fire TV " +
                    "asks you to allow installs from Reely.",
            )

            is UpdateStatus.Failed -> {
                SettingRow(title = "Check for updates", first = true, onClick = onCheck)
                ErrorNote(update.message)
            }
        }
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
        "Android Jetpack, Media3, Kotlin, OkHttp and Coil.",
    ),
}

@Composable
private fun AboutSection() {
    var reading by remember { mutableStateOf<Licence?>(null) }
    val open = reading
    if (open != null) {
        LicenceText(open, onClose = { reading = null })
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

    SettingGroup("Licences") {
        Licence.entries.forEach { licence ->
            SettingRow(
                title = licence.title,
                value = licence.kind,
                description = licence.covers,
                first = licence == Licence.entries.first(),
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
}

/** A licence's full text, scrolled with up and down; back returns to the list. */
@Composable
private fun LicenceText(licence: Licence, onClose: () -> Unit) {
    val context = LocalContext.current
    val text = remember(licence) {
        runCatching {
            context.assets.open("licenses/${licence.file}").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }
    val scroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)
    LaunchedEffect(licence) { focus.requestWhenReady() }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(text = "${licence.title} · ${licence.kind}", color = Chalk, style = ReelyType.RowTitle)
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
        if (!value.isNullOrEmpty()) {
            Text(
                text = value,
                color = if (focused) Ink else Muted,
                style = ReelyType.Meta,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 300.dp),
            )
        }
    }
}

private fun GuideStatus.describe(importedAt: Long): String = when (this) {
    is GuideStatus.Idle -> "Not loaded"
    is GuideStatus.Importing -> "Updating…"
    is GuideStatus.Ready -> "Updated ${relativeTime(importedAt)}"
    is GuideStatus.Failed -> "Couldn't update"
}

private val dayFormat = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

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
