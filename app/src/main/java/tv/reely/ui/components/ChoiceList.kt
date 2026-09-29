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
 * The list itself: a panel from the right, like every other menu here (see Menus.kt),
 * with the value in use ticked and the cursor starting on it. It holds the cursor while
 * it is up: up off the top or down off the bottom goes nowhere rather than out into the
 * screen behind it.
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
    Box(modifier = Modifier.fillMaxSize()) {
        MenuScrim()
        MenuPanel(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .onFocusChanged { holding = it.hasFocus }
                .focusProperties { onExit = { cancelFocusChange() } }
                .focusGroup(),
        ) {
            MenuHeading(title = request.title)
            // A long list (a library's decades, say) scrolls rather than running off the screen.
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                request.options.forEachIndexed { index, (label, description) ->
                    MenuItem(
                        label = label,
                        detail = description,
                        checked = index == request.selected,
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
