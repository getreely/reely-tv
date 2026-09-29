package tv.reely

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.core.SavedChannel
import tv.reely.core.Settings

/** The saved set of channels comes back as it was put away, names and all. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SavedMultiviewTest {
    @Test fun `saved channels survive a restart in their places`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val set = listOf(SavedChannel(12, "NFL 02"), SavedChannel(11, "NFL 01"), SavedChannel(40, "Sky, Sports"))
        Settings(context).savedMultiview = set
        assertEquals(set, Settings(context).savedMultiview)
        Settings(context).savedMultiview = emptyList()
        assertEquals(emptyList<SavedChannel>(), Settings(context).savedMultiview)
    }
}
