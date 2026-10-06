package tv.reely.core

import androidx.compose.runtime.mutableStateOf

/**
 * Picture in picture on a phone or tablet: whether leaving the app now should shrink what's
 * playing into a window over other apps, and whether it is in one. The player hides its
 * controls while it is; a television never goes into one.
 */
object PictureInPicture {
    /** Something is playing on a phone or tablet, so going home takes it along. */
    @Volatile var wanted: Boolean = false

    /** In the small window now. */
    val active = mutableStateOf(false)
}
