package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class OutputBufferStabilityTest {
    @Test fun `large native bursts retain adaptation headroom above the sixty millisecond floor`() {
        val policy = OutputBufferPolicy(48000, 960, 2880)
        assertEquals(2880, policy.update(0, 0))
        assertEquals(3840, policy.update(100, 1))
        assertEquals(4800, policy.update(200, 2))
        repeat(20) { policy.update(300L + it, 3 + it) }
        assertEquals(4800, policy.targetFrames)
    }

    @Test fun `small native bursts keep the existing sixty millisecond ceiling`() {
        val policy = OutputBufferPolicy(48000, 192, 576)
        policy.update(0, 0)
        repeat(100) { policy.update(100L + it, it + 1) }
        assertEquals(2880, policy.targetFrames)
    }

    @Test fun `recurring scheduler stalls stop causing underruns after a failed downsize`() {
        // Both backends share this controller. Model a device needing one extra output burst
        // every three seconds, after it has already had time to discover that requirement.
        for (burst in intArrayOf(192, 480, 960)) {
            val minimum = if (burst == 480) 960 else burst * 3
            val required = minimum + burst
            val policy = OutputBufferPolicy(48000, burst, minimum)
            var xruns = 0
            var afterWarmup = 0
            for (now in 0L..120_000L step 100) {
                if (now > 0 && now % 3000 == 0L && policy.targetFrames < required) xruns++
                policy.update(now, xruns)
                if (now == 30_000L) afterWarmup = xruns
            }
            assertEquals("burst=$burst must not repeatedly probe a depth that already failed", afterWarmup, xruns)
            assertEquals(required, policy.targetFrames)
        }
    }

    @Test fun `one isolated underrun can still recover the original low latency floor`() {
        val policy = OutputBufferPolicy(48000, 480)
        policy.update(0, 0)
        assertEquals(1440, policy.update(1000, 1))
        assertEquals(960, policy.update(11_000, 1))
        assertEquals(960, policy.update(120_000, 1))
    }

    @Test fun `counter reset does not turn a later isolated stall into a failed probe`() {
        val policy = OutputBufferPolicy(48000, 480)
        policy.update(0, 4)
        policy.update(1000, 5)
        assertEquals(960, policy.update(11_000, 5))
        policy.update(11_100, 0)
        assertEquals(1440, policy.update(12_000, 1))
        assertEquals(960, policy.update(22_000, 1))
    }

    @Test fun `a learned floor belongs only to the current output instance`() {
        val policy = OutputBufferPolicy(48000, 480)
        policy.update(0, 0)
        policy.update(1000, 1)
        policy.update(11_000, 1)
        policy.update(12_000, 2)
        assertEquals(1440, policy.update(22_000, 2))
        assertEquals(960, OutputBufferPolicy(48000, 480).update(22_000, 0))
    }
}
