package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.core.Reminder
import tv.reely.core.Reminders

/** Programmes to be told about: kept across restarts, and said in order. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RemindersTest {
    private val now = 1_710_000_000L
    private val later = Reminder(7, "Sports One", "The Final", now + 3_600)
    private val sooner = Reminder(9, "News", "Evening News", now + 600)
    private val justStarted = Reminder(3, "Films", "Matinee", now - 120)
    private val longGone = Reminder(4, "Films", "Breakfast", now - 3 * 3_600)

    @Test fun `kept as they were`() {
        val list = listOf(later, sooner)
        assertEquals(list, Reminders.decode(Reminders.encode(list)))
    }

    @Test fun `nothing kept, or something unreadable, is none`() {
        assertEquals(emptyList<Reminder>(), Reminders.decode(null))
        assertEquals(emptyList<Reminder>(), Reminders.decode("not json"))
    }

    @Test fun `soonest first, one just started still counts, long gone doesn't`() {
        val upcoming = Reminders.upcoming(listOf(later, longGone, sooner, justStarted), now)
        assertEquals(listOf(justStarted, sooner, later), upcoming)
    }

    @Test fun `one per programme`() {
        assertEquals(later.key, later.copy(title = "Renamed").key)
    }

    /** Back in the app hours later: a reminder that came due meanwhile isn't said then. */
    @Test fun `a reminder long past isn't said on coming back`() {
        org.junit.Assert.assertTrue(Reminders.stillWorthSaying(justStarted, now))
        org.junit.Assert.assertFalse(Reminders.stillWorthSaying(longGone, now))
    }
}
