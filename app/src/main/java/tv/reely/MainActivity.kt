package tv.reely

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import tv.reely.core.dropsWhenUnused
import tv.reely.ui.ReelyApp
import tv.reely.ui.theme.Ink
import tv.reely.ui.theme.ReelyTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // The store build targets Android 15+, which draws behind transparent bars: their
        // icons light, on a dark app, whatever the phone's own theme. A television has no bars.
        if (BuildConfig.EDGE_TO_EDGE) {
            enableEdgeToEdge(
                statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
                navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            )
        }
        super.onCreate(savedInstanceState)
        tv.reely.core.CrashLog.install(this)
        // A television gets the app it has always had; a phone or tablet the touch one,
        // which turns with the device rather than staying sideways.
        val television = tv.reely.core.FormFactor.isTelevision(this)
        if (!television) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        setContent {
            ReelyTheme {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize().background(Ink)
                ) {
                    if (television) ReelyApp() else tv.reely.ui.touch.TouchApp()
                }
            }
        }
    }

    /*
     * An arrow key that nothing used ends here. Past this point Android's view system
     * would move focus out of the app and back in from the opposite edge — see
     * dropsWhenUnused. Whatever the app does with the key, including moving focus, has
     * already happened inside super; this only catches what is left over.
     *
     * The suppression is for lint, not a workaround: this is the framework's public
     * Activity method, and androidx marks only its own override of it, part-way down the
     * class chain, as internal to the library.
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        super.dispatchKeyEvent(event) || dropsWhenUnused(event.keyCode)
}
