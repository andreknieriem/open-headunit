package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.messages.Messages
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FullNavigationProfileTest {

    @Test
    fun offAnnouncesExactlyWhatTheAppAnnouncedBefore() {
        assertEquals(AapVersionNegotiation.ANNOUNCED_MINOR, FullNavigationProfile.versionMinor(false))
        assertEquals(1000, FullNavigationProfile.navigationIntervalMs(false))
        assertEquals("1", FullNavigationProfile.softwareBuild(false))
        assertEquals("0.1.0", FullNavigationProfile.softwareVersion(false))
        // the request that goes out with the switch off is byte for byte the old one
        assertArrayEquals(Messages.versionRequest,
            Messages.versionRequest(AapVersionNegotiation.ANNOUNCED_MAJOR, FullNavigationProfile.versionMinor(false)))
    }

    @Test
    fun onAnnouncesWhatZlinkAnnounces() {
        assertEquals(6, FullNavigationProfile.versionMinor(true))
        assertEquals(500, FullNavigationProfile.navigationIntervalMs(true))
        assertEquals("2022-03-30-438482292", FullNavigationProfile.softwareBuild(true))
        assertEquals("2.0-linux", FullNavigationProfile.softwareVersion(true))
    }

    @Test
    fun theVersionRequestCarriesTheChosenMajorAndMinor() {
        // channel 0, flags 3 (unencrypted control), length 6, type 1 (VERSION_REQUEST), major 1, minor 6
        assertArrayEquals(byteArrayOf(0, 3, 0, 6, 0, 1, 0, 1, 0, 6), Messages.versionRequest(1, 6))
    }

    @Test
    fun onlyTheSwitchReachesTheOneSixMessageSet() {
        // the phone sends NavigationState (0x8006) from 1.6 on; the default stays below it
        assertTrue(FullNavigationProfile.versionMinor(true) >= AapVersionNegotiation.FEATURE_MINOR_1_6)
        assertFalse(FullNavigationProfile.versionMinor(false) >= AapVersionNegotiation.FEATURE_MINOR_1_6)
    }

    @Test
    fun withoutAContextTheSwitchReadsOff() {
        assertFalse(FullNavigationProfile.enabled(null))
    }
}
