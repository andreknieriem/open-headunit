package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaintenanceChimeTest {

    private val tone = MaintenanceChime.buildTone()

    @Test
    fun `the chime is short stereo with headroom`() {
        assertEquals(0, tone.size % 2)
        assertTrue("too long: ${tone.size / 2} frames", tone.size / 2 < 48_000)
        val peak = tone.maxOf { Math.abs(it.toInt()) }
        assertTrue("peak $peak", peak in 3_000..20_000)
    }

    @Test
    fun `it starts and ends in silence, so it never clicks`() {
        assertEquals(0, tone[0].toInt())
        val tail = tone.copyOfRange(tone.size - 2 * 4_800, tone.size)
        assertTrue(tail.all { it.toInt() == 0 })
    }
}
