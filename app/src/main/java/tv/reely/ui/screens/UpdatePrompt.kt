package tv.reely.ui.screens

import tv.reely.ui.components.keepCursorInside
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.reely.BuildConfig
import tv.reely.ui.UpdateStatus
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.components.requestWhenReady
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.ReelyType
import tv.reely.ui.theme.SurfaceRaised
import kotlin.math.roundToInt

/**
 * "A new version of Reely is ready": asked once, when the app finds one at startup.
 * Update now downloads it with the progress showing and hands it to the system installer;
 * Later puts it off until the next start. It holds the cursor while it is up.
 */
@Composable
fun UpdatePrompt(
    status: UpdateStatus,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    /** The installer again, on the update already downloaded. */
    onInstall: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onLater)
    // Each stage has its own button — Update now, then Hide while it downloads, then
    // Close — and the one pressed goes when the next arrives, so the cursor is put on
    // the new one every time rather than left with nowhere to be.
    val stage = when (status) {
        is UpdateStatus.Downloading -> 1
        is UpdateStatus.Handed -> 2
        is UpdateStatus.Failed -> 3
        else -> 0
    }
    val start = remember(stage) { FocusRequester() }
    var landed by remember(stage) { mutableStateOf(false) }
    val startButton = Modifier.focusRequester(start).onFocusChanged { if (it.isFocused) landed = true }
    LaunchedEffect(stage) {
        repeat(40) {
            if (landed) return@LaunchedEffect
            start.requestWhenReady()
            delay(50)
        }
    }

    val info = (status as? UpdateStatus.Available)?.info
    Box(
        modifier = modifier.fillMaxSize().background(Ink.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(520.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(SurfaceRaised)
                .border(1.dp, Chalk.copy(alpha = 0.12f), RoundedCornerShape(22.dp))
                .padding(horizontal = 32.dp, vertical = 28.dp)
                .keepCursorInside()
                .focusGroup(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(text = "reely", color = Accent, fontSize = 30.sp, lineHeight = 34.sp, style = ReelyType.Display)
            Text(
                text = when (status) {
                    is UpdateStatus.Downloading -> "Downloading the update…"
                    is UpdateStatus.Handed -> "Ready to install"
                    is UpdateStatus.Failed -> "The update didn't download"
                    else -> "A new version is available"
                },
                color = Chalk,
                style = ReelyType.Headline,
                textAlign = TextAlign.Center,
            )
            Text(
                text = when (status) {
                    is UpdateStatus.Downloading -> "It installs as soon as it's here."
                    is UpdateStatus.Handed -> status.note ?: "Confirm on the screen that appears."
                    is UpdateStatus.Failed -> status.message
                    else -> buildString {
                        append("Version ${info?.versionName ?: "?"} is ready")
                        info?.sizeBytes?.takeIf { it > 0 }?.let { append(" (${(it / 1_000_000.0).roundToInt()} MB)") }
                        append(". You have ${BuildConfig.VERSION_NAME}.")
                    }
                },
                color = Muted,
                style = ReelyType.Body,
                textAlign = TextAlign.Center,
            )

            if (status is UpdateStatus.Downloading) {
                val fraction = if (status.total > 0) (status.read.toFloat() / status.total).coerceIn(0f, 1f) else 0f
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Chalk.copy(alpha = 0.16f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(8.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Accent),
                    )
                }
                Text(
                    text = "${(fraction * 100).roundToInt()}%",
                    color = Muted,
                    style = ReelyType.Label,
                )
                // Something to hold the cursor while it downloads, and a way out.
                TvActionButton(
                    label = "Hide",
                    onClick = onLater,
                    modifier = startButton,
                )
            } else if (status is UpdateStatus.Handed) {
                // Install opens the installer again, should it have been closed or not
                // come up, without downloading the update a second time.
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TvActionButton(
                        label = "Install",
                        onClick = onInstall,
                        emphasised = true,
                        modifier = startButton,
                    )
                    TvActionButton(label = "Close", onClick = onLater)
                }
            } else {
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TvActionButton(
                        label = if (status is UpdateStatus.Failed) "Try again" else "Update now",
                        onClick = onUpdate,
                        emphasised = true,
                        modifier = startButton,
                    )
                    TvActionButton(label = "Later", onClick = onLater)
                }
            }
        }
    }
}
