package tv.reely

import androidx.compose.ui.focus.FocusRequester
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import tv.reely.ui.components.RowFocus

/**
 * The season switch crashed the app because a row pointed focus at an episode that had
 * been removed, and Compose throws from inside the focus search where nothing can catch
 * it. These pin the rule that came out of that: a row only ever offers a key that is
 * still on screen.
 */
class RowFocusTest {

    @Test
    fun `a row with nothing remembered defers to the ordinary focus search`() {
        assertSame(FocusRequester.Default, RowFocus().entry())
    }

    @Test
    fun `a row returns to the item the cursor was last on`() {
        val row = RowFocus()
        row.onPresent("ep1")
        row.onPresent("ep2")
        row.onFocused("ep2")

        assertSame(row.requesterFor("ep2"), row.entry())
    }

    @Test
    fun `an item that has gone is never offered`() {
        val row = RowFocus()
        row.onPresent("ep1")
        row.onFocused("ep1")
        row.onGone("ep1")

        // This is the season switch: the rail emptied with ep1 still remembered.
        assertSame(FocusRequester.Default, row.entry())
    }

    @Test
    fun `focus remembered on an item never composed is not offered`() {
        val row = RowFocus()
        // onFocused without onPresent should not happen, but a row must not hand Compose
        // a requester attached to nothing if it does.
        row.onFocused("ghost")

        assertSame(FocusRequester.Default, row.entry())
    }

    @Test
    fun `an item that comes back is offered again`() {
        val row = RowFocus()
        row.onPresent("ep1")
        row.onFocused("ep1")
        val requester = row.requesterFor("ep1")

        // Scrolled out of the rail and back in again.
        row.onGone("ep1")
        row.onPresent("ep1")

        // The remembered key was cleared by the removal, so the row defers...
        assertSame(FocusRequester.Default, row.entry())
        // ...but the item keeps the same requester, because the modifier chain must not
        // change shape when an item regains focus.
        assertSame(requester, row.requesterFor("ep1"))
    }

    @Test
    fun `removing a different item leaves the remembered one alone`() {
        val row = RowFocus()
        row.onPresent("ep1")
        row.onPresent("ep2")
        row.onFocused("ep2")
        row.onGone("ep1")

        assertSame(row.requesterFor("ep2"), row.entry())
    }

    @Test
    fun `every key gets its own requester`() {
        val row = RowFocus()

        assertNotSame(row.requesterFor("a"), row.requesterFor("b"))
        assertSame(row.requesterFor("a"), row.requesterFor("a"))
    }
}
