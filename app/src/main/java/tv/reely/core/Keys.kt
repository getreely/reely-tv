package tv.reely.core

import android.view.KeyEvent

/**
 * Whether a key nothing in the app used should be dropped rather than passed on.
 *
 * The arrow keys, and only those. When Compose finds nothing further in the direction
 * pressed — the Settings gear is the rightmost thing on screen, say — it hands the key
 * to Android's view system. To that system the whole app is one view, so it moves focus
 * out of it and straight back in from the opposite edge: right from the gear landed on
 * Search, and since arriving on a tab by arrow key opens it, the app went to Search.
 * Nothing on a television should wrap like that.
 *
 * Everything else still goes through. Back in particular must, or it could never leave.
 */
fun dropsWhenUnused(keyCode: Int): Boolean = keyCode in ARROWS

private val ARROWS = setOf(
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_UP_LEFT,
    KeyEvent.KEYCODE_DPAD_UP_RIGHT,
    KeyEvent.KEYCODE_DPAD_DOWN_LEFT,
    KeyEvent.KEYCODE_DPAD_DOWN_RIGHT,
)
