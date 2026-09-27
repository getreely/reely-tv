package tv.reely

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.core.CrashLog

/**
 * A crash is written down, passed on to whatever handled it before, apologised for once,
 * and readable until cleared.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrashLogTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val original = Thread.getDefaultUncaughtExceptionHandler()

    @After fun restore() {
        Thread.setDefaultUncaughtExceptionHandler(original)
        CrashLog.clear(context)
    }

    @Test fun `a crash is kept, said once, and cleared`() {
        var passedOn = false
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> passedOn = true }
        CrashLog.install(context)

        Thread.getDefaultUncaughtExceptionHandler()!!
            .uncaughtException(Thread.currentThread(), IllegalStateException("the guide had no channels"))

        assertTrue("the previous handler still runs", passedOn)
        val report = CrashLog.read(context)!!
        assertTrue(report.startsWith("Reely TV "))
        assertTrue(report.contains("IllegalStateException: the guide had no channels"))

        assertTrue(CrashLog.takeUnseen(context))
        assertFalse("only said once", CrashLog.takeUnseen(context))
        assertTrue("still readable after", CrashLog.read(context) != null)

        CrashLog.clear(context)
        assertNull(CrashLog.read(context))
    }

    @Test fun `installing twice records once`() {
        Thread.setDefaultUncaughtExceptionHandler { _, _ -> }
        CrashLog.install(context)
        val first = Thread.getDefaultUncaughtExceptionHandler()
        CrashLog.install(context)
        assertTrue(first === Thread.getDefaultUncaughtExceptionHandler())
    }
}
