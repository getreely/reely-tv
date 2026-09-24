package tv.reely

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
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
        super.onCreate(savedInstanceState)
        setContent {
            ReelyTheme {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize().background(Ink)
                ) {
                    ReelyApp()
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
