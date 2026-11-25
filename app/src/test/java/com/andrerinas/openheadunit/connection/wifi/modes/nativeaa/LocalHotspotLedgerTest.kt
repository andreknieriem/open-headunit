package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.LocalHotspotPolicy.RefreshAction
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.LocalHotspotPolicy.RestartCause
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.LocalHotspotPolicy.StartDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalHotspotLedgerTest {

    private val ledger = LocalHotspotLedger<String>()

    @Test
    fun `a live host is closed once before the decision and the cause is close`() {
        ledger.called(1_000L, "a")
        val closed = mutableListOf<String>()
        val decision = ledger.begin(2_000L) { closed += it; ledger.closed(it) }
        assertEquals(listOf("a"), closed)
        assertEquals(StartDecision.Go(RestartCause.CLOSE, 2), decision)
    }

    @Test
    fun `a host that closed or ended leaves nothing for begin to close`() {
        ledger.called(1_000L, "a")
        ledger.closed("a")
        var closes = 0
        ledger.begin(2_000L) { closes++ }
        ledger.called(2_000L, "b")
        ledger.ended("b", RestartCause.PLATFORM_STOP)
        ledger.begin(3_000L) { closes++ }
        assertEquals(0, closes)
    }

    @Test
    fun `begin closes the stray even when the close lambda does not tell the ledger`() {
        ledger.called(1_000L, "a")
        ledger.begin(2_000L) { }
        var closes = 0
        ledger.begin(3_000L) { closes++ }
        assertEquals(0, closes)
    }

    @Test
    fun `a refused call spends no stamp and leaves lastEnd alone`() {
        val first = ledger.called(1_000L, "a")
        ledger.closed("a")
        assertEquals(RestartCause.CLOSE, ledger.lastEnd)
        val second = ledger.called(2_000L, "b")
        ledger.threw(second, 2_100L)
        ledger.closed("b")
        assertEquals(listOf(1_000L), ledger.recentStarts(2_200L))
        assertEquals(RestartCause.CLOSE, ledger.lastEnd)
        ledger.answered("a")
        assertFalse(ledger.hasUnansweredStart())
        assertEquals(2_100L, ledger.lastRefusedAtMs)
        assertEquals(null, first.earlier)
        assertEquals("a", second.earlier)
    }

    @Test
    fun `a refresh after a refusal waits 5 s and then restarts, and an accepted call clears the stamp`() {
        val call = ledger.called(0L, "a")
        ledger.threw(call, 0L)
        ledger.closed("a")
        fun action(now: Long) =
            LocalHotspotPolicy.onRefresh(false, false, ledger.recentStarts(now), now, ledger.lastRefusedAtMs)
        assertEquals(RefreshAction.WAIT, action(1_000L))
        assertEquals(RefreshAction.WAIT, action(4_999L))
        assertEquals(RefreshAction.RESTART, action(5_000L))
        ledger.called(5_000L, "b")
        ledger.accepted()
        assertNull(ledger.lastRefusedAtMs)
    }

    @Test
    fun `three throws and no accepted call still start with attempt 1`() {
        for (i in 0 until 3) {
            val call = ledger.called(i * 10L, "h$i")
            ledger.threw(call, i * 10L)
            ledger.closed("h$i")
        }
        assertEquals(StartDecision.Go(null, 1), ledger.begin(100L) { })
    }

    @Test
    fun `a late answer closes before the mark clears`() {
        ledger.called(0L, "a")
        ledger.closed("a")
        var markedInside = false
        ledger.lateAnswer("a") { markedInside = ledger.hasUnansweredStart() }
        assertTrue(markedInside)
        assertFalse(ledger.hasUnansweredStart())
    }

    @Test
    fun `an answer of an older host does not clear the mark of a newer one`() {
        ledger.called(0L, "a")
        ledger.called(10L, "b")
        ledger.answered("a")
        assertTrue(ledger.hasUnansweredStart())
        ledger.answered("b")
        assertFalse(ledger.hasUnansweredStart())
    }

    @Test
    fun `a late pair is read by the next start and a null pair keeps the earlier one`() {
        ledger.called(0L, "a")
        ledger.closed("a")
        ledger.recordPair("wlan1" to "192.168.97.139")
        ledger.lateAnswer("a") { }
        assertEquals("wlan1" to "192.168.97.139", ledger.lastPair)
        ledger.recordPair(null)
        assertEquals("wlan1" to "192.168.97.139", ledger.lastPair)
        val candidates = listOf(ApInterfaceCandidate("wlan1", false, true, "192.168.97.139"))
        assertTrue(LocalHotspotPolicy.beforeSet(candidates, ledger.lastPair).isEmpty())
    }

    @Test
    fun `an end of a host that is not live changes nothing`() {
        ledger.called(0L, "a")
        ledger.closed("a")
        ledger.ended("a", RestartCause.PLATFORM_STOP)
        assertEquals(RestartCause.CLOSE, ledger.lastEnd)
    }
}
