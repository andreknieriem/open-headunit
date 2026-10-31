package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.connection.CommManager.EndCause
import org.junit.Assert.assertEquals
import org.junit.Test

class EndCausePolicyTest {
    @Test fun `a transport quit names the user first, then the phone, then a failure`() {
        assertEquals(EndCause.USER, EndCausePolicy.ofTransportQuit(isClean = false, wasUserExit = true))
        assertEquals(EndCause.USER, EndCausePolicy.ofTransportQuit(isClean = true, wasUserExit = true))
        assertEquals(EndCause.PHONE, EndCausePolicy.ofTransportQuit(isClean = true, wasUserExit = false))
        assertEquals(EndCause.LINK_FAILED, EndCausePolicy.ofTransportQuit(isClean = false, wasUserExit = false))
    }

    @Test fun `an explicit disconnect keeps the caller's cause`() {
        assertEquals(EndCause.DETACHED, EndCausePolicy.ofDisconnect(isUserExit = false, explicit = EndCause.DETACHED))
        assertEquals(EndCause.DETACHED, EndCausePolicy.ofDisconnect(isUserExit = true, explicit = EndCause.DETACHED))
    }

    @Test fun `an unnamed disconnect is the user's or the app's by its flag`() {
        assertEquals(EndCause.USER, EndCausePolicy.ofDisconnect(isUserExit = true, explicit = null))
        assertEquals(EndCause.APP, EndCausePolicy.ofDisconnect(isUserExit = false, explicit = null))
    }
}
