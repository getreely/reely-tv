package tv.reely.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import kotlinx.coroutines.delay

/**
 * Asks for focus until there is something there to take it.
 *
 * A FocusRequester throws until whatever it is attached to has been laid out, and the
 * thing doing the asking is almost always composed in the same pass as the thing being
 * asked for. A single attempt therefore fails, and because the failure is an exception
 * nobody wants to crash on, it gets swallowed — leaving nothing focused at all, which on
 * a television is a remote that does nothing.
 *
 * That one mistake accounted for the dead transport controls, the unpressable Skip Intro
 * button, the cursor landing on the search tab when opening an episode, and the channel
 * menu whose buttons could not be reached. Nothing in this app should call requestFocus
 * directly; this exists so the mistake cannot be made again.
 */
suspend fun FocusRequester.requestWhenReady(
    attempts: Int = 16,
    retryMs: Long = 32,
): Boolean {
    repeat(attempts) {
        if (runCatching { requestFocus() }.isSuccess) return true
        delay(retryMs)
    }
    return false
}

/**
 * Remembers where the cursor was in a row, so leaving it and coming back returns to the
 * same card rather than to whichever one happens to be nearest.
 *
 * Every item keeps its own requester for as long as the row lives, and the shape of an
 * item's modifier chain never changes. That matters: attaching a requester to only the
 * focused item rebuilds that item's focus node at the moment it gains focus, and the node
 * takes the focus with it. That is the same fault that sent the cursor to the search tab
 * on every navigation, and it is easy to reintroduce by making this conditional.
 */
@Stable
class RowFocus {
    private val requesters = mutableMapOf<String, FocusRequester>()

    /**
     * The keys currently on screen. A requester for an item that has gone is not merely
     * useless — handing one to Compose's focus machinery throws, and it throws from
     * inside the focus search rather than anywhere that can catch it. Changing season
     * emptied the rail and left the old episode remembered, so pressing down into it
     * brought the whole app down.
     */
    private val present = mutableSetOf<String>()
    private var remembered: String? = null

    fun requesterFor(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    fun onFocused(key: String) {
        remembered = key
    }

    fun onPresent(key: String) {
        present += key
    }

    fun onGone(key: String) {
        present -= key
        if (remembered == key) remembered = null
    }

    /**
     * Where focus should land on the way in. Only ever an item that is still there;
     * anything else defers to the ordinary focus search.
     */
    fun entry(): FocusRequester =
        remembered?.takeIf { it in present }?.let { requesters[it] } ?: FocusRequester.Default

    /** Puts the cursor on one item directly, for arriving at a row from somewhere else. */
    suspend fun land(key: String) {
        remembered = key
        requesterFor(key).requestWhenReady()
    }
}

@Composable
fun rememberRowFocus(): RowFocus = remember { RowFocus() }

/**
 * What an item in a row wears: its own requester, and a note to the row that it exists
 * for as long as it is composed. The second half is what stops a row pointing focus at
 * something that has since been scrolled away or replaced.
 */
@Composable
fun rowItem(row: RowFocus, key: String): Modifier {
    DisposableEffect(row, key) {
        row.onPresent(key)
        onDispose { row.onGone(key) }
    }
    return Modifier.focusRequester(row.requesterFor(key))
}

/**
 * Applied to a row, sends focus back to the item it was last on.
 *
 * `enter` is deprecated in favour of `onEnter`, which reports where focus should go by
 * calling into a scope rather than by returning a requester. The two do not map onto each
 * other cleanly — there is no obvious equivalent of returning FocusRequester.Default to
 * mean "do the ordinary thing" — and every focus regression in this app has been found by
 * using it on a television rather than by reading it. So this stays on the old call until
 * somebody can watch the change happen on a screen.
 */
@Suppress("DEPRECATION")
fun Modifier.restoreFocusTo(row: RowFocus): Modifier =
    this.focusProperties { enter = { row.entry() } }
