package tv.reely.ui

import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.reely.ui.screens.INSTALLER_WAIT_MS
import tv.reely.ui.screens.InstallerLauncher
import java.io.File

/**
 * The installer is opened from the screen in front, after the permission to install is
 * checked, and once per request. It used to come up only on the second try.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class InstallerLauncherTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var status by mutableStateOf<UpdateStatus>(UpdateStatus.Idle)
    private val handled = mutableListOf<Int>()
    private val notes = mutableListOf<String>()

    // FileProvider remembers the first folder it served from, and each test has its own.
    @Before fun forgetProviderFolders() {
        val cache = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        cache.isAccessible = true
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    private fun downloaded(): File =
        File(compose.activity.cacheDir, "updates").apply { mkdirs() }
            .let { File(it, "reely-update.apk").apply { writeText("apk") } }

    private fun launcher(allowed: Boolean) {
        shadowOf(compose.activity.packageManager).setCanRequestPackageInstalls(allowed)
        compose.setContent {
            InstallerLauncher(
                status = status,
                onHandled = { request ->
                    handled += request
                    (status as? UpdateStatus.Handed)?.let { status = it.copy(handled = request) }
                },
                onRetry = {},
                onDidNotOpen = { notes += it },
            )
        }
    }

    private fun nextStarted(): Intent? = shadowOf(compose.activity).nextStartedActivity

    @Test fun `the installer opens from the screen in front`() {
        launcher(allowed = true)
        status = UpdateStatus.Handed(downloaded())
        compose.waitForIdle()
        val intent = nextStarted()!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("in the app's own task", 0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
        assertEquals(listOf(1), handled)
    }

    @Test fun `without permission to install, the setting for it comes first`() {
        launcher(allowed = false)
        status = UpdateStatus.Handed(downloaded())
        compose.waitForIdle()
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, nextStarted()!!.action)
    }

    @Test fun `a request already acted on isn't acted on again`() {
        launcher(allowed = true)
        status = UpdateStatus.Handed(downloaded(), request = 1, handled = 1)
        compose.waitForIdle()
        assertNull(nextStarted())
    }

    @Test fun `when the installer doesn't come up, it's asked for once more, then says so`() {
        launcher(allowed = true)
        status = UpdateStatus.Handed(downloaded())
        compose.waitForIdle()
        assertEquals(Intent.ACTION_VIEW, nextStarted()?.action)
        compose.mainClock.advanceTimeBy(INSTALLER_WAIT_MS + 100)
        compose.waitForIdle()
        assertEquals("asked again", Intent.ACTION_VIEW, nextStarted()?.action)
        compose.mainClock.advanceTimeBy(INSTALLER_WAIT_MS + 100)
        compose.waitForIdle()
        assertNull("and no more", nextStarted())
        assertTrue(notes.single().startsWith("The installer didn't open"))
    }
}
