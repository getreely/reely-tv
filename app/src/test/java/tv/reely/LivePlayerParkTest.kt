package tv.reely

import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.core.LivePlayer

/** Out of sight, a channel lets go of its stream; back in sight, it picks it up again. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LivePlayerParkTest {
    @Test fun `parked, the stream is let go and the channel kept`() {
        val live = LivePlayer(ApplicationProvider.getApplicationContext())
        live.play("http://line.example.tv/live/1.ts")
        assertNotEquals(Player.STATE_IDLE, live.player.playbackState)

        live.park()
        assertEquals("hung up on the provider", Player.STATE_IDLE, live.player.playbackState)
        assertEquals("the channel is still there", 1, live.player.mediaItemCount)

        live.unpark()
        assertNotEquals(Player.STATE_IDLE, live.player.playbackState)
        assertTrue(live.player.playWhenReady)
        live.release()
    }

    @Test fun `nothing playing, nothing to park or pick up`() {
        val live = LivePlayer(ApplicationProvider.getApplicationContext())
        live.park()
        live.unpark()
        assertEquals(Player.STATE_IDLE, live.player.playbackState)
        assertEquals(0, live.player.mediaItemCount)
        live.release()
    }
}
