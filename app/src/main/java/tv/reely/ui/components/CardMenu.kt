package tv.reely.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Line
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.Parchment

/** One thing a card's menu can do. */
data class CardAction(
    val label: String,
    val emphasised: Boolean = false,
    val onSelect: () -> Unit,
)

/**
 * What holding OK on a card offers.
 *
 * Marking something watched, or starting it again from the beginning, meant opening its
 * page and coming back. On a row of things half-watched that is most of the work, and
 * none of it is why anybody opened the page.
 *
 * The caller draws this over everything rather than inside the row, because a row clips
 * its children and a menu that appears half cut off is worse than no menu.
 */
@Composable
fun CardMenu(
    title: String,
    subtitle: String?,
    actions: List<CardAction>,
    focusRequester: FocusRequester,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    /*
     * A hold opens this menu while the finger is still down, and by the time the key
     * comes up this has taken focus — so the release lands on a button here. The card
     * that opened it cannot swallow it, because it is no longer the thing receiving keys.
     * So a release with no press of its own behind it is thrown away.
     */
    var sawOwnPress by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .onPreviewKeyEvent { event ->
                if (!event.isSelect()) return@onPreviewKeyEvent false
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        sawOwnPress = true
                        false
                    }

                    KeyEventType.KeyUp -> !sawOwnPress
                    else -> false
                }
            }
            .widthIn(min = 260.dp, max = 420.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Ink.copy(alpha = 0.97f))
            .border(1.dp, Line, RoundedCornerShape(14.dp))
            .padding(20.dp)
            // Without the group the requester has nothing focusable of its own to hand
            // focus to, and every button here would be dead.
            .focusGroup()
            .focusRequester(focusRequester),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            color = Parchment,
            fontSize = 16.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        subtitle?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                color = Muted,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        actions.forEach { action ->
            TvActionButton(
                label = action.label,
                onClick = action.onSelect,
                emphasised = action.emphasised,
            )
        }
        TvActionButton(label = "Cancel", onClick = onCancel)
    }
}
