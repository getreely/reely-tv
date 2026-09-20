package tv.reely.core

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

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
    private var retries = 0

    init {
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                when {
                    /*
                     * The stream carried on without us. A live playlist only advertises
                     * the last handful of segments, so a stall, a pause, or the stick
                     * being slow for a moment can leave the next segment we want already
                     * expired — and then nothing plays again until somebody rejoins at
                     * the live edge, which is what this does. It is the documented
                     * recovery for this error, not a workaround.
                     */
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                        rejoin()

                    // Anything else that can be retried. Worth a few goes before giving
                    // up: a provider hiccup should not end an evening's viewing.
                    RECOVERABLE.any { error.errorCode in it } && retries < MAX_RETRIES -> {
                        retries++
                        scope.launch {
                            delay(RETRY_DELAY_MS * retries)
                            rejoin()
                        }
                    }
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) retries = 0
            }
        })
    }

    /** Jumps back to the live edge and starts again from there. */
    fun rejoin() {
        if (loaded == null) return
        player.seekToDefaultPosition()
        player.prepare()
        player.playWhenReady = true
    }

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
        retries = 0
        player.stop()
        player.clearMediaItems()
    }

    fun release() {
        loaded = null
        scope.cancel()
        player.release()
    }

    private companion object {
        /**
         * Media3's miscellaneous and IO ranges: timeouts, dropped connections, refused
         * requests, and whatever a provider does when it is having a bad minute. Decode
         * failures live in 3000 and above and are a different matter — retrying a stream
         * this device cannot decode would only fail again, slower.
         */
        val RECOVERABLE = listOf(1_000..1_004, 2_000..2_999)
        const val MAX_RETRIES = 4
        const val RETRY_DELAY_MS = 1_500L
    }
}
