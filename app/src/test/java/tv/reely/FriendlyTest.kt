package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.reely.core.Friendly
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** What reaches the screen when something fails: plain words, no codes or exceptions. */
class FriendlyTest {
    @Test fun `the network's failures are put into plain words`() {
        assertEquals(Friendly.OFFLINE, Friendly.error(UnknownHostException("plex.local")))
        assertEquals(Friendly.SLOW, Friendly.error(SocketTimeoutException("timeout")))
        assertEquals(Friendly.OFFLINE, Friendly.error(IOException("wrapped", UnknownHostException("x"))))
    }

    @Test fun `this app's own messages are kept, without a code in them`() {
        assertEquals("Incorrect username or password.", Friendly.error(IllegalStateException("Incorrect username or password.")))
        assertEquals(
            "Couldn't load your Plex servers. Try again.",
            Friendly.error(IllegalArgumentException("Couldn't load your Plex servers (error 503). Try again.")),
        )
        assertEquals("Reely couldn't do that.", Friendly.message("Reely couldn't do that (404)."))
    }

    @Test fun `anything technical becomes a general sorry`() {
        assertEquals(Friendly.GENERIC, Friendly.error(RuntimeException("NullPointerException at line 3")))
        assertEquals(Friendly.GENERIC, Friendly.error(IllegalStateException("Expected BEGIN_OBJECT but was STRING")))
        assertEquals(Friendly.GENERIC, Friendly.error(IllegalStateException("")))
        assertEquals(Friendly.GENERIC, Friendly.message("Failed to connect to http://10.0.0.2:32400"))
    }
}
