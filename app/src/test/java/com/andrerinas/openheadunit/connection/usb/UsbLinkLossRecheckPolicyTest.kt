package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.connection.CommManager.EndCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbLinkLossRecheckPolicyTest {
    private fun ask(
        lastWasUsb: Boolean = true, auto: Boolean = false, userExit: Boolean = false,
        endedBy: EndCause = EndCause.LINK_FAILED, ssl: Boolean = true, bus: Boolean = true,
    ) = UsbLinkLossRecheckPolicy.shouldRecheck(lastWasUsb, auto, userExit, endedBy, ssl, bus)

    @Test fun `a formed session lost to the link with the phone attached resumes with the setting off`() =
        assertTrue(ask())

    @Test fun `a non-USB last session never rechecks`() {
        assertFalse(ask(lastWasUsb = false))
        assertFalse(ask(lastWasUsb = false, auto = true))
    }

    @Test fun `with the setting off every other end stays quiet`() {
        assertFalse(ask(userExit = true))
        for (cause in EndCause.values()) {
            if (cause != EndCause.LINK_FAILED) assertFalse(cause.name, ask(endedBy = cause))
        }
        assertFalse(ask(ssl = false))
        assertFalse(ask(bus = false))
    }

    @Test fun `with the setting on the answer is yes whatever ended the session`() {
        for (cause in EndCause.values()) {
            assertTrue(cause.name, ask(auto = true, endedBy = cause, ssl = false, bus = false))
        }
        assertTrue(ask(auto = true, userExit = true))
    }
}
