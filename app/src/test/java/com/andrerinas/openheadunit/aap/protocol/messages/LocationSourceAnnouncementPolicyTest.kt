package com.andrerinas.openheadunit.aap.protocol.messages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSourceAnnouncementPolicyTest {

    /**
     * The regression this policy must never cause, and the one its audio sibling did cause: a
     * session to a real phone still offers this head unit's GPS.
     */
    @Test
    fun `a session that is not Self Mode announces the location sensor when the setting is on`() {
        assertTrue(
            LocationSourceAnnouncementPolicy.announcesLocation(
                useGpsForNavigation = true,
                isSelfModeSession = false
            )
        )
    }

    /** Android Auto reads this device's location directly, so the relay can only be staler. */
    @Test
    fun `a Self Mode session never announces it, whatever the setting says`() {
        assertFalse(
            LocationSourceAnnouncementPolicy.announcesLocation(
                useGpsForNavigation = true,
                isSelfModeSession = true
            )
        )
    }

    /** The setting wins over the transport: off means off on every session. */
    @Test
    fun `the setting being off drops it whatever the session is`() {
        assertFalse(
            LocationSourceAnnouncementPolicy.announcesLocation(
                useGpsForNavigation = false,
                isSelfModeSession = false
            )
        )
        assertFalse(
            LocationSourceAnnouncementPolicy.announcesLocation(
                useGpsForNavigation = false,
                isSelfModeSession = true
            )
        )
    }
}
