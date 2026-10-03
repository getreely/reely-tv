package tv.reely.core

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Television or touch screen. One app for both: on a television it's the app it has
 * always been, remote and all; on a phone or tablet it's laid out for fingers instead.
 */
object FormFactor {

    fun isTelevision(context: Context): Boolean {
        val mode = context.getSystemService(UiModeManager::class.java)?.currentModeType
        if (mode == Configuration.UI_MODE_TYPE_TELEVISION) return true
        val packages = context.packageManager
        return isTelevision(
            leanback = packages.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
            fireTv = packages.hasSystemFeature(FIRE_TV),
            touchscreen = packages.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN),
        )
    }

    /**
     * Anything without a touch screen is driven by a remote or keys, so it gets the
     * television's screens: they're the ones that work without touch.
     */
    internal fun isTelevision(leanback: Boolean, fireTv: Boolean, touchscreen: Boolean): Boolean =
        leanback || fireTv || !touchscreen

    private const val FIRE_TV = "amazon.hardware.fire_tv"
}
