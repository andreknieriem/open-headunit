package com.andrerinas.openheadunit.connection.bluetooth.xyauto

import org.junit.Assert.*
import org.junit.Test

class XyAutoMediaLeaseTest {
    private class Fake(var value: String? = "none") : XyAutoMediaLease.Port {
        var journal: XyAutoMediaLease.Journal? = null
        var bootId: String? = "1"
        override fun boot() = bootId
        var writable = true
        var disk = true
        var corrupt = false
        var readable = true
        var reads = 0
        var afterSave: (() -> Unit)? = null
        var afterWrite: (() -> Unit)? = null
        val writes = mutableListOf<String>()
        override fun read(): String? { reads++; return if (readable) value else null }
        override fun write(value: String): Boolean {
            check(journal != null) { "property changed before durable journal" }
            writes += value
            if (writable) this.value = value
            afterWrite?.invoke()
            return writable
        }
        override fun saved(): XyAutoMediaLease.Journal? {
            if (corrupt) throw java.io.IOException("corrupt")
            return journal
        }
        override fun save(record: XyAutoMediaLease.Journal): Boolean {
            if (disk) journal = record
            afterSave?.invoke()
            return disk
        }
        override fun clear(): Boolean { if (disk) { journal = null; corrupt = false }; return disk }
    }
    @Test fun normalSessionAndDuplicateAcquire() {
        val p = Fake(); val lease = XyAutoMediaLease(p)
        assertEquals(XyAutoMediaLease.Result.ACTIVE, lease.acquire())
        assertEquals(XyAutoMediaLease.Result.ACTIVE, lease.acquire())
        assertEquals(listOf("auto_wireless"), p.writes)
        assertEquals(XyAutoMediaLease.Result.RESTORED, lease.release())
        assertEquals("none", p.value); assertNull(p.journal)
    }
    @Test fun emptyIdleValueSurvivesCrash() {
        val p = Fake(""); XyAutoMediaLease(p).acquire()
        assertEquals(XyAutoMediaLease.Result.RESTORED, XyAutoMediaLease(p).release())
        assertEquals("", p.value)
    }
    @Test fun activeProjectionIsNeverReplaced() {
        for (value in listOf("auto_wireless", "carplay_wireless", "hicar", "unknown")) {
            val p = Fake(value)
            assertEquals(XyAutoMediaLease.Result.BUSY, XyAutoMediaLease(p).acquire())
            assertTrue(p.writes.isEmpty()); assertNull(p.journal)
        }
    }
    @Test fun newOwnerIsNotRestoredOver() {
        val p = Fake(); val lease = XyAutoMediaLease(p); lease.acquire()
        p.value = "carplay_wireless"
        lease.release()
        assertEquals("carplay_wireless", p.value); assertEquals(1, p.writes.size)
    }
    @Test fun failedRestoreKeepsJournalForRetry() {
        val p = Fake(); val lease = XyAutoMediaLease(p); lease.acquire(); p.writable = false
        assertEquals(XyAutoMediaLease.Result.RECOVERY_PENDING, lease.release())
        assertEquals("none", p.journal?.previous)
        assertEquals(XyAutoMediaLease.Result.RECOVERY_PENDING, lease.acquire())
        p.writable = true
        assertEquals(XyAutoMediaLease.Result.RESTORED, XyAutoMediaLease(p).release())
    }
    @Test fun failedJournalDoesNotMutateFirmware() {
        val p = Fake(); p.disk = false
        assertEquals(XyAutoMediaLease.Result.UNAVAILABLE, XyAutoMediaLease(p).acquire())
        assertTrue(p.writes.isEmpty())
    }
    @Test fun unavailablePropertyCannotBecomeAnIdleAcquisition() {
        val p = Fake(); p.readable = false
        assertEquals(XyAutoMediaLease.Result.UNAVAILABLE, XyAutoMediaLease(p).acquire())
        assertTrue(p.writes.isEmpty()); assertNull(p.journal)
    }
    @Test fun losingReadAccessAfterAWriteKeepsTheRecoveryRecord() {
        val p = Fake(); val lease = XyAutoMediaLease(p)
        p.afterWrite = { p.readable = false }
        assertEquals(XyAutoMediaLease.Result.RECOVERY_PENDING, lease.acquire())
        assertEquals("auto_wireless", p.value)
        assertEquals("none", p.journal?.previous)
        assertEquals(listOf("auto_wireless"), p.writes)
        p.afterWrite = null; p.readable = true
        assertEquals(XyAutoMediaLease.Result.RESTORED, lease.release())
        assertEquals("none", p.value); assertNull(p.journal)
    }
    @Test fun failedWriteDoesNotBecomeActive() {
        val p = Fake(); p.writable = false
        assertEquals(XyAutoMediaLease.Result.UNAVAILABLE, XyAutoMediaLease(p).acquire())
        assertEquals("none", p.value); assertNull(p.journal)
    }
    @Test fun foreignWriterDuringJournalSaveWins() {
        val p = Fake(); p.afterSave = { p.value = "hicar" }
        assertEquals(XyAutoMediaLease.Result.BUSY, XyAutoMediaLease(p).acquire())
        assertTrue(p.writes.isEmpty())
    }
    @Test fun unreadableStateKeepsRecoveryJournal() {
        val p = Fake(); val lease = XyAutoMediaLease(p); lease.acquire(); p.value = null
        assertEquals(XyAutoMediaLease.Result.RECOVERY_PENDING, lease.release())
        assertEquals("none", p.journal?.previous)
    }
    @Test fun rebootThenAnotherAaOwnerIsNotRestoredOver() {
        val p = Fake(); XyAutoMediaLease(p).acquire(); p.bootId = "2"
        p.writes.clear()
        assertEquals(XyAutoMediaLease.Result.RECOVERY_PENDING, XyAutoMediaLease(p).release())
        assertEquals("auto_wireless", p.value); assertTrue(p.writes.isEmpty()); assertNotNull(p.journal)
        p.value = "none"
        assertEquals(XyAutoMediaLease.Result.RESTORED, XyAutoMediaLease(p).release())
        assertNull(p.journal); assertTrue(p.writes.isEmpty())
    }
    @Test fun missingBootIdentityCannotAcquireOrRestore() {
        val p = Fake(); p.bootId = null
        assertEquals(XyAutoMediaLease.Result.UNAVAILABLE, XyAutoMediaLease(p).acquire())
        p.journal = XyAutoMediaLease.Journal("none", "1")
        assertEquals(XyAutoMediaLease.Result.RECOVERY_PENDING, XyAutoMediaLease(p).release())
        assertTrue(p.writes.isEmpty())
    }
    @Test fun staleJournalAfterRebootDoesNotWrite() {
        val p = Fake(); p.journal = XyAutoMediaLease.Journal("", "1"); p.value = "none"
        assertEquals(XyAutoMediaLease.Result.RESTORED, XyAutoMediaLease(p).release())
        assertTrue(p.writes.isEmpty())
    }
    @Test fun corruptRecordNeverWritesAnInventedPreviousValue() {
        for (value in listOf(null, "auto_wireless")) {
            val p = Fake(value); p.corrupt = true
            assertEquals(XyAutoMediaLease.Result.RECOVERY_PENDING, XyAutoMediaLease(p).release())
            assertEquals(XyAutoMediaLease.Result.RECOVERY_PENDING, XyAutoMediaLease(p).acquire())
            assertTrue(p.writes.isEmpty()); assertTrue(p.corrupt)
        }
        val p = Fake("none"); p.corrupt = true
        assertEquals(XyAutoMediaLease.Result.RESTORED, XyAutoMediaLease(p).release())
        assertFalse(p.corrupt); assertTrue(p.writes.isEmpty())
        assertEquals(XyAutoMediaLease.Result.ACTIVE, XyAutoMediaLease(p).acquire())
    }
    @Test fun absentRecordDoesNotReadVendorProperty() {
        val p = Fake("auto_wireless")
        assertEquals(XyAutoMediaLease.Result.IDLE, XyAutoMediaLease(p).release())
        assertEquals(0, p.reads)
    }
    @Test fun cancellationDuringJournalOrPropertyIoCannotLeaveAnActiveLease() {
        for (cancelDuringWrite in listOf(false, true)) {
            val p = Fake(); var current = true
            if (cancelDuringWrite) p.afterWrite = { current = false }
            else p.afterSave = { current = false }
            assertEquals(XyAutoMediaLease.Result.CANCELLED, XyAutoMediaLease(p).acquire { current })
            assertEquals("none", p.value); assertNull(p.journal)
            assertEquals(if (cancelDuringWrite) listOf("auto_wireless", "none") else emptyList<String>(), p.writes)
        }
    }
}
