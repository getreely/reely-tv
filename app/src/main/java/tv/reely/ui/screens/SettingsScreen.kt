package tv.reely.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import tv.reely.ui.components.FactLine
import tv.reely.ui.components.SectionHeading
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.components.TvChip
import tv.reely.ui.theme.Line
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.SurfaceRaised
import tv.reely.xtream.StreamFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private enum class Section(val title: String) {
    VIDEO("Video"),
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
    onNudgeThemeVolume: (Float) -> Unit,
    onRefreshChannels: () -> Unit,
    onRefreshGuide: () -> Unit,
    update: UpdateStatus,
    updateUrl: String,
    onCheckForUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var section by remember { mutableStateOf(Section.VIDEO) }

    Row(modifier = modifier.fillMaxSize().padding(horizontal = 36.dp, vertical = 14.dp)) {
        Column(
            modifier = Modifier.width(180.dp).fillMaxHeight().focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Settings",
                color = Chalk,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            Section.entries.forEach { entry ->
                TvChip(
                    label = entry.title,
                    selected = section == entry,
                    onClick = { section = entry },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(start = 26.dp)
                .verticalScroll(rememberScrollState())
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            when (section) {
                Section.VIDEO -> VideoSection(
                    prefs = prefs,
                    onNudgeSubtitleScale = onNudgeSubtitleScale,
                    onToggleSubtitleBackground = onToggleSubtitleBackground,
                    onNudgeUpNext = onNudgeUpNext,
                    onCyclePlaybackMode = onCyclePlaybackMode,
                    onCycleMaxBitrate = onCycleMaxBitrate,
                    onToggleThemeMusic = onToggleThemeMusic,
                    onToggleMatchFrameRate = onToggleMatchFrameRate,
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
                    updateUrl = updateUrl,
                    onCheck = onCheckForUpdate,
                    onInstall = onInstallUpdate,
                )

                Section.ABOUT -> AboutSection()
            }
        }
    }
}

@Composable
private fun VideoSection(
    prefs: PlayerPrefs,
    onNudgeSubtitleScale: (Float) -> Unit,
    onToggleSubtitleBackground: () -> Unit,
    onNudgeUpNext: (Int) -> Unit,
    onCyclePlaybackMode: () -> Unit,
    onCycleMaxBitrate: () -> Unit,
    onToggleThemeMusic: () -> Unit,
    onToggleMatchFrameRate: () -> Unit,
    onNudgeThemeVolume: (Float) -> Unit,
) {
    Panel(title = "Playback") {
        FactLine("Mode", playbackModeLabel(prefs.playbackMode))
        FactLine("Transcode ceiling", bitrateLabel(prefs.maxBitrateKbps))
        Buttons {
            TvActionButton(label = "Change mode", onClick = onCyclePlaybackMode)
            TvActionButton(label = "Change ceiling", onClick = onCycleMaxBitrate)
        }
    }

    Panel(title = "Subtitles") {
        FactLine("Size", "${(prefs.subtitleScale * 100).toInt()}%")
        FactLine("Background", if (prefs.subtitleBackground) "On" else "Off (outlined)")
        Text(
            text = "Subtitles start off on everything. Choosing a track in the player turns " +
                "them on and that choice carries to whatever is played next.",
            color = Muted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Buttons {
            TvActionButton(
                label = "Smaller",
                onClick = { onNudgeSubtitleScale(-Settings.SCALE_STEP) },
            )
            TvActionButton(
                label = "Bigger",
                onClick = { onNudgeSubtitleScale(Settings.SCALE_STEP) },
            )
            TvActionButton(
                label = if (prefs.subtitleBackground) "Background off" else "Background on",
                onClick = onToggleSubtitleBackground,
            )
        }
    }

    Panel(title = "Refresh rate") {
        FactLine("Match the screen to the film", if (prefs.matchFrameRate) "On" else "Off")
        Text(
            text = "Film runs at just under twenty-four frames a second and a television " +
                "sits at sixty, which does not divide — so some frames are held longer " +
                "than others and slow camera moves stutter. This asks the screen to " +
                "change rate to suit what is playing, and puts it back afterwards. Turn " +
                "it off if your television blanks for a second or two while it changes. " +
                "Live channels are left alone either way.",
            color = Muted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Buttons {
            TvActionButton(
                label = if (prefs.matchFrameRate) "Turn off" else "Turn on",
                onClick = onToggleMatchFrameRate,
                emphasised = prefs.matchFrameRate,
            )
        }
    }

    Panel(title = "Theme music") {
        FactLine("Show themes", if (prefs.themeMusic) "On" else "Off")
        FactLine("Volume", "${(prefs.themeVolume * 100).roundToInt()}%")
        Text(
            text = "A show's title music plays quietly under its page, where the server has " +
                "one. It stops the moment anything is played, and never takes sound away " +
                "from whatever else the television is doing. A tenth is about right; this " +
                "is a raw gain, so twice the number is a great deal more than twice as loud.",
            color = Muted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Buttons {
            TvActionButton(
                label = if (prefs.themeMusic) "Turn off" else "Turn on",
                onClick = onToggleThemeMusic,
                emphasised = prefs.themeMusic,
            )
            TvActionButton(
                label = "Quieter",
                onClick = { onNudgeThemeVolume(-Settings.THEME_VOLUME_STEP) },
            )
            TvActionButton(
                label = "Louder",
                onClick = { onNudgeThemeVolume(Settings.THEME_VOLUME_STEP) },
            )
        }
    }

    Panel(title = "Up Next") {
        FactLine(
            "Countdown",
            if (prefs.upNextSeconds > 0) "${prefs.upNextSeconds} seconds" else "Off — waits for you",
        )
        Buttons {
            TvActionButton(label = "Shorter", onClick = { onNudgeUpNext(-Settings.UP_NEXT_STEP) })
            TvActionButton(label = "Longer", onClick = { onNudgeUpNext(Settings.UP_NEXT_STEP) })
        }
    }
}

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
        Panel(title = "Live TV") {
            Text(
                text = "No provider configured. The Live TV tab will ask for a panel address.",
                color = Muted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
        return
    }

    Panel(title = "Refresh") {
        FactLine("Channels", "${live.categories.size} categories")
        FactLine("Guide", guide.status.describe())
        FactLine("Guide last imported", relativeTime(guide.importedAt))
        Text(
            text = "Providers add and drop channels without notice, and the guide goes stale " +
                "on its own. Neither is refetched unless asked.",
            color = Muted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Buttons {
            TvActionButton(
                label = if (live.busy) "Refreshing…" else "Refresh channels",
                onClick = onRefreshChannels,
            )
            TvActionButton(
                label = if (guide.status is GuideStatus.Importing) "Importing…" else "Refresh guide",
                onClick = onRefreshGuide,
            )
        }
    }

    Panel(title = "Provider") {
        if (live.error != null) ErrorNote(live.error)
        FactLine("Panel", live.credentials?.base ?: "—")
        FactLine("Account", live.account?.status ?: "—")
        FactLine(
            "Connections",
            "${live.account?.activeConnections ?: "?"} of ${live.account?.maxConnections ?: "?"} in use",
        )
        FactLine("Expires", live.account?.expiresAt?.let { epochLabel(it) } ?: "—")
        FactLine("Stream container", live.format.label)
        FactLine(
            "Guide preview",
            if (prefs.guidePreview) "On — uses one connection while browsing" else "Off",
        )
        Text(
            text = "HLS fetches the stream a segment at a time, so a moment's trouble costs a " +
                "segment rather than the whole connection, and a player that falls behind " +
                "can rejoin by itself. The cost is a slower start and sitting further " +
                "behind live. MPEG-TS is one continuous connection: quicker onto a channel " +
                "and closer to the action, with nothing to rejoin when it breaks.",
            color = Muted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        FactLine(
            "Multiview layout",
            if (prefs.multiviewLayout == Settings.LAYOUT_FOCUS)
                "Focus — the one you are hearing takes most of the screen"
            else
                "Grid — equal room for each",
        )
        Text(
            text = "Multiview opens one connection per channel, so four tiles needs four of " +
                "them — and this account allows " +
                "${live.account?.maxConnections ?: "?"}. In the grid, OK fills the screen " +
                "with a tile and back returns to it; holding OK swaps what is in it.",
            color = Muted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
        Buttons {
            TvActionButton(
                label = if (prefs.multiviewLayout == Settings.LAYOUT_FOCUS) "Use grid"
                else "Use focus layout",
                onClick = onToggleMultiviewLayout,
            )
        }
        Buttons {
            TvActionButton(
                label = "Use ${if (live.format == StreamFormat.TS) "HLS" else "MPEG-TS"}",
                onClick = onToggleFormat,
            )
            TvActionButton(
                label = if (prefs.guidePreview) "Preview off" else "Preview on",
                onClick = onToggleGuidePreview,
            )
            TvActionButton(label = "Sign out", onClick = onSignOutXtream)
        }
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
        Panel(title = "Plex") {
            Text(
                text = "Not signed in. The Home, Movies and TV Shows tabs will offer to link an account.",
                color = Muted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
        return
    }

    Panel(title = "Server") {
        if (plex.error != null) ErrorNote(plex.error)
        FactLine("Connected to", plex.serverName ?: "unknown")
        FactLine("Address", plex.baseUrl ?: "—")
        FactLine(
            "Libraries",
            plex.sections.joinToString(", ") { "${it.title} (${it.type})" }.ifEmpty { "none" },
        )
        Buttons {
            TvActionButton(label = "Sign out of Plex", onClick = onSignOutPlex)
        }
    }

    if (plex.libraryChoices.size > 1) {
        Panel(title = "Libraries in the tab menu") {
            Text(
                text = if (plex.favouriteSections.isEmpty())
                    "Every library on every server is offered. Pick some and only those will be."
                else
                    "Only the picked libraries are offered. Clear them all to go back to " +
                        "offering every one.",
                color = Muted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Column(
                modifier = Modifier.padding(top = 8.dp).focusGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                plex.libraryChoices.forEach { choice ->
                    val picked = choice.id in plex.favouriteSections
                    val name = if (plex.namesNeedServer) {
                        "${choice.section.title} — ${choice.serverName}"
                    } else {
                        choice.section.title
                    }
                    TvActionButton(
                        label = "${if (picked) "★" else "☆"}  $name",
                        onClick = { onToggleFavourite(choice) },
                        emphasised = picked,
                    )
                }
            }
        }
    }

    if (plex.servers.size > 1) {
        Panel(title = "Other servers") {
            Text(
                text = "This account can reach ${plex.servers.size} servers. Their libraries are " +
                    "offered together in the Movies and TV Shows menus, so picking a library " +
                    "is usually all that is needed and this is the long way round. Switching " +
                    "replaces every library, row and page with that server's own.",
                color = Muted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
            Column(
                modifier = Modifier.padding(top = 8.dp).focusGroup(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                plex.servers.forEach { server ->
                    TvActionButton(
                        label = if (server.name == plex.serverName) "${server.name} — in use"
                        else "Switch to ${server.name}",
                        onClick = { onSwitchServer(server) },
                        emphasised = server.name == plex.serverName,
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdatesSection(
    update: UpdateStatus,
    updateUrl: String,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
) {
    Panel(title = "This build") {
        FactLine("Version", "${BuildConfig.VERSION_NAME}  (build ${BuildConfig.VERSION_CODE})")
        FactLine("Update address", updateUrl)
        Text(
            text = "Releases are built and published by GitHub, so this address always " +
                "points at the newest one.",
            color = Muted,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }

    Panel(title = "Check") {
        when (update) {
            is UpdateStatus.Idle -> Text(
                text = "Nothing checked yet.",
                color = Muted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )

            is UpdateStatus.Checking -> Text(
                text = "Asking the server what it is holding…",
                color = Muted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )

            is UpdateStatus.UpToDate -> Text(
                text = "This is the build published there. Nothing to download.",
                color = Muted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )

            is UpdateStatus.Available -> {
                FactLine(
                    "Published",
                    "${update.info.versionName ?: "?"}  (build ${update.info.versionCode})",
                )
                if (update.info.sizeBytes > 0) {
                    FactLine("Size", "${update.info.sizeBytes / 1_048_576} MB")
                }
                update.info.published?.let { FactLine("Dated", it) }
                update.info.notes?.let {
                    Text(text = it, color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
                }
            }

            is UpdateStatus.Unlabelled -> {
                Text(
                    text = "There is a build at that address, but nothing saying which one. " +
                        "Whether it is newer than this one cannot be known without " +
                        "downloading it, so installing it is a decision rather than an " +
                        "upgrade. Publishing reely-tv.json beside the APK fixes that — " +
                        "the release build writes one.",
                    color = Muted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
                if (update.info.sizeBytes > 0) {
                    FactLine("Size", "${update.info.sizeBytes / 1_048_576} MB")
                }
                update.info.published?.let { FactLine("Dated", it) }
            }

            is UpdateStatus.Downloading -> {
                val total = update.total.takeIf { it > 0 } ?: 1
                FactLine("Downloading", "${update.read * 100 / total}%")
            }

            is UpdateStatus.Handed -> Text(
                text = "Handed to the installer. Fire OS asks once for permission to install " +
                    "from Reely before it will go ahead.",
                color = Muted,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )

            is UpdateStatus.Failed -> ErrorNote(update.message)
        }

        Buttons {
            TvActionButton(
                label = if (update is UpdateStatus.Checking) "Checking…" else "Check for update",
                onClick = onCheck,
            )
            when (update) {
                is UpdateStatus.Available -> TvActionButton(
                    label = "Download and install",
                    onClick = onInstall,
                    emphasised = true,
                )

                is UpdateStatus.Unlabelled -> TvActionButton(
                    label = "Install it anyway",
                    onClick = onInstall,
                )

                else -> Unit
            }
        }
    }
}

@Composable
private fun AboutSection() {
    Panel(title = "Playback") {
        Text(
            text = "Direct play streams the file as it sits on the server and asks this device " +
                "to decode it, which is the best picture and no work for the server. When the " +
                "device cannot decode something, the server re-encodes it on the fly — which " +
                "is what Auto falls back to, and what Always transcode does from the start. " +
                "A transcode burns subtitles into the picture, so image subtitles play too.",
            color = Muted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
        )
    }
    Panel(title = "The guide") {
        Text(
            text = "The provider's whole XMLTV guide is read straight into a local database as " +
                "it downloads, so a guide of several hundred thousand programmes never has to " +
                "fit in memory at once. Only the stretch on screen is held.",
            color = Muted,
            fontSize = 14.sp,
            lineHeight = 21.sp,
        )
    }
}

@Composable
private fun Panel(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceRaised)
            .border(1.dp, Line, RoundedCornerShape(12.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionHeading(title, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

@Composable
private fun Buttons(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.padding(top = 8.dp).focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        content()
    }
}

private fun GuideStatus.describe(): String = when (this) {
    is GuideStatus.Idle -> "Not loaded yet"
    is GuideStatus.Importing ->
        if (written == 0 && scanned == 0) "Downloading…"
        else "Importing… $written kept of $scanned read"

    is GuideStatus.Ready -> "$count programmes"
    is GuideStatus.Failed -> message
}

private fun playbackModeLabel(mode: String): String = when (mode) {
    Settings.MODE_DIRECT -> "Direct play only"
    Settings.MODE_TRANSCODE -> "Always transcode"
    else -> "Auto — transcode only if direct play fails"
}

private fun bitrateLabel(kbps: Int): String =
    if (kbps <= 0) "Original quality" else "${kbps / 1_000} Mbps"

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
        ago < 3_600 -> "${ago / 60} minutes ago"
        ago < 86_400 -> "${ago / 3_600} hours ago"
        else -> "${ago / 86_400} days ago"
    }
}
