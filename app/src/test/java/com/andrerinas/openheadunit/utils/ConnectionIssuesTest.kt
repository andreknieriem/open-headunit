package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The store's rules, which are the part that can go wrong quietly.
 *
 * Two properties matter more than the rest and each has a test of its own below: the conditions
 * are independent, because a single shared id once meant clearing one cancelled another; and a
 * clear must be safe on an issue that was never raised, because every clear site runs on the
 * success path whether or not anything had failed.
 */
class ConnectionIssuesTest {

    private class FakeStore : ConnectionIssueStore {
        val values = mutableMapOf<ConnectionIssue, Long>()
        override fun read(issue: ConnectionIssue): Long = values[issue] ?: 0L
        override fun write(issue: ConnectionIssue, atEpochMs: Long) { values[issue] = atEpochMs }
    }

    private val store = FakeStore()

    @Test
    fun `every issue round-trips through the store`() {
        // The store's read and write are parallel `when`s over the enum, so a kind added to one and
        // not the other is silently unrecordable. This is what catches that.
        val store = FakeStore()
        ConnectionIssue.values().forEachIndexed { i, issue ->
            ConnectionIssues.raise(store, issue, 1_000L + i)
        }
        val standing = ConnectionIssues.standing(store)
        assertEquals(ConnectionIssue.values().size, standing.size)
        ConnectionIssue.values().forEachIndexed { i, issue ->
            assertEquals(1_000L + i, standing.first { it.issue == issue }.raisedAtEpochMs)
        }
    }

    // A refusal is found while the main screen is already up and retrying, and that screen read the
    // record only on resume, so the banner stayed away (2026-10-24, Note8). It collects these now.
    @Test
    fun `a write that changes the record is announced`() {
        val before = ConnectionIssues.changes.value
        ConnectionIssues.raiseOnce(store, ConnectionIssue.PHONE_REFUSED_HANDS_FREE, 1_000L)
        assertEquals(before + 1, ConnectionIssues.changes.value)
        ConnectionIssues.clear(store, ConnectionIssue.PHONE_REFUSED_HANDS_FREE)
        assertEquals(before + 2, ConnectionIssues.changes.value)
    }

    @Test
    fun `a write that changes nothing is not announced`() {
        ConnectionIssues.raiseOnce(store, ConnectionIssue.PHONE_REFUSED_HANDS_FREE, 1_000L)
        val before = ConnectionIssues.changes.value
        ConnectionIssues.raiseOnce(store, ConnectionIssue.PHONE_REFUSED_HANDS_FREE, 2_000L)
        ConnectionIssues.clear(store, ConnectionIssue.BSSID_UNAVAILABLE)
        assertEquals(before, ConnectionIssues.changes.value)
    }

    @Test
    fun `nothing raised means nothing standing`() {
        assertTrue(ConnectionIssues.standing(store).isEmpty())
    }

    @Test
    fun `a raised issue stands with its stamp`() {
        ConnectionIssues.raise(store, ConnectionIssue.BSSID_UNAVAILABLE, 1_000L)

        val standing = ConnectionIssues.standing(store)
        assertEquals(1, standing.size)
        assertEquals(ConnectionIssue.BSSID_UNAVAILABLE, standing[0].issue)
        assertEquals(1_000L, standing[0].raisedAtEpochMs)
    }

    @Test
    fun `raising one issue leaves the others alone`() {
        ConnectionIssues.raise(store, ConnectionIssue.BSSID_UNAVAILABLE, 1_000L)

        assertEquals(0L, store.read(ConnectionIssue.BLUETOOTH_SENT_NO_DATA))
        assertEquals(0L, store.read(ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE))
    }

    @Test
    fun `clearing one issue leaves the others standing`() {
        ConnectionIssues.raise(store, ConnectionIssue.BSSID_UNAVAILABLE, 1_000L)
        ConnectionIssues.raise(store, ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE, 2_000L)

        ConnectionIssues.clear(store, ConnectionIssue.BSSID_UNAVAILABLE)

        val standing = ConnectionIssues.standing(store)
        assertEquals(1, standing.size)
        assertEquals(ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE, standing[0].issue)
    }

    @Test
    fun `clearing an issue that was never raised is harmless`() {
        ConnectionIssues.clear(store, ConnectionIssue.BLUETOOTH_SENT_NO_DATA)

        assertEquals(0L, store.read(ConnectionIssue.BLUETOOTH_SENT_NO_DATA))
        assertTrue(ConnectionIssues.standing(store).isEmpty())
    }

    @Test
    fun `re-raising moves the stamp forward rather than adding a second entry`() {
        ConnectionIssues.raise(store, ConnectionIssue.BLUETOOTH_SENT_NO_DATA, 1_000L)
        ConnectionIssues.raise(store, ConnectionIssue.BLUETOOTH_SENT_NO_DATA, 5_000L)

        val standing = ConnectionIssues.standing(store)
        assertEquals(1, standing.size)
        assertEquals(5_000L, standing[0].raisedAtEpochMs)
    }

    @Test
    fun `standing is ordered newest first`() {
        ConnectionIssues.raise(store, ConnectionIssue.BSSID_UNAVAILABLE, 1_000L)
        ConnectionIssues.raise(store, ConnectionIssue.BLUETOOTH_SENT_NO_DATA, 3_000L)
        ConnectionIssues.raise(store, ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE, 2_000L)

        assertEquals(
            listOf(
                ConnectionIssue.BLUETOOTH_SENT_NO_DATA,
                ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE,
                ConnectionIssue.BSSID_UNAVAILABLE
            ),
            ConnectionIssues.standing(store).map { it.issue }
        )
    }

    @Test
    fun `a cleared issue drops out of standing entirely`() {
        ConnectionIssues.raise(store, ConnectionIssue.BSSID_UNAVAILABLE, 1_000L)
        ConnectionIssues.clear(store, ConnectionIssue.BSSID_UNAVAILABLE)

        assertTrue(ConnectionIssues.standing(store).isEmpty())
    }

    @Test
    fun `raising once leaves a standing stamp where it is`() {
        // The poke loop re-detects its condition every pass. A moving stamp would overtake the
        // user's dismissal every time and the banner could never be dismissed at all.
        val store = FakeStore()
        ConnectionIssues.raiseOnce(store, ConnectionIssue.HANDS_FREE_HELD_ELSEWHERE, 1_000L)
        ConnectionIssues.raiseOnce(store, ConnectionIssue.HANDS_FREE_HELD_ELSEWHERE, 9_000L)
        assertEquals(1_000L, store.read(ConnectionIssue.HANDS_FREE_HELD_ELSEWHERE))
    }

    @Test
    fun `raising once after a clear stamps the new occurrence`() {
        val store = FakeStore()
        ConnectionIssues.raiseOnce(store, ConnectionIssue.HANDS_FREE_HELD_ELSEWHERE, 1_000L)
        ConnectionIssues.clear(store, ConnectionIssue.HANDS_FREE_HELD_ELSEWHERE)
        ConnectionIssues.raiseOnce(store, ConnectionIssue.HANDS_FREE_HELD_ELSEWHERE, 9_000L)
        assertEquals(9_000L, store.read(ConnectionIssue.HANDS_FREE_HELD_ELSEWHERE))
    }

    @Test
    fun `an ordinary raise still moves a standing stamp forward`() {
        // The banner answers "why did the last attempt fail", so an event-detected condition keeps
        // reporting its latest occurrence. Only the loop-detected one opts out.
        val store = FakeStore()
        ConnectionIssues.raise(store, ConnectionIssue.WIFI_RADIO_OFF, 1_000L)
        ConnectionIssues.raise(store, ConnectionIssue.WIFI_RADIO_OFF, 9_000L)
        assertEquals(9_000L, store.read(ConnectionIssue.WIFI_RADIO_OFF))
    }
}
