package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ZbtDaemonReachabilityTest {

    private var dials = 0
    private val claims = mutableListOf<ZbtDaemonReachability.CarrierClaim>()
    private fun claim() = ZbtDaemonReachability.claimCarrier().also { claims.add(it) }

    @Before
    fun clear() {
        ZbtDaemonReachability.forget()
        claims.forEach { it.close() }
        claims.clear()
        dials = 0
    }

    @After
    fun leaveNothingBehind() {
        ZbtDaemonReachability.forget()
        claims.forEach { it.close() }
        claims.clear()
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
        val owner = claim()
        owner.setLive(true)
        assertTrue(ZbtDaemonReachability.carrierLive())
        owner.setLive(false)
        assertFalse(ZbtDaemonReachability.carrierLive())
    }

    @Test
    fun `wanting the slot is tracked apart from holding it`() {
        // A carrier between reopen attempts holds nothing and still needs the slot back, which is
        // the whole window a probe used to be able to sit in.
        assertFalse(ZbtDaemonReachability.carrierWantsClient())
        val owner = claim()
        assertTrue(ZbtDaemonReachability.carrierWantsClient())
        assertFalse(ZbtDaemonReachability.carrierLive())
        owner.close()
        assertFalse(ZbtDaemonReachability.carrierWantsClient())
    }

    @Test
    fun `late release from old carrier preserves a replacement's live claim`() {
        val old = claim().also { it.setLive(true) }
        val fresh = claim().also { it.setLive(true) }
        old.setLive(false)
        old.close()
        old.close()
        assertTrue(ZbtDaemonReachability.carrierWantsClient())
        assertTrue(ZbtDaemonReachability.carrierLive())
        fresh.close()
        assertFalse(ZbtDaemonReachability.carrierWantsClient())
        assertFalse(ZbtDaemonReachability.carrierLive())
        old.setLive(true)
        assertFalse(ZbtDaemonReachability.carrierLive())
    }

    @Test
    fun `old live and replacement pending retain separate ownership facts`() {
        val old = claim().also { it.setLive(true) }
        val fresh = claim()
        old.close()
        assertTrue(ZbtDaemonReachability.carrierWantsClient())
        assertFalse(ZbtDaemonReachability.carrierLive())
        fresh.setLive(true)
        assertTrue(ZbtDaemonReachability.carrierLive())
    }

    @Test
    fun `carrier claim retires observer before its init admission`() {
        val opening = ZbtDaemonReachability.observerOpening { true }!!
        var closed = false
        try {
            opening.attach(java.io.Closeable { closed = true })
            claim()
            assertTrue(closed)
            try {
                opening.admitInit()
                org.junit.Assert.fail("retired observer must not admit init")
            } catch (_: ZbtChannelOpening.Cancelled) { }
            assertNull(ZbtDaemonReachability.observerOpening { true })
        } finally {
            opening.close()
            ZbtDaemonReachability.releaseObserver(opening)
        }
    }

    @Test
    fun `pending carrier makes measurement defer without dial or cache`() {
        claim()
        assertTrue(ZbtDaemonReachability.resolve({ 1_000L }, dial(false)))
        assertEquals(0, dials)
        assertNull(ZbtDaemonReachability.cached(nowMs = 1_000L))
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
    @Test
    fun `a legacy reachable port does not identify an HCT bridge`() {
        ZbtDaemonReachability.record(true, 1000L)
        assertNull(ZbtDaemonReachability.cached(1000L, requireIdentity = true))
        assertFalse(ZbtDaemonReachability.resolve({ 1000L }, dial(false), requireIdentity = true))
        assertEquals(true, ZbtDaemonReachability.cached(1000L))
        assertEquals(false, ZbtDaemonReachability.cached(1000L, requireIdentity = true))
    }

    @Test
    fun `identified greetings are cached expired and forgotten separately`() {
        assertTrue(ZbtDaemonReachability.resolve({ 1000L }, dial(true), requireIdentity = true))
        assertTrue(ZbtDaemonReachability.resolve({ 1001L }, dial(false), requireIdentity = true))
        assertEquals(1, dials)
        assertNull(ZbtDaemonReachability.cached(1000L))
        val later = 1000L + ZbtDaemonReachability.RECHECK_AFTER_MS
        assertEquals(true, ZbtDaemonReachability.cached(later, requireIdentity = true))
        ZbtDaemonReachability.expireIdleIdentity(later)
        assertNull(ZbtDaemonReachability.cached(later, requireIdentity = true))
        ZbtDaemonReachability.forget()
        assertNull(ZbtDaemonReachability.cached(1000L, requireIdentity = true))
    }

    @Test
    fun `identified live carrier keeps its route after cache expiry`() {
        assertTrue(ZbtDaemonReachability.resolve({ 1000L }, dial(true), requireIdentity = true))
        val later = 1000L + ZbtDaemonReachability.RECHECK_AFTER_MS
        val owner = claim().also { it.setLive(true) }
        ZbtDaemonReachability.expireIdleIdentity(later)
        assertEquals(true, ZbtDaemonReachability.cached(later, requireIdentity = true))
        owner.setLive(false)
        ZbtDaemonReachability.expireIdleIdentity(later)
        assertEquals(true, ZbtDaemonReachability.cached(later, requireIdentity = true))
        owner.close()
        ZbtDaemonReachability.record(true, later)
        ZbtDaemonReachability.expireIdleIdentity(later)
        assertEquals(true, ZbtDaemonReachability.cached(later, requireIdentity = true))
    }

    @Test
    fun `live carrier without a greeting cannot populate identity cache`() {
        assertFalse(ZbtDaemonReachability.resolve({ 1000L }, dial(true),
            carrierLive = { true }, requireIdentity = true))
        assertEquals(0, dials)
        assertNull(ZbtDaemonReachability.cached(1000L, requireIdentity = true))
    }

    @Test
    fun `failed HCT detection keeps the Android button until a fresh arming`() {
        assertFalse(ZbtDaemonReachability.resolve({ 1000L }, dial(false), requireIdentity = true))
        val later = 1000L + ZbtDaemonReachability.RECHECK_AFTER_MS
        val cached = ZbtDaemonReachability.cached(later, requireIdentity = true)
        assertEquals(false, cached)
        assertEquals(com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalBtTransportPolicy.WifiButton.ANDROID_RADIO,
            com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalBtTransportPolicy.wifiButton(
                ZbtDiscoveryPolicy.Snapshot(ZbtDiscoveryPolicy.Decision(ZbtDiscoveryPolicy.Mode.IDENTITY, "HCT"), cached),
                false,
                false
            ))
        ZbtDaemonReachability.expireIdleIdentity(later)
        assertNull(ZbtDaemonReachability.cached(later, requireIdentity = true))
        assertTrue(ZbtDaemonReachability.resolve({ later }, dial(true), requireIdentity = true))
    }

    @Test
    fun `HCT boot refusals retry until greeting is identified`() {
        val outcomes = ArrayDeque(listOf(ZbtIdentityProbe.Outcome.REFUSED,
            ZbtIdentityProbe.Outcome.REFUSED, ZbtIdentityProbe.Outcome.IDENTIFIED))
        assertTrue(ZbtDaemonReachability.resolve(nowMs = { clock }, requireIdentity = true,
            retryRefusals = true, sleep = { clock += it }, identityDial = {
                dials++; outcomes.removeFirst()
            }))
        assertEquals(3, dials)
        assertEquals(3000L, clock)
        assertEquals(true, ZbtDaemonReachability.cached(clock, requireIdentity = true))
    }

    @Test
    fun `HCT silent or malformed peer is not retried like a boot refusal`() {
        assertFalse(ZbtDaemonReachability.resolve(nowMs = { clock }, requireIdentity = true,
            retryRefusals = true, sleep = { clock += it }, identityDial = {
                dials++; ZbtIdentityProbe.Outcome.UNCONFIRMED
            }))
        assertEquals(1, dials)
        assertEquals(0L, clock)
    }

    @Test
    fun `HCT cancellation during refusal backoff stores no answer`() {
        var cancelled = false
        assertFalse(ZbtDaemonReachability.resolve(nowMs = { clock }, requireIdentity = true,
            retryRefusals = true, sleep = { clock += it; cancelled = true },
            keepTrying = { !cancelled }, identityDial = { ZbtIdentityProbe.Outcome.REFUSED }))
        assertNull(ZbtDaemonReachability.cached(clock, requireIdentity = true))
    }

    @Test
    fun `HCT probe yields to reconnecting carrier without caching a refusal`() {
        claim()
        assertFalse(ZbtDaemonReachability.resolve({ 1000L }, dial(true), requireIdentity = true))
        assertEquals(0, dials)
        assertNull(ZbtDaemonReachability.cached(1000L, requireIdentity = true))
    }

    @Test
    fun `HCT absent daemon gets only a short boot grace period`() {
        assertFalse(ZbtDaemonReachability.resolve(nowMs = { clock }, requireIdentity = true,
            retryRefusals = true, sleep = { clock += it }, identityDial = {
                dialTimes.add(clock); ZbtIdentityProbe.Outcome.REFUSED
            }))
        assertEquals(listOf(0L, 1000L, 3000L), dialTimes)
    }

    @Test
    fun `HCT refusal outside boot falls back after a single dial`() {
        clock = 120000L
        assertFalse(ZbtDaemonReachability.resolve(nowMs = { clock }, requireIdentity = true,
            retryRefusals = true, sleep = { fail("outside boot must not wait") }, identityDial = {
                dials++; ZbtIdentityProbe.Outcome.REFUSED
            }))
        assertEquals(1, dials)
        assertEquals(false, ZbtDaemonReachability.cached(clock, requireIdentity = true))
    }

    @Test
    fun `settings refusal leaves arming free to try the daemon again`() {
        assertFalse(ZbtDaemonReachability.resolve(nowMs = { clock }, requireIdentity = true,
            cacheIdentityRefusal = false, identityDial = { ZbtIdentityProbe.Outcome.REFUSED }))
        assertNull(ZbtDaemonReachability.cached(clock, requireIdentity = true))
        assertTrue(ZbtDaemonReachability.resolve(nowMs = { clock }, requireIdentity = true,
            identityDial = { ZbtIdentityProbe.Outcome.IDENTIFIED }))
    }

    @Test
    fun `refused candidate stays settled until a fresh idle arming then can recover`() {
        clock = 200_000L // Beyond the boot-only retry window, as after ACC resume.
        assertFalse(ZbtDaemonReachability.resolve(nowMs = { clock }, requireIdentity = true,
            identityDial = { dials++; ZbtIdentityProbe.Outcome.REFUSED }))
        clock++
        // Neither a UI read nor another resolve may change the running Android fallback.
        assertEquals(false, ZbtDaemonReachability.cached(clock, requireIdentity = true))
        assertFalse(ZbtDaemonReachability.resolve(nowMs = { clock }, requireIdentity = true,
            identityDial = { fail("same arming must not redial"); ZbtIdentityProbe.Outcome.IDENTIFIED }))
        assertEquals(1, dials)

        ZbtDaemonReachability.expireIdleIdentity(clock)
        assertNull(ZbtDaemonReachability.cached(clock, requireIdentity = true))
        assertTrue(ZbtDaemonReachability.resolve(nowMs = { clock }, requireIdentity = true,
            identityDial = { dials++; ZbtIdentityProbe.Outcome.IDENTIFIED }))
        assertEquals(2, dials)
    }

    @Test
    fun `rearming does not repeatedly probe a silent or incompatible listener`() {
        clock = 200_000L
        assertFalse(ZbtDaemonReachability.resolve(nowMs = { clock }, requireIdentity = true,
            identityDial = { ZbtIdentityProbe.Outcome.UNCONFIRMED }))
        ZbtDaemonReachability.expireIdleIdentity(clock + 1)
        assertEquals(false, ZbtDaemonReachability.cached(clock + 1, requireIdentity = true))
        assertFalse(ZbtDaemonReachability.resolve(nowMs = { clock + 1 }, requireIdentity = true,
            identityDial = { fail("unconfirmed listener keeps its cooldown"); ZbtIdentityProbe.Outcome.IDENTIFIED }))
        ZbtDaemonReachability.expireIdleIdentity(clock + ZbtDaemonReachability.RECHECK_AFTER_MS)
        assertNull(ZbtDaemonReachability.cached(clock, requireIdentity = true))
    }

    @Test
    fun `carrier ownership protects a refusal even at a fresh arming`() {
        clock = 200_000L
        assertFalse(ZbtDaemonReachability.resolve(nowMs = { clock }, requireIdentity = true,
            identityDial = { ZbtIdentityProbe.Outcome.REFUSED }))
        val owner = claim().also { it.setLive(true) }
        ZbtDaemonReachability.expireIdleIdentity(clock + 1)
        assertEquals(false, ZbtDaemonReachability.cached(clock, requireIdentity = true))
        owner.setLive(false)
        ZbtDaemonReachability.expireIdleIdentity(clock + 1)
        assertEquals(false, ZbtDaemonReachability.cached(clock, requireIdentity = true))
        owner.close()
        ZbtDaemonReachability.expireIdleIdentity(clock + 1)
        assertNull(ZbtDaemonReachability.cached(clock, requireIdentity = true))
    }

    @Test
    fun `a newer identity outcome replaces an earlier refusal`() {
        clock = 200_000L
        assertFalse(ZbtDaemonReachability.resolve(nowMs = { clock }, requireIdentity = true,
            identityDial = { ZbtIdentityProbe.Outcome.REFUSED }))
        ZbtDaemonReachability.expireIdleIdentity(++clock)
        assertFalse(ZbtDaemonReachability.resolve(nowMs = { clock }, requireIdentity = true,
            identityDial = { ZbtIdentityProbe.Outcome.UNCONFIRMED }))
        ZbtDaemonReachability.expireIdleIdentity(++clock)
        assertEquals(false, ZbtDaemonReachability.cached(clock, requireIdentity = true))
    }

}
