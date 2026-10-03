package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ZbtDaemonReachabilityTest {

    private var dials = 0

    @Before
    fun clear() {
        ZbtDaemonReachability.forget()
        ZbtDaemonReachability.setCarrierLive(false)
        ZbtDaemonReachability.setCarrierWantsClient(false)
        dials = 0
    }

    @After
    fun leaveNothingBehind() {
        ZbtDaemonReachability.forget()
        ZbtDaemonReachability.setCarrierLive(false)
        ZbtDaemonReachability.setCarrierWantsClient(false)
    }

    private fun dial(answer: Boolean): () -> Boolean = { dials++; answer }

    @Test
    fun `nothing is known before anything is asked`() {
        assertNull(ZbtDaemonReachability.cached(nowMs = 1_000L))
    }

    @Test
    fun `a fresh answer is dialled once and then read`() {
        assertTrue(ZbtDaemonReachability.resolve({ 1_000L }, dial(true)))
        assertTrue(ZbtDaemonReachability.resolve({ 1_000L }, dial(true)))
        assertEquals(1, dials)
        assertTrue(ZbtDaemonReachability.cached(nowMs = 1_000L) == true)
    }

    @Test
    fun `a refusal is remembered like an answer`() {
        assertFalse(ZbtDaemonReachability.resolve({ 1_000L }, dial(false)))
        assertFalse(ZbtDaemonReachability.resolve({ 1_000L }, dial(false)))
        assertEquals(1, dials)
    }

    @Test
    fun `a stale answer is measured again`() {
        // A daemon that was down at app start can be up by the time the user tries again.
        ZbtDaemonReachability.resolve({ 1_000L }, dial(false))
        val later = 1_000L + ZbtDaemonReachability.RECHECK_AFTER_MS
        assertNull(ZbtDaemonReachability.cached(nowMs = later))
        assertTrue(ZbtDaemonReachability.resolve({ later }, dial(true)))
        assertEquals(2, dials)
    }

    @Test
    fun `what the carrier saw replaces what the dial predicted`() {
        ZbtDaemonReachability.resolve({ 1_000L }, dial(false))
        ZbtDaemonReachability.record(true, nowMs = 1_500L)
        assertTrue(ZbtDaemonReachability.cached(nowMs = 1_500L) == true)
        assertTrue(ZbtDaemonReachability.resolve({ 1_500L }, dial(false)))
        assertEquals(1, dials)
    }

    @Test
    fun `forgetting re-arms the measurement`() {
        ZbtDaemonReachability.resolve({ 1_000L }, dial(true))
        ZbtDaemonReachability.forget()
        assertNull(ZbtDaemonReachability.cached(nowMs = 1_000L))
        ZbtDaemonReachability.resolve({ 1_000L }, dial(true))
        assertEquals(2, dials)
    }

    @Test
    fun `a live carrier answers without dialling`() {
        // The daemon serves one client, so a dial beside our own session would measure silence and
        // cache it as a refusal for ten minutes.
        assertTrue(ZbtDaemonReachability.resolve({ 1_000L }, dial(false), carrierLive = { true }))
        assertEquals(0, dials)
    }

    @Test
    fun `a live carrier does not overwrite what was measured`() {
        assertFalse(ZbtDaemonReachability.resolve({ 1_000L }, dial(false)))
        assertTrue(ZbtDaemonReachability.resolve({ 1_000L }, dial(false), carrierLive = { true }))
        assertFalse(ZbtDaemonReachability.cached(nowMs = 1_000L) == true)
    }

    @Test
    fun `the carrier flag tracks what it is set to`() {
        assertFalse(ZbtDaemonReachability.carrierLive())
        ZbtDaemonReachability.setCarrierLive(true)
        assertTrue(ZbtDaemonReachability.carrierLive())
        ZbtDaemonReachability.setCarrierLive(false)
        assertFalse(ZbtDaemonReachability.carrierLive())
    }

    @Test
    fun `wanting the slot is tracked apart from holding it`() {
        // A carrier between reopen attempts holds nothing and still needs the slot back, which is
        // the whole window a probe used to be able to sit in.
        assertFalse(ZbtDaemonReachability.carrierWantsClient())
        ZbtDaemonReachability.setCarrierWantsClient(true)
        assertTrue(ZbtDaemonReachability.carrierWantsClient())
        assertFalse(ZbtDaemonReachability.carrierLive())
        ZbtDaemonReachability.setCarrierWantsClient(false)
        assertFalse(ZbtDaemonReachability.carrierWantsClient())
    }

    private var clock = 0L
    private val dialTimes = mutableListOf<Long>()

    private fun scripted(vararg answers: Boolean): () -> Boolean {
        var i = 0
        return { dialTimes.add(clock); dials++; answers[minOf(i++, answers.size - 1)] }
    }

    private fun retrying(
        dial: () -> Boolean,
        carrierLive: () -> Boolean = { false },
        keepTrying: () -> Boolean = { true }
    ) = ZbtDaemonReachability.resolve(
        nowMs = { clock },
        dial = dial,
        carrierLive = carrierLive,
        retryRefusals = true,
        sleep = { clock += it },
        keepTrying = keepTrying
    )

    @Test
    fun `a daemon back after two refusals is taken and cached`() {
        assertTrue(retrying(scripted(false, false, true)))
        assertEquals(3, dials)
        assertTrue(ZbtDaemonReachability.cached(nowMs = clock) == true)
    }

    @Test
    fun `a refusal then a silent daemon still takes the route`() {
        // The silent verdict is reported as reachable by the dial, so the loop ends on it.
        assertTrue(retrying(scripted(false, true)))
        assertEquals(2, dials)
    }

    @Test
    fun `a daemon that never listens is dialled across the window and then ruled out`() {
        assertFalse(retrying(scripted(false)))
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 15_000L, 23_000L, 30_000L), dialTimes)
        assertFalse(ZbtDaemonReachability.cached(nowMs = clock) == true)
        assertTrue(ZbtDaemonReachability.cached(nowMs = clock) == false)
        assertFalse(retrying(scripted(true)))
        assertEquals(7, dials)
    }

    @Test
    fun `abandoning the window records nothing`() {
        var calls = 0
        assertFalse(retrying(scripted(false), keepTrying = { calls++ < 3 }))
        assertNull(ZbtDaemonReachability.cached(nowMs = clock))
    }

    @Test
    fun `a caller already stopped never dials`() {
        assertFalse(retrying(scripted(false), keepTrying = { false }))
        assertEquals(0, dials)
        assertNull(ZbtDaemonReachability.cached(nowMs = clock))
    }

    @Test
    fun `a carrier going live mid window ends it as an answer`() {
        var live = false
        val dial = { dials++; live = dials == 2; false }
        assertTrue(retrying(dial, carrierLive = { live }))
        assertEquals(2, dials)
    }

    @Test
    fun `the one-shot default dials once and caches the refusal`() {
        assertFalse(ZbtDaemonReachability.resolve({ 1_000L }, dial(false)))
        assertEquals(1, dials)
        assertTrue(ZbtDaemonReachability.cached(nowMs = 1_000L) == false)
    }

    @Test
    fun `the answer's age is null before one and measured after`() {
        assertNull(ZbtDaemonReachability.answerAgeMs(5_000L))
        ZbtDaemonReachability.record(false, nowMs = 2_000L)
        assertEquals(3_000L, ZbtDaemonReachability.answerAgeMs(5_000L))
    }
}
