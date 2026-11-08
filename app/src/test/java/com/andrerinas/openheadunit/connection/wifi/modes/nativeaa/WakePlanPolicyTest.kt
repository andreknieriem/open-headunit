package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakePlanPolicyTest {

    private val a = "A0:46:5A:97:E4:95"
    private val b = "DC:B7:2E:5E:4E:59"

    @Test
    fun `a second phone that forms a session is added in front, not swapped in`() {
        assertEquals(listOf(b, a), WakePlanPolicy.movedToFront(listOf(a), b))
    }

    @Test
    fun `a phone learned again moves to the front with no duplicate, case ignored`() {
        assertEquals(listOf(a, b), WakePlanPolicy.movedToFront(listOf(b, a), a.lowercase()))
    }

    @Test
    fun `a handshake that ends before SSL learns nothing`() {
        assertNull(WakePlanPolicy.phoneToLearn(awaiting = null, wirelessSession = true))
    }

    @Test
    fun `a wired session learns nothing`() {
        assertNull(WakePlanPolicy.phoneToLearn(awaiting = a, wirelessSession = false))
    }

    @Test
    fun `a wireless session learns the awaiting phone`() {
        assertEquals(a, WakePlanPolicy.phoneToLearn(awaiting = a, wirelessSession = true))
    }

    @Test
    fun `the last connected phone seeds the learned phones once, and an empty one seeds nothing`() {
        assertEquals(listOf(a), WakePlanPolicy.seedLearned(stored = null, lastConnected = a))
        assertEquals(emptyList<String>(), WakePlanPolicy.seedLearned(stored = "", lastConnected = a))
        assertEquals(emptyList<String>(), WakePlanPolicy.seedLearned(stored = null, lastConnected = ""))
    }

    @Test
    fun `stored MACs come back normalised, without blanks or duplicates`() {
        val raw = " ${a.lowercase()},,$b,${a.replace(':', '-')} ,not-a-mac"
        assertEquals(listOf(a, b), WakePlanPolicy.parseMacs(raw))
        assertEquals("$a,$b", WakePlanPolicy.formatMacs(WakePlanPolicy.parseMacs(raw)))
    }
}
