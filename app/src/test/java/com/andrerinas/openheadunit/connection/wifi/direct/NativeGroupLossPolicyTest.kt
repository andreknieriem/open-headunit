package com.andrerinas.openheadunit.connection.wifi.direct

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeGroupLossPolicyTest {

    @Test
    fun `a Native group this unit owned is dropped when the platform removes it`() {
        assertTrue(NativeGroupLossPolicy.invalidatesOnDisconnect(isNativeMode = true, wasGroupOwner = true))
    }

    @Test
    fun `another mode's group is left to that mode`() {
        assertFalse(NativeGroupLossPolicy.invalidatesOnDisconnect(isNativeMode = false, wasGroupOwner = true))
    }

    @Test
    fun `a disconnect from a group this unit did not own changes nothing`() {
        assertFalse(NativeGroupLossPolicy.invalidatesOnDisconnect(isNativeMode = true, wasGroupOwner = false))
    }

    @Test
    fun `neither condition, nothing to drop`() {
        assertFalse(NativeGroupLossPolicy.invalidatesOnDisconnect(isNativeMode = false, wasGroupOwner = false))
    }
}
