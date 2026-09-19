package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.connection.wifi.P2pDiscoveryNetwork.Companion.InterfaceAddress
import org.junit.Assert.*
import org.junit.Test

class P2pDiscoveryNetworkTest {
    @Test fun hiddenInterfaceNameUsesActualOwnerIpOnlyOnUnambiguousP2pInterface() {
        val addresses = listOf(InterfaceAddress("wlan0", "192.168.49.1", 24), InterfaceAddress("p2p0", "10.42.0.1", 28))
        assertEquals("p2p0", P2pDiscoveryNetwork.select(null, "10.42.0.1", true, addresses)?.interfaceName)
        assertNull(P2pDiscoveryNetwork.select(null, "192.168.49.1", true, addresses))
    }
    @Test fun onlyTheActualP2pInterfaceIsSelectedAlongsideStationWifi() {
        val addresses = listOf(InterfaceAddress("wlan0", "192.168.1.20", 24),
            InterfaceAddress("p2p-wlan0-0", "10.42.0.1", 28))
        val selected = P2pDiscoveryNetwork.select("p2p-wlan0-0", "10.42.0.1", true, addresses)!!
        assertEquals("10.42.0.1", selected.localIpv4)
        assertEquals(28, selected.prefixLength)
        assertEquals((2..14).map { "10.42.0.$it" }, selected.hosts().toList())
    }

    @Test fun missingInterfaceOrAddressNeverFallsBackToStationOrFixedGateway() {
        val addresses = listOf(InterfaceAddress("wlan0", "192.168.49.1", 24))
        assertNull(P2pDiscoveryNetwork.select("p2p0", "192.168.49.1", true, addresses))
        assertNull(P2pDiscoveryNetwork.select(null, "192.168.49.1", true, addresses))
        assertNull(P2pDiscoveryNetwork.select("p2p0", null, true, addresses))
        assertNull(P2pDiscoveryNetwork.select("p2p0", "fe80::1", true, addresses))
    }

    @Test fun subnetExcludesOwnAddressNetworkAndBroadcast() {
        val hosts = P2pDiscoveryNetwork("p2p0", "172.20.7.130", 25, true).hosts().toSet()
        assertEquals(125, hosts.size)
        assertTrue("172.20.7.129" in hosts)
        assertTrue("172.20.7.254" in hosts)
        assertFalse("172.20.7.130" in hosts)
        assertFalse("172.20.7.128" in hosts)
        assertFalse("172.20.7.255" in hosts)
        assertFalse("172.20.7.1" in hosts)
    }

    @Test fun noClientNoIpv4OrUnsafePrefixProducesNoTargets() {
        assertTrue(P2pDiscoveryNetwork("p2p0", "10.42.0.1", 24, false).hosts().none())
        for (prefix in listOf(-1, 0, 15, 31, 32, 33))
            assertTrue(P2pDiscoveryNetwork("p2p0", "10.42.0.1", prefix, true).hosts().none())
        for (address in listOf("fe80::1", "invalid", "10.42.0.999"))
            assertTrue(P2pDiscoveryNetwork("p2p0", address, 24, true).hosts().none())
    }

    @Test fun changedGroupAddressIsNotReplacedByOldInterfaceAddress() {
        assertNull(P2pDiscoveryNetwork.select("p2p0", "10.42.0.1", true,
            listOf(InterfaceAddress("p2p0", "192.168.49.1", 24))))
    }
}
