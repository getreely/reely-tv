package tv.reely.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color

/** One thing a card's menu can do. */
data class CardAction(
    val label: String,
    val emphasised: Boolean = false,
    /** A glyph beside the words, drawn in the colour it's handed. */
    val icon: (@Composable (Color) -> Unit)? = null,
    val onSelect: () -> Unit,
)

/**
 * What holding OK on a card offers.
 *
 * Marking something watched, or starting it again from the beginning, meant opening its
 * page and coming back. On a row of things half-watched that is most of the work, and
 * none of it is why anybody opened the page.
 *
 * A panel from the right edge, with the title's own picture at the top: see Menus.kt.
 * The caller draws it over everything, with a MenuScrim behind, rather than inside the
 * row, because a row clips its children. Back closes it.
 */
@Composable
fun CardMenu(
    title: String,
    subtitle: String?,
    actions: List<CardAction>,
    focusRequester: FocusRequester,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    /** The title's wide picture, for the top of the panel. */
    imageUrl: String? = null,
    logoUrl: String? = null,
    progress: Float? = null,
) {
    BackHandler(onBack = onCancel)
    // The rest of the hold that opened it is thrown away by the panel; see MenuPanel.
    MenuPanel(
        modifier = modifier
            // Up off the top or down off the bottom stays in the menu.
            .focusProperties { onExit = { cancelFocusChange() } }
            // Without the group the requester has nothing focusable of its own to hand
            // focus to, and every row here would be dead.
            .focusGroup()
            .focusRequester(focusRequester),
    ) {
        if (imageUrl != null || logoUrl != null) {
            MenuArtHeader(title = title, meta = subtitle, imageUrl = imageUrl, logoUrl = logoUrl, progress = progress)
        } else {
            MenuHeading(title = title, subtitle = subtitle)
        }
        // Scrolls rather than running off the bottom, if a title ever has more to offer
        // than fits; the row with the cursor is always brought into view.
        Column(
            modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            actions.forEach { action ->
                MenuItem(label = action.label, onClick = action.onSelect, icon = action.icon)
            }
        }
    }
}

/**
 * Tells a menu's own presses of OK from the tail of the hold that opened it. Each call
 * says whether to throw the event away.
 */
internal class StraySelect {
    private var sawOwnPress = false

    /** A key going down; only a fresh one, not a repeat, is a press of this menu's own. */
    fun down(repeatCount: Int): Boolean {
        if (repeatCount == 0) sawOwnPress = true
        return !sawOwnPress
    }

    fun up(): Boolean = !sawOwnPress
}
