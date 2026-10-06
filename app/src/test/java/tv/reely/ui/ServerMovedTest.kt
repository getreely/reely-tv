package tv.reely.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A server found somewhere else while the app stays open: after the television had slept,
 * nothing would play until the app was restarted. What's on screen was read from the old
 * address and still names it, so the old address has to lead to the new one.
 */
class ServerMovedTest {

    private val section = tv.reely.plex.PlexSection(key = "1", title = "Movies", type = "movie")
    private val before = PlexState(
        token = "account",
        serverName = "Home",
        baseUrl = "https://old.plex.direct:32400",
        serverToken = "home-token",
        libraryChoices = listOf(
            LibraryChoice("Home", "https://old.plex.direct:32400", "home-token", section),
            LibraryChoice("Friend", "https://relay-1.plex.direct:8443", "friend-token", section),
        ),
    )

    @Test fun `the server in use moves, and an item read from the old address plays from the new`() {
        val after = before.afterMoves(mapOf("https://old.plex.direct:32400" to ("https://new.plex.direct:32400" to "home-token")))
        assertEquals("https://new.plex.direct:32400", after.baseUrl)
        assertEquals("https://new.plex.direct:32400", after.baseFor("https://old.plex.direct:32400"))
        assertEquals("home-token", after.tokenFor("https://old.plex.direct:32400"))
        assertEquals("https://new.plex.direct:32400", after.baseFor(null))
        assertEquals("https://new.plex.direct:32400", after.libraryChoices[0].baseUrl)
    }

    @Test fun `a friend's server given another relay address and token`() {
        val after = before.afterMoves(mapOf("https://relay-1.plex.direct:8443" to ("https://relay-2.plex.direct:8443" to "friend-token-2")))
        assertEquals("https://old.plex.direct:32400", after.baseUrl)
        assertEquals("https://relay-2.plex.direct:8443", after.baseFor("https://relay-1.plex.direct:8443"))
        assertEquals("friend-token-2", after.tokenFor("https://relay-1.plex.direct:8443"))
        assertEquals("friend-token-2", after.libraryChoices[1].token)
    }

    @Test fun `only a new token, the same address`() {
        val after = before.afterMoves(mapOf("https://old.plex.direct:32400" to ("https://old.plex.direct:32400" to "fresh-token")))
        assertEquals("fresh-token", after.serverToken)
        assertEquals("fresh-token", after.tokenFor(null))
        assertTrue(after.moved.isEmpty())
    }

    @Test fun `moving twice, the first address still finds it`() {
        val once = before.afterMoves(mapOf("https://old.plex.direct:32400" to ("https://b.plex.direct:32400" to "home-token")))
        val twice = once.afterMoves(mapOf("https://b.plex.direct:32400" to ("https://c.plex.direct:32400" to "home-token")))
        assertEquals("https://c.plex.direct:32400", twice.baseFor("https://old.plex.direct:32400"))
        assertEquals("https://c.plex.direct:32400", twice.baseFor("https://b.plex.direct:32400"))
        assertEquals("home-token", twice.tokenFor("https://old.plex.direct:32400"))
    }

    @Test fun `the same item, by its old address and its new, is the same server's`() {
        val after = before.afterMoves(mapOf("https://old.plex.direct:32400" to ("https://new.plex.direct:32400" to "home-token")))
        val state = ReelyState(plex = after)
        assertTrue(state.sameServer("https://old.plex.direct:32400", "https://new.plex.direct:32400"))
        assertTrue(state.sameServer("https://old.plex.direct:32400", null))
    }
}
