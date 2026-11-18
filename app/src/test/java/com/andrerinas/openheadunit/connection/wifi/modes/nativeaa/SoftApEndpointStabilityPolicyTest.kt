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

    private val groupNet = "WiFi Direct group"

    @Test
    fun `a WiFi Direct group whose name and BSSID repeat but whose IP moved is not stable`() {
        val prev = SoftApAddressRecord("192.168.119.244", digest, null, true)
        val v = SoftApEndpointStabilityPolicy.grade(STABLE, "192.168.85.190", psk, null, prev, network = groupNet)
        assertEquals(CHANGED, v.stability)
        assertTrue(WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, 5299, v.stability) is WppEndpointDecision.Withhold)
        assertFalse(WppEndpointPolicy.keepsNetwork(v.stability))
    }

    @Test
    fun `a group IP that moves on every create never grades stable`() {
        var rec: SoftApAddressRecord? = null
        for (ip in listOf("192.168.85.176", "192.168.52.47", "192.168.119.244", "192.168.85.190", "192.168.232.193")) {
            val v = SoftApEndpointStabilityPolicy.grade(STABLE, ip, psk, null, rec, network = groupNet)
            assertTrue(v.stability != STABLE)
            rec = v.remember
        }
    }

    @Test
    fun `a group at 192-168-49-1 across two creates grades stable`() {
        val first = SoftApEndpointStabilityPolicy.grade(STABLE, "192.168.49.1", psk, null, null, network = groupNet)
        assertEquals(UNPROVEN, first.stability)
        val second = SoftApEndpointStabilityPolicy.grade(STABLE, "192.168.49.1", psk, null, first.remember, network = groupNet)
        assertEquals(STABLE, second.stability)
        assertEquals(WppEndpointDecision.Advertise(5299), WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, 5299, second.stability))
    }

    @Test
    fun `a repeat seen only by reading a surviving group does not prove the address`() {
        val prev = SoftApAddressRecord("192.168.49.1", digest, null, false)
        val v = SoftApEndpointStabilityPolicy.grade(STABLE, "192.168.49.1", psk, null, prev, readNotCreated = true, network = groupNet)
        assertEquals(UNPROVEN, v.stability)
        assertFalse(v.remember!!.spannedBoot)
    }

    @Test
    fun `a group graded before the adopt decision counts as a read and writes nothing`() {
        val origin = SoftApEndpointStabilityPolicy.groupOrigin(wasRead = false, adoptDecisionPending = true)
        assertEquals(SoftApEndpointStabilityPolicy.GroupOrigin.UNDECIDED, origin)
        assertTrue(SoftApEndpointStabilityPolicy.gradesAsRead(origin))
        assertFalse(SoftApEndpointStabilityPolicy.recordsAddress(origin, "192.168.49.1"))
        val prev = SoftApAddressRecord("192.168.49.1", digest, null, false)
        val v = SoftApEndpointStabilityPolicy.grade(
            STABLE, "192.168.49.1", psk, null, prev,
            readNotCreated = SoftApEndpointStabilityPolicy.gradesAsRead(origin), network = groupNet,
        )
        assertEquals(UNPROVEN, v.stability)
        assertFalse(v.remember!!.spannedBoot)
    }

    @Test
    fun `a surviving group delivered before any bring-up chose is undecided and writes nothing`() {
        val origin = SoftApEndpointStabilityPolicy.groupOrigin(
            wasRead = false, adoptDecisionPending = SoftApEndpointStabilityPolicy.UNDECIDED_UNTIL_BRING_UP)
        assertEquals(SoftApEndpointStabilityPolicy.GroupOrigin.UNDECIDED, origin)
        assertFalse(SoftApEndpointStabilityPolicy.recordsAddress(origin, "192.168.49.1"))
        val prev = SoftApAddressRecord("192.168.49.1", digest, null, false)
        val v = SoftApEndpointStabilityPolicy.grade(
            STABLE, "192.168.49.1", psk, null, prev,
            readNotCreated = SoftApEndpointStabilityPolicy.gradesAsRead(origin), network = groupNet,
        )
        assertFalse(v.remember!!.spannedBoot)
    }

    @Test
    fun `only a decided group writes the record, and only a create proves it`() {
        val read = SoftApEndpointStabilityPolicy.groupOrigin(wasRead = true, adoptDecisionPending = false)
        val created = SoftApEndpointStabilityPolicy.groupOrigin(wasRead = false, adoptDecisionPending = false)
        assertEquals(SoftApEndpointStabilityPolicy.GroupOrigin.READ, read)
        assertEquals(SoftApEndpointStabilityPolicy.GroupOrigin.CREATED, created)
        assertTrue(SoftApEndpointStabilityPolicy.recordsAddress(read, "192.168.49.1"))
        assertTrue(SoftApEndpointStabilityPolicy.recordsAddress(created, "192.168.49.1"))
        assertTrue(SoftApEndpointStabilityPolicy.gradesAsRead(read))
        assertFalse(SoftApEndpointStabilityPolicy.gradesAsRead(created))
    }

    @Test
    fun `a decided delivery that read no address leaves the grade to the next delivery`() {
        val created = SoftApEndpointStabilityPolicy.groupOrigin(wasRead = false, adoptDecisionPending = false)
        assertFalse(SoftApEndpointStabilityPolicy.recordsAddress(created, null))
        assertFalse(SoftApEndpointStabilityPolicy.recordsAddress(created, ""))
        val v = SoftApEndpointStabilityPolicy.grade(STABLE, "", psk, null, null, network = groupNet)
        assertEquals(null, v.remember)
    }

    @Test
    fun `a read keeps an address that a create already proved`() {
        val prev = SoftApAddressRecord("192.168.49.1", digest, null, true)
        val v = SoftApEndpointStabilityPolicy.grade(STABLE, "192.168.49.1", psk, null, prev, readNotCreated = true, network = groupNet)
        assertEquals(STABLE, v.stability)
    }

    @Test
    fun `the reason names the network it graded`() {
        val prev = SoftApAddressRecord("192.168.119.244", digest, null, true)
        val v = SoftApEndpointStabilityPolicy.grade(STABLE, "192.168.85.190", psk, null, prev, network = groupNet)
        assertTrue(v.reason!!.contains(groupNet))
        assertFalse(v.reason!!.contains("access point"))
    }

    @Test
    fun `fields 5 and 6 agree on every grade`() {
        for (v in listOf(UNPROVEN, CHANGED, RENAMED, STABLE)) {
            assertEquals(
                WppEndpointPolicy.keepsNetwork(v),
                WppEndpointPolicy.decide(NativeStrategy.WIFI_DIRECT, 5299, v) is WppEndpointDecision.Advertise,
            )
        }
    }

    private val groupAd = SoftApEndpointStabilityPolicy.advertisement("DIRECT-ab-car", psk, "0a:11:22:33:44:55", "192.168.85.190")

    @Test
    fun `an endpoint advertised at one group IP and a create at another names the move`() {
        assertEquals(
            "address 192.168.85.190 -> 192.168.52.47",
            SoftApEndpointStabilityPolicy.groupAddressMoved(groupAd, "DIRECT-ab-car", psk, "192.168.52.47"),
        )
    }

    @Test
    fun `the same group IP owes nothing`() {
        assertNull(SoftApEndpointStabilityPolicy.groupAddressMoved(groupAd, "DIRECT-ab-car", psk, "192.168.85.190"))
        assertTrue(SoftApEndpointStabilityPolicy.sameNetwork(groupAd, "DIRECT-ab-car", psk))
    }

    @Test
    fun `a rotated name or passphrase is not a move`() {
        assertNull(SoftApEndpointStabilityPolicy.groupAddressMoved(groupAd, "DIRECT-cd-car", psk, "192.168.52.47"))
        assertNull(SoftApEndpointStabilityPolicy.groupAddressMoved(groupAd, "DIRECT-ab-car", "other-pass-9", "192.168.52.47"))
        assertFalse(SoftApEndpointStabilityPolicy.sameNetwork(groupAd, "DIRECT-cd-car", psk))
        assertFalse(SoftApEndpointStabilityPolicy.sameNetwork(groupAd, "DIRECT-ab-car", "other-pass-9"))
    }

    @Test
    fun `nothing advertised on a group owes nothing`() {
        assertNull(SoftApEndpointStabilityPolicy.groupAddressMoved(null, "DIRECT-ab-car", psk, "192.168.52.47"))
        assertFalse(SoftApEndpointStabilityPolicy.sameNetwork(null, "DIRECT-ab-car", psk))
    }

    // An access point whose BSSID is random per boot (Android 10's stock Wi-Fi HAL): same name, address and
    // password all along, the BSSID equal at every bring-up inside a boot and new after each restart.
    @Test
    fun `a BSSID that moved restarts the restart proof even when the address already spanned a boot`() {
        val earned = SoftApAddressRecord("192.168.43.1", digest, 7, spannedBoot = true, bssid = "9e:00:00:40:9e:a4")
        val v = SoftApEndpointStabilityPolicy.grade(CHANGED, "192.168.43.1", psk, 8, earned, bssid = "9e:00:00:ea:b0:10")
        assertEquals(CHANGED, v.stability)
        assertEquals(SoftApAddressRecord("192.168.43.1", digest, 8, spannedBoot = false, bssid = "9e:00:00:ea:b0:10"), v.remember)
    }

    @Test
    fun `the new BSSID repeating inside the same boot is not stable`() {
        val afterMove = SoftApAddressRecord("192.168.43.1", digest, 8, spannedBoot = false, bssid = "9e:00:00:ea:b0:10")
        // The name-and-BSSID verdict says STABLE here: same name, same BSSID as the last bring-up.
        val v = SoftApEndpointStabilityPolicy.grade(STABLE, "192.168.43.1", psk, 8, afterMove, bssid = "9e:00:00:ea:b0:10")
        assertEquals(UNPROVEN, v.stability)
        assertFalse(v.remember!!.spannedBoot)
    }

    @Test
    fun `a BSSID that holds across a restart is stable`() {
        val first = SoftApAddressRecord("192.168.43.1", digest, 7, spannedBoot = false, bssid = "00:11:22:33:44:55")
        val v = SoftApEndpointStabilityPolicy.grade(STABLE, "192.168.43.1", psk, 8, first, bssid = "00:11:22:33:44:55")
        assertEquals(STABLE, v.stability)
        assertTrue(v.remember!!.spannedBoot)
    }

    @Test
    fun `a record from before the BSSID was tracked adopts it and earns its proof again`() {
        val old = SoftApAddressRecord("192.168.43.1", digest, 7, spannedBoot = true)
        val first = SoftApEndpointStabilityPolicy.grade(STABLE, "192.168.43.1", psk, 8, old, bssid = "00:11:22:33:44:55")
        assertEquals(UNPROVEN, first.stability)
        assertEquals(SoftApAddressRecord("192.168.43.1", digest, 8, spannedBoot = false, bssid = "00:11:22:33:44:55"), first.remember)
        // Same boot again: still not proven. Next boot with the same BSSID: stable.
        assertEquals(UNPROVEN, SoftApEndpointStabilityPolicy.grade(STABLE, "192.168.43.1", psk, 8, first.remember, bssid = "00:11:22:33:44:55").stability)
        assertEquals(STABLE, SoftApEndpointStabilityPolicy.grade(STABLE, "192.168.43.1", psk, 9, first.remember, bssid = "00:11:22:33:44:55").stability)
    }

    @Test
    fun `BSSID case does not count as a move`() {
        val first = SoftApAddressRecord("192.168.43.1", digest, 7, spannedBoot = true, bssid = "9E:00:00:EA:B0:10")
        assertEquals(STABLE, SoftApEndpointStabilityPolicy.grade(STABLE, "192.168.43.1", psk, 8, first, bssid = "9e:00:00:ea:b0:10").stability)
    }
}
