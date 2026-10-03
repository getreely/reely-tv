package tv.reely.ui

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder
import tv.reely.screens.Shots
import tv.reely.ui.screens.Panel
import tv.reely.ui.screens.TrackPanel
import tv.reely.ui.theme.ReelyTheme

/** The player's Audio menu lists where the sound can go, and choosing one keeps it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = Shots.QUALIFIERS)
class AudioOutputMenuTest {
    @get:Rule val compose = createComposeRule()

    @Test fun `the TV and bluetooth headphones are there to choose`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.getSystemService(AudioManager::class.java)).setOutputDevices(
            listOf(
                AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_HDMI).build(),
                AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP).build(),
            )
        )
        val player = ExoPlayer.Builder(context).build()
        val picked = mutableListOf<String?>()
        compose.setContent {
            ReelyTheme {
                TrackPanel(
                    panel = Panel.AUDIO,
                    player = player,
                    prefs = PlayerPrefs(),
                    tracksVersion = 0,
                    focusRequester = androidx.compose.runtime.remember { FocusRequester() },
                    onClose = {},
                    onNudgeScale = {},
                    onToggleBackground = {},
                    onPickOutput = { picked += it },
                )
            }
        }
        compose.onNodeWithText("PLAY SOUND ON").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("TV").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Bluetooth device").performScrollTo().performClick()
        compose.onNodeWithText("Automatic").performScrollTo().performClick()
        assertEquals(listOf("BLUETOOTH:", null), picked)
        player.release()
    }
}
