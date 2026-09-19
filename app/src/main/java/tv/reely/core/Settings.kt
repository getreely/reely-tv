package tv.reely.core

import android.content.Context

/**
 * Viewing preferences. Not secrets, so these live in ordinary preferences rather than
 * the Keystore-backed store next door.
 */
class Settings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("reely-tv-settings", Context.MODE_PRIVATE)

    var subtitleScale: Float
        get() = prefs.getFloat(SUBTITLE_SCALE, DEFAULT_SCALE).coerceIn(MIN_SCALE, MAX_SCALE)
        set(value) = prefs.edit().putFloat(SUBTITLE_SCALE, value.coerceIn(MIN_SCALE, MAX_SCALE)).apply()

    var subtitleBackground: Boolean
        get() = prefs.getBoolean(SUBTITLE_BACKGROUND, false)
        set(value) = prefs.edit().putBoolean(SUBTITLE_BACKGROUND, value).apply()

    /**
     * Whether the guide plays a preview of the channel under the cursor. It costs one of
     * the provider's simultaneous connections while it runs, so it can be switched off.
     */
    var guidePreview: Boolean
        get() = prefs.getBoolean(GUIDE_PREVIEW, true)
        set(value) = prefs.edit().putBoolean(GUIDE_PREVIEW, value).apply()

    /** direct, auto or transcode. Auto only transcodes after direct play has failed. */
    var playbackMode: String
        get() = prefs.getString(PLAYBACK_MODE, MODE_AUTO) ?: MODE_AUTO
        set(value) = prefs.edit().putString(PLAYBACK_MODE, value).apply()

    /** Ceiling for a transcode, in kilobits. Zero asks the server for original quality. */
    var maxBitrateKbps: Int
        get() = prefs.getInt(MAX_BITRATE, 0)
        set(value) = prefs.edit().putInt(MAX_BITRATE, value).apply()

    /** Seconds before the next episode starts by itself. Zero switches that off. */
    var upNextSeconds: Int
        get() = prefs.getInt(UP_NEXT_SECONDS, DEFAULT_UP_NEXT).coerceIn(0, MAX_UP_NEXT)
        set(value) = prefs.edit().putInt(UP_NEXT_SECONDS, value.coerceIn(0, MAX_UP_NEXT)).apply()

    companion object {
        private const val SUBTITLE_SCALE = "subtitle.scale"
        private const val SUBTITLE_BACKGROUND = "subtitle.background"
        private const val UP_NEXT_SECONDS = "upnext.seconds"
        private const val GUIDE_PREVIEW = "guide.preview"
        private const val PLAYBACK_MODE = "playback.mode"
        private const val MAX_BITRATE = "playback.maxBitrate"

        // A multiplier on ExoPlayer's standard caption size, so 1.0 is "normal".
        const val DEFAULT_SCALE = 0.9f
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2.0f
        const val SCALE_STEP = 0.1f

        const val DEFAULT_UP_NEXT = 12
        const val MAX_UP_NEXT = 30
        const val UP_NEXT_STEP = 3

        const val MODE_DIRECT = "direct"
        const val MODE_AUTO = "auto"
        const val MODE_TRANSCODE = "transcode"

        /** Zero means original; the rest are the ceilings offered on the Status screen. */
        val BITRATE_CHOICES = listOf(0, 20_000, 12_000, 8_000, 4_000, 2_000)
    }
}
