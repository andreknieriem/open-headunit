package com.andrerinas.openheadunit.connection.wifi

import org.junit.Assert.*
import org.junit.Test

class ServerP2pPolicyTest {
    @Test fun onlyServerP2pSkipsSavedWifiAddress() {
        assertTrue(ServerP2pPolicy.skipsSavedWifiAddress(true, true))
        assertFalse(ServerP2pPolicy.skipsSavedWifiAddress(true, false))
        assertFalse(ServerP2pPolicy.skipsSavedWifiAddress(false, true))
        assertFalse(ServerP2pPolicy.skipsSavedWifiAddress(false, false))
    }

    @Test fun serverP2pFailureKeepsRecoveryArmedButExplicitExitStillHonorsKill() {
        val attempt = ServerP2pAttempt()
        attempt.begin(true)
        assertFalse(attempt.failureIsUserExit)
        assertFalse(attempt.honorKillOnFailure)
        assertFalse(attempt.honorKillOnTransportQuit(false))
        assertTrue(attempt.honorKillOnTransportQuit(true))
    }

    @Test fun subsequentUsbIpOrOrdinarySocketAttemptRestoresLegacyPolicy() {
        val attempt = ServerP2pAttempt()
        attempt.begin(true)
        attempt.begin()
        assertFalse(attempt.active)
        assertTrue(attempt.failureIsUserExit)
        assertTrue(attempt.honorKillOnFailure)
        assertTrue(attempt.honorKillOnTransportQuit(false))
    }

    @Test fun legacyPolicyIsDefault() {
        val attempt = ServerP2pAttempt()
        assertTrue(attempt.failureIsUserExit)
        assertTrue(attempt.honorKillOnFailure)
    }
}
