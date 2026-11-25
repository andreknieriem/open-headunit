package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHotspotRunTest {

    private fun usedRun() = LocalHotspotRun().apply {
        generation = 4
        reservation = Any()
        startInFlight = true
        bandMask = 2
        liveFrequencyMhz = 5785
        notAppliedLogged = true
    }

    @Test
    fun `a used run equals a fresh run of the next generation after reset`() {
        val run = usedRun()
        assertNotEquals(LocalHotspotRun(), run)
        run.reset()
        assertEquals(LocalHotspotRun(generation = 5), run)
    }

    @Test
    fun `a second run after a stop sees none of the first`() {
        val run = usedRun()
        run.reset()
        assertEquals(null, run.reservation)
        assertEquals(false, run.notAppliedLogged)
    }

    @Test
    fun `a generation from before a reset is stale after the next launch`() {
        val run = usedRun()
        val old = run.generation
        run.reset()
        run.generation++
        assertNotEquals(old, run.generation)
        assertFalse(run.finish(old))
    }

    @Test
    fun `finish with a stale generation leaves the reservation and the in-flight mark`() {
        val run = usedRun()
        assertFalse(run.finish(run.generation - 1))
        assertNotNull(run.reservation)
        assertTrue(run.startInFlight)
    }

    @Test
    fun `a live frequency is taken once, and never from an older run`() {
        val run = usedRun()
        assertFalse(run.noteLive(run.generation, 5785))
        assertFalse(run.noteLive(run.generation - 1, 2437))
        assertEquals(5785, run.liveFrequencyMhz)
        assertTrue(run.noteLive(run.generation, 2437))
        assertEquals(2437, run.liveFrequencyMhz)
    }

    @Test
    fun `finish with the current generation clears both`() {
        val run = usedRun()
        assertTrue(run.finish(run.generation))
        assertEquals(0, run.bandMask)
        assertEquals(0, run.liveFrequencyMhz)
        assertNull(run.reservation)
        assertFalse(run.startInFlight)
    }
}
