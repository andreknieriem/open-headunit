package com.andrerinas.openheadunit.maintenance

import com.andrerinas.openheadunit.maintenance.CanBoxDistancePolicy.Anchor
import com.andrerinas.openheadunit.maintenance.CanBoxDistancePolicy.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CanBoxDistancePolicyTest {

    private val wall = 1_700_000_000_000L
    private val hour = 3_600_000L

    private fun anchor(a: Long?, b: Long?, miles: Boolean = false, gps: Double = 0.0, at: Long = wall) =
        Anchor(a, b, miles, gps, at)

    private fun reading(a: Long?, b: Long?, miles: Boolean = false) = TripReading(a, b, miles)

    @Test
    fun `the first reading becomes the anchor and adds nothing`() {
        val step = CanBoxDistancePolicy.step(null, reading(5871, 2245), null, wall)
        assertEquals(Kind.FIRST, step.kind)
        assertEquals(0.0, step.addMeters, 0.0)
        assertEquals(5871L, step.anchor.tripATenths)
        assertFalse(step.coversGap)
    }

    @Test
    fun `TRIP A moving forward is counted in tenths of a km`() {
        val step = CanBoxDistancePolicy.step(anchor(5871, 2245), reading(5873, 2247), 30_000, wall)
        assertEquals(Kind.ADDED, step.kind)
        assertEquals(200.0, step.addMeters, 1e-9)
        assertTrue(step.coversGap)
    }

    @Test
    fun `miles count as miles`() {
        val step = CanBoxDistancePolicy.step(anchor(100, null, miles = true), reading(110, null, miles = true), 60_000, wall)
        assertEquals(1_609.344, step.addMeters, 1e-6)
    }

    @Test
    fun `the drive while the app was closed is counted from the anchor of the last run`() {
        // 578.6 km when the app closed, 587.1 km two hours later.
        val step = CanBoxDistancePolicy.step(anchor(5786, 2161, at = wall), reading(5871, 2245), null, wall + 2 * hour)
        assertEquals(Kind.ADDED, step.kind)
        assertEquals(8_500.0, step.addMeters, 1e-6)
        assertTrue(step.coversGap)
    }

    @Test
    fun `an unchanged reading keeps the anchor itself`() {
        val start = anchor(5871, 2245, gps = 120.0)
        val step = CanBoxDistancePolicy.step(start, reading(5871, 2245), 5_000, wall + 5_000)
        assertEquals(Kind.SAME, step.kind)
        assertSame(start, step.anchor)
        assertTrue(step.coversGap)
    }

    @Test
    fun `what the GPS added while the box was silent is taken off`() {
        val step = CanBoxDistancePolicy.step(anchor(5871, null, gps = 1_250.0), reading(5891, null), 120_000, wall)
        assertEquals(2_000.0, step.boxMeters, 1e-9)
        assertEquals(750.0, step.addMeters, 1e-9)
        assertEquals(0.0, step.anchor.gpsMetersSince, 0.0)
    }

    @Test
    fun `the GPS having counted more than the box never makes the odometer go back`() {
        val step = CanBoxDistancePolicy.step(anchor(5871, null, gps = 900.0), reading(5872, null), 60_000, wall)
        assertEquals(0.0, step.addMeters, 0.0)
    }

    @Test
    fun `a reset TRIP A is measured by TRIP B`() {
        val step = CanBoxDistancePolicy.step(anchor(5871, 2245), reading(3, 2250), 60_000, wall)
        assertEquals(Kind.COUNTED_BY_OTHER, step.kind)
        assertEquals(500.0, step.addMeters, 1e-9)
        assertTrue(step.coversGap)
    }

    @Test
    fun `a reset TRIP B with TRIP A moving forward is plain counting`() {
        val step = CanBoxDistancePolicy.step(anchor(5871, 2245), reading(5874, 1), 60_000, wall)
        assertEquals(Kind.ADDED, step.kind)
        assertEquals(300.0, step.addMeters, 1e-9)
    }

    @Test
    fun `both counters reset only add the distance since the reset and do not cover the gap`() {
        val step = CanBoxDistancePolicy.step(anchor(5871, 2245, at = wall), reading(12, 40), null, wall + hour)
        assertEquals(Kind.RESET, step.kind)
        assertEquals(4_000.0, step.addMeters, 1e-9)
        assertFalse(step.coversGap)
    }

    @Test
    fun `a single counter that resets counts its new value and nothing before it`() {
        val step = CanBoxDistancePolicy.step(anchor(9998, null), reading(2, null), 60_000, wall)
        assertEquals(Kind.RESET, step.kind)
        assertEquals(200.0, step.addMeters, 1e-9)
    }

    @Test
    fun `a change of units starts over without counting`() {
        val step = CanBoxDistancePolicy.step(anchor(5871, null), reading(3648, null, miles = true), 1_000, wall)
        assertEquals(Kind.UNIT_CHANGED, step.kind)
        assertEquals(0.0, step.addMeters, 0.0)
        assertTrue(step.anchor.miles)
    }

    @Test
    fun `a jump no car makes between two readings is not counted`() {
        // 5 km in two seconds.
        val step = CanBoxDistancePolicy.step(anchor(5871, null), reading(5921, null), 2_000, wall)
        assertEquals(Kind.IMPLAUSIBLE, step.kind)
        assertEquals(0.0, step.addMeters, 0.0)
        assertEquals(5921L, step.anchor.tripATenths)
        assertFalse(step.coversGap)
    }

    @Test
    fun `the gap since the last run is bounded by the time it lasted`() {
        // 300 km in one hour while closed.
        val step = CanBoxDistancePolicy.step(anchor(5000, null, at = wall), reading(8000, null), null, wall + hour)
        assertEquals(Kind.IMPLAUSIBLE, step.kind)
        // 150 km in that hour is believable.
        val ok = CanBoxDistancePolicy.step(anchor(5000, null, at = wall), reading(6500, null), null, wall + hour)
        assertEquals(Kind.ADDED, ok.kind)
    }

    @Test
    fun `a clock that went back still bounds the gap, just loosely`() {
        val ok = CanBoxDistancePolicy.step(anchor(5000, null, at = wall), reading(6500, null), null, wall - hour)
        assertEquals(Kind.ADDED, ok.kind)
        val huge = CanBoxDistancePolicy.step(anchor(0, null, at = wall), reading(11_000_000, null), null, wall - hour)
        assertEquals(Kind.IMPLAUSIBLE, huge.kind)
    }

    @Test
    fun `the counter that was there going missing starts over`() {
        val step = CanBoxDistancePolicy.step(anchor(5871, null), reading(null, 2245), 1_000, wall)
        assertEquals(Kind.FIRST, step.kind)
        assertEquals(0.0, step.addMeters, 0.0)
    }

    @Test
    fun `the anchor survives the settings and a damaged value reads as none`() {
        val start = anchor(5871, null, miles = true, gps = 12.5, at = wall)
        assertEquals(start, CanBoxDistancePolicy.decode(CanBoxDistancePolicy.encode(start)))
        assertEquals("", CanBoxDistancePolicy.encode(null))
        assertNull(CanBoxDistancePolicy.decode(""))
        assertNull(CanBoxDistancePolicy.decode("x,,0,0.0,1"))
        assertNull(CanBoxDistancePolicy.decode(",,0,0.0,1"))
        assertNull(CanBoxDistancePolicy.decode("1,2,3,0.0,1"))
        assertNull(CanBoxDistancePolicy.decode("1,2,0,-5.0,1"))
    }
}
