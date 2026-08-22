package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCredentialsPreflightPolicyTest {

    private val realMac = "AA:BB:CC:DD:EE:FF"
    private val readable = SoftApCredentials("HeadUnitAP", "swordfish")

    private fun probe(
        manualSsid: String = "",
        manualPassword: String = "",
        staticBssid: String? = "0",
        systemConfig: SoftApCredentials? = readable,
        probedBssid: String? = realMac,
        bssidProbeConclusive: Boolean = true,
        locationServicesEnabled: Boolean? = true
    ) = PreflightProbe(
        manualSsid, manualPassword, staticBssid, systemConfig,
        probedBssid, bssidProbeConclusive, locationServicesEnabled
    )

    private fun hotspot(probe: PreflightProbe) =
        NativeCredentialsPreflightPolicy.evaluate(NativeTransport.HOTSPOT, probe)

    private fun wifiDirect(probe: PreflightProbe) =
        NativeCredentialsPreflightPolicy.evaluate(NativeTransport.WIFI_DIRECT, probe)

    // --- the case the whole feature exists for -------------------------------------------------

    @Test
    fun `a device that will not name its own access point is asked for both name and password`() {
        // getSoftApConfiguration() refused. This is the reported failure behind most "hotspot mode
        // is dead" claims, and both halves are unobtainable together because the same call carries
        // them.
        val report = hotspot(probe(systemConfig = null))
        assertEquals(
            listOf(CredentialField.HOTSPOT_NAME, CredentialField.HOTSPOT_PASSWORD),
            report.mustEnter
        )
        assertTrue(report.hasFindings)
    }

    @Test
    fun `a device that names its own access point is asked for nothing`() {
        val report = hotspot(probe())
        assertEquals(emptyList<CredentialField>(), report.mustEnter)
        assertFalse(report.hasFindings)
        assertEquals(FieldVerdict.AVAILABLE_FROM_DEVICE, report.verdicts[CredentialField.HOTSPOT_NAME])
    }

    @Test
    fun `overrides already set are not asked for again`() {
        val report = hotspot(probe(
            manualSsid = "MyAP",
            manualPassword = "hunter2",
            staticBssid = realMac,
            systemConfig = null,
            probedBssid = null
        ))
        assertEquals(emptyList<CredentialField>(), report.mustEnter)
        report.verdicts.values.forEach { assertEquals(FieldVerdict.SUPPLIED_BY_USER, it) }
    }

    // --- the half-finished override -------------------------------------------------------------

    @Test
    fun `naming the network without a password still needs the password`() {
        // SoftApCredentialsPolicy.resolve stops reading the system configuration the moment a manual
        // name exists, so this sends an open network rather than the device's own passphrase. The
        // one case where half an override is worse than none, so it is caught here rather than left
        // to a warning at connect time.
        val report = hotspot(probe(manualSsid = "MyAP", systemConfig = readable))
        assertEquals(listOf(CredentialField.HOTSPOT_PASSWORD), report.mustEnter)
        assertEquals(FieldVerdict.SUPPLIED_BY_USER, report.verdicts[CredentialField.HOTSPOT_NAME])
    }

    @Test
    fun `a configuration that is present but blank supplies neither half`() {
        // HotspotConfigReader's pre-Q branch returns the fields verbatim with no emptiness check, so
        // a blanked configuration arrives as a non-null pair of empty strings. Testing the value
        // rather than the nullness is what keeps this from asking only for the name — which would
        // then stop SoftApCredentialsPolicy.resolve consulting the device and send an open network.
        val report = hotspot(probe(systemConfig = SoftApCredentials("", "")))
        assertEquals(
            listOf(CredentialField.HOTSPOT_NAME, CredentialField.HOTSPOT_PASSWORD),
            report.mustEnter
        )
    }

    @Test
    fun `an open hotspot still needs a passphrase typed in`() {
        // The phone refuses an open network, so a named one with no key is not "available".
        val report = hotspot(probe(systemConfig = SoftApCredentials("HeadUnitAP", "")))
        assertEquals(listOf(CredentialField.HOTSPOT_PASSWORD), report.mustEnter)
        assertEquals(FieldVerdict.AVAILABLE_FROM_DEVICE, report.verdicts[CredentialField.HOTSPOT_NAME])
    }

    @Test
    fun `a password on its own is enough when the device can still name the network`() {
        val report = hotspot(probe(manualPassword = "hunter2"))
        assertEquals(emptyList<CredentialField>(), report.mustEnter)
    }

    // --- BSSID ----------------------------------------------------------------------------------

    @Test
    fun `an unset static BSSID is the string zero, not a MAC`() {
        // The default is "0", which is not MAC-shaped. Treating it as a set override is the bug
        // SoftApBssidPolicy's [BUG_FIX] note records: it won the chain and was published verbatim.
        assertEquals(
            FieldVerdict.AVAILABLE_FROM_DEVICE,
            hotspot(probe(staticBssid = "0")).verdicts[CredentialField.BSSID]
        )
    }

    @Test
    fun `a malformed static BSSID does not count as supplied`() {
        val report = hotspot(probe(staticBssid = "not-a-mac", probedBssid = null))
        assertEquals(FieldVerdict.MUST_BE_ENTERED, report.verdicts[CredentialField.BSSID])
    }

    @Test
    fun `a masked address is not an address`() {
        val report = wifiDirect(probe(probedBssid = "02:00:00:00:00:00"))
        assertEquals(FieldVerdict.MUST_BE_ENTERED, report.verdicts[CredentialField.BSSID])
    }

    @Test
    fun `nothing to read from is unknown, and unknown is never asked about`() {
        // The hotspot is off, or no P2P group exists. Every unit looks like a unit that cannot
        // report its own MAC at this moment, and prompting here would fire on healthy hardware.
        val report = wifiDirect(probe(probedBssid = null, bssidProbeConclusive = false))
        assertEquals(FieldVerdict.UNKNOWN, report.verdicts[CredentialField.BSSID])
        assertEquals(emptyList<CredentialField>(), report.mustEnter)
        assertFalse(report.hasFindings)
    }

    // --- location services ----------------------------------------------------------------------

    @Test
    fun `location off is offered instead of asking for a MAC`() {
        // The usual cause of a masked BSSID, and a switch beats a typed address: a static override
        // outranks every automatic source afterwards, so a MAC typed to work around this outlives
        // the problem it was for.
        val report = wifiDirect(probe(probedBssid = null, locationServicesEnabled = false))
        assertTrue(report.locationServicesOff)
        assertEquals(emptyList<CredentialField>(), report.mustEnter)
        assertTrue(report.hasFindings)
    }

    @Test
    fun `location off is not raised when the BSSID is already in hand`() {
        assertFalse(wifiDirect(probe(locationServicesEnabled = false)).locationServicesOff)
        assertFalse(wifiDirect(probe(
            staticBssid = realMac, probedBssid = null, locationServicesEnabled = false
        )).locationServicesOff)
    }

    @Test
    fun `location off does not suppress the hotspot credentials`() {
        val report = hotspot(probe(
            systemConfig = null, probedBssid = null, locationServicesEnabled = false
        ))
        assertTrue(report.locationServicesOff)
        assertEquals(
            listOf(CredentialField.HOTSPOT_NAME, CredentialField.HOTSPOT_PASSWORD),
            report.mustEnter
        )
    }

    @Test
    fun `location that could not be asked about is not location that is off`() {
        assertFalse(wifiDirect(probe(
            probedBssid = null, locationServicesEnabled = null
        )).locationServicesOff)
    }

    // --- transport scope --------------------------------------------------------------------------

    @Test
    fun `WiFi Direct is never asked for a network name or password`() {
        // Both are generated by the framework for each group and read back off WifiP2pGroup, so a
        // name the user typed could not match the group the phone is told to join. No setting for
        // one exists, and asking would produce a value nothing reads.
        val report = wifiDirect(probe(manualSsid = "", systemConfig = null, probedBssid = realMac))
        assertEquals(setOf(CredentialField.BSSID), report.verdicts.keys)
        assertEquals(emptyList<CredentialField>(), report.mustEnter)
    }

    @Test
    fun `the hotspot transport reports all three fields`() {
        assertEquals(
            listOf(CredentialField.HOTSPOT_NAME, CredentialField.HOTSPOT_PASSWORD, CredentialField.BSSID),
            hotspot(probe()).verdicts.keys.toList()
        )
    }
}
