package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.connection.usb.UsbRecoveryDevice
import org.junit.Assert.*
import org.junit.Test

class GalSessionProfileTest {
    @Test fun `opening fallback is distinct from the setting and only retries the same peer once`() {
        val policy = GalVersionPolicy()
        val accessory = UsbRecoveryDevice("/usb/1", 0x18d1, 0x2d00, "Vendor", "Model", "serial")
        val refuser = GalVersionPolicy.usbPeer(accessory)!!
        val other = GalVersionPolicy.usbPeer(accessory.copy(serial = "other"))
        assertEquals(GalVersionPolicy.Opening.DEFAULT, policy.opening(false, refuser, 10))
        assertEquals(GalVersionPolicy.Opening.SETTING, policy.opening(true, refuser, 10))
        policy.noteRefusal(refuser, 10)
        assertEquals(GalVersionPolicy.Opening.DEFAULT, policy.opening(false, other, 11))
        assertEquals(GalVersionPolicy.Opening.DEFAULT, policy.opening(false, null, 12))
        val retry = policy.opening(false, GalVersionPolicy.usbPeer(accessory.copy(path = "/usb/2")), 13)
        assertEquals(GalVersionPolicy.Opening.PEER_REFUSAL, retry)
        assertEquals(GalProfile.COMPATIBILITY, retry.profile)
        assertNotEquals(GalVersionPolicy.Opening.SETTING.logSuffix, retry.logSuffix)
        assertEquals(GalVersionPolicy.Opening.DEFAULT, policy.opening(false, refuser, 14))
    }

    @Test fun `serialless USB only retries its attachment within the refusal window`() {
        val policy = GalVersionPolicy()
        val accessory = UsbRecoveryDevice("/usb/1", 0x18d1, 0x2d00, "Vendor", "Model", null)
        val refuser = GalVersionPolicy.usbPeer(accessory)!!
        policy.noteRefusal(refuser, 0)
        val reattached = GalVersionPolicy.usbPeer(accessory.copy(path = "/usb/2"))
        assertEquals(GalVersionPolicy.Opening.DEFAULT, policy.opening(false, reattached, 1))
        assertEquals(GalVersionPolicy.Opening.PEER_REFUSAL, policy.opening(false, refuser, 2))
        policy.noteRefusal(refuser, 5)
        assertEquals(GalVersionPolicy.Opening.DEFAULT, policy.opening(false, refuser, 30_005))
    }

    @Test fun `only a legacy refusal from a modern opening offers a retry`() {
        val legacy = AapVersionNegotiation.Result(1, 5, -1, null)
        assertTrue(GalVersionPolicy.isLegacyRefusal(GalProfile.GAL_6_1, legacy))
        assertFalse(GalVersionPolicy.isLegacyRefusal(GalProfile.COMPATIBILITY, legacy))
        for (reply in listOf(legacy.copy(major = 0), legacy.copy(major = 6),
            legacy.copy(minor = 1), legacy.copy(minor = 65535), legacy.copy(status = 0))) {
            assertFalse(GalVersionPolicy.isLegacyRefusal(GalProfile.GAL_6_1, reply))
        }
    }

    // Maximum reply constants of inspected senders: AA 9.7 4.2, 12.4 4.5, 14 and 15 5.0,
    // 16.0 to 17.2 6.0, 17.3 to 18.0 6.1, 18.1 and 18.2 6.2. Play services 12.8.76 answers 1.5.
    @Test fun `reply selects the highest implemented profile it covers and never exceeds the request`() {
        val cases = listOf(
            GalVersion(6, 2) to GalProfile.GAL_6_1,
            GalVersion(6, 1) to GalProfile.GAL_6_1,
            GalVersion(6, 0) to GalProfile.GAL_1_6,
            GalVersion(5, 0) to GalProfile.GAL_1_6,
            GalVersion(4, 2) to GalProfile.GAL_1_6,
            GalVersion(1, 7) to GalProfile.GAL_1_6,
            GalVersion(1, 5) to GalProfile.COMPATIBILITY,
            GalVersion(1, 1) to GalProfile.COMPATIBILITY
        )
        for ((reply, expected) in cases) {
            assertEquals("reply $reply", expected, GalVersionPolicy.select(GalProfile.GAL_6_1, reply))
        }
        assertEquals(GalProfile.COMPATIBILITY, GalVersionPolicy.select(GalProfile.COMPATIBILITY, GalVersion(6, 2)))
    }

    @Test fun `higher response never promotes the selected request or its feature gates`() {
        val response = AapVersionNegotiation.Result(6, 1, 0, null)
        val session = GalSessionProfile(GalProfile.GAL_1_6, response)
        assertSame(response, session.finalResponse)
        assertEquals(GalVersion(1, 6), session.requestedVersion)
        val contract = GalConnectionContract.resolve(session, GalDisplaySettings(false, false, false))
        assertTrue(contract.guidance48Khz)
        assertFalse(contract.media.optionsFor(Channel.ID_AUD).embedded)
        assertFalse(contract.media.optionsFor(Channel.ID_VID).standalone)
    }

    @Test fun `lower ceiling is rejected instead of silently changing requested profile`() {
        for (profile in GalProfile.entries) {
            val lower = AapVersionNegotiation.Result(profile.version.major, profile.version.minor - 1, 0, null)
            assertThrows(IllegalArgumentException::class.java) {
                GalConnectionContract.resolve(GalSessionProfile(profile, lower), GalDisplaySettings(false, false, false))
            }
        }
    }

    @Test fun `existing accepted nonzero status policy remains unchanged`() {
        val response = AapVersionNegotiation.Result(6, 1, 1, null)
        assertTrue(response.accepted)
        val session = GalSessionProfile(GalProfile.GAL_6_1, response)
        assertSame(session, GalConnectionContract.resolve(session, GalDisplaySettings(false, false, false)).negotiation)
    }
}
