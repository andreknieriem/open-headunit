package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothAddressSeedPolicyTest {

    @Test
    fun `a blank field takes the detected address`() {
        assertEquals("AA:BB:CC:DD:EE:FF", BluetoothAddressSeedPolicy.seed("", "AA:BB:CC:DD:EE:FF"))
        assertEquals("AA:BB:CC:DD:EE:FF", BluetoothAddressSeedPolicy.seed(null, "AA:BB:CC:DD:EE:FF"))
        assertEquals("AA:BB:CC:DD:EE:FF", BluetoothAddressSeedPolicy.seed("   ", "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `what the user typed is never overwritten`() {
        // A hand-entered address is usually there because the detected one was wrong.
        assertEquals("11:22:33:44:55:66",
            BluetoothAddressSeedPolicy.seed("11:22:33:44:55:66", "AA:BB:CC:DD:EE:FF"))
    }

    @Test
    fun `nothing detected leaves the field blank`() {
        assertEquals("", BluetoothAddressSeedPolicy.seed("", null))
        assertEquals("", BluetoothAddressSeedPolicy.seed(null, null))
    }

    @Test
    fun `the old hardcoded default is not a user's address`() {
        assertTrue(BluetoothAddressSeedPolicy.isLegacyDefault("40:EF:4C:A3:CB:A5"))
        assertTrue(BluetoothAddressSeedPolicy.isLegacyDefault(" 40:ef:4c:a3:cb:a5 "))
        assertFalse(BluetoothAddressSeedPolicy.isLegacyDefault("11:22:33:44:55:66"))
        assertFalse(BluetoothAddressSeedPolicy.isLegacyDefault(""))
        assertFalse(BluetoothAddressSeedPolicy.isLegacyDefault(null))
    }
}
