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

    /**
     * Which container live television is asked for: "ts" or "m3u8".
     *
     * HLS by default. A continuous MPEG-TS connection starts faster and sits closer to
     * live, but any interruption ends it and there is nothing to rejoin. HLS fetches
     * segments one at a time, so a blip costs a segment rather than the stream, and
     * falling behind has a defined cure the player can apply by itself.
     */
    var streamFormat: String
        get() = prefs.getString(STREAM_FORMAT, FORMAT_HLS) ?: FORMAT_HLS
        set(value) = prefs.edit().putString(STREAM_FORMAT, value).apply()

    /**
     * How several channels share the screen: "grid" gives them equal room, "focus" gives
     * the one you are listening to most of it and lines the rest up beside it.
     */
    var multiviewLayout: String
        get() = prefs.getString(MULTIVIEW_LAYOUT, LAYOUT_GRID) ?: LAYOUT_GRID
        set(value) = prefs.edit().putString(MULTIVIEW_LAYOUT, value).apply()

    /**
     * A show's theme tune under its page. Off unless asked for: something that makes
     * noise on its own should be chosen rather than discovered.
     */
    var themeMusic: Boolean
        get() = prefs.getBoolean(THEME_MUSIC, false)
        set(value) = prefs.edit().putBoolean(THEME_MUSIC, value).apply()

    /**
     * Themes are mastered loud, and this is a linear gain rather than anything the ear
     * reads as a percentage: a tenth is about where a title tune sits under a page
     * without competing with it. Tested on a television rather than guessed at.
     */
    var themeVolume: Float
        get() = prefs.getFloat(THEME_VOLUME, DEFAULT_THEME_VOLUME).coerceIn(MIN_THEME_VOLUME, 1f)
        set(value) = prefs.edit().putFloat(THEME_VOLUME, value.coerceIn(MIN_THEME_VOLUME, 1f)).apply()

    /**
     * Section keys the tab menu offers. Empty means every library is offered, which is
     * right until somebody with eight of them says otherwise.
     */
    var favouriteSections: Set<String>
        get() = prefs.getStringSet(FAVOURITE_SECTIONS, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(FAVOURITE_SECTIONS, value).apply()

    /** Where a newer build is published. Changeable, but there is a sensible default. */
    var updateUrl: String
        get() = prefs.getString(UPDATE_URL, DEFAULT_UPDATE_URL) ?: DEFAULT_UPDATE_URL
        set(value) = prefs.edit().putString(UPDATE_URL, value).apply()

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
        private const val UPDATE_URL = "update.url"
        private const val FAVOURITE_SECTIONS = "library.favourites"
        private const val STREAM_FORMAT = "live.format"
        private const val MULTIVIEW_LAYOUT = "live.multiview.layout"
        private const val THEME_MUSIC = "theme.music"
        // Renamed when the default came down from a level that turned out to be four
        // times too loud, so an install that had already nudged it starts again.
        private const val THEME_VOLUME = "theme.volume.quiet"

        const val LAYOUT_GRID = "grid"
        const val LAYOUT_FOCUS = "focus"

        const val FORMAT_TS = "ts"
        const val FORMAT_HLS = "m3u8"

        const val DEFAULT_THEME_VOLUME = 0.10f
        const val MIN_THEME_VOLUME = 0.05f
        const val THEME_VOLUME_STEP = 0.05f

        const val DEFAULT_UPDATE_URL = "http://192.168.68.80:5555/stuff/reely-tv.apk"

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
