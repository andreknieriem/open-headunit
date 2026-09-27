package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.direct.StoredP2pIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointRetirementPolicyTest {

    private val advertised = StoredP2pIdentity("DIRECT-G9-POCOX3NFC", "abcDEF123456")
    private val renamed = StoredP2pIdentity("DIRECT-S1-POCOX3NFC", "abcDEF123456")
    private val rekeyed = StoredP2pIdentity("DIRECT-G9-POCOX3NFC", "zzzZZZ999999")

    private fun owed(
        advertised: StoredP2pIdentity? = this.advertised,
        keepIdentity: Boolean = true,
        kept: StoredP2pIdentity? = this.advertised,
        appNamesGroup: Boolean = true,
        rotationPending: Boolean = false,
    ) = EndpointRetirementPolicy.owed(advertised, keepIdentity, kept, appNamesGroup, rotationPending)

    @Test
    fun `nothing advertised owes nothing`() {
        assertFalse(owed(advertised = null, keepIdentity = false, kept = null))
        assertFalse(owed(advertised = null, appNamesGroup = false, keepIdentity = false))
    }

    @Test
    fun `the kept pair still being the advertised one owes nothing`() {
        // The toggle turned back on with the same pair: nothing moved.
        assertFalse(owed())
    }

    @Test
    fun `a renamed kept pair owes a retirement`() {
        assertTrue(owed(kept = renamed))
    }

    @Test
    fun `a password-only change owes a retirement`() {
        assertTrue(owed(kept = rekeyed))
    }

    @Test
    fun `the keep toggle off owes a retirement even with the pair still stored`() {
        assertTrue(owed(keepIdentity = false))
    }

    @Test
    fun `below Q only a pending purge moves the name`() {
        // The stored pair is never on the air there, so comparing to it would retire every session.
        assertFalse(owed(appNamesGroup = false, kept = renamed))
        assertTrue(owed(appNamesGroup = false, rotationPending = true))
        assertTrue(owed(appNamesGroup = false, keepIdentity = false))
    }

    @Test
    fun `the advertised group on the air is the one being retired`() {
        assertTrue(
            EndpointRetirementPolicy.isRetiring(
                advertised, keepIdentity = true, kept = renamed, appNamesGroup = true, rotationPending = false,
                liveName = advertised.networkName, livePassphrase = advertised.passphrase,
            )
        )
    }

    @Test
    fun `the new identity on the air is not being retired`() {
        assertFalse(
            EndpointRetirementPolicy.isRetiring(
                advertised, keepIdentity = true, kept = renamed, appNamesGroup = true, rotationPending = false,
                liveName = renamed.networkName, livePassphrase = renamed.passphrase,
            )
        )
    }

    @Test
    fun `a group matching the advertised name but not its password is not the one retired`() {
        assertFalse(
            EndpointRetirementPolicy.isRetiring(
                advertised, keepIdentity = false, kept = null, appNamesGroup = true, rotationPending = false,
                liveName = advertised.networkName, livePassphrase = rekeyed.passphrase,
            )
        )
    }

    @Test
    fun `the advertised group is not retiring while nothing is owed`() {
        assertFalse(
            EndpointRetirementPolicy.isRetiring(
                advertised, keepIdentity = true, kept = advertised, appNamesGroup = true, rotationPending = false,
                liveName = advertised.networkName, livePassphrase = advertised.passphrase,
            )
        )
    }

    @Test
    fun `an endpoint on WiFi Direct records the live pair`() {
        assertEquals(
            advertised,
            EndpointRetirementPolicy.recordsAdvertisement(
                NativeStrategy.WIFI_DIRECT, advertised.networkName, advertised.passphrase
            )
        )
    }

    @Test
    fun `an endpoint on the hotspot records nothing`() {
        assertNull(
            EndpointRetirementPolicy.recordsAdvertisement(
                NativeStrategy.HOTSPOT, advertised.networkName, advertised.passphrase
            )
        )
    }

    @Test
    fun `a pair no create could ask for again is not recorded`() {
        assertNull(EndpointRetirementPolicy.recordsAdvertisement(NativeStrategy.WIFI_DIRECT, "HomeWifi", "abcDEF123456"))
        assertNull(EndpointRetirementPolicy.recordsAdvertisement(NativeStrategy.WIFI_DIRECT, advertised.networkName, "short"))
    }
}
