package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStability.CHANGED
import com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStability.RENAMED
import com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStability.STABLE
import com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStability.UNPROVEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftApEndpointStabilityPolicyTest {

    private val psk = "hunter2hunter2"
    private val digest = SoftApEndpointStabilityPolicy.passphraseDigest(psk)
    private fun record(ip: String = "192.168.4.159", boot: Int? = 7, spanned: Boolean = false) =
        SoftApAddressRecord(ip, digest, boot, spanned)

    @Test
    fun `a first reading is never stable and is remembered`() {
        val v = SoftApEndpointStabilityPolicy.grade(STABLE, "192.168.4.159", psk, 7, null)
        assertEquals(UNPROVEN, v.stability)
        assertEquals(record(), v.remember)
    }

    @Test
    fun `an address that moved is CHANGED and replaces the record`() {
        val v = SoftApEndpointStabilityPolicy.grade(STABLE, "192.168.77.20", psk, 8, record())
        assertEquals(CHANGED, v.stability)
        assertEquals(record(ip = "192.168.77.20", boot = 8), v.remember)
    }

    @Test
    fun `a password that changed is CHANGED`() {
        val v = SoftApEndpointStabilityPolicy.grade(STABLE, "192.168.4.159", "another-pass-1", 7, record())
        assertEquals(CHANGED, v.stability)
    }

    @Test
    fun `the same address in the same boot is not yet stable`() {
        val v = SoftApEndpointStabilityPolicy.grade(STABLE, "192.168.4.159", psk, 7, record())
        assertEquals(UNPROVEN, v.stability)
        assertFalse(v.remember!!.spannedBoot)
    }

    @Test
    fun `the same address after a restart is stable and stays so`() {
        val first = SoftApEndpointStabilityPolicy.grade(STABLE, "192.168.4.159", psk, 8, record())
        assertEquals(STABLE, first.stability)
        assertTrue(first.remember!!.spannedBoot)
        val later = SoftApEndpointStabilityPolicy.grade(STABLE, "192.168.4.159", psk, 8, first.remember)
        assertEquals(STABLE, later.stability)
    }

    @Test
    fun `without a boot count a repeat is enough`() {
        val v = SoftApEndpointStabilityPolicy.grade(STABLE, "192.168.43.1", psk, null, record(ip = "192.168.43.1", boot = null))
        assertEquals(STABLE, v.stability)
    }

    @Test
    fun `it never promotes past the name and BSSID verdict`() {
        val spanned = record(spanned = true)
        assertEquals(UNPROVEN, SoftApEndpointStabilityPolicy.grade(UNPROVEN, "192.168.4.159", psk, 9, spanned).stability)
        assertEquals(CHANGED, SoftApEndpointStabilityPolicy.grade(CHANGED, "192.168.4.159", psk, 9, spanned).stability)
        assertEquals(RENAMED, SoftApEndpointStabilityPolicy.grade(RENAMED, "192.168.9.9", psk, 9, spanned).stability)
    }

    @Test
    fun `an unreadable address withholds and remembers nothing`() {
        val v = SoftApEndpointStabilityPolicy.grade(STABLE, "", psk, 7, record(spanned = true))
        assertEquals(UNPROVEN, v.stability)
        assertNull(v.remember)
    }

    @Test
    fun `an advertised access point that moved names what moved`() {
        val ad = SoftApEndpointStabilityPolicy.advertisement("AndroidAP_7935", psk, "56:A1:4C:D3:A0:F2", "192.168.4.159")
        assertNull(SoftApEndpointStabilityPolicy.movedSinceAdvertised(ad, "AndroidAP_7935", psk, "56:a1:4c:d3:a0:f2", "192.168.4.159"))
        val moved = SoftApEndpointStabilityPolicy.movedSinceAdvertised(ad, "AndroidAP_7935", psk, "56:A1:4C:D3:A0:F2", "192.168.30.2")
        assertNotNull(moved)
        assertTrue(moved!!.contains("192.168.4.159 -> 192.168.30.2"))
        assertEquals("password", SoftApEndpointStabilityPolicy.movedSinceAdvertised(ad, "AndroidAP_7935", "other-pass-9", "56:A1:4C:D3:A0:F2", "192.168.4.159"))
    }

    @Test
    fun `nothing advertised means nothing owed`() {
        assertNull(SoftApEndpointStabilityPolicy.movedSinceAdvertised(null, "x", psk, "56:A1:4C:D3:A0:F2", "192.168.4.159"))
        assertNull(SoftApEndpointStabilityPolicy.advertisement("AndroidAP_7935", psk, "56:A1:4C:D3:A0:F2", ""))
    }
}
