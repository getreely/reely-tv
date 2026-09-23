package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.reely.core.GuideRequest

class GuideRequestTest {

    @Test
    fun `browsing changes channel`() {
        val request = GuideRequest.browse(hasChannels = true)
        assertTrue(request.open)
        assertFalse(request.adds)
        assertNull(request.replaces)
    }

    @Test
    fun `adding puts the channel in a new tile`() {
        val request = GuideRequest.add(hasChannels = true)
        assertTrue(request.open)
        assertTrue(request.adds)
        assertNull(request.replaces)
        assertEquals("Add", request.verb)
    }

    @Test
    fun `replacing names the tile it is for`() {
        val request = GuideRequest.replace(slot = 2, hasChannels = true)
        assertTrue(request.adds)
        assertEquals(2, request.replaces)
        assertEquals("Replace with", request.verb)
    }

    /**
     * The fourth-channel bug. A guide raised to retune tile two, closed, and then raised
     * again from the spare cell used to keep pointing at tile two — so choosing a channel
     * replaced one that was already fine and never added the fourth.
     */
    @Test
    fun `opening to add forgets the tile a previous open was replacing`() {
        var request = GuideRequest.replace(slot = 2, hasChannels = true)
        request = GuideRequest.Closed
        request = GuideRequest.add(hasChannels = true)
        assertNull(request.replaces)
    }

    @Test
    fun `closing forgets everything`() {
        assertEquals(GuideRequest(), GuideRequest.Closed)
        assertFalse(GuideRequest.Closed.open)
        assertFalse(GuideRequest.Closed.adds)
        assertNull(GuideRequest.Closed.replaces)
    }

    /** Nothing to show means nothing to raise, whatever it was going to be for. */
    @Test
    fun `no channels means the guide stays shut`() {
        assertFalse(GuideRequest.browse(hasChannels = false).open)
        assertFalse(GuideRequest.add(hasChannels = false).open)
        assertFalse(GuideRequest.replace(slot = 1, hasChannels = false).open)
    }
}
