package tv.reely.ui.components

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import java.util.Date

/** The time now, which moves on as each minute turns rather than on a timer of its own. */
@Composable
fun rememberNow(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            // To just past the next minute, so the clock turns over with the television's.
            delay(60_000 - now % 60_000 + 50)
            now = System.currentTimeMillis()
        }
    }
    return now
}

/** A time the way the television writes one: "9:41 PM", or "21:41" where it's set to 24-hour. */
fun clockTime(context: Context, millis: Long): String =
    android.text.format.DateFormat.getTimeFormat(context).format(Date(millis))
