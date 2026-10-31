package com.andrerinas.openheadunit.connection.self

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfLaunchCoalescePolicyTest {

    @Test
    fun `an idle request with nothing connected is the one that starts a launch`() {
        assertTrue(SelfLaunchCoalescePolicy.shouldStart(launchInFlight = false, isConnected = false))
    }

    @Test
    fun `a second request while one is in flight does not start another`() {
        // The defect: auto-start-self-mode and an explicit ACTION_START_SELF_MODE both fired, and on
        // the 17.4+ route the loser's failure report disconnected the winner's session.
        assertFalse(SelfLaunchCoalescePolicy.shouldStart(launchInFlight = true, isConnected = false))
    }

    @Test
    fun `a request arriving on a live session has nothing to do`() {
        assertFalse(SelfLaunchCoalescePolicy.shouldStart(launchInFlight = false, isConnected = true))
        assertFalse(SelfLaunchCoalescePolicy.shouldStart(launchInFlight = true, isConnected = true))
    }

    // The second rule, that running out of launchers is no failure once something connected, is
    // decided from physical arrivals by CommManager.reportLaunchFailure: InternalFailureRecoveryTest
    // and the Self host fixture hold it for a duplicate that reached the launchers some other way.
}
