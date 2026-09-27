package tv.reely.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.reely.ui.theme.Accent
import tv.reely.ui.theme.Chalk
import tv.reely.ui.theme.Faint
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Muted
import tv.reely.ui.theme.ReelyType
import tv.reely.ui.theme.SurfaceRaised

/** A list of choices, drawn by the screen over everything: a setting's values, a library's sorts. */
class ChoiceRequest(
    val title: String,
    val options: List<Pair<String, String?>>,
    val selected: Int,
    val onPick: (Int) -> Unit,
    /** Where the cursor goes back to when the list closes: whatever opened it. */
    val returnTo: FocusRequester,
)

/**
 * The list itself, over a dimmed screen. It holds the cursor while it is up: up off the
 * top or down off the bottom goes nowhere rather than out into the screen behind it.
 */
@Composable
fun ChoicePanel(request: ChoiceRequest, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val start = remember(request) { FocusRequester() }
    var holding by remember(request) { mutableStateOf(false) }
    LaunchedEffect(request) {
        repeat(40) {
            if (holding) return@LaunchedEffect
            start.requestWhenReady()
            delay(50)
        }
    }
    Box(
        modifier = Modifier.fillMaxSize().background(Ink.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(480.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceRaised)
                .border(1.dp, Chalk.copy(alpha = 0.12f), RoundedCornerShape(20.dp))
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .onFocusChanged { holding = it.hasFocus }
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = request.title,
                color = Chalk,
                style = ReelyType.RowTitle,
                modifier = Modifier.padding(start = 12.dp, bottom = 10.dp),
            )
            // A long list (a library's decades, say) scrolls rather than running off the screen.
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                request.options.forEachIndexed { index, (label, description) ->
                    OptionRow(
                        label = label,
                        description = description,
                        chosen = index == request.selected,
                        onClick = {
                            request.onPick(index)
                            onClose()
                        },
                        modifier = if (index == request.selected) Modifier.focusRequester(start) else Modifier,
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionRow(
    label: String,
    description: String?,
    chosen: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }
    val colors = pillColors(focused)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) colors.fill else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // A radio: a ring, filled when this is the one in use.
        Box(
            modifier = Modifier
                .size(20.dp)
                .border(2.dp, if (focused) Ink else if (chosen) Accent else Faint, CircleShape)
                .padding(4.dp)
                .clip(CircleShape)
                .background(if (chosen) (if (focused) Ink else Accent) else Color.Transparent),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = label,
                color = if (focused) Ink else Chalk,
                style = ReelyType.Meta,
                fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium,
            )
            if (description != null) {
                Text(
                    text = description,
                    color = if (focused) Ink.copy(alpha = 0.7f) else Muted,
                    style = ReelyType.Label,
                )
            }
        }
    }
}
