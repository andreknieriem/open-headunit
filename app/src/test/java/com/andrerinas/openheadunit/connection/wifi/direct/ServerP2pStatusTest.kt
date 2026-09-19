package com.andrerinas.openheadunit.connection.wifi.direct

import org.junit.Assert.*
import org.junit.Test

class ServerP2pStatusTest {
    private val status = ServerP2pStatus()

    private fun snapshot(available: Boolean, now: Long) =
        status.group(status.request(), available, now)

    @Test fun confirmedGroupCanBeVisibleWithoutADeviceName() {
        snapshot(true, 0)
        assertTrue(status.badge.groupAvailable)
        assertNull(status.badge.deviceName)
    }

    @Test fun knownNameAloneDoesNotClaimAGroupExists() {
        status.localName("OpenHU")
        assertFalse(status.badge.groupAvailable)
        assertEquals("OpenHU", status.badge.deviceName)
    }

    @Test fun temporaryNullDoesNotFlickerOrForgetLocalName() {
        status.localName("OpenHU")
        snapshot(true, 0)
        snapshot(false, 100)
        status.localName(null)
        status.localName("  ")
        assertTrue(status.badge.groupAvailable)
        snapshot(true, 2100)
        assertEquals(ServerP2pBadge(true, "OpenHU"), status.badge)
    }

    @Test fun hidingRequiresTwoNegativeSnapshotsAtLeastTwoSecondsApart() {
        snapshot(true, 0)
        snapshot(false, 100)
        snapshot(false, 2099)
        assertTrue(status.badge.groupAvailable)
        snapshot(false, 2100)
        assertFalse(status.badge.groupAvailable)
    }

    @Test fun positiveSnapshotResetsMissingWindow() {
        snapshot(true, 0)
        snapshot(false, 100)
        snapshot(true, 1000)
        snapshot(false, 2200)
        assertTrue(status.badge.groupAvailable)
        snapshot(false, 4200)
        assertFalse(status.badge.groupAvailable)
    }

    @Test fun olderCallbackCannotOverwriteNewerSnapshot() {
        val old = status.request()
        snapshot(true, 100)
        assertFalse(status.group(old, false, 3000))
        assertTrue(status.badge.groupAvailable)
    }

    @Test fun disconnectInvalidatesPendingCallbacksWithoutClaimingGroupIsGone() {
        snapshot(true, 0)
        val pending = status.request()
        status.invalidateRequests()
        assertFalse(status.group(pending, false, 10))
        assertTrue(status.badge.groupAvailable)
    }

    @Test fun radioOffHidesImmediatelyAndRejectsPendingCallback() {
        snapshot(true, 0)
        val pending = status.request()
        status.clearGroup()
        assertFalse(status.badge.groupAvailable)
        assertFalse(status.group(pending, true, 100))
        snapshot(true, 200)
        assertTrue(status.badge.groupAvailable)
    }

    @Test fun stopClearsNameAndGroupAndRejectsLateCallbackAcrossRestart() {
        status.localName("OpenHU")
        snapshot(true, 0)
        val pending = status.request()
        status.stop()
        assertEquals(ServerP2pBadge(), status.badge)
        snapshot(true, 200)
        assertFalse(status.group(pending, false, 300))
        assertEquals(ServerP2pBadge(true, null), status.badge)
    }

    @Test fun renameCallbackAndTimeoutCanProceedOnlyOnceInEitherOrder() {
        for (first in listOf("success", "refusal", "timeout")) {
            val gate = ServerP2pRenameGate()
            assertTrue(first, gate.complete())
            assertFalse(first, gate.complete())
        }
    }
}
