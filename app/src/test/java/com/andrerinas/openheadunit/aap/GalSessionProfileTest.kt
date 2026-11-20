package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import org.junit.Assert.*
import org.junit.Test

class GalSessionProfileTest {
    @Test fun `setting selects the exact requested profile including 5 dot 1`() {
        assertEquals(listOf("1.2", "1.6", "5.0", "5.1", "6.0", "6.1"),
            GalProfile.entries.map { it.version.toString() })
        for (profile in GalProfile.entries) {
            assertEquals(profile, GalProfile.fromSetting(profile.version.toString()))
        }
        assertEquals(GalProfile.COMPATIBILITY, GalProfile.fromSetting("99.9"))
    }

    @Test fun `higher response never promotes the selected request or its feature gates`() {
        val response = AapVersionNegotiation.Result(6, 1, 0, null)
        val session = GalSessionProfile(GalProfile.GAL_5_0, response)
        assertSame(response, session.finalResponse)
        assertEquals(GalVersion(5, 0), session.requestedVersion)
        val contract = GalConnectionContract.resolve(session, GalDisplaySettings(false, false, false))
        assertFalse(contract.media.optionsFor(Channel.ID_AUD).standalone)
        assertFalse(contract.media.optionsFor(Channel.ID_VID).standalone)
    }

    @Test fun `lower ceiling is rejected instead of silently changing requested profile`() {
        for (profile in GalProfile.entries) {
            val lower = if (profile.version.minor == 0) {
                AapVersionNegotiation.Result(profile.version.major - 1, 9, 0, null)
            } else AapVersionNegotiation.Result(profile.version.major, profile.version.minor - 1, 0, null)
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
