package com.andrerinas.openheadunit.aap

import org.junit.Assert.*
import org.junit.Test

class FragmentedMessageAuditTest {
    @Test fun `first run detects even a one byte hole without learning a baseline`() {
        val audit = FragmentedMessageAudit()
        assertNull(audit.onMessage(3, 9, 10, 20))
        val result = audit.onMessage(3, 10, 9, 0)!!
        assertEquals(FragmentedMessageAudit.Outcome.DELTA_CHANGED, result.outcome)
        assertEquals(1L, result.delta)
    }

    @Test fun `complete plaintext runs need no encryption overhead estimate`() {
        val audit = FragmentedMessageAudit()
        for (parts in listOf(listOf(10, 10), listOf(3, 11, 6), listOf(19, 0, 1))) {
            parts.forEachIndexed { i, length ->
                val flags = 8 or (if (i == 0) 1 else 0) or (if (i == parts.lastIndex) 2 else 0)
                assertNull(audit.onMessage(3, flags, length, if (i == 0) 20 else 0))
            }
        }
    }

    @Test fun `channels and control flags do not interfere with accounting`() {
        val audit = FragmentedMessageAudit()
        audit.onMessage(255, 13, 2, 4)
        audit.onMessage(3, 9, 5, 8)
        assertNull(audit.onMessage(255, 14, 2, 0))
        assertEquals(-1L, audit.onMessage(3, 10, 4, 0)!!.delta)
    }

    @Test fun `replacement and reset cannot hide unfinished runs`() {
        val audit = FragmentedMessageAudit()
        audit.onMessage(3, 9, 2, 4)
        assertEquals(FragmentedMessageAudit.Outcome.TRUNCATED_RUN, audit.onMessage(3, 11, 2, 0)!!.outcome)
        assertEquals(FragmentedMessageAudit.Outcome.ORPHANED_FRAGMENT, audit.onMessage(3, 10, 2, 0)!!.outcome)
        audit.onMessage(3, 9, 2, 4)
        audit.reset()
        assertEquals(FragmentedMessageAudit.Outcome.ORPHANED_FRAGMENT, audit.onMessage(3, 10, 2, 0)!!.outcome)
        assertNull(audit.onMessage(-1, 9, 2, 4))
    }

    @Test fun `historical MT50 fragment count sequence stays healthy after unwrap`() {
        // Original recorded encrypted deltas were -29 * count, across VIDEO and MUSIC_PLAYBACK.
        // These are the recorded counts, with synthetic 1000-byte plaintext fragments. They
        // preserve the regression shape, not a replay of captured TLS records or a fixed TLS size.
        val counts = listOf(3, 7, 8, 8, 7, 7, 4, 2, 2, 2, 2, 3, 5, 4, 8, 4, 4, 3, 3, 3, 3)
        val audit = FragmentedMessageAudit()
        for (channel in listOf(2, 6)) for (count in counts) {
            for (part in 0 until count) {
                val flags = when (part) { 0 -> 9; count - 1 -> 10; else -> 8 }
                assertNull("channel=$channel fragments=$count part=$part",
                    audit.onMessage(channel, flags, 1000, if (part == 0) count * 1000 else 0))
            }
        }
    }

    @Test fun `missing middle is detected after a different healthy fragment count`() {
        val audit = FragmentedMessageAudit()
        audit.onMessage(2, 9, 1000, 3000)
        audit.onMessage(2, 8, 1000, 0)
        assertNull(audit.onMessage(2, 10, 1000, 0))
        audit.onMessage(2, 9, 1000, 5000)
        repeat(2) { audit.onMessage(2, 8, 1000, 0) } // One of three middles is absent.
        val result = audit.onMessage(2, 10, 1000, 0)!!
        assertEquals(FragmentedMessageAudit.Outcome.DELTA_CHANGED, result.outcome)
        assertEquals(4, result.fragments)
        assertEquals(1000L, result.delta)
    }

    @Test fun `damaged first run cannot become a baseline for subsequent runs`() {
        val audit = FragmentedMessageAudit()
        repeat(3) {
            audit.onMessage(2, 9, 100, 300)
            assertEquals(100L, audit.onMessage(2, 10, 100, 0)!!.delta)
        }
        audit.onMessage(2, 9, 100, 300)
        audit.onMessage(2, 8, 100, 0)
        assertNull(audit.onMessage(2, 10, 100, 0))
    }

    @Test fun `truncated run reports its own totals and replacement completes normally`() {
        val audit = FragmentedMessageAudit()
        audit.onMessage(2, 9, 100, 300)
        audit.onMessage(2, 8, 100, 0)
        val previous = audit.onMessage(2, 9, 20, 50)!!
        assertEquals(FragmentedMessageAudit.Outcome.TRUNCATED_RUN, previous.outcome)
        assertEquals(300, previous.declaredTotal)
        assertEquals(200L, previous.observedTotal)
        assertEquals(2, previous.fragments)
        assertNull(audit.onMessage(2, 10, 30, 0))
    }

    @Test fun `orphaned middle and last have no open run to complete`() {
        val audit = FragmentedMessageAudit()
        for (flags in listOf(8, 10)) {
            val orphan = audit.onMessage(2, flags, 1, 0)!!
            assertEquals(FragmentedMessageAudit.Outcome.ORPHANED_FRAGMENT, orphan.outcome)
            assertEquals(0, orphan.fragments)
        }
        repeat(3) { assertNull(audit.onMessage(2, 11, 20, 0)) }
    }

    @Test fun `reset retires every channel without carrying observations forward`() {
        val audit = FragmentedMessageAudit()
        for (channel in listOf(2, 6, 255)) audit.onMessage(channel, 9, 100, 200)
        audit.reset()
        for (channel in listOf(2, 6, 255)) {
            assertEquals(FragmentedMessageAudit.Outcome.ORPHANED_FRAGMENT,
                audit.onMessage(channel, 10, 100, 0)!!.outcome)
            audit.onMessage(channel, 9, 10, 20)
            assertNull(audit.onMessage(channel, 10, 10, 0))
        }
        for (channel in listOf(-1, 256, 9999)) assertNull(audit.onMessage(channel, 9, 1, 2))
    }

    @Test fun `large observed totals cannot wrap into a healthy length`() {
        val audit = FragmentedMessageAudit()
        audit.onMessage(2, 9, Int.MAX_VALUE, Int.MAX_VALUE)
        val result = audit.onMessage(2, 10, Int.MAX_VALUE, 0)!!
        assertEquals(2L * Int.MAX_VALUE, result.observedTotal)
        assertEquals(-Int.MAX_VALUE.toLong(), result.delta)
    }

    @Test fun `diagnostic includes the actual byte discrepancy and measured totals`() {
        val audit = FragmentedMessageAudit()
        audit.onMessage(2, 9, 100, 300)
        val result = audit.onMessage(2, 10, 100, 0)!!
        for (field in listOf("channel=2", "fragments=2", "declaredTotal=300", "observed=200", "delta=100")) {
            assertTrue("missing $field in $result", result.toString().contains(field))
        }
    }

}
