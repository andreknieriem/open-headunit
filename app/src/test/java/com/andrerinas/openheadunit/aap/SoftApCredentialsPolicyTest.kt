package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftApCredentialsPolicyTest {

    private fun decide(
        manualSsid: String = "",
        manualPassphrase: String = "",
        systemConfig: SoftApCredentials? = null,
        ip: String? = "192.168.43.1"
    ) = SoftApCredentialsPolicy.decide(manualSsid, manualPassphrase, systemConfig, ip)

    @Test
    fun `an interface with no address of its own is not an access point yet`() {
        assertEquals(SoftApCredentialsAttempt.NO_AP_YET, decide(ip = null))
        assertEquals(SoftApCredentialsAttempt.NO_AP_YET, decide(ip = ""))
    }

    @Test
    fun `the device naming its own access point is enough`() {
        assertEquals(
            SoftApCredentialsAttempt.PUBLISHED,
            decide(systemConfig = SoftApCredentials("AndroidAP", "swordfish"))
        )
    }

    @Test
    fun `a device that will not name its access point is a dead end, not a wait`() {
        // The distinction this policy exists for. There is an access point — the interface has an
        // address — and the read that would name it has failed. It will fail again next second, so
        // returning NO_AP_YET here is what made the resolve loop spend its whole budget polling and
        // switch on a hotspot that was already up.
        assertEquals(SoftApCredentialsAttempt.CONFIG_UNREADABLE, decide(systemConfig = null))
    }

    @Test
    fun `the manual override is the way through on a device that hides its configuration`() {
        // What the on-screen instruction tells the user to do, so it has to be the case that does
        // it: no system configuration available, and the route runs anyway.
        assertEquals(
            SoftApCredentialsAttempt.PUBLISHED,
            decide(manualSsid = "OHU-TEST", manualPassphrase = "testtest1234", systemConfig = null)
        )
        assertEquals(
            SoftApCredentials("OHU-TEST", "testtest1234"),
            SoftApCredentialsPolicy.resolve("OHU-TEST", "testtest1234", null)
        )
    }

    @Test
    fun `a named network with no password goes out open, and that is the caller's warning to give`() {
        // Not an oversight being pinned by accident. The caller does not read the system
        // configuration at all once a manual name is set, so there is nothing to fall back to, and
        // pairing a user-typed name with a passphrase read off the device would hand the phone a
        // mismatched pair that fails with nothing pointing at why. The empty passphrase is the
        // honest answer; SoftApCredentialsProvider logs it.
        assertEquals(
            SoftApCredentialsAttempt.PUBLISHED,
            decide(manualSsid = "OHU-TEST", manualPassphrase = "", systemConfig = null)
        )
        assertEquals(
            SoftApCredentials("OHU-TEST", ""),
            SoftApCredentialsPolicy.resolve("OHU-TEST", "", null)
        )
    }

    @Test
    fun `each field takes the user's override where there is one`() {
        // Field-by-field precedence, which is the function's own rule. Note the first case cannot
        // arise from SoftApCredentialsProvider as it stands — it stops reading the system
        // configuration once a manual name is set, so a manual name never meets a system
        // passphrase. Pinned anyway, so that if a caller ever does pass both, the answer is the
        // documented one rather than whatever falls out.
        assertEquals(
            SoftApCredentials("OHU-TEST", "fromTheDevice"),
            SoftApCredentialsPolicy.resolve("OHU-TEST", "", SoftApCredentials("AndroidAP", "fromTheDevice"))
        )
        assertEquals(
            SoftApCredentials("AndroidAP", "typedByHand"),
            SoftApCredentialsPolicy.resolve("", "typedByHand", SoftApCredentials("AndroidAP", "fromTheDevice"))
        )
    }

    @Test
    fun `a system configuration with an empty name is no configuration at all`() {
        // getSoftApConfiguration() can return an object whose SSID is empty rather than refusing
        // outright; that is the same dead end and must read as one.
        assertEquals(
            SoftApCredentialsAttempt.CONFIG_UNREADABLE,
            decide(systemConfig = SoftApCredentials("", ""))
        )
    }

    @Test
    fun `the address is checked before the name, so a missing one is never reported as unreadable`() {
        // Ordering matters for the message the user sees: "this device will not let apps read its
        // hotspot name" is wrong and unactionable when the truth is that no access point is up.
        assertEquals(SoftApCredentialsAttempt.NO_AP_YET, decide(systemConfig = null, ip = null))
    }

    // What a successful publish is allowed to conclude about the hardware.

    @Test
    fun `a device that names its own access point with a passphrase disproves the record`() {
        assertTrue(
            SoftApCredentialsPolicy.disprovesConfigUnreadable(
                manualSsid = "",
                manualPassphrase = "",
                systemConfig = SoftApCredentials("AndroidAP", "swordfish")
            )
        )
    }

    @Test
    fun `a manual name is a way round the fault, not proof it is gone`() {
        // The measured defect. With a name set and the password blank, the run that showed the
        // banner also deleted the record behind it - and decide() can never raise it again once a
        // name resolves, so the instruction was gone for good. Neither half of this is a disproof:
        // the device still refuses to name its access point in both.
        assertFalse(
            SoftApCredentialsPolicy.disprovesConfigUnreadable("OHU-TEST", "testtest1234", null)
        )
        assertFalse(
            SoftApCredentialsPolicy.disprovesConfigUnreadable("OHU-TEST", "", null)
        )
    }

    @Test
    fun `an access point the device names but cannot secure is not a disproof`() {
        // Android Auto refuses an open network, so this publish is about to fail for the very
        // reason the record describes. Before this rule it had no signal of any kind.
        assertFalse(
            SoftApCredentialsPolicy.disprovesConfigUnreadable("", "", SoftApCredentials("AndroidAP", ""))
        )
    }

    @Test
    fun `a passphrase typed by hand still leaves the name as the device's own`() {
        // Counter-intuitive and deliberate, so it is pinned rather than left to be tidied away: the
        // record's claim is about the *name*, and the device supplied that. The passphrase is only
        // required so a publish the phone will refuse cannot count as a success.
        assertTrue(
            SoftApCredentialsPolicy.disprovesConfigUnreadable("", "typedByHand", SoftApCredentials("AndroidAP", ""))
        )
    }

    @Test
    fun `nothing readable at all is not a disproof`() {
        assertFalse(SoftApCredentialsPolicy.disprovesConfigUnreadable("", "", null))
        // The empty-SSID configuration the pre-Q reader can hand back is the same dead end.
        assertFalse(
            SoftApCredentialsPolicy.disprovesConfigUnreadable("", "", SoftApCredentials("", ""))
        )
    }

    @Test
    fun `joinable needs both halves`() {
        assertTrue(SoftApCredentialsPolicy.isJoinable("OHU-TEST", "testtest1234"))
        assertFalse(SoftApCredentialsPolicy.isJoinable("OHU-TEST", ""))
        assertFalse(SoftApCredentialsPolicy.isJoinable("", "testtest1234"))
        assertFalse(SoftApCredentialsPolicy.isJoinable("", ""))
    }

    @Test
    fun `a disproof is only ever reached on an attempt that publishes`() {
        // The clear site sits behind decide() == PUBLISHED, so a predicate that could be true
        // anywhere else would be describing a state its caller never sees.
        for (manualSsid in listOf("", "OHU-TEST")) {
            for (manualPassphrase in listOf("", "testtest1234")) {
                for (system in listOf(
                    null,
                    SoftApCredentials("", ""),
                    SoftApCredentials("AndroidAP", ""),
                    SoftApCredentials("AndroidAP", "swordfish")
                )) {
                    // The provider reads the system configuration only when no manual name is set,
                    // so a state the call site cannot produce proves nothing either way.
                    val config = if (manualSsid.isEmpty()) system else null
                    if (!SoftApCredentialsPolicy.disprovesConfigUnreadable(manualSsid, manualPassphrase, config)) continue
                    assertEquals(
                        "ssid='$manualSsid' pw='$manualPassphrase' system=$config",
                        SoftApCredentialsAttempt.PUBLISHED,
                        decide(manualSsid, manualPassphrase, config)
                    )
                }
            }
        }
    }
}
