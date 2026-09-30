package tv.reely.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import tv.reely.core.AudioOutput
import tv.reely.core.AudioOutputs
import tv.reely.core.BluetoothAudio

/**
 * Keeps these players' sound on the output chosen, [key], and again whenever something is
 * connected or disconnected: headphones switched on after the film started, say. When
 * the one chosen isn't there, the sound goes wherever the system sends it — the TV.
 */
@Composable
fun FollowAudioOutput(players: Collection<ExoPlayer>, key: String?) {
    val context = LocalContext.current
    DisposableEffect(players.toList(), key) {
        fun route() = players.forEach { AudioOutputs.route(context, it, key) }
        route()
        val stop = AudioOutputs.watch(context) { route() }
        onDispose { stop() }
    }
}

/**
 * On the way into the player, the headphones chosen last time connected if they're
 * paired but not connected, as they would be after the television was off. If they
 * don't answer — in their case, out of range — the TV carries on as it was.
 */
@Composable
fun ConnectChosenOutput(key: String?) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val wanted = key ?: return@LaunchedEffect
        if (AudioOutputs.isConnected(context, wanted)) return@LaunchedEffect
        val paired = AudioOutputs.available(context).firstOrNull { it.key == wanted && !it.connected }
        val address = paired?.address ?: return@LaunchedEffect
        BluetoothAudio.connect(context, address) { AudioOutputs.isConnected(context, wanted) }
    }
}

/** The outputs to choose from, and choosing one: connecting it first when it needs it. */
@Stable
class OutputPicker internal constructor(
    private val context: android.content.Context,
    private val scope: CoroutineScope,
    private val onPick: (String?) -> Unit,
) {
    var outputs by mutableStateOf(AudioOutputs.available(context))
        internal set

    /** The one being connected now, and the last one that wouldn't. */
    var connecting by mutableStateOf<String?>(null)
        private set
    var failed by mutableStateOf<String?>(null)
        private set

    internal fun refresh() {
        outputs = AudioOutputs.available(context)
    }

    fun pick(output: AudioOutput?) {
        // Kept whether it connects or not: next time it's about, it's used.
        onPick(output?.key)
        failed = null
        if (output == null || output.connected) return
        val address = output.address ?: return
        connecting = output.key
        scope.launch {
            val result = BluetoothAudio.connect(context, address) { AudioOutputs.isConnected(context, output.key) }
            connecting = null
            refresh()
            when (result) {
                BluetoothAudio.Result.CONNECTED -> Unit
                BluetoothAudio.Result.FAILED -> failed = output.key
                // This system only lets its own settings connect it.
                BluetoothAudio.Result.UNSUPPORTED -> {
                    failed = output.key
                    AudioOutputs.pairingIntent(context)?.let { runCatching { context.startActivity(it) } }
                }
            }
        }
    }
}

@Composable
fun rememberOutputPicker(onPick: (String?) -> Unit): OutputPicker {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest by androidx.compose.runtime.rememberUpdatedState(onPick)
    val picker = remember { OutputPicker(context, scope) { latest(it) } }
    DisposableEffect(picker) {
        val stop = AudioOutputs.watch(context) { picker.refresh() }
        onDispose { stop() }
    }
    return picker
}

/** The outputs connected or paired, kept up to date while on screen. */
@Composable
fun rememberAudioOutputs(): List<AudioOutput> {
    val context = LocalContext.current
    var outputs by remember { mutableStateOf(AudioOutputs.available(context)) }
    DisposableEffect(Unit) {
        val stop = AudioOutputs.watch(context) { outputs = AudioOutputs.available(context) }
        onDispose { stop() }
    }
    return outputs
}
