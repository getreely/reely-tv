package tv.reely.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
 * The cursor onto the first of [targets] that is on screen. The last should be somewhere
 * that is always there: a place to put the cursor that may not exist at that moment —
 * the button a menu was opened from, Play under a grid of channels, a card taken away —
 * left it nowhere, or on the bare picture, and the remote did nothing until something
 * else moved it.
 */
suspend fun focusFirstOf(vararg targets: FocusRequester): Boolean {
    for (target in targets) if (target.requestWhenReady()) return true
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
class RowFocus(private val screen: ScreenFocus? = null) {
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

    /** Which item is where, for the ones on screen, to find the one beside a card that went. */
    private val keyAt = mutableMapOf<Int, String>()
    private var focusedKey: String? = null

    // A card taken away loses the cursor a moment before it's gone, so what lost it last,
    // and when, is what says the card that went had it.
    private var blurredKey: String? = null
    private var blurredAt = 0L

    /**
     * Where a card was that went while it had the cursor: Remove from Continue Watching,
     * say, or Mark watched on it. Read by the row, which puts the cursor on the card that
     * took its place, or the one before it at the end. Without that, the cursor had
     * nowhere to be and went back to the top of the page.
     */
    internal var lost by mutableIntStateOf(-1)
        private set
    internal var lostCount by mutableIntStateOf(0)
        private set

    fun requesterFor(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    fun onFocused(key: String) {
        remembered = key
        screen?.last = this
    }

    /** The card last on, whether or not it is on screen now. */
    internal val rememberedKey: String? get() = remembered

    /**
     * The first card on screen, for a row that hasn't had the cursor yet. Rows that give
     * positions know it for certain (keyAt[0]); for the rest it's the first card to
     * appear, since a row lays its cards out from the start.
     */
    private var firstSeen: String? = null

    fun onPresent(key: String, index: Int = -1) {
        present += key
        if (index >= 0) keyAt[index] = key
        if (firstSeen == null || index == 0) firstSeen = key
    }

    fun onGone(key: String, index: Int = -1) {
        // The card is kept in mind even so: entry() only ever hands out one that's
        // present, and a screen coming back puts the cursor on it once it's composed.
        present -= key
        if (index >= 0 && keyAt[index] == key) keyAt.remove(index)
        if (firstSeen == key) firstSeen = null
        val justLost = blurredKey == key && System.nanoTime() - blurredAt < JUST_LOST_NS && focusedKey == null
        if (focusedKey == key || justLost) {
            focusedKey = null
            blurredKey = null
            if (index >= 0) {
                lost = index
                lostCount++
            }
        }
    }

    internal fun onFocusState(key: String, focused: Boolean) {
        if (focused) {
            focusedKey = key
            // Every card in a row, whether or not it says so itself.
            onFocused(key)
        } else if (focusedKey == key) {
            focusedKey = null
            blurredKey = key
            blurredAt = System.nanoTime()
        }
    }

    /** The card at [index] now, or the nearest before it: what's beside one that went. */
    internal fun nearest(index: Int): String? =
        keyAt[index] ?: keyAt.keys.filter { it < index }.maxOrNull()?.let { keyAt[it] }

    /** The cursor onto the card at [index], or the nearest before it, when there is one. */
    suspend fun landAt(index: Int) {
        // A frame for the row to lay the cards out again without the one that went.
        withFrameNanos { }
        nearest(index)?.let { land(it) }
    }

    /**
     * Where focus should land on the way in: the card last on, or for a row not yet
     * visited, its first card, as every streaming app does. Only ever an item that is
     * still there; anything else defers to the ordinary focus search.
     *
     * The first card used to be left to the focus search, which takes whatever sits
     * straight below the cursor. A row scrolls to keep its card a third of the way in, so
     * down from any row landed on the third card of the next, every time.
     */
    fun entry(byArrow: Boolean = true): FocusRequester {
        remembered?.takeIf { it in present }?.let { key -> requesters[key]?.let { return it } }
        // Only for the cursor moved in by the remote. Code asking for one card (a show's
        // page opening on the episode that was playing) gets that card.
        if (!byArrow) return FocusRequester.Default
        val first = keyAt[0]?.takeIf { it in present } ?: firstSeen?.takeIf { it in present }
        return first?.let { requesterFor(it) } ?: FocusRequester.Default
    }

    /** Puts the cursor on one item directly, for arriving at a row from somewhere else. */
    suspend fun land(key: String) {
        remembered = key
        requesterFor(key).requestWhenReady()
    }
}

/** How recently a card may have lost the cursor for its going to count as taking it. */
private const val JUST_LOST_NS = 250_000_000L

@Composable
fun rememberRowFocus(): RowFocus = remember { RowFocus() }

/**
 * A row that outlives its screen: named, and kept by the screen's [ScreenFocus] rather
 * than by the composition, so going to a title and coming back finds the cursor where it
 * was. Outside a screen that keeps them, the same as [rememberRowFocus].
 */
@Composable
fun rememberRowFocus(id: String): RowFocus {
    val screen = LocalScreenFocus.current
    return remember(screen, id) { screen?.row(id) ?: RowFocus() }
}

/**
 * Where the cursor was on one screen: its rows, and which of them it was last in.
 *
 * Every screen was built again from nothing on the way back to it, so Back from a title
 * opened from the third row of Home put the cursor on the first card of the first row,
 * with the page scrolled to the top. The app keeps one of these per screen for as long
 * as it runs, and on the way back puts the cursor on the card it was on.
 */
@Stable
class ScreenFocus {
    private val rows = mutableMapOf<String, RowFocus>()
    internal var last: RowFocus? = null

    fun row(id: String): RowFocus = rows.getOrPut(id) { RowFocus(this) }

    /** The cursor back on the card last on; false when there's none to go back to. */
    suspend fun restore(): Boolean {
        val row = last ?: return false
        val key = row.rememberedKey ?: return false
        return row.requesterFor(key).requestWhenReady()
    }
}

/** The screen showing's [ScreenFocus], provided by the app around each screen. */
val LocalScreenFocus = androidx.compose.runtime.staticCompositionLocalOf<ScreenFocus?> { null }

/**
 * What an item in a row wears: its own requester, and a note to the row that it exists
 * for as long as it is composed. The second half is what stops a row pointing focus at
 * something that has since been scrolled away or replaced.
 */
@Composable
fun rowItem(row: RowFocus, key: String, index: Int = -1): Modifier {
    DisposableEffect(row, key, index) {
        row.onPresent(key, index)
        onDispose { row.onGone(key, index) }
    }
    return Modifier
        .focusRequester(row.requesterFor(key))
        .onFocusChanged { row.onFocusState(key, it.isFocused) }
}

/**
 * For a row whose cards can go while one has the cursor: puts it on the card beside the
 * one that went. Needs the cards' positions, given to [rowItem].
 */
@Composable
fun FollowRemovals(row: RowFocus) {
    val count = row.lostCount
    LaunchedEffect(row, count) {
        if (count > 0) row.landAt(row.lost)
    }
}

/**
 * Applied to a row, sends focus back to the item it was last on.
 *
 * The scope's three outcomes are what the old `enter` expressed by what it returned:
 * requesting focus on a requester is returning one, doing nothing is FocusRequester.Default
 * asking for the ordinary focus search, and cancelFocusChange() is FocusRequester.Cancel.
 */
fun Modifier.restoreFocusTo(row: RowFocus): Modifier =
    this.focusProperties {
        onEnter = {
            // The app putting the cursor back on one card goes to that card.
            if (!FocusReturn.active) {
                val remembered = row.entry(byArrow = requestedFocusDirection.isArrow())
                if (remembered != FocusRequester.Default) remembered.requestFocus()
            }
        }
    }

/**
 * Whether a focus change is the remote's arrows moving the cursor, as opposed to the app
 * itself putting the cursor somewhere (the row a list was opened from, say).
 */
internal fun FocusDirection.isArrow(): Boolean =
    this == FocusDirection.Up || this == FocusDirection.Down ||
        this == FocusDirection.Left || this == FocusDirection.Right ||
        this == FocusDirection.Next || this == FocusDirection.Previous

/**
 * Keeps the arrows inside a menu, list or question while it's up, so pressing past its
 * edge can't wander into the screen behind.
 *
 * Only the arrows. Stopping every way out also stopped the app putting the cursor back
 * where it came from as the menu closed; with nowhere left to be, the cursor fell to the
 * first thing in the window, the search button, every time a setting was chosen. That is
 * the jump that kept coming back.
 */
fun Modifier.keepCursorInside(): Modifier = this.focusProperties {
    onExit = { if (requestedFocusDirection.isArrow()) cancelFocusChange() }
}

/**
 * The app putting the cursor back where it came from, as a list or a menu closes.
 *
 * Pages have rules for where the cursor lands on the way in: Settings starts at its
 * sections, a row at the card it was last on. Those are for the remote's arrows. When a
 * list closed and the app asked for the row it was opened from, Settings took that as
 * arriving and sent the cursor to the sections instead, and from there it could end up
 * anywhere, the search button above all. While this is at work the rules stand aside.
 */
object FocusReturn {
    var active: Boolean = false
        private set

    /** The cursor onto [target], trying for a few frames while the screen settles. */
    suspend fun to(target: FocusRequester): Boolean {
        active = true
        try {
            return target.requestWhenReady()
        } finally {
            active = false
        }
    }
}
