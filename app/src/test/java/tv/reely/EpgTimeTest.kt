package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tv.reely.xtream.XmltvImporter
import tv.reely.xtream.XtreamProgramme

/**
 * XMLTV timestamps are parsed by hand, because a formatter allocating a few hundred
 * thousand times during an import is what makes a guide refresh take minutes on a stick.
 * Hand-rolled calendar arithmetic is exactly the sort of thing that is quietly wrong for
 * one month of the year, hence these.
 */
class EpgTimeTest {

    @Test
    fun `a plain timestamp is read as UTC`() {
        // 2024-01-15 14:30:00Z
        assertEquals(1_705_329_000L, XmltvImporter.parseTime("20240115143000"))
    }

    @Test
    fun `a positive offset is subtracted to reach UTC`() {
        // 14:30 in +0100 is 13:30Z.
        assertEquals(1_705_325_400L, XmltvImporter.parseTime("20240115143000 +0100"))
    }

    @Test
    fun `a negative offset is added to reach UTC`() {
        // 14:30 in -0500 is 19:30Z.
        assertEquals(1_705_347_000L, XmltvImporter.parseTime("20240115143000 -0500"))
    }

    @Test
    fun `an offset with minutes in it is handled`() {
        // India is +0530.
        assertEquals(1_705_309_200L, XmltvImporter.parseTime("20240115143000 +0530"))
    }

    @Test
    fun `an offset without the space between is still read`() {
        assertEquals(1_705_325_400L, XmltvImporter.parseTime("20240115143000+0100"))
    }

    @Test
    fun `the epoch itself`() {
        assertEquals(0L, XmltvImporter.parseTime("19700101000000"))
    }

    @Test
    fun `a leap day is a real day`() {
        // 2024-02-29 00:00:00Z. Out by 86400 if February is assumed to have 28 days.
        assertEquals(1_709_164_800L, XmltvImporter.parseTime("20240229000000"))
        // And the day after it is the first of March.
        assertEquals(1_709_251_200L, XmltvImporter.parseTime("20240301000000"))
    }

    @Test
    fun `a century that is not a leap year`() {
        // 1900 was not a leap year; 2000 was. The civil-calendar algorithm has to know.
        assertEquals(951_782_400L, XmltvImporter.parseTime("20000229000000"))
    }

    @Test
    fun `the end of a year rolls over`() {
        assertEquals(1_704_067_199L, XmltvImporter.parseTime("20231231235959"))
        assertEquals(1_704_067_200L, XmltvImporter.parseTime("20240101000000"))
    }

    @Test
    fun `every month starts where a calendar says it does`() {
        val firsts = listOf(
            "20240101000000" to 1_704_067_200L,
            "20240201000000" to 1_706_745_600L,
            "20240301000000" to 1_709_251_200L,
            "20240401000000" to 1_711_929_600L,
            "20240501000000" to 1_714_521_600L,
            "20240601000000" to 1_717_200_000L,
            "20240701000000" to 1_719_792_000L,
            "20240801000000" to 1_722_470_400L,
            "20240901000000" to 1_725_148_800L,
            "20241001000000" to 1_727_740_800L,
            "20241101000000" to 1_730_419_200L,
            "20241201000000" to 1_733_011_200L,
        )
        for ((raw, expected) in firsts) {
            assertEquals(raw, expected, XmltvImporter.parseTime(raw))
        }
    }

    @Test
    fun `rubbish is zero rather than a crash`() {
        // A bad entry in a dump of hundreds of thousands must not take the import down.
        assertEquals(0L, XmltvImporter.parseTime(null))
        assertEquals(0L, XmltvImporter.parseTime(""))
        assertEquals(0L, XmltvImporter.parseTime("2024"))
        assertEquals(0L, XmltvImporter.parseTime("not a timestamp"))
        assertEquals(0L, XmltvImporter.parseTime("2024xx15143000"))
        assertEquals(0L, XmltvImporter.parseTime("20241315000000"))
        assertEquals(0L, XmltvImporter.parseTime("20240100000000"))
    }

    @Test
    fun `a programme reports how far through it is`() {
        val programme = XtreamProgramme("Match of the Day", null, 1_000, 2_000)

        assertEquals(0f, programme.progressAt(1_000)!!, 0.001f)
        assertEquals(0.5f, programme.progressAt(1_500)!!, 0.001f)
        assertEquals(1f, programme.progressAt(2_000)!!, 0.001f)
    }

    @Test
    fun `a programme that is not on has no progress`() {
        val programme = XtreamProgramme("Match of the Day", null, 1_000, 2_000)

        assertNull(programme.progressAt(999))
        assertNull(programme.progressAt(2_001))
    }

    @Test
    fun `a programme with no duration has no progress`() {
        // Panels do emit these, and dividing by the span would give an infinity that ends
        // up as the width of a guide block.
        assertNull(XtreamProgramme("Filler", null, 1_000, 1_000).progressAt(1_000))
        assertNull(XtreamProgramme("Backwards", null, 2_000, 1_000).progressAt(1_500))
    }
}
