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

    @Test fun `staggered fragments cannot pin the target after a recovered stall`() {
        for (spacing in listOf(0L, 1L, 4L, 8L)) {
            for (splitIngress in listOf(false, true)) for (splitPcm in listOf(false, true)) {
                val policy = AdaptiveJitterPolicy(48000, 2)
                policy.onArrival(0, 960); policy.onPcmDelivery(0, 960)
                policy.onArrival(250, 960); policy.onPcmDelivery(250, 960)
                for (batch in 0L..2000L) {
                    // Each 80ms period supplies two 40ms groups, 20ms apart. The
                    // fragments may arrive a few milliseconds apart on either clock.
                    for (now in listOf(330 + batch * 80, 350 + batch * 80)) {
                        repeat(if (splitIngress) 2 else 1) { part ->
                            policy.onArrival(now + part * spacing, if (splitIngress) 960 else 1920)
                        }
                        repeat(if (splitPcm) 2 else 1) { part ->
                            policy.onPcmDelivery(now + part * spacing, if (splitPcm) 960 else 1920)
                        }
                    }
                }
                // Moving the second half later reduces the net supply peak by at
                // most that spacing. It must not prevent the old reserve from aging.
                val minimumMs = 85 - spacing.toInt()
                assertTrue("spacing=$spacing ingressSplit=$splitIngress pcmSplit=$splitPcm target=${policy.targetFrames}",
                    policy.targetFrames in minimumMs * 48..85 * 48)
            }
        }
    }

    @Test fun `near paced fragments retain credit across small early deliveries`() {
        val policy = AdaptiveJitterPolicy(48000, 2)
        policy.onArrival(0, 960); policy.onPcmDelivery(0, 960)
        policy.onArrival(250, 960); policy.onPcmDelivery(250, 960)
        for (batch in 0L..2000L) {
            // Four 20ms fragments occupy only 48ms of an 80ms period. Every
            // early interval is below the old per-delivery 5ms tolerance.
            for (part in 0L..3L) {
                val now = 330 + batch * 80 + part * 16
                policy.onArrival(now, 960); policy.onPcmDelivery(now, 960)
            }
        }
        assertTrue("target=${policy.targetFrames}", policy.targetFrames <= 80 * 48)
    }
}
