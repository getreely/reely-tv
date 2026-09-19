package tv.reely.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.reely.core.Settings
import tv.reely.ui.LiveState
import tv.reely.ui.PlayerPrefs
import tv.reely.ui.PlexState
import tv.reely.ui.components.FactLine
import tv.reely.ui.components.SectionHeading
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.theme.Line
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Parchment
import tv.reely.ui.theme.SurfaceRaised
import tv.reely.xtream.StreamFormat

@Composable
fun StatusScreen(
    plex: PlexState,
    live: LiveState,
    prefs: PlayerPrefs,
    onSignOutPlex: () -> Unit,
    onSignOutXtream: () -> Unit,
    onToggleFormat: () -> Unit,
    onNudgeSubtitleScale: (Float) -> Unit,
    onToggleSubtitleBackground: () -> Unit,
    onNudgeUpNext: (Int) -> Unit,
    onToggleGuidePreview: () -> Unit,
    onCyclePlaybackMode: () -> Unit,
    onCycleMaxBitrate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 40.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            text = "Status",
            color = Parchment,
            fontSize = 24.sp,
            lineHeight = 30.sp,
            fontWeight = FontWeight.SemiBold,
        )

        Panel(title = "Playback") {
            FactLine("Mode", playbackModeLabel(prefs.playbackMode))
            FactLine("Transcode ceiling", bitrateLabel(prefs.maxBitrateKbps))
            Row(
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TvActionButton(label = "Change mode", onClick = onCyclePlaybackMode)
                TvActionButton(label = "Change ceiling", onClick = onCycleMaxBitrate)
            }
            FactLine("Subtitle size", "${(prefs.subtitleScale * 100).toInt()}%")
            FactLine("Subtitle background", if (prefs.subtitleBackground) "On" else "Off (outlined)")
            FactLine(
                "Up Next countdown",
                if (prefs.upNextSeconds > 0) "${prefs.upNextSeconds} seconds" else "Off — waits for you",
            )
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TvActionButton(
                    label = "Subtitles smaller",
                    onClick = { onNudgeSubtitleScale(-Settings.SCALE_STEP) },
                )
                TvActionButton(
                    label = "Subtitles bigger",
                    onClick = { onNudgeSubtitleScale(Settings.SCALE_STEP) },
                )
                TvActionButton(
                    label = if (prefs.subtitleBackground) "Background off" else "Background on",
                    onClick = onToggleSubtitleBackground,
                )
            }
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TvActionButton(
                    label = "Countdown shorter",
                    onClick = { onNudgeUpNext(-Settings.UP_NEXT_STEP) },
                )
                TvActionButton(
                    label = "Countdown longer",
                    onClick = { onNudgeUpNext(Settings.UP_NEXT_STEP) },
                )
            }
        }

        Panel(title = "Plex library") {
            if (plex.isConnected) {
                FactLine("Server", plex.serverName ?: "unknown")
                FactLine("Address", plex.baseUrl ?: "—")
                FactLine(
                    "Libraries",
                    plex.sections.joinToString(", ") { "${it.title} (${it.type})" }
                        .ifEmpty { "none" },
                )
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    TvActionButton(label = "Sign out of Plex", onClick = onSignOutPlex)
                }
            } else {
                Text(
                    text = "Not signed in. The Home, Movies and TV Shows tabs will offer to link an account.",
                    color = Muted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        }

        Panel(title = "Live TV") {
            if (live.isConnected) {
                FactLine("Panel", live.credentials?.base ?: "—")
                FactLine("Account", live.account?.status ?: "—")
                FactLine(
                    "Connections",
                    "${live.account?.activeConnections ?: "?"} of ${live.account?.maxConnections ?: "?"} in use",
                )
                FactLine("Categories", live.categories.size.toString())
                FactLine("Stream container", live.format.label)
                FactLine(
                    "Guide preview",
                    if (prefs.guidePreview) "On — uses one connection while browsing" else "Off",
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TvActionButton(
                        label = "Use ${if (live.format == StreamFormat.TS) "HLS" else "MPEG-TS"}",
                        onClick = onToggleFormat,
                    )
                    TvActionButton(
                        label = if (prefs.guidePreview) "Preview off" else "Preview on",
                        onClick = onToggleGuidePreview,
                    )
                    TvActionButton(label = "Sign out of live TV", onClick = onSignOutXtream)
                }
            } else {
                Text(
                    text = "No provider configured. The Live TV tab will ask for a panel address.",
                    color = Muted,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                )
            }
        }

        Panel(title = "About this build") {
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
            Text(
                text = "The Guide tab reads the provider's whole XMLTV guide and writes it straight " +
                    "into a local database as it downloads, so a guide of several hundred thousand " +
                    "programmes never has to fit in memory at once.",
                color = Muted,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
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
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SectionHeading(title, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}

private fun playbackModeLabel(mode: String): String = when (mode) {
    Settings.MODE_DIRECT -> "Direct play only"
    Settings.MODE_TRANSCODE -> "Always transcode"
    else -> "Auto — transcode only if direct play fails"
}

private fun bitrateLabel(kbps: Int): String =
    if (kbps <= 0) "Original quality" else "${kbps / 1_000} Mbps"
