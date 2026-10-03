package tv.reely.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.Line

/** The panel's width, which the caller needs to keep it on screen. */
val TAB_MENU_WIDTH = 300.dp

/** One thing a tab's menu can do. */
data class TabMenuItem(
    val label: String,
    val selected: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * The panel a tab drops when it is chosen: where in this library to go, which library to
 * use, and — for an account that can reach more than one — which server to use at all.
 *
 * Switching server lives here as well as in Settings because it is a thing people do
 * while browsing, not a thing they set up once.
 */
@Composable
fun TabMenu(
    groups: List<Pair<String, List<TabMenuItem>>>,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    // Dropped from its tab rather than slid in from the side, since it belongs to the tab,
    // but with the same rows as every other menu (see Menus.kt).
    Column(
        modifier = modifier
            .width(TAB_MENU_WIDTH)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF7101216))
            .border(1.dp, tv.reely.ui.theme.GlassEdge, RoundedCornerShape(16.dp))
            .padding(10.dp)
            .focusGroup()
            .focusRequester(focusRequester),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        groups.forEach { (heading, items) ->
            if (items.isEmpty()) return@forEach
            MenuSection(heading)
            items.forEach { item ->
                MenuItem(label = item.label, checked = item.selected, onClick = item.onClick)
            }
        }
    }
}