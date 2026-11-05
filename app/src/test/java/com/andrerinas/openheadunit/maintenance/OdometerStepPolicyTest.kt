package com.andrerinas.openheadunit.maintenance

import com.andrerinas.openheadunit.maintenance.OdometerStepPolicy.Fix
import com.andrerinas.openheadunit.maintenance.OdometerStepPolicy.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OdometerStepPolicyTest {

    // About 111 m of latitude per 0.001 degree.
    private fun fix(latOffset: Double, atMs: Long, accuracy: Float? = 5f, speed: Float? = 10f) =
        Fix(10.0 + latOffset, 20.0, atMs, accuracy, speed)

    @Test
    fun `the first usable fix becomes the reference and adds nothing`() {
        val first = fix(0.0, 0)
        val step = OdometerStepPolicy.step(null, first)
        assertEquals(Kind.FIRST, step.kind)
        assertEquals(0.0, step.addMeters, 0.0)
        assertSame(first, step.reference)
    }

    @Test
    fun `a fix with poor or unknown accuracy is skipped and keeps the reference`() {
        val reference = fix(0.0, 0)
        assertEquals(Kind.INACCURATE, OdometerStepPolicy.step(reference, fix(0.001, 1000, accuracy = 40f)).kind)
        val unknown = OdometerStepPolicy.step(reference, fix(0.001, 1000, accuracy = null))
        assertEquals(Kind.INACCURATE, unknown.kind)
        assertSame(reference, unknown.reference)
    }

    @Test
    fun `driving adds the distance between fixes`() {
        val step = OdometerStepPolicy.step(fix(0.0, 0), fix(0.0002, 1000))
        assertEquals(Kind.ADDED, step.kind)
        assertEquals(22.2, step.addMeters, 0.5)
    }

    @Test
    fun `drift while parked is ignored and the reference time moves on`() {
        val reference = fix(0.0, 0)
        val step = OdometerStepPolicy.step(reference, fix(0.00008, 1000, speed = 0.1f))
        assertEquals(Kind.STILL, step.kind)
        assertEquals(0.0, step.addMeters, 0.0)
        assertEquals(1000L, step.reference!!.atMs)
        assertEquals(reference.latitude, step.reference!!.latitude, 0.0)
    }

    @Test
    fun `a short move inside the fix error waits instead of counting`() {
        val step = OdometerStepPolicy.step(fix(0.0, 0, accuracy = 20f), fix(0.0001, 1000, accuracy = 20f))
        assertEquals(Kind.WAITING, step.kind)
        assertEquals(0.0, step.addMeters, 0.0)
    }

    @Test
    fun `an impossible speed between two fixes is a jump and adds nothing`() {
        val step = OdometerStepPolicy.step(fix(0.0, 0), fix(0.01, 1000))
        assertEquals(Kind.JUMP, step.kind)
        assertEquals(0.0, step.addMeters, 0.0)
    }

    @Test
    fun `a gap at a believable speed is bridged with the straight line`() {
        // About 1.1 km in 60 s, 67 km/h.
        val step = OdometerStepPolicy.step(fix(0.0, 0), fix(0.01, 60_000))
        assertEquals(Kind.BRIDGED, step.kind)
        assertEquals(1112.0, step.addMeters, 5.0)
    }

    @Test
    fun `a gap at an impossible average speed is not bridged`() {
        // About 11 km in 60 s.
        assertEquals(Kind.JUMP, OdometerStepPolicy.step(fix(0.0, 0), fix(0.1, 60_000)).kind)
    }

    @Test
    fun `a gap longer than the bridge limit restarts without adding`() {
        val step = OdometerStepPolicy.step(fix(0.0, 0), fix(0.01, OdometerStepPolicy.MAX_BRIDGE_MS + 1))
        assertEquals(Kind.JUMP, step.kind)
        assertEquals(0.0, step.addMeters, 0.0)
    }

    @Test
    fun `a long wait in traffic is not mistaken for a lost signal`() {
        var reference: Fix? = fix(0.0, 0)
        for (second in 1..60) {
            reference = OdometerStepPolicy.step(reference, fix(0.0, second * 1000L, speed = 0f)).reference
        }
        val moving = OdometerStepPolicy.step(reference, fix(0.0002, 61_000))
        assertEquals(Kind.ADDED, moving.kind)
    }

    @Test
    fun `a fix out of order waits`() {
        assertEquals(Kind.WAITING, OdometerStepPolicy.step(fix(0.0, 5000), fix(0.001, 4000)).kind)
    }

    @Test
    fun `the distance formula matches a known great circle`() {
        // One degree of latitude is about 111.2 km on a spherical Earth.
        assertEquals(111_195.0, OdometerStepPolicy.distanceMeters(0.0, 0.0, 1.0, 0.0), 1.0)
    }
}
