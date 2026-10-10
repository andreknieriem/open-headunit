package com.andrerinas.openheadunit.connection.wifi.scan

import org.junit.Assert.*
import org.junit.Test

class FytBootStartPolicyTest {
    private fun eligible(sdk: Int = 29, unlocked: Boolean = true, enabled: Boolean = true,
                         remembered: Boolean = true, native: Boolean = true, running: Boolean = false,
                         pending: Boolean = false, boot: Int = 15, lastBoot: Int = 14) =
        FytBootStartPolicy.shouldStart(sdk, unlocked, enabled, remembered, native, running, pending, boot, lastBoot)

    @Test fun `reboot permits one attempt but reopening or a stopped Shizuku does not repeat it`() {
        assertTrue(eligible())
        assertFalse(eligible(lastBoot = 15))
        assertTrue(eligible(boot = 16, lastBoot = 15))
        assertFalse(eligible(boot = -1))
    }

    @Test fun `explicit close and disabled option prevent automatic reopening`() {
        assertFalse(eligible(remembered = false))
        assertFalse(eligible(enabled = false))
        assertFalse(eligible(pending = true))
    }

    @Test fun `only unlocked supported native FYT setup qualifies`() {
        for (sdk in 16..36) assertEquals(sdk in 26..29, eligible(sdk = sdk))
        assertFalse(eligible(unlocked = false))
        assertFalse(eligible(native = false))
        assertFalse(eligible(running = true))
    }
}
