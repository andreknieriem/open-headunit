package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionActivationPolicyTest {

    @Test
    fun `shouldActivate returns false when session is loopback`() {
        assertFalse(
            "Loopback sessions run on the same device and should not activate MediaSession",
            MediaSessionActivationPolicy.shouldActivate(
                isLoopbackSession = true,
                isSelfModeLauncherActive = false
            )
        )
    }

    @Test
    fun `shouldActivate returns false when self mode launcher is active`() {
        assertFalse(
            "Self mode launcher running on the same device should not activate MediaSession",
            MediaSessionActivationPolicy.shouldActivate(
                isLoopbackSession = false,
                isSelfModeLauncherActive = true
            )
        )
    }

    @Test
    fun `shouldActivate returns true for remote USB and WiFi sessions`() {
        assertTrue(
            "Remote sessions should activate MediaSession to relay hardware media buttons to phone",
            MediaSessionActivationPolicy.shouldActivate(
                isLoopbackSession = false,
                isSelfModeLauncherActive = false
            )
        )
    }

    @Test
    fun `shouldSyncAaMetadata returns false in self mode even if preference is enabled`() {
        assertFalse(
            "Loopback sessions should not publish AA media metadata to local system MediaSession",
            MediaSessionActivationPolicy.shouldSyncAaMetadata(
                isLoopbackSession = true,
                isSelfModeLauncherActive = false,
                userPreference = true
            )
        )
        assertFalse(
            "Self mode active launcher should not publish AA media metadata to local system MediaSession",
            MediaSessionActivationPolicy.shouldSyncAaMetadata(
                isLoopbackSession = false,
                isSelfModeLauncherActive = true,
                userPreference = true
            )
        )
    }

    @Test
    fun `shouldSyncAaMetadata follows user preference in remote sessions`() {
        assertTrue(
            "Remote session should sync metadata when user preference is enabled",
            MediaSessionActivationPolicy.shouldSyncAaMetadata(
                isLoopbackSession = false,
                isSelfModeLauncherActive = false,
                userPreference = true
            )
        )
        assertFalse(
            "Remote session should respect user preference when disabled",
            MediaSessionActivationPolicy.shouldSyncAaMetadata(
                isLoopbackSession = false,
                isSelfModeLauncherActive = false,
                userPreference = false
            )
        )
    }
}
