package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.reely.plex.formatAirDate
import java.util.Locale

class AirDateTest {
    @Test fun `a Plex date reads as a date`() {
        assertEquals("Sep 16, 2008", formatAirDate("2008-09-16", Locale.US))
    }

    @Test fun `the first of the month stays on the first`() {
        assertEquals("Jan 1, 2020", formatAirDate("2020-01-01", Locale.US))
    }

    @Test fun `in the viewer's own order`() {
        assertEquals("16 Sept 2008", formatAirDate("2008-09-16", Locale.UK))
    }

    @Test fun `nothing for what isn't a whole date`() {
        assertNull(formatAirDate(null, Locale.US))
        assertNull(formatAirDate("", Locale.US))
        assertNull(formatAirDate("2008", Locale.US))
        assertNull(formatAirDate("2008-13-40", Locale.US))
    }
}
