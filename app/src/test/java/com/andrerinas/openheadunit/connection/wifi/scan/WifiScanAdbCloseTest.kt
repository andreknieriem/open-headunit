package com.andrerinas.openheadunit.connection.wifi.scan

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class WifiScanAdbCloseTest {
    @Test fun `failed recovery keeps ADB available instead of shutting down the helper`() = runBlocking {
        val gate = ScanControlAdbClose()
        var finished = false
        assertEquals(ScanControlAdbClose.Result.SCAN_RECOVERY, gate.run(restore = { false },
            close = { fail("Restore failed, ADB must stay available"); true },
            finished = { finished = true }))
        assertTrue(finished)
        assertFalse(gate.active)
    }

    @Test fun `close waits for restoration and refuses another simultaneous close`() = runBlocking {
        val gate = ScanControlAdbClose()
        val restored = CompletableDeferred<Boolean>()
        var closed = false
        val closing = async(start = CoroutineStart.UNDISPATCHED) {
            gate.run(restore = { restored.await() }, close = { closed = true; true }, finished = {})
        }
        assertFalse(closed)
        assertTrue(gate.active)
        assertEquals(ScanControlAdbClose.Result.BUSY, gate.run(restore = { fail("Duplicate restore"); true },
            close = { fail("Duplicate close"); true }, finished = { fail("Not the owner") }))
        restored.complete(true)
        assertEquals(ScanControlAdbClose.Result.CLOSED, closing.await())
        assertTrue(closed)
        assertFalse(gate.active)
    }

    @Test fun `cancellation and failed shutdown release the gate without bypassing restoration`() = runBlocking {
        val gate = ScanControlAdbClose()
        var finished = false
        val closing = launch(start = CoroutineStart.UNDISPATCHED) {
            gate.run(restore = { awaitCancellation() }, close = { fail("Cancelled close"); true },
                finished = { finished = true })
        }
        closing.cancelAndJoin()
        assertTrue(finished)
        assertFalse(gate.active)
        val order = mutableListOf<String>()
        assertEquals(ScanControlAdbClose.Result.ADB_RECOVERY, gate.run(restore = { order += "restore"; true },
            close = { order += "close"; false }, finished = { order += "finish" }))
        assertEquals(listOf("restore", "close", "finish"), order)
        assertFalse(gate.active)
    }
}
