package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class AacProgressWatchdogTest {
    @Test fun `repeated input timeouts and Stop cannot hide unsubmitted work`() {
        val watchdog = AacProgressWatchdog()
        for (now in 0L..800L step 200) {
            watchdog.inputWaiting(now)
            assertFalse(watchdog.stalled(now, true, true))
        }
        assertTrue(watchdog.stalled(1000, true, true))
    }
    @Test fun `output copy counts before a delayed bank handoff`() {
        val watchdog = AacProgressWatchdog()
        watchdog.inputAccepted(0)
        assertFalse(watchdog.stalled(999, false, false))
        watchdog.outputCopied()
        assertFalse(watchdog.stalled(10000, false, false))
    }
    @Test fun `accepting more input cannot hide a codec that never produces output`() {
        val watchdog = AacProgressWatchdog()
        for (now in 0L..1000L step 100) watchdog.inputAccepted(now)
        assertTrue(watchdog.stalled(1000, false, false))
        assertFalse(watchdog.stalled(1000, false, true))
        watchdog.inputWaiting(1000)
        assertTrue(watchdog.stalled(2000, true, true))
    }
    @Test fun `idle and recreated codecs do not inherit an old progress deadline`() {
        val watchdog = AacProgressWatchdog()
        assertFalse(watchdog.stalled(10000, false, false))
        watchdog.inputWaiting(0)
        watchdog.reset()
        assertFalse(watchdog.stalled(10000, true, false))
    }

    @Test fun `a quiet closed tail does not expire just because the next Start arrives`() {
        val watchdog = AacProgressWatchdog()
        watchdog.inputAccepted(0)
        assertFalse(watchdog.stalled(100, false, true))
        assertFalse(watchdog.stalled(2000, false, false)) // Start without DATA
        watchdog.inputAccepted(2010)
        assertFalse(watchdog.stalled(2500, false, false))
        watchdog.outputCopied()
        assertFalse(watchdog.stalled(4000, false, false))
    }
}
