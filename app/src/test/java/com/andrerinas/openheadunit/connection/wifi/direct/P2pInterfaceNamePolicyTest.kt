package com.andrerinas.openheadunit.connection.wifi.direct

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pInterfaceNamePolicyTest {

    @Test
    fun `every name the platform gives a P2P interface is accepted`() {
        assertTrue(P2pInterfaceNamePolicy.canCarryGroupAddress("p2p0"))
        assertTrue(P2pInterfaceNamePolicy.canCarryGroupAddress("p2p-dev-wlan0"))
        assertTrue(P2pInterfaceNamePolicy.canCarryGroupAddress("p2p-wlan0-0"))
        assertTrue(P2pInterfaceNamePolicy.canCarryGroupAddress("p2p-wlan0-12"))
        assertTrue(P2pInterfaceNamePolicy.canCarryGroupAddress("P2P-WLAN0-3"))
    }

    @Test
    fun `the station is refused, which is the address that reached a phone`() {
        // The MT50 answered a blind sysfs scan with wlan0's MAC while the group was up on
        // p2p-wlan0-12, and it went out as the group's BSSID and graded its identity.
        assertFalse(P2pInterfaceNamePolicy.canCarryGroupAddress("wlan0"))
        assertFalse(P2pInterfaceNamePolicy.canCarryGroupAddress("wlan1"))
    }

    @Test
    fun `a soft AP interface is refused, because it answers a different question`() {
        assertFalse(P2pInterfaceNamePolicy.canCarryGroupAddress("wlan2"))
        assertFalse(P2pInterfaceNamePolicy.canCarryGroupAddress("ap0"))
        assertFalse(P2pInterfaceNamePolicy.canCarryGroupAddress("swlan0"))
    }

    @Test
    fun `the rest of a head unit's interface list is refused`() {
        // 18 interfaces on one reporter's unit, 13 of them seth_lte stubs.
        assertFalse(P2pInterfaceNamePolicy.canCarryGroupAddress("seth_lte0"))
        assertFalse(P2pInterfaceNamePolicy.canCarryGroupAddress("rmnet0"))
        assertFalse(P2pInterfaceNamePolicy.canCarryGroupAddress("eth0"))
        assertFalse(P2pInterfaceNamePolicy.canCarryGroupAddress("lo"))
        assertFalse(P2pInterfaceNamePolicy.canCarryGroupAddress("dummy0"))
    }

    @Test
    fun `no name is not a name`() {
        assertFalse(P2pInterfaceNamePolicy.canCarryGroupAddress(null))
        assertFalse(P2pInterfaceNamePolicy.canCarryGroupAddress(""))
    }
}
