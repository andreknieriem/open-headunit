package com.andrerinas.openheadunit.connection.projection

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbReadFaultPolicyTest {
    @Test fun `an armed fault takes a read that carried data`() =
        assertTrue(UsbReadFaultPolicy.shouldFault(armed = true, bytesRead = 512))

    @Test fun `an armed fault leaves an empty or failed read alone`() {
        assertFalse(UsbReadFaultPolicy.shouldFault(armed = true, bytesRead = 0))
        assertFalse(UsbReadFaultPolicy.shouldFault(armed = true, bytesRead = -1))
    }

    @Test fun `a disarmed fault never fires`() =
        assertFalse(UsbReadFaultPolicy.shouldFault(armed = false, bytesRead = 512))
}
