package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private val c = "00:11:22:33:44:55"
    private val now = 1_000_000L

    private fun plan(
        list: List<String> = emptyList(),
        onlyListed: Boolean = false,
        learned: List<String> = emptyList(),
        explicit: String? = null,
        linked: Set<String> = emptySet(),
        phones: List<String> = listOf(a, b),
        backedOff: Map<String, Long> = emptyMap()
    ) = WakePlanPolicy.plan(list, onlyListed, learned, explicit, linked, phones, backedOff, now)

    @Test
    fun `an empty Phones list wakes the learned phones first, most recent first, then every other phone`() {
        assertEquals(listOf(b, c, a), plan(learned = listOf(b, c), phones = listOf(a, b, c)).order)
    }

    @Test
    fun `the Phones list comes first in its order, then the learned phones, then the rest`() {
        assertEquals(listOf(c, a, b), plan(list = listOf(c), learned = listOf(a), phones = listOf(a, b, c)).order)
        assertEquals(listOf(b, a, c), plan(list = listOf(b, a), phones = listOf(a, b, c)).order)
    }

    @Test
    fun `with Only these phones on, only the listed phones are in the plan`() {
        assertEquals(listOf(b), plan(list = listOf(b), onlyListed = true, learned = listOf(a)).order)
    }

    @Test
    fun `Only these phones with an empty list still wakes every phone`() {
        assertEquals(listOf(a, b), plan(onlyListed = true).order)
    }

    @Test
    fun `a listed phone with no bond is skipped`() {
        assertEquals(listOf(a, b), plan(list = listOf(c, a)).order)
    }

    @Test
    fun `a linked phone goes first, and the others keep their order`() {
        assertEquals(listOf(c, a, b), plan(phones = listOf(a, b, c), linked = setOf(c)).order)
    }

    @Test
    fun `a backed-off phone goes behind the others until its 5 minutes end`() {
        assertEquals(listOf(b, a), plan(backedOff = mapOf(a to now + 1)).order)
        assertEquals(listOf(a, b), plan(backedOff = mapOf(a to now)).order)
    }

    @Test
    fun `a single phone that is backed off is still in the plan`() {
        assertEquals(listOf(a), plan(phones = listOf(a), backedOff = mapOf(a to now + 1)).order)
    }

    @Test
    fun `an explicit pick outside the list stays out with Only these phones on`() {
        assertEquals(listOf(a), plan(list = listOf(a), onlyListed = true, explicit = b).order)
    }

    @Test
    fun `an explicit pick in the list heads the plan with Only these phones on`() {
        assertEquals(listOf(b, a), plan(list = listOf(a, b), onlyListed = true, explicit = b).order)
    }

    @Test
    fun `an explicit pick outside the list heads the plan with Only these phones off`() {
        assertEquals(listOf(b, a), plan(list = listOf(a), onlyListed = false, explicit = b).order)
    }

    @Test
    fun `an explicit pick that is backed off goes behind the others`() {
        assertEquals(listOf(a, b), plan(explicit = b, backedOff = mapOf(b to now + 1)).order)
    }

    @Test
    fun `after A forms a session, B is still in the plan`() {
        assertEquals(listOf(a, b), plan(learned = listOf(a)).order)
    }

    @Test
    fun `known holds the listed, learned and linked phones that are bonded`() {
        val p = plan(list = listOf(c), learned = listOf(a), linked = setOf(b), phones = listOf(a, b, c))
        assertEquals(setOf(a, b, c), p.known)
        assertEquals(setOf(a), plan(learned = listOf(a)).known)
        assertEquals(emptySet<String>(), plan().known)
    }

    @Test
    fun `one connected phone is the pick, inside or outside the list`() {
        assertEquals(b, WakePlanPolicy.autoPick(plan(list = listOf(a), learned = listOf(a)), listOf(b)))
    }

    @Test
    fun `a linked phone outside the list is not the pick with Only these phones on`() {
        val p = plan(list = listOf(a), onlyListed = true, learned = listOf(a))
        assertEquals(a, WakePlanPolicy.autoPick(p, listOf(b)))
    }

    @Test
    fun `an unlisted link changes nothing about the pick`() {
        val p = plan(list = listOf(a), onlyListed = true)
        assertEquals(WakePlanPolicy.autoPick(p, emptyList()), WakePlanPolicy.autoPick(p, listOf(b)))
    }

    @Test
    fun `the one linked phone is the pick with Only these phones off`() {
        assertEquals(b, WakePlanPolicy.autoPick(plan(list = listOf(a), onlyListed = false), listOf(b)))
    }

    @Test
    fun `a linked phone in the list is the pick with Only these phones on`() {
        assertEquals(b, WakePlanPolicy.autoPick(plan(list = listOf(a, b), onlyListed = true), listOf(b)))
    }

    @Test
    fun `the first listed phone is the pick among several connected`() {
        val p = plan(list = listOf(b, a), linked = setOf(a, b))
        assertEquals(b, WakePlanPolicy.autoPick(p, listOf(a, b)))
    }

    @Test
    fun `two connected phones with nothing listed or learned pick nobody`() {
        assertNull(WakePlanPolicy.autoPick(plan(linked = setOf(a, b)), listOf(a, b)))
    }

    @Test
    fun `a learned phone is the pick among two connected phones`() {
        assertEquals(b, WakePlanPolicy.autoPick(plan(learned = listOf(b), linked = setOf(a, b)), listOf(a, b)))
    }

    @Test
    fun `chosen holds the listed and learned phones, not a phone that is only linked`() {
        val p = plan(list = listOf(c), learned = listOf(a), linked = setOf(b), phones = listOf(a, b, c))
        assertEquals(setOf(a, c), p.chosen)
    }

    @Test
    fun `the most recent learned phone is the pick when nothing is listed and none is connected`() {
        assertEquals(b, WakePlanPolicy.autoPick(plan(learned = listOf(b, a)), emptyList()))
    }

    @Test
    fun `a fresh install with two phones and none connected picks nobody`() {
        assertNull(WakePlanPolicy.autoPick(plan(), emptyList()))
    }

    @Test
    fun `a fresh install with one connected phone picks it`() {
        assertEquals(a, WakePlanPolicy.autoPick(plan(phones = listOf(a), linked = setOf(a)), listOf(a)))
    }

    @Test
    fun `a fresh install with one paired phone picks it`() {
        assertEquals(a, WakePlanPolicy.autoPick(plan(phones = listOf(a)), emptyList()))
    }

    @Test
    fun `no phone gives no pick`() {
        assertNull(WakePlanPolicy.autoPick(plan(phones = emptyList()), emptyList()))
    }

    @Test
    fun `a listed phone B outranks a learned phone A`() {
        assertEquals(b, WakePlanPolicy.autoPick(plan(list = listOf(b), learned = listOf(a)), emptyList()))
    }

    @Test
    fun `a backed-off first phone is not the pick while another known phone is not backed off`() {
        val p = plan(learned = listOf(a, b), backedOff = mapOf(a to now + 1))
        assertEquals(b, WakePlanPolicy.autoPick(p, emptyList()))
    }

    @Test
    fun `editing keeps the kept phones in order and adds new ones at the end`() {
        assertEquals(listOf(b, a, c), WakePlanPolicy.edited(listOf(b, a), setOf(c, a, b)))
    }

    @Test
    fun `editing removes the phones the user cleared`() {
        assertEquals(listOf(b), WakePlanPolicy.edited(listOf(b, a), setOf(b)))
        assertEquals(emptyList<String>(), WakePlanPolicy.edited(listOf(b, a), emptySet()))
    }

    @Test
    fun `a phone whose link closes with no TCP session is a failure`() {
        assertTrue(WakePlanPolicy.failedBeforeSession(a, a, abortedLocally = false, sessionUp = false, onOurNetwork = false))
    }

    @Test
    fun `a link that closes while the transport is still connecting is not a failure`() {
        assertFalse(WakePlanPolicy.failedBeforeSession(a, a, abortedLocally = false, sessionUp = true, onOurNetwork = false))
    }

    @Test
    fun `a phone whose link closes after its session formed is not a failure`() {
        assertFalse(WakePlanPolicy.failedBeforeSession(null, a, abortedLocally = false, sessionUp = false, onOurNetwork = false))
    }

    @Test
    fun `our own abort is not a failure`() {
        assertFalse(WakePlanPolicy.failedBeforeSession(a, a, abortedLocally = true, sessionUp = false, onOurNetwork = false))
    }

    @Test
    fun `a phone that reached our network is not backed off`() {
        assertFalse(WakePlanPolicy.failedBeforeSession(a, a, abortedLocally = false, sessionUp = false, onOurNetwork = true))
    }

    @Test
    fun `a link from another phone than the one awaiting is not this phone's failure`() {
        assertFalse(WakePlanPolicy.failedBeforeSession(a, b, abortedLocally = false, sessionUp = false, onOurNetwork = false))
    }

    @Test
    fun `an explicit pick is learned only when its own records say phone`() {
        assertTrue(WakePlanPolicy.learnsExplicitPick(DriverCandidatePolicy.Verdict.PHONE, fromPlan = false))
        assertFalse(WakePlanPolicy.learnsExplicitPick(DriverCandidatePolicy.Verdict.UNKNOWN, fromPlan = false))
        assertFalse(WakePlanPolicy.learnsExplicitPick(DriverCandidatePolicy.Verdict.NOT_A_PHONE, fromPlan = false))
    }

    @Test
    fun `a phone the wake plan picked is not learned, even when its records say phone`() {
        assertFalse(WakePlanPolicy.learnsExplicitPick(DriverCandidatePolicy.Verdict.PHONE, fromPlan = true))
    }

    @Test
    fun `the plan holds another phone only when one differs from the given address, case ignored`() {
        assertTrue(WakePlanPolicy.hasAnother(plan(phones = listOf(a, b)), a))
        assertFalse(WakePlanPolicy.hasAnother(plan(phones = listOf(a)), a.lowercase()))
        assertTrue(WakePlanPolicy.hasAnother(plan(list = listOf(b), onlyListed = true), a))
    }
}
