package tv.reely

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.reely.core.dropsWhenUnused

class KeysTest {

    /** Right from the Settings gear used to come back in on the left, at Search. */
    @Test
    fun `an arrow nothing used is dropped rather than wrapped`() {
        for (key in listOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        )) assertTrue(dropsWhenUnused(key))
    }

    /** Back has to reach the system, or the app could never be left. */
    @Test
    fun `back and everything else still go through`() {
        for (key in listOf(
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_VOLUME_UP,
        )) assertFalse(dropsWhenUnused(key))
    }
}
