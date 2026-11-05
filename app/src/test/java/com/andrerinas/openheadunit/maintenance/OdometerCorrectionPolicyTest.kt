package com.andrerinas.openheadunit.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OdometerCorrectionPolicyTest {

    @Test
    fun `enough distance works out the ratio between the dashboard and the GPS`() {
        val result = OdometerCorrectionPolicy.correct(1.0, 100_000, 60_000.0, 163_000)
        assertTrue(result.updated)
        assertFalse(result.clamped)
        assertTrue(result.restartStretch)
        assertEquals(1.05, result.factor, 1e-9)
    }

    @Test
    fun `a ratio outside the range is clamped and says so`() {
        val high = OdometerCorrectionPolicy.correct(1.0, 0, 50_000.0, 80_000)
        assertEquals(OdometerCorrectionPolicy.MAX_FACTOR, high.factor, 0.0)
        assertTrue(high.clamped)
        val low = OdometerCorrectionPolicy.correct(1.0, 0, 100_000.0, 50_000)
        assertEquals(OdometerCorrectionPolicy.MIN_FACTOR, low.factor, 0.0)
        assertTrue(low.clamped)
    }

    @Test
    fun `a short stretch keeps the factor and the base so it can keep building up`() {
        val result = OdometerCorrectionPolicy.correct(1.03, 100_000, 10_000.0, 110_500)
        assertFalse(result.updated)
        assertFalse(result.restartStretch)
        assertEquals(1.03, result.factor, 0.0)
    }

    @Test
    fun `no base starts a new stretch without touching the factor`() {
        val result = OdometerCorrectionPolicy.correct(0.97, null, 90_000.0, 200_000)
        assertFalse(result.updated)
        assertTrue(result.restartStretch)
        assertEquals(0.97, result.factor, 0.0)
    }

    @Test
    fun `a number below the base means the base was wrong, so it starts over`() {
        val result = OdometerCorrectionPolicy.correct(1.0, 100_000, 60_000.0, 90_000)
        assertFalse(result.updated)
        assertTrue(result.restartStretch)
    }

    @Test
    fun `a stored factor is kept inside the range`() {
        assertEquals(1.0, OdometerCorrectionPolicy.sanitize(Double.NaN), 0.0)
        assertEquals(1.0, OdometerCorrectionPolicy.sanitize(0.0), 0.0)
        assertEquals(1.1, OdometerCorrectionPolicy.sanitize(3.0), 0.0)
        assertEquals(0.9, OdometerCorrectionPolicy.sanitize(0.5), 0.0)
        assertEquals(1.02, OdometerCorrectionPolicy.sanitize(1.02), 0.0)
    }
}
