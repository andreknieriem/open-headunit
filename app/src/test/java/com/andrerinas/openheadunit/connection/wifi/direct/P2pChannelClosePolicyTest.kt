package com.andrerinas.openheadunit.connection.wifi.direct

import com.andrerinas.openheadunit.connection.wifi.direct.P2pChannelClosePolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class P2pChannelClosePolicyTest {

    @Test
    fun `a kept group keeps the channel whatever else holds`() {
        for (removalOwed in listOf(false, true)) for (sdk in listOf(26, 27, 34)) {
            assertEquals(Decision.KEEP_OPEN, P2pChannelClosePolicy.decide(true, removalOwed, sdk))
        }
    }

    @Test
    fun `a removal in flight closes the channel after it`() {
        assertEquals(Decision.CLOSE_AFTER_REMOVAL, P2pChannelClosePolicy.decide(false, true, 28))
    }

    @Test
    fun `no removal closes the channel now`() {
        assertEquals(Decision.CLOSE_NOW, P2pChannelClosePolicy.decide(false, false, 27))
        assertEquals(Decision.CLOSE_NOW, P2pChannelClosePolicy.decide(false, false, 34))
    }

    @Test
    fun `below API 27 the channel cannot be closed`() {
        assertEquals(Decision.NOT_CLOSABLE, P2pChannelClosePolicy.decide(false, false, 26))
        assertEquals(Decision.NOT_CLOSABLE, P2pChannelClosePolicy.decide(false, true, 26))
    }
}
