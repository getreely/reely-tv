package tv.reely.ui.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.reely.core.Reminder
import tv.reely.ui.components.TvActionButton
import tv.reely.ui.components.sheet
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.ReelyType

/** How long a reminder stays up unanswered. */
private const val REMINDER_SHOWN_MS = 2L * 60_000

/**
 * "Starting now": a programme somebody asked to be told about, with one press to watch
 * it. It puts itself away after a couple of minutes if nobody answers.
 */
@Composable
fun ReminderCard(
    reminder: Reminder,
    focusRequester: FocusRequester,
    onWatch: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(reminder) {
        delay(REMINDER_SHOWN_MS)
        dismiss()
    }
    Column(
        modifier = modifier
            .widthIn(max = 520.dp)
            .sheet(radius = 18)
            .padding(horizontal = 22.dp, vertical = 18.dp)
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = "Starting now", color = Muted, style = ReelyType.Label)
        Text(
            text = reminder.title,
            color = Chalk,
            style = ReelyType.Headline,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(text = "On ${reminder.channelName}", color = Muted, style = ReelyType.Body, maxLines = 1)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvActionButton(
                label = "Watch",
                onClick = onWatch,
                emphasised = true,
                modifier = Modifier.focusRequester(focusRequester),
            )
            TvActionButton(label = "Dismiss", onClick = onDismiss)
        }
    }
}
