package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.connection.wifi.modes.native.ApInterfaceCandidate
import com.andrerinas.openheadunit.connection.wifi.modes.native.SoftApNetworkPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftApNetworkPolicyTest {

    private fun iface(
        name: String,
        up: Boolean = true,
        loopback: Boolean = false,
        ipv4: String? = "192.168.43.1"
    ) = ApInterfaceCandidate(name, isLoopback = loopback, isUp = up, siteLocalIpv4 = ipv4)

    @Test
    fun `a dedicated access point interface wins over the station one`() {
        val picked = SoftApNetworkPolicy.pickApInterface(
            listOf(iface("wlan0", ipv4 = "192.168.1.55"), iface("ap0"))
        )

        assertEquals("ap0", picked?.name)
    }

    @Test
    fun `the vendor spellings are all recognised, in preference order`() {
        assertEquals("ap0", SoftApNetworkPolicy.pickApInterface(
            listOf(iface("wlan0"), iface("softap0"), iface("swlan0"), iface("ap0"))
        )?.name)
        assertEquals("swlan0", SoftApNetworkPolicy.pickApInterface(
            listOf(iface("wlan0"), iface("softap0"), iface("swlan0"))
        )?.name)
        assertEquals("softap0", SoftApNetworkPolicy.pickApInterface(
            listOf(iface("wlan0"), iface("softap0"))
        )?.name)
    }

    @Test
    fun `a plain wlan0 is used when it is all there is`() {
        // The OEM ZLink head unit serves Android Auto off exactly this.
        assertEquals("wlan0", SoftApNetworkPolicy.pickApInterface(listOf(iface("wlan0")))?.name)
    }

    @Test
    fun `an unfamiliar name is still usable, it just loses to a familiar one`() {
        assertEquals("eth_ap", SoftApNetworkPolicy.pickApInterface(listOf(iface("eth_ap")))?.name)
        assertEquals(
            "wlan0",
            SoftApNetworkPolicy.pickApInterface(listOf(iface("eth_ap"), iface("wlan0")))?.name
        )
    }

    @Test
    fun `the station interface is excluded, which is what separates it from the access point`() {
        // The shape measured on a real head unit: the soft AP runs as wlan2 holding
        // 192.168.246.32 while wlan0 is the station. They are indistinguishable by name, and the
        // AP's address is not a .1 gateway, so the station address is the only thing to go on.
        val picked = SoftApNetworkPolicy.pickApInterface(
            listOf(iface("wlan0", ipv4 = "192.168.1.55"), iface("wlan2", ipv4 = "192.168.246.32")),
            stationIpv4 = "192.168.1.55"
        )

        assertEquals("wlan2", picked?.name)
    }

    @Test
    fun `an access point is picked whatever address it holds`() {
        // Not every soft AP takes the .1 of its subnet - the tested unit does not.
        for (ip in listOf("192.168.43.1", "192.168.246.32", "10.0.0.7")) {
            assertEquals(ip, "wlan2", SoftApNetworkPolicy.pickApInterface(listOf(iface("wlan2", ipv4 = ip)))?.name)
        }
    }

    @Test
    fun `with no station to exclude the name preference decides`() {
        assertEquals(
            "ap0",
            SoftApNetworkPolicy.pickApInterface(
                listOf(iface("wlan0", ipv4 = "192.168.1.55"), iface("ap0", ipv4 = "192.168.5.9"))
            )?.name
        )
    }

    @Test
    fun `excluding the station cannot leave us with nothing to advertise`() {
        // Only the station is up: there is no access point, and saying so beats handing the phone
        // the address of a network it is already on.
        assertNull(
            SoftApNetworkPolicy.pickApInterface(
                listOf(iface("wlan0", ipv4 = "192.168.1.55")),
                stationIpv4 = "192.168.1.55"
            )
        )
    }

    @Test
    fun `isApHost answers for a single interface on the same rules`() {
        assertTrue(SoftApNetworkPolicy.isApHost(iface("wlan2", ipv4 = "192.168.246.32")))
        assertFalse(
            SoftApNetworkPolicy.isApHost(iface("wlan0", ipv4 = "192.168.1.55"), stationIpv4 = "192.168.1.55")
        )
        assertFalse(SoftApNetworkPolicy.isApHost(iface("tun0", ipv4 = "10.14.208.1")))
        assertFalse(SoftApNetworkPolicy.isApHost(iface("wlan2", up = false)))
    }

    @Test
    fun `a WiFi Direct group is never mistaken for our access point`() {
        // That is the other transport, with its own credential source.
        assertNull(SoftApNetworkPolicy.pickApInterface(listOf(iface("p2p-wlan0-7"))))
    }

    @Test
    fun `a MediaTek AP client is a station, not an access point`() {
        // apcli0 is the interface that joins someone else's network; ra0 is the access point. It
        // starts with "ap", so without the exclusion it would rank best of everything.
        assertEquals(
            "ra0",
            SoftApNetworkPolicy.pickApInterface(
                listOf(iface("apcli0", ipv4 = "192.168.1.55"), iface("ra0", ipv4 = "192.168.43.1"))
            )?.name
        )
        assertNull(SoftApNetworkPolicy.pickApInterface(listOf(iface("apcli0"))))
        assertFalse(SoftApNetworkPolicy.isApHost(iface("apcli0")))
    }

    @Test
    fun `a cellular bridge is not an access point`() {
        // The live shape from a Unisoc head unit: the real AP (wlan2) was down, and seth_lte0 was
        // up with a private address, so it was the only candidate and got advertised as the
        // network the phone should join.
        assertNull(
            SoftApNetworkPolicy.pickApInterface(listOf(iface("seth_lte0", ipv4 = "10.72.44.51")))
        )
        assertEquals(
            "wlan2",
            SoftApNetworkPolicy.pickApInterface(
                listOf(iface("seth_lte0", ipv4 = "10.72.44.51"), iface("wlan2", ipv4 = "192.168.246.32"))
            )?.name
        )
    }

    @Test
    fun `an interface named for the station role is excluded`() {
        assertNull(SoftApNetworkPolicy.pickApInterface(listOf(iface("sta0"))))
        assertEquals(
            "wlan0",
            SoftApNetworkPolicy.pickApInterface(listOf(iface("sta0"), iface("wlan0")))?.name
        )
    }

    @Test
    fun `an unrecognised access point still wins once the stations are excluded`() {
        // ra0/rai0 are deliberately not in the preferred list; they do not need to be.
        assertEquals("ra0", SoftApNetworkPolicy.pickApInterface(listOf(iface("ra0")))?.name)
        assertEquals("rai0", SoftApNetworkPolicy.pickApInterface(listOf(iface("rai0")))?.name)
    }

    @Test
    fun `eligible reports what the choice was made from`() {
        val candidates = listOf(
            iface("wlan0", ipv4 = "192.168.1.55"),
            iface("wlan2", ipv4 = "192.168.246.32"),
            iface("p2p-wlan0-3"),
            iface("lo", loopback = true)
        )

        // Two survivors means the name broke the tie, which is worth saying out loud.
        assertEquals(2, SoftApNetworkPolicy.eligible(candidates).size)
        assertEquals(1, SoftApNetworkPolicy.eligible(candidates, stationIpv4 = "192.168.1.55").size)
    }

    @Test
    fun `this app's own VPN interface is not an access point`() {
        // The github flavour parks a dummy VPN on the device. It is up and holds a site-local
        // address, so nothing but the name excludes it.
        assertNull(SoftApNetworkPolicy.pickApInterface(listOf(iface("tun0", ipv4 = "10.14.208.1"))))
        assertNull(SoftApNetworkPolicy.pickApInterface(listOf(iface("dummy0", ipv4 = "10.0.0.1"))))
        assertEquals(
            "wlan2",
            SoftApNetworkPolicy.pickApInterface(
                listOf(iface("tun0", ipv4 = "10.14.208.1"), iface("wlan2", ipv4 = "192.168.43.1"))
            )?.name
        )
    }

    @Test
    fun `loopback, down and address-less interfaces are all excluded`() {
        assertNull(SoftApNetworkPolicy.pickApInterface(listOf(iface("lo", loopback = true, ipv4 = "127.0.0.1"))))
        assertNull(SoftApNetworkPolicy.pickApInterface(listOf(iface("ap0", up = false))))
        assertNull(SoftApNetworkPolicy.pickApInterface(listOf(iface("ap0", ipv4 = null))))
    }

    @Test
    fun `nothing at all yields nothing`() {
        assertNull(SoftApNetworkPolicy.pickApInterface(emptyList()))
    }

    @Test
    fun `an excluded preferred interface does not shadow a usable unpreferred one`() {
        val picked = SoftApNetworkPolicy.pickApInterface(
            listOf(iface("ap0", up = false), iface("wlan0"))
        )

        assertEquals("wlan0", picked?.name)
    }
}
