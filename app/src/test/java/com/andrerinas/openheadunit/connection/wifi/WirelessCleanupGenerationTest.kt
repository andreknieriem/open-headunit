package com.andrerinas.openheadunit.connection.wifi

import org.junit.Assert.*
import org.junit.Test

class WirelessCleanupGenerationTest {
    @Test fun `Start after terminal publication but before observer invalidates old cleanup`() {
        val generations = WirelessCleanupGeneration()
        val terminal = Any()
        // CommManager publishes D, explicit Start runs, then the Main observer processes D.
        generations.advance(terminal)
        assertNull(generations.captureFor(terminal))
        val laterTerminal = Any()
        assertNotNull(generations.captureFor(laterTerminal))
    }

    @Test fun `Start during teardown wait invalidates captured owner even on same launcher`() {
        val generations = WirelessCleanupGeneration()
        val terminal = Any()
        val ticket = generations.captureFor(terminal)!!
        generations.advance(terminal)
        assertFalse(generations.isCurrent(ticket))
    }

    @Test fun `Start while an earlier attempt is Connecting fences only that attempt's failed end`() {
        val generations = WirelessCleanupGeneration()
        val connecting = Any()
        // Self is admitted during Connecting; the attempt's terminal is created after the arm.
        generations.advance(attempt = connecting)
        assertNull(generations.captureFor(Any(), connecting, reachedPhysical = false))
        // An attempt that connected after the arm superseded it and keeps its own end policy.
        assertNotNull(generations.captureFor(Any(), connecting, reachedPhysical = true))
        // The arm's own later attempt, and ends without an attempt token, are unaffected.
        assertNotNull(generations.captureFor(Any(), Any(), reachedPhysical = false))
        assertNotNull(generations.captureFor(Any(), null, reachedPhysical = false))
        // A further arm with no attempt in flight retires the recorded one.
        generations.advance()
        assertNotNull(generations.captureFor(Any(), connecting, reachedPhysical = false))
    }

    @Test fun `ordinary terminal cleanup retains ownership until another arm`() {
        val generations = WirelessCleanupGeneration()
        generations.advance(Any())
        val ticket = generations.captureFor(Any())!!
        assertTrue(generations.isCurrent(ticket))
    }
}
