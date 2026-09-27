package com.andrerinas.openheadunit.connection.wifi.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pBssidSourcePolicyTest {

    private val apAddress = "00:27:15:43:06:6A"
    private val p2pSetting = "AA:BB:CC:DD:EE:FF"
    private val groupAddress = "D2:65:D0:00:51:73"

    @Test
    fun `an address the hardware reported outranks both settings`() {
        val choice = P2pBssidSourcePolicy.resolve(groupAddress, p2pSetting, apAddress)
        assertEquals(groupAddress, choice.bssid)
        assertEquals(P2pBssidSourcePolicy.Source.DETECTED, choice.source)
        assertFalse(choice.fixedByUser)
    }

    @Test
    fun `the WiFi Direct setting answers only where no rung did`() {
        val choice = P2pBssidSourcePolicy.resolve(null, p2pSetting, apAddress)
        assertEquals(p2pSetting, choice.bssid)
        assertEquals(P2pBssidSourcePolicy.Source.P2P_SETTING, choice.source)
        assertTrue(choice.fixedByUser)
    }

    @Test
    fun `a masked reading is not an address, so a setting answers`() {
        assertEquals(
            P2pBssidSourcePolicy.Source.P2P_SETTING,
            P2pBssidSourcePolicy.resolve("02:00:00:00:00:00", p2pSetting, apAddress).source
        )
        assertEquals(
            P2pBssidSourcePolicy.Source.ACCESS_POINT_SETTING,
            P2pBssidSourcePolicy.resolve("00:00:00:00:00:00", "0", apAddress).source
        )
    }

    @Test
    fun `the access point's address comes last and is never called user-fixed`() {
        val choice = P2pBssidSourcePolicy.resolve(null, null, apAddress)
        assertEquals(apAddress, choice.bssid)
        assertEquals(P2pBssidSourcePolicy.Source.ACCESS_POINT_SETTING, choice.source)
        // A constant address on the wrong interface must not make the identity read stable: an
        // endpoint pinned to it strands the phone.
        assertFalse(choice.fixedByUser)
    }

    @Test
    fun `nothing anywhere yields no address rather than a placeholder`() {
        val choice = P2pBssidSourcePolicy.resolve(null, "0", null)
        assertEquals("", choice.bssid)
        assertEquals(P2pBssidSourcePolicy.Source.NONE, choice.source)
    }

    @Test
    fun `a hand-typed address is normalised to the spelling the phone is given`() {
        assertEquals(apAddress, P2pBssidSourcePolicy.resolve(null, null, "00-27-15-43-06-6a").bssid)
        assertEquals(groupAddress, P2pBssidSourcePolicy.resolve("d2:65:d0:00:51:73", null, null).bssid)
    }

    @Test
    fun `every label names its tier`() {
        assertEquals("detected", P2pBssidSourcePolicy.label(P2pBssidSourcePolicy.Source.DETECTED))
        assertEquals("WiFi Direct setting", P2pBssidSourcePolicy.label(P2pBssidSourcePolicy.Source.P2P_SETTING))
        assertEquals(
            "access point setting (stand-in)",
            P2pBssidSourcePolicy.label(P2pBssidSourcePolicy.Source.ACCESS_POINT_SETTING)
        )
    }

    @Test
    fun `a reading and the WiFi Direct setting can both be the group's own address`() {
        assertTrue(P2pBssidSourcePolicy.resolve(groupAddress, p2pSetting, apAddress).canBeGroupsOwn)
        assertTrue(P2pBssidSourcePolicy.resolve(null, p2pSetting, apAddress).canBeGroupsOwn)
    }

    @Test
    fun `the access point's stand-in cannot be the group's own address`() {
        // It is a well-formed address on another interface, so comparing it to the last group reads
        // as a move and remembering it judges every later group against the wrong one.
        val choice = P2pBssidSourcePolicy.resolve(null, "0", apAddress)
        assertEquals(P2pBssidSourcePolicy.Source.ACCESS_POINT_SETTING, choice.source)
        assertFalse(choice.canBeGroupsOwn)
    }

    @Test
    fun `nothing anywhere is not the group's own address either`() {
        assertFalse(P2pBssidSourcePolicy.resolve(null, "0", "0").canBeGroupsOwn)
    }
}
