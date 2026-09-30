package tv.reely.core

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Headphones or a speaker paired with the television, connected or not. */
data class PairedAudio(val name: String, val address: String)

/**
 * Bluetooth headphones and speakers the television already knows, and connecting one.
 *
 * Fire TV remembers AirPods and the like once paired but doesn't connect them by itself,
 * so without this, choosing them meant a trip to its Bluetooth settings every time.
 *
 * Android has no public way for an app to connect a paired device, only the system's own
 * settings. The call the settings use is reached here instead; it's allowed on Fire OS 7
 * (Android 9), and where a newer system refuses it, [connect] says so and the Bluetooth
 * settings are opened for it instead.
 */
object BluetoothAudio {

    enum class Result { CONNECTED, FAILED, UNSUPPORTED }

    /** Paired devices that play sound: headphones, earbuds, speakers. Not remotes or game pads. */
    @SuppressLint("MissingPermission") // Checked by allowed().
    fun paired(context: Context): List<PairedAudio> {
        val adapter = adapter(context) ?: return emptyList()
        if (!allowed(context) || !adapter.isEnabled) return emptyList()
        return runCatching {
            adapter.bondedDevices.orEmpty()
                .filter { isAudio(it.bluetoothClass?.majorDeviceClass, it.bluetoothClass?.hasService(BluetoothClass.Service.AUDIO)) }
                .map { PairedAudio(name = it.name?.takeIf(String::isNotBlank) ?: "Bluetooth device", address = it.address) }
                .sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    internal fun isAudio(majorClass: Int?, hasAudioService: Boolean?): Boolean =
        majorClass == BluetoothClass.Device.Major.AUDIO_VIDEO || hasAudioService == true

    /**
     * Connects a paired device for sound, and waits for it to turn up as somewhere sound
     * can go. [isConnected] is asked whether it has, each time an output comes or goes.
     */
    @SuppressLint("MissingPermission") // Checked by allowed().
    suspend fun connect(context: Context, address: String, isConnected: () -> Boolean): Result {
        if (isConnected()) return Result.CONNECTED
        val adapter = adapter(context) ?: return Result.UNSUPPORTED
        if (!allowed(context)) return Result.UNSUPPORTED
        if (!adapter.isEnabled) return Result.FAILED
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return Result.FAILED
        val a2dp = withTimeoutOrNull(PROXY_WAIT_MS) { proxy(context, adapter) } ?: return Result.UNSUPPORTED
        try {
            val started = runCatching {
                BluetoothA2dp::class.java.getMethod("connect", BluetoothDevice::class.java).invoke(a2dp, device) as? Boolean
            }.getOrNull()
            // Refused or not there to call: only the system's settings can do it.
            if (started != true) return Result.UNSUPPORTED
        } finally {
            runCatching { adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp) }
        }
        val arrived = withTimeoutOrNull(CONNECT_WAIT_MS) {
            suspendCancellableCoroutine { waiting ->
                var stop: () -> Unit = {}
                stop = AudioOutputs.watch(context) {
                    if (isConnected() && waiting.isActive) {
                        stop()
                        waiting.resume(Unit)
                    }
                }
                waiting.invokeOnCancellation { stop() }
            }
        }
        return if (arrived != null || isConnected()) Result.CONNECTED else Result.FAILED
    }

    private suspend fun proxy(context: Context, adapter: BluetoothAdapter): BluetoothA2dp? =
        suspendCancellableCoroutine { waiting ->
            val asked = runCatching {
                adapter.getProfileProxy(
                    context.applicationContext,
                    object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile?) {
                            if (waiting.isActive) waiting.resume(proxy as? BluetoothA2dp)
                        }

                        override fun onServiceDisconnected(profile: Int) = Unit
                    },
                    BluetoothProfile.A2DP,
                )
            }.getOrDefault(false)
            if (!asked && waiting.isActive) waiting.resume(null)
        }

    private fun adapter(context: Context): BluetoothAdapter? =
        runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()

    /** Android 12 and later ask before an app may see paired devices; before that it's granted with the app. */
    private fun allowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private const val PROXY_WAIT_MS = 3_000L

    /** Earbuds in a closed case never answer; long enough for ones that are only slow. */
    private const val CONNECT_WAIT_MS = 12_000L
}
