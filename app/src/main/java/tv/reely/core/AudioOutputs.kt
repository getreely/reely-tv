package tv.reely.core

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer

/** Somewhere the sound can go: the television, a Bluetooth speaker or headphones, USB. */
data class AudioOutput(
    /** Kept in settings, to find the same one again next time it's connected. */
    val key: String,
    val label: String,
    val kind: Kind,
    /** Paired but not connected: choosing it connects it first. */
    val connected: Boolean = true,
    /** A Bluetooth device's address, for connecting it. */
    val address: String? = null,
) {
    enum class Kind { TV, BLUETOOTH, USB, HEADPHONES, SPEAKER }
}

/**
 * The sound outputs connected now, and sending a player's sound to one of them.
 *
 * Pairing something new is the system's job — Settings, Controllers & Bluetooth Devices
 * on a Fire TV. Once paired, headphones and speakers are listed here whether connected
 * or not, and connected from here (see BluetoothAudio). Automatic leaves it to the
 * system, which is what every app does unless told otherwise.
 */
object AudioOutputs {

    /** What is connected, one entry per thing a person would recognise, TV first. */
    fun connected(context: Context): List<Pair<AudioOutput, AudioDeviceInfo>> {
        val manager = context.getSystemService(AudioManager::class.java) ?: return emptyList()
        val devices = runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
            .getOrDefault(emptyList())
        return choices(
            devices.mapNotNull { device ->
                describe(device.type, device.productName?.toString(), addressOf(device))?.let { it to device }
            }
        )
    }

    /**
     * Everything the sound could go to: what's connected, then Bluetooth headphones and
     * speakers that are paired but not connected now, to connect from the menu.
     */
    fun available(context: Context): List<AudioOutput> =
        withPaired(connected(context).map { it.first }, BluetoothAudio.paired(context))

    internal fun withPaired(connected: List<AudioOutput>, paired: List<PairedAudio>): List<AudioOutput> {
        val here = connected.map { it.key }.toSet()
        val away = paired
            .map { AudioOutput(bluetoothKey(it.address), it.name, AudioOutput.Kind.BLUETOOTH, connected = false, address = it.address) }
            .filterNot { it.key in here }
        return connected + away
    }

    /**
     * Whether choosing [key] means letting go of Bluetooth headphones or speakers: the TV,
     * or anything else that isn't Bluetooth. Automatic leaves it all to the system.
     */
    fun releasesBluetooth(key: String?): Boolean =
        key != null && !key.startsWith("${AudioOutput.Kind.BLUETOOTH.name}:")

    /** Whether the output kept in settings is connected now. */
    fun isConnected(context: Context, key: String): Boolean = connected(context).any { it.first.key == key }

    private fun addressOf(device: AudioDeviceInfo): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address?.takeIf { it.isNotBlank() } else null

    private fun bluetoothKey(idOrName: String) = "${AudioOutput.Kind.BLUETOOTH.name}:$idOrName"

    /**
     * One entry per output: a television on HDMI shows up two or three times over (HDMI,
     * ARC, the same with a different name), and a stick's own "speaker" is only there for
     * the system's sake, not somewhere sound could be heard, when a television is.
     */
    internal fun <T> choices(found: List<Pair<AudioOutput, T>>): List<Pair<AudioOutput, T>> {
        val hasTv = found.any { it.first.kind == AudioOutput.Kind.TV }
        return found
            .filterNot { hasTv && it.first.kind == AudioOutput.Kind.SPEAKER }
            .distinctBy { it.first.key }
            .sortedBy { it.first.kind.ordinal }
    }

    /** What a device is called in the menu, by its type and the name it gives; null to leave out. */
    internal fun describe(type: Int, productName: String?, address: String? = null): AudioOutput? {
        val name = productName?.trim()?.takeIf { it.isNotEmpty() && !it.equals(Build.MODEL, ignoreCase = true) }
        val kind = when (type) {
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, TYPE_HDMI_EARC -> AudioOutput.Kind.TV
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, TYPE_BLE_HEADSET, TYPE_BLE_SPEAKER, TYPE_BLE_BROADCAST ->
                AudioOutput.Kind.BLUETOOTH
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                AudioOutput.Kind.USB
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> AudioOutput.Kind.HEADPHONES
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioOutput.Kind.SPEAKER
            else -> return null
        }
        val label = when (kind) {
            // The television is the television, whatever the HDMI link calls itself.
            AudioOutput.Kind.TV -> "TV"
            AudioOutput.Kind.BLUETOOTH -> name ?: "Bluetooth device"
            AudioOutput.Kind.USB -> name ?: "USB audio"
            AudioOutput.Kind.HEADPHONES -> "Headphones"
            AudioOutput.Kind.SPEAKER -> "Built-in speakers"
        }
        // A Bluetooth device by its address, the same whatever it's called, and how it's
        // found among the paired ones; by name on systems that don't give the address.
        val key = when (kind) {
            AudioOutput.Kind.BLUETOOTH -> bluetoothKey(address ?: name.orEmpty())
            AudioOutput.Kind.USB -> "${kind.name}:${name.orEmpty()}"
            else -> kind.name
        }
        return AudioOutput(
            key = key,
            label = label,
            kind = kind,
            address = address.takeIf { kind == AudioOutput.Kind.BLUETOOTH },
        )
    }

    /**
     * Sends a player's sound to the output kept in settings, when it's connected; to
     * wherever the system sends it otherwise. A speaker switched off falls back to the
     * system's choice until it's on again, and then is used again.
     */
    fun route(context: Context, player: ExoPlayer, key: String?) {
        val device = key?.let { wanted -> connected(context).firstOrNull { it.first.key == wanted }?.second }
        runCatching { player.setPreferredAudioDevice(device) }
    }

    /** Told whenever something is connected or disconnected. Returns the way to stop. */
    fun watch(context: Context, onChange: () -> Unit): () -> Unit {
        val manager = context.getSystemService(AudioManager::class.java) ?: return {}
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = onChange()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = onChange()
        }
        manager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        return { manager.unregisterAudioDeviceCallback(callback) }
    }

    /** The system's Bluetooth settings, for pairing something new; null where there are none. */
    fun pairingIntent(context: Context): Intent? =
        Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
            .takeIf { it.resolveActivity(context.packageManager) != null }

    // Newer than this app's lowest Android, so by number: absent on older devices anyway.
    private const val TYPE_BLE_HEADSET = 26
    private const val TYPE_BLE_SPEAKER = 27
    private const val TYPE_HDMI_EARC = 29
    private const val TYPE_BLE_BROADCAST = 30
}
