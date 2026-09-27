package com.andrerinas.openheadunit.connection.wifi.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pIdentityRotationPolicyTest {

    // --- mechanism ---

    @Test
    fun `from API 29 the app names the group itself`() {
        assertEquals(
            P2pIdentityRotationPolicy.Mechanism.NAMED_RECREATE,
            P2pIdentityRotationPolicy.mechanism(29)
        )
        assertEquals(
            P2pIdentityRotationPolicy.Mechanism.NAMED_RECREATE,
            P2pIdentityRotationPolicy.mechanism(36)
        )
    }

    @Test
    fun `below API 29 the stored profile is what has to go`() {
        assertEquals(
            P2pIdentityRotationPolicy.Mechanism.PURGE_PROFILE_THEN_RECREATE,
            P2pIdentityRotationPolicy.mechanism(17)
        )
        assertEquals(
            P2pIdentityRotationPolicy.Mechanism.PURGE_PROFILE_THEN_RECREATE,
            P2pIdentityRotationPolicy.mechanism(28)
        )
    }

    // --- applyNow ---

    @Test
    fun `an idle native WiFi Direct group is rotated now`() {
        assertTrue(
            P2pIdentityRotationPolicy.applyNow(
                sessionLive = false,
                handshakeInFlight = false,
                nativeWifiDirectActive = true,
            )
        )
        assertNull(
            P2pIdentityRotationPolicy.deferralReason(
                sessionLive = false,
                handshakeInFlight = false,
                nativeWifiDirectActive = true,
            )
        )
    }

    @Test
    fun `a create still being answered is not built over`() {
        assertFalse(
            P2pIdentityRotationPolicy.applyNow(
                sessionLive = false,
                handshakeInFlight = false,
                nativeWifiDirectActive = true,
                createOutstanding = true,
            )
        )
        assertNotNull(
            P2pIdentityRotationPolicy.deferralReason(
                sessionLive = false,
                handshakeInFlight = false,
                nativeWifiDirectActive = true,
                createOutstanding = true,
            )
        )
    }

    @Test
    fun `a projecting session keeps its group`() {
        assertFalse(
            P2pIdentityRotationPolicy.applyNow(
                sessionLive = true,
                handshakeInFlight = false,
                nativeWifiDirectActive = true,
            )
        )
        assertTrue(
            P2pIdentityRotationPolicy.deferralReason(
                sessionLive = true,
                handshakeInFlight = false,
                nativeWifiDirectActive = true,
            )!!.contains("projecting")
        )
    }

    @Test
    fun `a handshake mid-exchange keeps its group`() {
        assertFalse(
            P2pIdentityRotationPolicy.applyNow(
                sessionLive = false,
                handshakeInFlight = true,
                nativeWifiDirectActive = true,
            )
        )
    }

    @Test
    fun `nothing is rotated when this mode hosts no group`() {
        assertFalse(
            P2pIdentityRotationPolicy.applyNow(
                sessionLive = false,
                handshakeInFlight = false,
                nativeWifiDirectActive = false,
            )
        )
        assertNotNull(
            P2pIdentityRotationPolicy.deferralReason(
                sessionLive = false,
                handshakeInFlight = false,
                nativeWifiDirectActive = false,
            )
        )
    }

    // --- purgeBeforeCreate ---

    @Test
    fun `a pending rotation purges the stored profile below API 29`() {
        assertTrue(
            P2pIdentityRotationPolicy.purgeBeforeCreate(17, keepIdentity = true, rotationPending = true)
        )
    }

    @Test
    fun `a new network on every connection purges every create below API 29`() {
        assertTrue(
            P2pIdentityRotationPolicy.purgeBeforeCreate(17, keepIdentity = false, rotationPending = false)
        )
    }

    @Test
    fun `an ordinary create below API 29 keeps the profile the setting asks it to keep`() {
        assertFalse(
            P2pIdentityRotationPolicy.purgeBeforeCreate(17, keepIdentity = true, rotationPending = false)
        )
    }

    @Test
    fun `nothing is ever purged from API 29`() {
        assertFalse(
            P2pIdentityRotationPolicy.purgeBeforeCreate(29, keepIdentity = true, rotationPending = true)
        )
        assertFalse(
            P2pIdentityRotationPolicy.purgeBeforeCreate(29, keepIdentity = false, rotationPending = true)
        )
        assertFalse(
            P2pIdentityRotationPolicy.purgeBeforeCreate(36, keepIdentity = false, rotationPending = false)
        )
    }

    @Test
    fun `below Q any group this unit owns is read rather than recreated`() {
        // Nothing was requested there, so neither half is compared and a recreate would only mint
        // another name the phone has to be told about.
        assertTrue(
            reads(sdkInt = 17, liveName = "DIRECT-l9-Tablet", livePassphrase = "somethingElse")
        )
    }

    @Test
    fun `a group this unit does not own is never read`() {
        for (sdk in listOf(17, P2pIdentityRotationPolicy.NAMED_CREATE_SDK)) {
            assertFalse("sdk $sdk", reads(sdkInt = sdk, isGroupOwner = false))
        }
    }

    @Test
    fun `from Q a group matching both halves is read`() {
        assertTrue(reads())
    }

    @Test
    fun `from Q a different name is not read`() {
        assertFalse(reads(liveName = "DIRECT-XX-Other"))
    }

    @Test
    fun `from Q the same name with a different passphrase is not read`() {
        // The measured defect: reading this survivor serves the phone the passphrase the stored pair
        // no longer has, and the create that would have applied the new one never runs.
        assertFalse(reads(livePassphrase = "RigPass99999"))
    }

    @Test
    fun `from Q a group whose passphrase cannot be read is not read`() {
        assertFalse(reads(livePassphrase = null))
    }

    private fun reads(
        sdkInt: Int = P2pIdentityRotationPolicy.NAMED_CREATE_SDK,
        isGroupOwner: Boolean = true,
        liveName: String? = "DIRECT-PB-HeadUnit",
        requestedName: String = "DIRECT-PB-HeadUnit",
        livePassphrase: String? = "RigPass12345",
        requestedPassphrase: String = "RigPass12345",
    ) = P2pIdentityRotationPolicy.readsExistingGroup(
        sdkInt, isGroupOwner, liveName, requestedName, livePassphrase, requestedPassphrase
    )
}
