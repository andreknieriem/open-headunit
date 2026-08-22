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
}
