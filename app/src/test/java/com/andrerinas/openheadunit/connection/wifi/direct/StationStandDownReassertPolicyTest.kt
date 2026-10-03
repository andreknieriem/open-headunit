package com.andrerinas.openheadunit.connection.wifi.direct

import com.andrerinas.openheadunit.connection.wifi.direct.StationStandDownReassertPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class StationStandDownReassertPolicyTest {

    private fun decide(
        mode: StationStandDownMode = StationStandDownMode.ALWAYS,
        networkId: Int = 4,
        associated: Boolean? = true,
        leftSeen: Boolean = true,
        reassertsSoFar: Int = 0,
        windowStartMs: Long = 0L,
        nowMs: Long = 100_000L,
        lastReassertAtMs: Long = 0L,
        contested: Boolean = false,
        isRecheck: Boolean = false,
    ) = StationStandDownReassertPolicy.decide(
        mode, networkId, associated, leftSeen, reassertsSoFar, windowStartMs, nowMs,
        lastReassertAtMs, contested, isRecheck
    )

    @Test
    fun `always with a record and a joined station reasserts`() {
        assertEquals(Decision.Reassert, decide())
    }

    @Test
    fun `auto with a record reasserts because a record means auto chose to stand down`() {
        assertEquals(Decision.Reassert, decide(mode = StationStandDownMode.AUTO))
    }

    @Test
    fun `never ignores even with a record`() {
        assertEquals(Decision.Ignore, decide(mode = StationStandDownMode.NEVER))
    }

    @Test
    fun `no record is ignored so a restore is not undone`() {
        assertEquals(Decision.Ignore, decide(networkId = -1))
    }

    @Test
    fun `a join read before the station completes gets one more read`() {
        assertEquals(Decision.Recheck(1_500L), decide(associated = false))
        assertEquals(Decision.Recheck(1_500L), decide(associated = null))
    }

    @Test
    fun `a recheck never schedules another`() {
        assertEquals(Decision.Ignore, decide(associated = false, isRecheck = true))
        assertEquals(Decision.Ignore, decide(associated = null, isRecheck = true))
    }

    @Test
    fun `a recheck that finds the station joined reasserts`() {
        assertEquals(Decision.Reassert, decide(isRecheck = true))
    }

    @Test
    fun `no record and never are not rechecked or budgeted`() {
        assertEquals(Decision.Ignore, decide(networkId = -1, associated = null))
        assertEquals(Decision.Ignore, decide(mode = StationStandDownMode.NEVER, associated = null))
        assertEquals(Decision.Ignore, decide(networkId = -1, reassertsSoFar = 3, windowStartMs = 90_000L))
        assertEquals(
            Decision.Ignore,
            decide(mode = StationStandDownMode.NEVER, reassertsSoFar = 3, windowStartMs = 90_000L)
        )
    }

    @Test
    fun `a join before the station was once seen gone is the stand-down's own replay`() {
        assertEquals(Decision.Ignore, decide(leftSeen = false))
        assertEquals(Decision.Ignore, decide(leftSeen = false, mode = StationStandDownMode.AUTO))
    }

    @Test
    fun `the replay does not spend the budget or reach the give-up`() {
        assertEquals(Decision.Ignore, decide(leftSeen = false, reassertsSoFar = 3, windowStartMs = 90_000L))
        assertEquals(Decision.Ignore, decide(leftSeen = false, associated = null))
        assertEquals(
            Decision.Ignore,
            decide(leftSeen = false, reassertsSoFar = 1, nowMs = 54_000L, lastReassertAtMs = 50_000L)
        )
    }

    @Test
    fun `any read of the station gone during a stand-down latches it, whichever reader made it`() {
        assertEquals(true, StationStandDownReassertPolicy.latchesLeft(standDownInForce = true, associated = false))
        assertEquals(false, StationStandDownReassertPolicy.latchesLeft(standDownInForce = true, associated = true))
        assertEquals(false, StationStandDownReassertPolicy.latchesLeft(standDownInForce = true, associated = null))
        assertEquals(false, StationStandDownReassertPolicy.latchesLeft(standDownInForce = false, associated = false))
    }

    @Test
    fun `the first rejoin is answered at once whatever the clock says`() {
        assertEquals(Decision.Reassert, decide(nowMs = 1L, lastReassertAtMs = 0L))
        assertEquals(Decision.Reassert, decide(nowMs = 9_000_000L, lastReassertAtMs = 0L))
    }

    @Test
    fun `a rejoin inside the spacing is deferred by what remains`() {
        assertEquals(
            Decision.Defer(6_000L),
            decide(reassertsSoFar = 1, nowMs = 54_000L, lastReassertAtMs = 50_000L)
        )
    }

    @Test
    fun `a rejoin exactly one spacing later reasserts`() {
        assertEquals(
            Decision.Reassert,
            decide(reassertsSoFar = 1, nowMs = 60_000L, lastReassertAtMs = 50_000L)
        )
    }

    @Test
    fun `a spent budget is the contested verdict, spaced or not`() {
        assertEquals(
            Decision.BudgetSpent,
            decide(reassertsSoFar = 3, windowStartMs = 30_000L, nowMs = 70_000L, lastReassertAtMs = 50_000L)
        )
        assertEquals(
            Decision.BudgetSpent,
            decide(reassertsSoFar = 3, windowStartMs = 30_000L, nowMs = 52_000L, lastReassertAtMs = 50_000L)
        )
    }

    @Test
    fun `a rejoin once the window has passed reasserts with a fresh budget`() {
        assertEquals(
            Decision.Reassert,
            decide(reassertsSoFar = 3, windowStartMs = 30_000L, nowMs = 330_000L, lastReassertAtMs = 50_000L)
        )
        assertEquals(true, StationStandDownReassertPolicy.opensWindow(30_000L, 330_000L))
    }

    @Test
    fun `the window opens on the first re-assertion and not before it passes`() {
        assertEquals(true, StationStandDownReassertPolicy.opensWindow(0L, 5_000L))
        assertEquals(false, StationStandDownReassertPolicy.opensWindow(30_000L, 329_999L))
    }

    @Test
    fun `a fresh stand-down after a spent one reasserts again`() {
        assertEquals(Decision.Reassert, decide(reassertsSoFar = 0, windowStartMs = 0L))
    }

    @Test
    fun `contested stops the first rejoin of an arming`() {
        assertEquals(Decision.Suppressed, decide(contested = true))
    }

    @Test
    fun `contested with the budget spent is suppressed and never a second verdict`() {
        assertEquals(
            Decision.Suppressed,
            decide(contested = true, reassertsSoFar = 3, windowStartMs = 30_000L, nowMs = 70_000L, lastReassertAtMs = 50_000L)
        )
    }

    @Test
    fun `contested inside the spacing is suppressed and schedules nothing`() {
        assertEquals(
            Decision.Suppressed,
            decide(contested = true, reassertsSoFar = 1, nowMs = 54_000L, lastReassertAtMs = 50_000L)
        )
    }

    @Test
    fun `contested still ignores no record, never and the replay`() {
        assertEquals(Decision.Ignore, decide(contested = true, networkId = -1))
        assertEquals(Decision.Ignore, decide(contested = true, mode = StationStandDownMode.NEVER))
        assertEquals(Decision.Ignore, decide(contested = true, leftSeen = false))
    }

    @Test
    fun `contested keeps the unreadable rule so only a completed station is suppressed`() {
        assertEquals(Decision.Recheck(1_500L), decide(contested = true, associated = null))
        assertEquals(Decision.Ignore, decide(contested = true, associated = null, isRecheck = true))
    }

    @Test
    fun `one or two rejoins never reach the verdict`() {
        assertEquals(
            Decision.Reassert,
            decide(reassertsSoFar = 2, windowStartMs = 30_000L, nowMs = 70_000L, lastReassertAtMs = 50_000L)
        )
        assertEquals(
            Decision.Defer(6_000L),
            decide(reassertsSoFar = 2, windowStartMs = 30_000L, nowMs = 54_000L, lastReassertAtMs = 50_000L)
        )
    }

    @Test
    fun `a stored fingerprint matches only the same non-blank ROM`() {
        assertEquals(false, StationStandDownReassertPolicy.isContested(null, "a/b"))
        assertEquals(false, StationStandDownReassertPolicy.isContested("", ""))
        assertEquals(true, StationStandDownReassertPolicy.isContested("a/b", "a/b"))
        assertEquals(false, StationStandDownReassertPolicy.isContested("a/b", "a/c"))
    }

    @Test
    fun `only a changed mode clears the verdict`() {
        assertEquals(false, StationStandDownReassertPolicy.modeChangeClearsVerdict(1, 1))
        assertEquals(true, StationStandDownReassertPolicy.modeChangeClearsVerdict(1, 0))
        assertEquals(true, StationStandDownReassertPolicy.modeChangeClearsVerdict(0, 1))
        assertEquals(true, StationStandDownReassertPolicy.modeChangeClearsVerdict(1, 2))
    }

    @Test
    fun `only a stand-down that held through a session retires the home WiFi banner`() {
        assertEquals(true, StationStandDownReassertPolicy.retiresRejoinIssue(true, false))
        assertEquals(false, StationStandDownReassertPolicy.retiresRejoinIssue(true, true))
        assertEquals(false, StationStandDownReassertPolicy.retiresRejoinIssue(false, false))
        assertEquals(false, StationStandDownReassertPolicy.retiresRejoinIssue(false, true))
    }

    @Test
    fun `the budget and spacing are pinned`() {
        assertEquals(3, StationStandDownReassertPolicy.MAX_REASSERTS)
        assertEquals(10_000L, StationStandDownReassertPolicy.MIN_SPACING_MS)
        assertEquals(300_000L, StationStandDownReassertPolicy.BUDGET_WINDOW_MS)
    }

    @Test
    fun `config status wording`() {
        assertEquals("current", StationStandDownReassertPolicy.describeConfigStatus(0))
        assertEquals("disabled", StationStandDownReassertPolicy.describeConfigStatus(1))
        assertEquals("enabled", StationStandDownReassertPolicy.describeConfigStatus(2))
        assertEquals("unreadable", StationStandDownReassertPolicy.describeConfigStatus(null))
        assertEquals("unreadable", StationStandDownReassertPolicy.describeConfigStatus(7))
    }

    @Test
    fun `lock wording`() {
        assertEquals("WifiLock not held", StationStandDownReassertPolicy.describeLock(null))
        assertEquals("WifiLock held for 976ms", StationStandDownReassertPolicy.describeLock(976L))
    }
}
