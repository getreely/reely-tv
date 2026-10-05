package tv.reely

import android.bluetooth.BluetoothClass
import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.core.AudioOutputs
import tv.reely.core.BluetoothAudio
import tv.reely.core.PairedAudio

/** What the sound menu lists: one TV, Bluetooth by its own name, paired ones to connect. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AudioOutputsTest {
    private fun found(vararg devices: Triple<Int, String?, String?>) =
        devices.mapNotNull { (type, name, address) -> AudioOutputs.describe(type, name, address)?.let { it to Unit } }

    @Test fun `a television on HDMI is listed once, as the TV`() {
        val list = AudioOutputs.choices(
            found(
                Triple(AudioDeviceInfo.TYPE_HDMI, "SAMSUNG", null),
                Triple(AudioDeviceInfo.TYPE_HDMI_ARC, "Soundbar", null),
                Triple(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, null, null),
            )
        ).map { it.first }
        assertEquals(listOf("TV"), list.map { it.label })
    }

    @Test fun `bluetooth headphones go by their own name, after the TV`() {
        val list = AudioOutputs.choices(
            found(
                Triple(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Tom's AirPods Pro", "AA:BB:CC:DD:EE:FF"),
                Triple(AudioDeviceInfo.TYPE_HDMI, null, null),
            )
        ).map { it.first }
        assertEquals(listOf("TV", "Tom's AirPods Pro"), list.map { it.label })
        assertEquals("BLUETOOTH:AA:BB:CC:DD:EE:FF", list[1].key)
        assertEquals("AA:BB:CC:DD:EE:FF", list[1].address)
    }

    @Test fun `things that aren't somewhere to listen are left out`() {
        assertNull(AudioOutputs.describe(AudioDeviceInfo.TYPE_TELEPHONY, null))
        assertNull(AudioOutputs.describe(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, "Phone call"))
    }

    @Test fun `paired headphones not connected are listed to connect, once`() {
        val connected = found(
            Triple(AudioDeviceInfo.TYPE_HDMI, null, null),
            Triple(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Speaker", "11:22:33:44:55:66"),
        ).map { it.first }
        val paired = listOf(
            PairedAudio("Speaker", "11:22:33:44:55:66"),
            PairedAudio("Tom's AirPods Pro", "AA:BB:CC:DD:EE:FF"),
        )
        val list = AudioOutputs.withPaired(connected, paired)
        assertEquals(listOf("TV", "Speaker", "Tom's AirPods Pro"), list.map { it.label })
        assertTrue(list[1].connected)
        assertFalse("the AirPods need connecting", list[2].connected)
        // The same key as when they're connected, so the choice finds them either way.
        assertEquals("BLUETOOTH:AA:BB:CC:DD:EE:FF", list[2].key)
    }

    @Test fun `headphones and speakers count as audio, remotes and game pads don't`() {
        assertTrue(BluetoothAudio.isAudio(BluetoothClass.Device.Major.AUDIO_VIDEO, false))
        assertTrue(BluetoothAudio.isAudio(BluetoothClass.Device.Major.UNCATEGORIZED, true))
        assertFalse(BluetoothAudio.isAudio(BluetoothClass.Device.Major.PERIPHERAL, false))
    }

    @Test fun `choosing the TV lets go of Bluetooth, so the remote's volume reaches the TV`() {
        assertTrue(AudioOutputs.releasesBluetooth("TV"))
        assertTrue(AudioOutputs.releasesBluetooth("USB:Soundbar"))
        assertFalse("a Bluetooth choice keeps it", AudioOutputs.releasesBluetooth("BLUETOOTH:AA:BB:CC:DD:EE:FF"))
        assertFalse("Automatic leaves it to the system", AudioOutputs.releasesBluetooth(null))
    }

    @Test fun `headphones dying is the sound's output going, not the stream or the file failing`() {
        val p = androidx.media3.common.PlaybackException::class.java
        fun code(name: String) = p.getField(name).getInt(null)
        assertTrue(AudioOutputs.lostOutput(code("ERROR_CODE_AUDIO_TRACK_INIT_FAILED")))
        assertTrue(AudioOutputs.lostOutput(code("ERROR_CODE_AUDIO_TRACK_WRITE_FAILED")))
        assertFalse("a stream that won't load is the stream", AudioOutputs.lostOutput(code("ERROR_CODE_IO_NETWORK_CONNECTION_FAILED")))
        assertFalse("sound this device can't decode is the file", AudioOutputs.lostOutput(code("ERROR_CODE_DECODING_FAILED")))
        assertFalse(AudioOutputs.lostOutput(null))
    }
}
