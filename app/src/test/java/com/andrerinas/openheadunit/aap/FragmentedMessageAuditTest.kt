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
}
