package tv.reely.core

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * A show's theme tune, played quietly under its page.
 *
 * Deliberately does not take audio focus. This is background to something somebody is
 * looking at, not a thing they chose to listen to, and a player that claimed focus would
 * pause whatever else the television was doing to play thirty seconds of a title sequence.
 */
class ThemePlayer(context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val player = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            /* handleAudioFocus = */ false,
        )
        .build()
        .apply { volume = 0f }

    private var loaded: String? = null
    private var fade: Job? = null

    /** Starts a theme, fading it up. Asking for the one already playing changes nothing. */
    fun play(url: String, volume: Float) {
        if (loaded == url) return
        loaded = url
        fade?.cancel()
        player.setMediaItem(MediaItem.fromUri(url))
        player.prepare()
        player.volume = 0f
        player.playWhenReady = true
        fade = scope.launch { ramp(to = volume.coerceIn(0f, 1f)) }
    }

    /** Leaving the page: fade out, so it does not stop mid-bar. */
    fun fadeOut() {
        if (loaded == null) return
        loaded = null
        fade?.cancel()
        fade = scope.launch {
            ramp(to = 0f)
            player.stop()
            player.clearMediaItems()
        }
    }

    /**
     * Cut it dead. For when something is about to start playing: a theme fading over the
     * opening of an episode is worse than never having played at all.
     */
    fun silence() {
        loaded = null
        fade?.cancel()
        player.volume = 0f
        player.stop()
        player.clearMediaItems()
    }

    fun release() {
        silence()
        scope.cancel()
        player.release()
    }

    private suspend fun ramp(to: Float) {
        val from = player.volume
        val steps = 12
        repeat(steps) { step ->
            player.volume = from + (to - from) * (step + 1) / steps
            delay(FADE_MS / steps)
        }
        player.volume = to
    }

    private companion object {
        const val FADE_MS = 600L
    }
}
