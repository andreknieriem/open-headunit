package com.andrerinas.openheadunit.connection.wifi.scan

import org.junit.Assert.*
import org.junit.Test

class FytConnectionStartTest {
    @Test fun `app launch alone cannot claim a start even if setup is eligible`() {
        assertNull(FytConnectionStart().claim(true))
    }

    @Test fun `handshake progress and service death cannot duplicate a connection attempt`() {
        val gate = FytConnectionStart()
        val service = Any()
        gate.session(service)
        val first = gate.claim(true)!!
        repeat(4) {
            gate.session(service)
            assertNull(gate.claim(true))
            assertTrue(first.valid)
        }
    }

    @Test fun `disconnect cancels old work and reconnect with the same service gets a new attempt`() {
        val gate = FytConnectionStart()
        val service = Any()
        gate.session(service)
        val old = gate.claim(true)!!
        gate.end()
        assertFalse(old.valid)
        assertNull(gate.claim(true))
        gate.session(service)
        val current = gate.claim(true)!!
        assertTrue(current.valid)
        assertFalse(old.valid)
    }

    @Test fun `switching owners invalidates a startup still waiting for the vendor service`() {
        val gate = FytConnectionStart()
        gate.session(Any())
        val old = gate.claim(true)!!
        gate.session(Any())
        assertFalse(old.valid)
        assertNotNull(gate.claim(true))
    }

    @Test fun `disabling cancels pending startup without repeated attempts in the same connection`() {
        val gate = FytConnectionStart()
        val service = Any()
        gate.session(service)
        val pending = gate.claim(true)!!
        gate.cancel()
        assertFalse(pending.valid)
        gate.session(service)
        assertNull(gate.claim(true))
    }

    @Test fun `initially ineligible connection can start once when option becomes enabled`() {
        val gate = FytConnectionStart()
        gate.session(Any())
        assertNull(gate.claim(false))
        assertNotNull(gate.claim(true))
        assertNull(gate.claim(true))
    }

    @Test fun `automatic startup respects platform consent service and recovery state`() {
        fun eligible(sdk: Int = 29, unlocked: Boolean = true, enabled: Boolean = true,
                     remembered: Boolean = true, running: Boolean = false, pending: Boolean = false) =
            FytConnectionStart.eligible(sdk, unlocked, enabled, remembered, running, pending)
        for (sdk in 16..36) assertEquals(sdk in 26..29, eligible(sdk = sdk))
        assertFalse(eligible(unlocked = false))
        assertFalse(eligible(enabled = false))
        assertFalse(eligible(remembered = false))
        assertFalse(eligible(running = true))
        assertFalse(eligible(pending = true))
    }
}
