package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.reely.plex.PlexApi
import tv.reely.plex.PlexApi.PlexConnection

/**
 * A home network whose router will not look up plex.direct names still has to reach the
 * server locally: the plain address follows each local one, ahead of the internet.
 */
class ConnectionOrderTest {
    private val local = PlexConnection("https://192-168-1-20.abc.plex.direct:32400", "192.168.1.20", 32400, local = true, relay = false)
    private val remote = PlexConnection("https://104-138-202-217.abc.plex.direct:32400", "104.138.202.217", 32400, local = false, relay = false)
    private val relay = PlexConnection("https://relay.plex.direct:8443", "relay", 8443, local = false, relay = true)

    @Test fun `local, then the plain local address, then the internet, then the relay`() {
        assertEquals(
            listOf(
                "https://192-168-1-20.abc.plex.direct:32400",
                "http://192.168.1.20:32400",
                "https://104-138-202-217.abc.plex.direct:32400",
                "https://relay.plex.direct:8443",
            ),
            PlexApi.connectionOrder(listOf(relay, remote, local)),
        )
    }

    @Test fun `a remote address gets no plain twin`() {
        assertEquals(listOf(remote.uri), PlexApi.connectionOrder(listOf(remote)))
    }
}
