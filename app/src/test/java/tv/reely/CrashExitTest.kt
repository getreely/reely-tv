package tv.reely

import android.app.ActivityManager.RunningAppProcessInfo
import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.core.CrashLog
import java.util.Date

/** The closures the app's own crash handler can't see, and which of them are worth a report. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrashExitTest {
    private val onScreen = RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    private val cached = RunningAppProcessInfo.IMPORTANCE_CACHED

    @Test fun `a decoder crash is reported`() {
        assertNotNull(CrashLog.exitKind(ApplicationExitInfo.REASON_CRASH_NATIVE, onScreen, 0))
    }

    @Test fun `running out of memory on screen is reported, in the background it isn't`() {
        assertNotNull(CrashLog.exitKind(ApplicationExitInfo.REASON_LOW_MEMORY, onScreen, 0))
        assertNull(CrashLog.exitKind(ApplicationExitInfo.REASON_LOW_MEMORY, cached, 0))
        assertNotNull(CrashLog.exitKind(ApplicationExitInfo.REASON_SIGNALED, onScreen, 9))
        assertNull(CrashLog.exitKind(ApplicationExitInfo.REASON_SIGNALED, cached, 9))
    }

    @Test fun `closing on purpose, updating, and Kotlin crashes are not`() {
        assertNull(CrashLog.exitKind(ApplicationExitInfo.REASON_USER_REQUESTED, onScreen, 0))
        assertNull(CrashLog.exitKind(ApplicationExitInfo.REASON_EXIT_SELF, onScreen, 0))
        assertNull(CrashLog.exitKind(ApplicationExitInfo.REASON_PACKAGE_UPDATED, onScreen, 0))
        // Already written down by the uncaught-exception handler, with its trace.
        assertNull(CrashLog.exitKind(ApplicationExitInfo.REASON_CRASH, onScreen, 0))
    }

    @Test fun `the report says what happened and how much memory was in use`() {
        val report = CrashLog.exitReport(
            kind = "It crashed in native code, most likely a video or audio decoder.",
            description = "crash",
            memoryKb = 512 * 1024,
            at = Date(0),
            trace = null,
        )
        val lines = report.lines()
        assertTrue(lines[0].startsWith("Reely TV "))
        assertEquals("It crashed in native code, most likely a video or audio decoder.", lines[3])
        assertTrue(report.contains("System's note: crash"))
        assertTrue(report.contains("Memory in use: 512 MB"))
    }
}
