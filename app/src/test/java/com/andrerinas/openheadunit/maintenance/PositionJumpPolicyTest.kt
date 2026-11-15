package com.andrerinas.openheadunit.maintenance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PositionJumpPolicyTest {

    @Test
    fun `no previous position has nothing to compare`() {
        assertNull(PositionJumpPolicy.missedMeters(null, null, 10.0, 20.0))
    }

    @Test
    fun `parking a little further down the street is not a jump`() {
        assertNull(PositionJumpPolicy.missedMeters(10.0, 20.0, 10.01, 20.0))
    }

    @Test
    fun `waking up far away reports the straight line`() {
        assertEquals(5_560.0, PositionJumpPolicy.missedMeters(10.0, 20.0, 10.05, 20.0)!!, 10.0)
    }
}
