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

    /** Seconds before the next episode starts by itself. Zero switches that off. */
    var upNextSeconds: Int
        get() = prefs.getInt(UP_NEXT_SECONDS, DEFAULT_UP_NEXT).coerceIn(0, MAX_UP_NEXT)
        set(value) = prefs.edit().putInt(UP_NEXT_SECONDS, value.coerceIn(0, MAX_UP_NEXT)).apply()

    companion object {
        private const val SUBTITLE_SCALE = "subtitle.scale"
        private const val SUBTITLE_BACKGROUND = "subtitle.background"
        private const val UP_NEXT_SECONDS = "upnext.seconds"

        // A multiplier on ExoPlayer's standard caption size, so 1.0 is "normal".
        const val DEFAULT_SCALE = 0.9f
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2.0f
        const val SCALE_STEP = 0.1f

        const val DEFAULT_UP_NEXT = 12
        const val MAX_UP_NEXT = 30
        const val UP_NEXT_STEP = 3
    }
}
