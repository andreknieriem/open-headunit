package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zjsocket

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zjsocket.ZjSocketPolicy.Outcome
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zjsocket.ZjSocketPolicy.Verdict
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.JoinRefusalPolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zjsocket.ZjSocketPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZjSocketPolicyTest {

    @Test
    fun `probe tries the existing paths in order and stops at the first connect`() {
        val tried = mutableListOf<String>()
        val result = ZjSocketPolicy.probe(listOf("/a", "/b", "/c"), { true }) { path ->
            tried += path
            if (path == "/b") Outcome.CONNECTS else Outcome.REFUSED
        }
        assertEquals(listOf("/a", "/b"), tried)
        assertEquals(ZjSocketPolicy.Probe("/b", Outcome.CONNECTS), result)
    }

    @Test
    fun `probe skips a missing path without a connect`() {
        val tried = mutableListOf<String>()
        val result = ZjSocketPolicy.probe(listOf("/a", "/b"), { it == "/b" }) { path ->
            tried += path
            Outcome.DENIED
        }
        assertEquals(listOf("/b"), tried)
        assertEquals(ZjSocketPolicy.Probe("/b", Outcome.DENIED), result)
    }

    @Test
    fun `probe answers missing when no path exists`() {
        val result = ZjSocketPolicy.probe(listOf("/a"), { false }) { error("no connect expected") }
        assertEquals(ZjSocketPolicy.Probe(null, Outcome.MISSING), result)
    }

    @Test
    fun `only a connect counts as connecting`() {
        assertTrue(ZjSocketPolicy.connects(Outcome.CONNECTS))
        for (outcome in listOf(Outcome.REFUSED, Outcome.DENIED, Outcome.TIMED_OUT, Outcome.MISSING))
            assertFalse(outcome.name, ZjSocketPolicy.connects(outcome))
    }

    @Test
    fun `a permission failure is denied and any other failure is refused`() {
        assertEquals(Outcome.DENIED, ZjSocketPolicy.failureOutcome("Permission denied"))
        assertEquals(Outcome.DENIED, ZjSocketPolicy.failureOutcome("connect failed: EACCES (Permission denied)"))
        assertEquals(Outcome.REFUSED, ZjSocketPolicy.failureOutcome("Connection refused"))
        assertEquals(Outcome.REFUSED, ZjSocketPolicy.failureOutcome(null))
    }

    @Test
    fun `a positive answer is kept at any age`() {
        assertEquals(true, ZjSocketPolicy.cachedAnswer(Verdict(true, 0L), 10 * ZjSocketPolicy.NEGATIVE_MAX_AGE_MS))
    }

    @Test
    fun `a negative answer expires by age`() {
        val verdict = Verdict(false, 1_000L)
        assertEquals(false, ZjSocketPolicy.cachedAnswer(verdict, 1_000L + ZjSocketPolicy.NEGATIVE_MAX_AGE_MS - 1))
        assertNull(ZjSocketPolicy.cachedAnswer(verdict, 1_000L + ZjSocketPolicy.NEGATIVE_MAX_AGE_MS))
    }

    @Test
    fun `no verdict is no answer`() {
        assertNull(ZjSocketPolicy.cachedAnswer(null, 5L))
    }

    @Test
    fun `arming keeps a positive and drops a negative`() {
        assertTrue(ZjSocketPolicy.keptAtArming(Verdict(true, 0L)))
        assertFalse(ZjSocketPolicy.keptAtArming(Verdict(false, 0L)))
        assertFalse(ZjSocketPolicy.keptAtArming(null))
    }

    private fun decide(
        running: Boolean = true,
        sessionUp: Boolean = false,
        handshakeInFlight: Boolean = false,
        settling: Boolean = false,
        mayServe: Boolean = true
    ) = ZjSocketPolicy.decide(running, sessionUp, handshakeInFlight, settling, mayServe)

    @Test
    fun `a running carrier with nothing in the way dials`() {
        assertEquals(Action.DIAL, decide())
    }

    @Test
    fun `each single obstacle makes the carrier wait`() {
        assertEquals(Action.WAIT, decide(sessionUp = true))
        assertEquals(Action.WAIT, decide(handshakeInFlight = true))
        assertEquals(Action.WAIT, decide(settling = true))
        assertEquals(Action.WAIT, decide(mayServe = false))
    }

    @Test
    fun `only a carrier that is no longer running stops`() {
        assertEquals(Action.STOP, decide(running = false))
        assertEquals(Action.STOP, decide(running = false, sessionUp = true, handshakeInFlight = true))
    }

    @Test
    fun `a live session waits and never stops the carrier`() {
        for (settling in listOf(false, true))
            for (inFlight in listOf(false, true))
                for (mayServe in listOf(false, true))
                    assertEquals(Action.WAIT, decide(sessionUp = true, handshakeInFlight = inFlight, settling = settling, mayServe = mayServe))
    }

    @Test
    fun `after a served attempt the delay follows the join refusal count`() {
        for (refusals in 0..6)
            assertEquals(
                JoinRefusalPolicy.retryDelayMs(refusals, ZjSocketPolicy.BASE_RETRY_MS),
                ZjSocketPolicy.retryDelayMs(afterServed = true, count = refusals)
            )
    }

    @Test
    fun `refused connects double from the base and stop at the cap`() {
        val base = ZjSocketPolicy.BASE_RETRY_MS
        assertEquals(base, ZjSocketPolicy.retryDelayMs(afterServed = false, count = 1))
        assertEquals(base * 2, ZjSocketPolicy.retryDelayMs(afterServed = false, count = 2))
        assertEquals(base * 4, ZjSocketPolicy.retryDelayMs(afterServed = false, count = 3))
        assertEquals(base * 8, ZjSocketPolicy.retryDelayMs(afterServed = false, count = 4))
        assertEquals(ZjSocketPolicy.CONNECT_BACKOFF_CAP_MS, ZjSocketPolicy.retryDelayMs(afterServed = false, count = 5))
        assertEquals(ZjSocketPolicy.CONNECT_BACKOFF_CAP_MS, ZjSocketPolicy.retryDelayMs(afterServed = false, count = 500))
    }

    @Test
    fun `a handshake cancelled by the session-end rearm leaves the carrier running`() {
        assertFalse(ZjSocketPolicy.cancellationEndsCarrier(stopped = false, carrierActive = true))
    }

    @Test
    fun `a cancellation ends the carrier when the carrier was stopped or cancelled`() {
        assertTrue(ZjSocketPolicy.cancellationEndsCarrier(stopped = true, carrierActive = true))
        assertTrue(ZjSocketPolicy.cancellationEndsCarrier(stopped = false, carrierActive = false))
        assertTrue(ZjSocketPolicy.cancellationEndsCarrier(stopped = true, carrierActive = false))
    }

    @Test
    fun `the socket is probed once, at the first ZBT refusal, while unmeasured`() {
        assertTrue(ZjSocketPolicy.probesDuringZbtWait(refusals = 1, zjAnswer = null))
        assertFalse(ZjSocketPolicy.probesDuringZbtWait(refusals = 2, zjAnswer = null))
        assertFalse(ZjSocketPolicy.probesDuringZbtWait(refusals = 1, zjAnswer = false))
        assertFalse(ZjSocketPolicy.probesDuringZbtWait(refusals = 1, zjAnswer = true))
    }

    @Test
    fun `a socket that connects ends the ZBT wait only after a refusal`() {
        assertTrue(ZjSocketPolicy.endsZbtWait(refusals = 1, zjConnects = true))
        assertFalse(ZjSocketPolicy.endsZbtWait(refusals = 0, zjConnects = true))
        assertFalse(ZjSocketPolicy.endsZbtWait(refusals = 3, zjConnects = false))
        assertFalse(ZjSocketPolicy.endsZbtWait(refusals = 3, zjConnects = null))
    }
}
