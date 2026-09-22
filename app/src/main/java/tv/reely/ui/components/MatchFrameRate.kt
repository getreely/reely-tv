package tv.reely.ui.components

import android.app.Activity
import android.os.Build
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import tv.reely.core.DisplayMode
import tv.reely.core.FrameRate

/**
 * Asks the screen to run at a rate that divides evenly into what is playing, and puts it
 * back on the way out.
 *
 * Putting it back matters as much as setting it: leaving the panel at twenty-four hertz
 * after a film would leave every menu in the app moving at twenty-four hertz too.
 */
@Composable
fun MatchFrameRate(player: ExoPlayer, activity: Activity?, enabled: Boolean) {
    DisposableEffect(player, activity, enabled) {
        if (activity == null || !enabled) return@DisposableEffect onDispose {}

        val window = activity.window
        val originalModeId = window.attributes.preferredDisplayModeId

        fun apply() {
            @Suppress("DEPRECATION")
            val display: Display? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display
                else window.windowManager.defaultDisplay
            display ?: return

            val contentFps = player.videoFormat?.frameRate ?: return
            val modes = display.supportedModes.map {
                DisplayMode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate)
            }
            val current = modes.firstOrNull { it.id == display.mode.modeId } ?: return
            val target = FrameRate.bestMode(contentFps, current, modes) ?: return

            window.attributes = window.attributes.apply { preferredDisplayModeId = target.id }
        }

        val listener = object : Player.Listener {
            // The frame rate is only known once the format has been read, which is after
            // the player has been handed the media rather than when it was built.
            override fun onTracksChanged(tracks: Tracks) = apply()
        }
        player.addListener(listener)
        apply()

        onDispose {
            player.removeListener(listener)
            window.attributes = window.attributes.apply { preferredDisplayModeId = originalModeId }
        }
    }
}
