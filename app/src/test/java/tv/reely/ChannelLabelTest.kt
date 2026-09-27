package tv.reely

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.reely.ui.components.channelLabel

class ChannelLabelTest {
    @Test fun `a provider's region prefix is left off`() {
        assertEquals("BBC One", channelLabel("UK | BBC One"))
        assertEquals("CNN", channelLabel("US: CNN"))
        assertEquals("Das Erste", channelLabel("[DE] Das Erste"))
        assertEquals("Sky Sports Main Event", channelLabel("UK| Sky Sports Main Event"))
    }

    @Test fun `a name without one is left as it is`() {
        assertEquals("Channel 4", channelLabel("Channel 4"))
        assertEquals("BBC One HD", channelLabel("BBC One HD"))
        assertEquals("ITV1", channelLabel(" ITV1 "))
    }

    @Test fun `nothing is taken if nothing would be left`() {
        assertEquals("UK |", channelLabel("UK |"))
    }
}
