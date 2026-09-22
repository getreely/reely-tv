package tv.reely.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** OK on a remote, whichever button the manufacturer decided that is. */
fun KeyEvent.isSelect(): Boolean = key == Key.DirectionCenter || key == Key.Enter

/**
 * A press of OK and a hold of OK, told apart in one place.
 *
 * Two rules matter and both are easy to get wrong, which is why this exists rather than
 * being written out at each call site.
 *
 * A hold is a key-down repeat: Android repeats a held key once the system's long-press
 * timeout elapses, so the first repeat is the signal that the finger is still down.
 *
 * A press therefore cannot act until the key comes up. Acting on the way down means a
 * hold does both things — on a tile that was maximise followed by the menu opening over
 * it, and in the guide it would have been a channel change followed by the menu. The
 * rest of the press has to be swallowed too: Compose fires a click on key-up, and by
 * then whatever the hold opened has taken focus, so letting go would press a button in
 * it. Consuming every select event here is what stops that.
 *
 * Borrowed from Plezy's DpadSelectLongPressController, which is the same idea with a
 * timer in place of the repeat count. A timer does not depend on the remote reporting
 * repeats; the repeat count follows whatever long-press timeout the system is set to.
 * The repeat is kept because it is known to work on the hardware this runs on.
 */
/** What a half-press turned out to mean. */
enum class SelectOutcome { NONE, PRESS, HOLD }

@Stable
class SelectPress {
    /** Set once a hold has been acted on, and cleared when the key comes back up. */
    private var held = false

    /**
     * The decision on its own, with no key event attached, because this is the part that
     * has been wrong twice and a stubbed android.view.KeyEvent cannot report a repeat.
     */
    fun step(down: Boolean, repeat: Boolean): SelectOutcome {
        if (down) {
            // Only the first repeat counts. Later ones are the same finger still down.
            if (repeat && !held) {
                held = true
                return SelectOutcome.HOLD
            }
            return SelectOutcome.NONE
        }
        val outcome = if (held) SelectOutcome.NONE else SelectOutcome.PRESS
        held = false
        return outcome
    }

    /**
     * Returns true when the event belonged to this press and should go no further.
     * Call it before anything else that wants OK, and only while this press owns it.
     */
    fun handle(event: KeyEvent, onPress: () -> Unit, onHold: () -> Unit): Boolean {
        if (!event.isSelect()) return false
        val down = when (event.type) {
            KeyEventType.KeyDown -> true
            KeyEventType.KeyUp -> false
            else -> return false
        }
        when (step(down, event.nativeKeyEvent.repeatCount >= 1)) {
            SelectOutcome.PRESS -> onPress()
            SelectOutcome.HOLD -> onHold()
            SelectOutcome.NONE -> Unit
        }
        return true
    }

    /** Forgets a press in flight, for when what it was aimed at has gone. */
    fun reset() {
        held = false
    }
}

@Composable
fun rememberSelectPress(): SelectPress = remember { SelectPress() }
