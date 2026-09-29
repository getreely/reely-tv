package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.reely.ui.HomeRow
import tv.reely.ui.screens.interleave

/** Home's rows: ids that stay put, and Reely's rows mixing films and shows. */
class HomeRowsTest {
    @Test fun `films and shows take turns`() {
        assertEquals(listOf("f1", "s1", "f2", "s2", "f3"), interleave(listOf(listOf("f1", "f2", "f3"), listOf("s1", "s2"))))
        assertEquals(emptyList<String>(), interleave(emptyList<List<String>>()))
    }

    @Test fun `every row has its own id`() {
        assertEquals(HomeRow.entries.size, HomeRow.entries.map { it.id }.toSet().size)
    }
}
