package tv.reely.ui.screens

import android.content.ActivityNotFoundException
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import tv.reely.core.Updater
import tv.reely.ui.UpdateStatus

/** How long the installer has to come up before it's asked for again. */
internal const val INSTALLER_WAIT_MS = 3_000L

/**
 * Opens the system installer on a downloaded update, from the screen that is showing.
 *
 * Before, the view model started the installer from the application, and on Fire TV it
 * often didn't come up the first time. This starts it from the activity in front. It
 * checks that this app is allowed to install first, and if it isn't, opens the setting
 * for it and carries on once that's switched on. It checks that the installer actually
 * came up, and asks again once if it didn't.
 *
 * Each request is acted on once, even if this leaves the screen and comes back.
 */
@Composable
fun InstallerLauncher(
    status: UpdateStatus,
    onHandled: (request: Int) -> Unit,
    onRetry: () -> Unit,
    onDidNotOpen: (String) -> Unit,
) {
    val handed = status as? UpdateStatus.Handed
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val handledNow by rememberUpdatedState(onHandled)
    val retry by rememberUpdatedState(onRetry)
    val didNotOpen by rememberUpdatedState(onDidNotOpen)
    // Gone to the system setting for installing from this app, to be back from.
    var awaitingPermission by remember { mutableStateOf(false) }

    LaunchedEffect(handed?.file, handed?.request) {
        if (handed == null || handed.request <= handed.handled) return@LaunchedEffect
        handledNow(handed.request)
        if (!Updater.mayInstall(context)) {
            awaitingPermission = runCatching { context.startActivity(Updater.permissionIntent(context)) }.isSuccess
            if (!awaitingPermission) didNotOpen("Allow installs from Reely in Settings, then press Install.")
            return@LaunchedEffect
        }
        repeat(2) {
            val left = CompletableDeferred<Unit>()
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) left.complete(Unit)
            }
            lifecycle.addObserver(observer)
            try {
                context.startActivity(Updater.installIntent(context, handed.file))
                if (withTimeoutOrNull(INSTALLER_WAIT_MS) { left.await() } != null) return@LaunchedEffect
            } catch (_: ActivityNotFoundException) {
                didNotOpen("This device has no installer Reely can open.")
                return@LaunchedEffect
            } catch (_: IllegalArgumentException) {
                // The file isn't where the provider serves from; downloading again fixes it.
                didNotOpen("The update couldn't be opened. Check for updates to download it again.")
                return@LaunchedEffect
            } finally {
                lifecycle.removeObserver(observer)
            }
        }
        didNotOpen("The installer didn't open. Press Install to try again.")
    }

    // Back from the setting. Only a return counts: an observer is told the current state
    // as it's added, and that is not somebody coming back.
    DisposableEffect(lifecycle) {
        var away = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> away = true
                Lifecycle.Event.ON_RESUME -> if (away && awaitingPermission) {
                    awaitingPermission = false
                    if (Updater.mayInstall(context)) retry()
                    else didNotOpen("Installs from Reely are still off. Turn them on in Settings, then press Install.")
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
}
