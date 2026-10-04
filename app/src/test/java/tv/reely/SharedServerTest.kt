package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.plex.PlexApi
import tv.reely.plex.PlexServer
import tv.reely.plex.unreachableMessage

/**
 * Somebody with no server of their own, only a friend's shared with them: it's the one
 * signed in to, and when it can't be reached they're told whose it is, not to check
 * "your Plex server".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedServerTest {

    private val sharedOnly = """
        [
          {"name": "Living Room", "provides": "server", "owned": false, "sourceTitle": "tim",
           "accessToken": "shared-token",
           "connections": [
             {"uri": "https://104-138-202-217.abc.plex.direct:32400", "address": "104.138.202.217", "port": 32400, "local": false, "relay": false},
             {"uri": "https://192-168-1-20.abc.plex.direct:32400", "address": "192.168.1.20", "port": 32400, "local": true, "relay": false}
           ]},
          {"name": "Kitchen Echo", "provides": "player", "connections": []}
        ]
    """.trimIndent()

    @Test fun `a server shared with the account is one to sign in to, with the token it was given`() {
        val servers = PlexApi.serversFrom(sharedOnly, "account-token")
        assertEquals(listOf("Living Room"), servers.map { it.name })
        assertEquals("shared-token", servers[0].accessToken)
        assertEquals(false, servers[0].owned)
        assertEquals("tim", servers[0].sharedBy)
        assertEquals(
            listOf(
                "https://192-168-1-20.abc.plex.direct:32400",
                "http://192.168.1.20:32400",
                "https://104-138-202-217.abc.plex.direct:32400",
            ),
            servers[0].connections,
        )
    }

    @Test fun `the account's own server comes before one shared with it`() {
        val both = """
            [
              {"name": "Friend's", "provides": "server", "owned": false, "sourceTitle": "tim", "accessToken": "s",
               "connections": [{"uri": "https://a.plex.direct:32400", "address": "1.2.3.4", "port": 32400, "local": false}]},
              {"name": "Mine", "provides": "server", "owned": true, "accessToken": "m",
               "connections": [{"uri": "https://b.plex.direct:32400", "address": "5.6.7.8", "port": 32400, "local": false}]}
            ]
        """.trimIndent()
        assertEquals(listOf("Mine", "Friend's"), PlexApi.serversFrom(both, "account-token").map { it.name })
    }

    @Test fun `when it can't be reached, it's named, and whose it is`() {
        val shared = PlexServer("Living Room", "s", listOf("https://a"), owned = false, sharedBy = "tim")
        assertEquals(
            "Found Living Room, shared by tim, but couldn't reach it. It may be off, or not reachable from here. Reely keeps trying.",
            unreachableMessage(listOf(shared)),
        )
        val mine = PlexServer("Den", "m", listOf("https://b"))
        assertEquals(
            "Found Den and Living Room, shared by tim, but couldn't reach any of them. Reely keeps trying.",
            unreachableMessage(listOf(mine, shared)),
        )
    }
}
