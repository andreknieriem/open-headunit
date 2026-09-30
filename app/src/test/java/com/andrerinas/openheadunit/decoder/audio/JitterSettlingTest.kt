package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class JitterSettlingTest {
    @Test fun `one late batch does not leave a clean link hundreds of milliseconds behind for minutes`() {
        val policy = AdaptiveJitterPolicy(48000, 2)
        policy.onArrival(0, 480)
        policy.onPcmDelivery(0, 480)
        policy.onArrival(300, 480)
        policy.onPcmDelivery(300, 480)
        val raised = policy.targetFrames
        assertTrue(raised >= 14400)
        for (now in 310L..10000L step 10) {
            policy.onArrival(now, 480); policy.onPcmDelivery(now, 480)
        }
        assertEquals(raised, policy.targetFrames) // require ten quiet seconds first
        var previous = policy.targetFrames
        for (now in 10010L..90000L step 10) {
            policy.onArrival(now, 480); policy.onPcmDelivery(now, 480)
            assertTrue("release in small steps", previous - policy.targetFrames in 0..240)
            previous = policy.targetFrames
        }
        assertTrue("recover to at most 80ms on a settled link", policy.targetFrames <= 3840)
    }

    @Test fun `recurring decoder batches retain their proven reserve`() {
        val policy = AdaptiveJitterPolicy(48000, 2)
        for (now in 0L..90000L step 10) {
            policy.onArrival(now, 480)
            if (now % 300 == 0L) repeat(30) { policy.onPcmDelivery(now, 480) }
        }
        assertTrue(policy.targetFrames >= 14400)
    }
}
