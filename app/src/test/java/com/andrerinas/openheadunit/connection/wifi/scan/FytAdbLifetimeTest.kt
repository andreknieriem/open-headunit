package com.andrerinas.openheadunit.connection.wifi.scan

import org.junit.Assert.*
import org.junit.Test

class FytAdbLifetimeTest {
    private val opened = FytAdbPortJournal.Record("-1", 12, "running", 43127, keepOpen = true)

    @Test fun `retained ADB survives reopening the app and remains manually closable`() {
        assertFalse(opened.needsRecovery(12))
        assertTrue(opened.canClose(12))
    }

    @Test fun `failed or cancelled startup does not turn a retained listener into rollback`() {
        // The journal is written before opening ADB and no success flag is needed to retain it.
        assertTrue(opened.keepOpen)
        assertFalse(opened.stopRequested)
        assertFalse(opened.needsRecovery(12))
    }

    @Test fun `interrupted explicit close is resumed and retains daemon stop intent`() {
        val closing = opened.copy(keepOpen = false, stopRequested = true)
        assertTrue(closing.needsRecovery(12))
        assertTrue(closing.canClose(12))
        assertTrue(closing.stopRequested)
        assertEquals(opened.port, closing.port)
        assertEquals(opened.previous, closing.previous)
    }

    @Test fun `legacy records still request the rollback promised by older builds`() {
        val legacy = FytAdbPortJournal.Record("-1", 12, "running", 43127)
        assertTrue(legacy.needsRecovery(12))
        assertFalse(legacy.stopRequested)
    }

    @Test fun `records from an earlier boot cannot stop a newly started daemon`() {
        for (record in listOf(opened, opened.copy(keepOpen = false, stopRequested = true))) {
            assertFalse(record.needsRecovery(13))
            assertFalse(record.canClose(13))
        }
    }
}
