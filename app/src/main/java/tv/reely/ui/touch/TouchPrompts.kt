package tv.reely.ui.touch

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import tv.reely.BuildConfig
import tv.reely.core.Reminder
import tv.reely.ui.UpdateStatus
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.SurfaceRaised
import kotlin.math.roundToInt

/**
 * "A new version is available", sized for a phone: the same stages as the television's —
 * Update now, the download's progress, Install, Try again — with buttons for a finger.
 */
@Composable
internal fun TouchUpdatePrompt(
    status: UpdateStatus,
    onUpdate: () -> Unit,
    onLater: () -> Unit,
    onInstall: () -> Unit,
) {
    BackHandler(onBack = onLater)
    val info = (status as? UpdateStatus.Available)?.info
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Ink.copy(alpha = 0.72f))
            // A press outside the card is a press on nothing behind it.
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(SurfaceRaised)
                .border(1.dp, Chalk.copy(alpha = 0.12f), RoundedCornerShape(22.dp))
                .padding(horizontal = 24.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("reely", color = Accent, fontSize = 26.sp, style = MaterialTheme.typography.headlineMedium)
            Text(
                when (status) {
                    is UpdateStatus.Downloading -> "Downloading the update…"
                    is UpdateStatus.Handed -> "Ready to install"
                    is UpdateStatus.Failed -> "The update didn't download"
                    else -> "A new version is available"
                },
                color = Chalk,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                when (status) {
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
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            when (status) {
                is UpdateStatus.Downloading -> {
                    val fraction = if (status.total > 0) (status.read.toFloat() / status.total).coerceIn(0f, 1f) else 0f
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(Chalk.copy(alpha = 0.16f))) {
                        Box(Modifier.fillMaxWidth(fraction).height(8.dp).clip(RoundedCornerShape(50)).background(Accent))
                    }
                    Text("${(fraction * 100).roundToInt()}%", color = Muted, style = MaterialTheme.typography.labelMedium)
                    TouchSecondaryButton("Hide", onLater)
                }
                is UpdateStatus.Handed -> Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TouchPrimaryButton("Install", onInstall)
                    TouchSecondaryButton("Close", onLater)
                }
                else -> Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TouchPrimaryButton(if (status is UpdateStatus.Failed) "Try again" else "Update now", onUpdate)
                    TouchSecondaryButton("Later", onLater)
                }
            }
        }
    }
}

/** How long a reminder stays up unanswered, as on the television. */
private const val REMINDER_SHOWN_MS = 2L * 60_000

/** "Starting now", above the tabs: one press to watch it. It goes by itself after a while. */
@Composable
internal fun TouchReminder(reminder: Reminder, onWatch: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(reminder) {
        delay(REMINDER_SHOWN_MS)
        dismiss()
    }
    Column(
        modifier = modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceRaised)
            .border(1.dp, Chalk.copy(alpha = 0.12f), RoundedCornerShape(18.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Starting now", color = Accent, style = MaterialTheme.typography.labelMedium)
        Text(reminder.title, color = Chalk, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("On ${reminder.channelName}", color = Muted, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TouchPrimaryButton("Watch", onWatch)
            TouchSecondaryButton("Dismiss", onDismiss)
        }
    }
}
