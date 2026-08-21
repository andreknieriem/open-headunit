package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.connection.wifi.server.WirelessServerHistory
import com.andrerinas.openheadunit.connection.wifi.server.WirelessServerRestartPolicy
import com.andrerinas.openheadunit.connection.wifi.server.WirelessServerRestartPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The case these exist for: a reporter whose head unit hosted its network, woke the phone, handed
 * out credentials, and then aborted every handshake with "nothing is listening on port 5288",
 * repeatedly, for as long as the capture ran. The server object was assigned and not listening, and
 * the old guard read "assigned" as "running".
 */
class WirelessServerRestartPolicyTest {

    private val now = 1_000_000L
    private val never = 0L

    @Test
    fun `a listening server is left alone`() {
        assertEquals(
            Action.NO_OP,
            WirelessServerRestartPolicy.decide(
                assigned = true, alive = true, listening = true, nowMs = now,
                history = WirelessServerHistory(lastRebuildAtMs = never, rebuildsInWindow = 0, windowStartedAtMs = never),
            ),
        )
    }

    @Test
    fun `nothing assigned yet means start, not rebuild`() {
        assertEquals(
            Action.START,
            WirelessServerRestartPolicy.decide(
                assigned = false, alive = false, listening = false, nowMs = now,
                history = WirelessServerHistory(lastRebuildAtMs = never, rebuildsInWindow = 0, windowStartedAtMs = never),
            ),
        )
    }

    @Test
    fun `assigned but not listening is the whole bug, and it rebuilds`() {
        // The old code returned early here because the field was non-null, so the port stayed
        // unbound for the life of the mode and every handshake aborted against it.
        assertEquals(
            Action.REBUILD,
            WirelessServerRestartPolicy.decide(
                assigned = true, alive = false, listening = false, nowMs = now,
                history = WirelessServerHistory(lastRebuildAtMs = never, rebuildsInWindow = 0, windowStartedAtMs = never),
            ),
        )
    }

    @Test
    fun `a handshake retrying every few seconds cannot drive a rebuild per attempt`() {
        // The observed cadence in the reporter's log is a fresh handshake roughly every 4 s. Each
        // one asks; only the first may act.
        var lastRebuild = now
        for (attempt in 1..5) {
            val at = now + attempt * 4_000L
            val action = WirelessServerRestartPolicy.decide(
                assigned = true, alive = false, listening = false, nowMs = at,
                history = WirelessServerHistory(lastRebuildAtMs = lastRebuild, rebuildsInWindow = 1, windowStartedAtMs = now),
            )
            if (at - lastRebuild < WirelessServerRestartPolicy.REBUILD_COOLDOWN_MS) {
                assertEquals("attempt $attempt at +${at - now}ms", Action.BACKOFF, action)
            } else {
                assertEquals("attempt $attempt at +${at - now}ms", Action.REBUILD, action)
                lastRebuild = at
            }
        }
    }

    @Test
    fun `a port that will never bind stops being retried`() {
        val action = WirelessServerRestartPolicy.decide(
            assigned = true, alive = false, listening = false, nowMs = now + 30_000L,
            history = WirelessServerHistory(lastRebuildAtMs = now, rebuildsInWindow = WirelessServerRestartPolicy.MAX_REBUILDS_PER_WINDOW,
            windowStartedAtMs = now),
        )
        assertEquals(Action.BACKOFF, action)
    }

    @Test
    fun `a unit that fails once an hour is never talked out of trying`() {
        // The exhausted count must not be permanent: the window ages out and the next failure is
        // treated as the first one again.
        val muchLater = now + WirelessServerRestartPolicy.REBUILD_WINDOW_MS + 1
        assertFalse(WirelessServerRestartPolicy.windowIsOpen(muchLater, now))
        assertEquals(
            Action.REBUILD,
            WirelessServerRestartPolicy.decide(
                assigned = true, alive = false, listening = false, nowMs = muchLater,
                history = WirelessServerHistory(lastRebuildAtMs = now, rebuildsInWindow = WirelessServerRestartPolicy.MAX_REBUILDS_PER_WINDOW,
                windowStartedAtMs = now),
            ),
        )
        assertEquals(1, WirelessServerRestartPolicy.nextRebuildCount(muchLater, now, 3))
        assertEquals(muchLater, WirelessServerRestartPolicy.nextWindowStart(muchLater, now))
    }

    @Test
    fun `counting inside one window accumulates rather than resetting`() {
        val soon = now + 5_000L
        assertTrue(WirelessServerRestartPolicy.windowIsOpen(soon, now))
        assertEquals(2, WirelessServerRestartPolicy.nextRebuildCount(soon, now, 1))
        assertEquals(now, WirelessServerRestartPolicy.nextWindowStart(soon, now))
    }

    @Test
    fun `a first ever rebuild is not held back by an unset timestamp`() {
        // lastRebuildAtMs of 0 means "never", not "at time zero, so 1000000ms ago".
        assertEquals(
            Action.REBUILD,
            WirelessServerRestartPolicy.decide(
                assigned = true, alive = false, listening = false, nowMs = 500L,
                history = WirelessServerHistory(lastRebuildAtMs = never, rebuildsInWindow = 0, windowStartedAtMs = never),
            ),
        )
    }

    @Test
    fun `every action says why, because that is the point of the change`() {
        for (action in Action.values()) {
            val text = WirelessServerRestartPolicy.describe(action, assigned = true, listening = false)
            assertTrue("$action has no reason text", text.isNotBlank())
        }
        assertEquals(
            "a server exists but its port is not bound",
            WirelessServerRestartPolicy.describe(Action.REBUILD, assigned = true, listening = false),
        )
    }

    @Test
    fun `a server still binding is waited for, never torn down`() {
        // The bind is retried inside the coroutine, so assigned-and-not-listening is a normal
        // transient state for a second or so. Reading it as dead would replace a server that was
        // about to succeed, and the replacement would race the original for the same port -
        // SO_REUSEADDR does not cover a live listener, only one in TIME_WAIT.
        assertEquals(
            Action.AWAIT,
            WirelessServerRestartPolicy.decide(
                assigned = true, alive = true, listening = false, nowMs = now,
                history = WirelessServerHistory(lastRebuildAtMs = never, rebuildsInWindow = 0, windowStartedAtMs = never),
            ),
        )
    }

    @Test
    fun `a live session is never disturbed`() {
        // A projection session arrived over this socket. Replacing it can only do harm, and the
        // phone is already connected, so nothing here needs repairing.
        assertEquals(
            Action.NO_OP,
            WirelessServerRestartPolicy.decide(
                assigned = true, alive = false, listening = false, nowMs = now, sessionBusy = true,
                history = WirelessServerHistory(lastRebuildAtMs = never, rebuildsInWindow = 0, windowStartedAtMs = never),
            ),
        )
    }

    @Test
    fun `every state maps to exactly one action, and none is left undecided`() {
        val seen = mutableSetOf<Action>()
        for (assigned in listOf(false, true)) {
            for (alive in listOf(false, true)) {
                for (listening in listOf(false, true)) {
                    seen += WirelessServerRestartPolicy.decide(
                        assigned = assigned, alive = alive, listening = listening, nowMs = now,
                        history = WirelessServerHistory(lastRebuildAtMs = never, rebuildsInWindow = 0, windowStartedAtMs = never),
                    )
                }
            }
        }
        // BACKOFF needs a prior attempt to be reachable, so it is not expected from this sweep.
        assertEquals(setOf(Action.NO_OP, Action.START, Action.AWAIT, Action.REBUILD), seen)
    }
}
