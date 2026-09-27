package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.reely.plex.PlexApi

/** Plex's home/users answer, as read into profiles. org.json is Android's, hence Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeUsersTest {
    private val home = """
        {"id":1,"name":"Barrett Home","users":[
          {"id":11,"uuid":"a1","title":"tbarrett","username":"tbarrett","thumb":"https://plex.tv/users/a1/avatar",
           "admin":true,"restricted":false,"protected":true,"hasPassword":true},
          {"id":12,"uuid":"b2","title":"Kids","username":"","thumb":"","admin":false,"restricted":true,
           "protected":false,"hasPassword":false},
          {"id":13,"uuid":"c3","title":"","username":"overseerr","admin":false,"restricted":false,
           "protected":false,"hasPassword":true},
          {"id":14,"title":"no uuid"}
        ]}
    """.trimIndent()

    @Test fun `reads everybody in the Home`() {
        val users = PlexApi.homeUsersFrom(home)
        assertEquals(listOf("tbarrett", "Kids", "overseerr"), users.map { it.title })
    }

    @Test fun `a PIN is the protected flag, not the account password`() {
        val (owner, kids, other) = PlexApi.homeUsersFrom(home)
        assertTrue(owner.protected)
        assertFalse(kids.protected)
        assertFalse(other.protected)
    }

    @Test fun `owner, managed, and pictures`() {
        val (owner, kids, _) = PlexApi.homeUsersFrom(home)
        assertTrue(owner.admin)
        assertTrue(kids.restricted)
        assertEquals("https://plex.tv/users/a1/avatar", owner.thumb)
        assertNull(kids.thumb)
    }

    @Test fun `a bare list reads too, and anything else is nobody`() {
        assertEquals(1, PlexApi.homeUsersFrom("""[{"uuid":"x","title":"Solo"}]""").size)
        assertTrue(PlexApi.homeUsersFrom("").isEmpty())
        assertTrue(PlexApi.homeUsersFrom("<html>").isEmpty())
    }
}
