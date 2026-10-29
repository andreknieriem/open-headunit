package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class GalSessionProfileTest {
    @Before @After fun resetPolicy() = GalVersionPolicy.resetForTest()

    @Test fun `opening request is 6 dot 1 unless compatibility mode or a refused opening`() {
        assertEquals(GalProfile.GAL_6_1, GalVersionPolicy.opening(compatibilityMode = false))
        assertEquals(GalProfile.COMPATIBILITY, GalVersionPolicy.opening(compatibilityMode = true))
        // A refused 1.2 has nothing lower to fall back to and must not change the next request.
        GalVersionPolicy.noteRefused(GalProfile.COMPATIBILITY)
        assertEquals(GalProfile.GAL_6_1, GalVersionPolicy.opening(compatibilityMode = false))
        GalVersionPolicy.noteRefused(GalProfile.GAL_6_1)
        assertEquals(GalProfile.COMPATIBILITY, GalVersionPolicy.opening(compatibilityMode = false))
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
