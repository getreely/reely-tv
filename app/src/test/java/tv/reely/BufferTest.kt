package tv.reely

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.reely.ui.screens.playbackBufferBytes

/** How much video the player may hold, from how much memory the device gives the app. */
class BufferTest {
    private val mb = 1024L * 1024

    @Test fun `a stick keeps the player's own figure`() {
        assertEquals(C.LENGTH_UNSET, playbackBufferBytes(192 * mb))
        assertEquals(C.LENGTH_UNSET, playbackBufferBytes(256 * mb))
    }

    @Test fun `room to spare goes partly to the buffer`() {
        assertEquals((128 * mb).toInt(), playbackBufferBytes(320 * mb))
        assertEquals((512 * mb * 4 / 10).toInt(), playbackBufferBytes(512 * mb))
    }

    @Test fun `and never more than a ceiling`() {
        assertEquals((384 * mb).toInt(), playbackBufferBytes(2048 * mb))
    }
}
