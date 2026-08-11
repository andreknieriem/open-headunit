package com.andrerinas.headunitrevived.connection

import com.andrerinas.openheadunit.connection.wifi.modes.native.NativeAaHandshakeManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAaHandshakeManagerTest {

    @Test
    fun `single-radio device has no secondaries`() {
        val result = NativeAaHandshakeManager.filterSecondaryServiceNames(
            "bluetooth_manager",
            listOf("bluetooth_manager")
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `default primary plus one extra radio flags the extra as secondary`() {
        val result = NativeAaHandshakeManager.filterSecondaryServiceNames(
            "bluetooth_manager",
            listOf("bluetooth_manager", "bluetooth_manager_1")
        )
        assertEquals(listOf("bluetooth_manager_1"), result)
    }

    @Test
    fun `empty primary setting falls back to bluetooth_manager as primary`() {
        val result = NativeAaHandshakeManager.filterSecondaryServiceNames(
            "",
            listOf("bluetooth_manager", "bluetooth_manager_1")
        )
        assertEquals(listOf("bluetooth_manager_1"), result)
    }

    @Test
    fun `user-selected non-default primary makes the other radio the secondary`() {
        val result = NativeAaHandshakeManager.filterSecondaryServiceNames(
            "bluetooth_manager_1",
            listOf("bluetooth_manager", "bluetooth_manager_1")
        )
        assertEquals(listOf("bluetooth_manager"), result)
    }

    @Test
    fun `three radios with one selected as primary leaves the other two as secondaries`() {
        val result = NativeAaHandshakeManager.filterSecondaryServiceNames(
            "bluetooth_manager_1",
            listOf("bluetooth_manager", "bluetooth_manager_1", "bluetooth_manager_2")
        )
        assertEquals(listOf("bluetooth_manager", "bluetooth_manager_2"), result)
    }

    @Test
    fun `duplicate service names in the input collapse to one secondary entry`() {
        val result = NativeAaHandshakeManager.filterSecondaryServiceNames(
            "bluetooth_manager",
            listOf("bluetooth_manager", "bluetooth_manager_1", "bluetooth_manager_1")
        )
        assertEquals(listOf("bluetooth_manager_1"), result)
    }

    @Test
    fun `no services reported at all yields no secondaries`() {
        val result = NativeAaHandshakeManager.filterSecondaryServiceNames(
            "bluetooth_manager",
            emptyList()
        )
        assertTrue(result.isEmpty())
    }
}
