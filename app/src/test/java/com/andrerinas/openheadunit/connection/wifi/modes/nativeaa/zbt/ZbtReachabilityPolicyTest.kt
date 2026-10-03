package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.ZbtReachabilityPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZbtReachabilityPolicyTest {

    @Test
    fun `a refused port is the one answer that means no daemon`() {
        assertEquals(
            Verdict.NOTHING_LISTENING,
            ZbtReachabilityPolicy.classify("ConnectException", answered = false)
        )
    }

    @Test
    fun `a connect timeout is a daemon that is there, not one that is missing`() {
        // Loopback only times out when a listener is not draining its backlog, which #1005's
        // module does while a phone is linked to it.
        assertEquals(
            Verdict.LISTENING_SILENT,
            ZbtReachabilityPolicy.classify("SocketTimeoutException", answered = false)
        )
    }

    @Test
    fun `a frame back is the daemon answering`() {
        assertEquals(Verdict.ANSWERED, ZbtReachabilityPolicy.classify(null, answered = true))
    }

    @Test
    fun `connected and silent is busy, not absent`() {
        assertEquals(Verdict.LISTENING_SILENT, ZbtReachabilityPolicy.classify(null, answered = false))
    }

    @Test
    fun `an unexpected connect failure is read as present rather than absent`() {
        // Refusing the route costs a bttype extra unit Native AA outright, so anything that is not
        // a plain refusal is worth trying.
        assertEquals(
            Verdict.LISTENING_SILENT,
            ZbtReachabilityPolicy.classify("SocketException", answered = false)
        )
    }

    @Test
    fun `only an outright refusal rules the module route out`() {
        assertTrue(ZbtReachabilityPolicy.reachable(Verdict.ANSWERED))
        assertTrue(ZbtReachabilityPolicy.reachable(Verdict.LISTENING_SILENT))
        assertFalse(ZbtReachabilityPolicy.reachable(Verdict.NOTHING_LISTENING))
    }

    @Test
    fun `redial gaps walk 1, 2, 4, 8, 8 seconds`() {
        var since = 0L
        val gaps = (1..5).map { n ->
            val gap = ZbtReachabilityPolicy.redialAfterMs(n, since)!!
            since += gap
            gap / 1000
        }
        assertEquals(listOf(1L, 2L, 4L, 8L, 8L), gaps)
    }

    @Test
    fun `the last gap is clamped so the final dial lands on the window`() {
        val window = ZbtReachabilityPolicy.REFUSAL_WINDOW_MS
        assertEquals(7_000L, ZbtReachabilityPolicy.redialAfterMs(6, window - 7_000L))
        assertEquals(2_000L, ZbtReachabilityPolicy.redialAfterMs(6, window - 2_000L))
    }

    @Test
    fun `no redial is offered at or past the window`() {
        val window = ZbtReachabilityPolicy.REFUSAL_WINDOW_MS
        assertNull(ZbtReachabilityPolicy.redialAfterMs(7, window))
        assertNull(ZbtReachabilityPolicy.redialAfterMs(7, window + 1))
    }
}
