package com.andrerinas.openheadunit.connection.carplaybox

import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayBoxExclusionPolicyTest {

    @Test
    fun `Android Auto waits for a live box session`() {
        assertTrue(CarPlayBoxExclusionPolicy.refusesAa(true))
        assertFalse(CarPlayBoxExclusionPolicy.refusesAa(false))
    }

    @Test
    fun `a box refuses beside a live box or Android Auto session`() {
        assertTrue(CarPlayBoxExclusionPolicy.refusesBox(true, false, null))
        assertTrue(CarPlayBoxExclusionPolicy.refusesBox(false, true, null))
    }

    @Test
    fun `a fresh holder blocks the box and a stale one does not`() {
        val stale = ConnectionPriorityPolicy.STALE_CLAIM_MS
        assertTrue(CarPlayBoxExclusionPolicy.refusesBox(false, false, 0L))
        assertTrue(CarPlayBoxExclusionPolicy.refusesBox(false, false, stale - 1))
        assertFalse(CarPlayBoxExclusionPolicy.refusesBox(false, false, stale))
        assertFalse(CarPlayBoxExclusionPolicy.refusesBox(false, false, null))
    }

    @Test
    fun `the refusal names its reason`() {
        assertEquals("a box session is live", CarPlayBoxExclusionPolicy.boxRefusal(true, true, 0L))
        assertEquals("an Android Auto session is live", CarPlayBoxExclusionPolicy.boxRefusal(false, true, 0L))
        assertEquals("an attempt is in flight", CarPlayBoxExclusionPolicy.boxRefusal(false, false, 5L))
        assertNull(CarPlayBoxExclusionPolicy.boxRefusal(false, false, null))
    }
}
