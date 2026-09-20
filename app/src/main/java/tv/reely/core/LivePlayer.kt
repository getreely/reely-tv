package tv.reely.core

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer

/**
 * The one player live television uses, wherever it is being shown.
 *
 * The guide's preview and the full-screen player used to be separate players, so choosing
 * the channel you were already watching a preview of tore the stream down and dialled the
 * provider again — a wait, and briefly two of the account's connections for one channel.
 * They share this instead: going full screen rebinds the picture to another view and the
 * stream never notices.
 */
class LivePlayer(context: Context) {

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(
            // A small start buffer: channel-switch latency is what separates good from bad.
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(2_000, 30_000, 1_000, 2_000)
                .build()
        )
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            /* handleAudioFocus = */ true,
        )
        .build()
        .apply { setWakeMode(C.WAKE_MODE_NETWORK) }

    private var loaded: String? = null

    /** Starts a channel, or leaves the one already running alone. */
    fun play(url: String) {
        if (loaded == url && player.playbackState != Player.STATE_IDLE) {
            player.playWhenReady = true
            return
        }
        loaded = url
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    /** True when this is already the channel on screen, so nothing need be disturbed. */
    fun isShowing(url: String): Boolean =
        loaded == url && player.playbackState != Player.STATE_IDLE

    fun stop() {
        loaded = null
        player.stop()
        player.clearMediaItems()
    }

    fun release() {
        loaded = null
        player.release()
    }
}
