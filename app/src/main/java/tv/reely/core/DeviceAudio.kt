package tv.reely.core

import androidx.media3.decoder.ffmpeg.FfmpegLibrary
import android.content.Context
import android.media.MediaCodecList
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioCapabilities

/**
 * Which sound formats this device can play, asked of the device rather than assumed.
 *
 * Two ways to play a format, and either will do: a decoder on the device, or passing it
 * untouched over HDMI to a television or soundbar that decodes it. The second is the
 * one that changes — it depends on what is plugged in and, on a Fire TV, on the Surround
 * Sound setting — so it is asked each time, with the same question and the same audio
 * attributes the player's own output asks with. The decoders do not change and are
 * listed once.
 */
class DeviceAudio(context: Context) {

    private val appContext = context.applicationContext

    private val decoders: Set<String> by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filterNot { it.isEncoder }
                .flatMap { it.supportedTypes.asList() }
                .map { it.lowercase() }
                .toSet()
        }.getOrDefault(emptySet())
    }

    /**
     * Whether a format, by Plex's name for it, plays here at this many channels. A name
     * this does not recognise answers yes: guessing no would convert audio that may well
     * have played, and the check during playback still catches a silence.
     *
     * Three ways count: the device's decoders, passing it over HDMI, and the app's own
     * FFmpeg decoder. The last is what makes Dolby and DTS play on a device with neither
     * — without it the server had to convert them, and live channels, with no server to
     * ask, played silent.
     */
    fun canPlay(codec: String, channels: Int): Boolean {
        val mime = mimeFor(codec) ?: return true
        return playsNatively(mime, channels) || decodesInApp(mime)
    }

    /** Only the app's FFmpeg decoder plays it, which the stats panel says. */
    private fun onlyInApp(codec: String, channels: Int): Boolean {
        val mime = mimeFor(codec) ?: return false
        return !playsNatively(mime, channels) && decodesInApp(mime)
    }

    private fun decodesInApp(mime: String): Boolean =
        runCatching { FfmpegLibrary.supportsFormat(mime) }.getOrDefault(false)

    private fun playsNatively(mime: String, channels: Int): Boolean {
        if (mime == MimeTypes.AUDIO_AAC || mime in decoders) return true
        val format = Format.Builder()
            .setSampleMimeType(mime)
            .setChannelCount(channels.coerceAtLeast(2))
            .setSampleRate(48_000)
            .build()
        return runCatching {
            AudioCapabilities.getCapabilities(appContext, ATTRIBUTES, null)
                .isPassthroughPlaybackSupported(format, ATTRIBUTES)
        }.getOrDefault(false)
    }

    /** For the stats panel: the formats worth knowing about, and which of them play. */
    fun summary(): String = SUMMARISED
        .filter { (codec, _) -> canPlay(codec, 6) }
        .joinToString("  ·  ") { (codec, label) ->
            if (onlyInApp(codec, 6)) "$label (in app)" else label
        }

    private companion object {
        /** The same as the player's, so passthrough is asked about on the same terms. */
        val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val SUMMARISED = listOf(
            "aac" to "AAC",
            "ac3" to "Dolby Digital",
            "eac3" to "Dolby Digital Plus",
            "truehd" to "TrueHD",
            "dca" to "DTS",
        )

        fun mimeFor(codec: String): String? = when (codec.lowercase()) {
            "aac" -> MimeTypes.AUDIO_AAC
            "ac3" -> MimeTypes.AUDIO_AC3
            "eac3" -> MimeTypes.AUDIO_E_AC3
            "truehd" -> MimeTypes.AUDIO_TRUEHD
            "dca", "dts" -> MimeTypes.AUDIO_DTS
            "mp3" -> MimeTypes.AUDIO_MPEG
            "mp2" -> MimeTypes.AUDIO_MPEG_L2
            "flac" -> MimeTypes.AUDIO_FLAC
            "opus" -> MimeTypes.AUDIO_OPUS
            "vorbis" -> MimeTypes.AUDIO_VORBIS
            "alac" -> MimeTypes.AUDIO_ALAC
            else -> null
        }
    }
}
