package tv.reely

import org.junit.Assert.assertEquals
import tv.reely.ui.components.SelectOutcome
import tv.reely.ui.components.SelectPress
import org.junit.Test

/**
 * Telling a press of OK from a hold of OK. Both mistakes this guards against have been
 * shipped: acting on the way down, so a hold did the press as well and maximised a tile
 * before opening the menu over it; and letting the release through, so the menu that had
 * just opened had a button pressed by the finger coming off the same key.
 */
class SelectPressTest {

    private fun SelectPress.down(repeat: Boolean = false) = step(down = true, repeat = repeat)
    private fun SelectPress.up() = step(down = false, repeat = false)

    @Test
    fun `a press acts when the key comes up, never on the way down`() {
        val press = SelectPress()

        assertEquals(SelectOutcome.NONE, press.down())
        assertEquals(SelectOutcome.PRESS, press.up())
    }

    @Test
    fun `a hold acts on the first repeat`() {
        val press = SelectPress()

        assertEquals(SelectOutcome.NONE, press.down())
        assertEquals(SelectOutcome.HOLD, press.down(repeat = true))
    }

    @Test
    fun `a hold does not also act as a press when the key comes up`() {
        val press = SelectPress()
        press.down()
        press.down(repeat = true)

        // The release belongs to the hold. Letting it through pressed whatever the menu
        // it opened had just focused.
        assertEquals(SelectOutcome.NONE, press.up())
    }

    @Test
    fun `holding longer only acts once`() {
        val press = SelectPress()
        press.down()

        assertEquals(SelectOutcome.HOLD, press.down(repeat = true))
        assertEquals(SelectOutcome.NONE, press.down(repeat = true))
        assertEquals(SelectOutcome.NONE, press.down(repeat = true))
        assertEquals(SelectOutcome.NONE, press.up())
    }

    @Test
    fun `the press after a hold is an ordinary press again`() {
        val press = SelectPress()
        press.down()
        press.down(repeat = true)
        press.up()

        assertEquals(SelectOutcome.NONE, press.down())
        assertEquals(SelectOutcome.PRESS, press.up())
    }

    @Test
    fun `two presses in a row both act`() {
        val press = SelectPress()

        press.down()
        assertEquals(SelectOutcome.PRESS, press.up())
        press.down()
        assertEquals(SelectOutcome.PRESS, press.up())
    }

    @Test
    fun `a release with no press behind it still reads as a press`() {
        // The key can go down on one screen and come up on the next. Acting is the
        // lesser of the two evils: the alternative is a button that does nothing.
        assertEquals(SelectOutcome.PRESS, SelectPress().up())
    }

    @Test
    fun `forgetting a press in flight makes the next release a press`() {
        val press = SelectPress()
        press.down()
        press.down(repeat = true)
        press.reset()

        assertEquals(SelectOutcome.PRESS, press.up())
    }

    @Test
    fun `a hold says it is waiting for the release`() {
        val press = SelectPress()
        assertEquals(false, press.awaitingRelease)

        press.down()
        assertEquals(false, press.awaitingRelease)

        press.down(repeat = true)
        // What a caller has to consult before deciding it no longer owns the key. On a
        // multiview tile the hold opened a menu, the caller's "no menu is open" test then
        // failed, and the release went to the menu instead of being swallowed.
        assertEquals(true, press.awaitingRelease)

        press.up()
        assertEquals(false, press.awaitingRelease)
    }

    @Test
    fun `an ordinary press never claims to be waiting`() {
        val press = SelectPress()
        press.down()
        assertEquals(false, press.awaitingRelease)
        press.up()
        assertEquals(false, press.awaitingRelease)
    }
}
