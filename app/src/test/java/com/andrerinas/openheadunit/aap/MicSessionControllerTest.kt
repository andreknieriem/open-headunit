package com.andrerinas.openheadunit.aap

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MicSessionControllerTest {
    private class Fixture {
        val lifecycle = ArrayDeque<() -> Unit>()
        val sending = ArrayDeque<() -> Unit>()
        val captures = mutableListOf<MicSessionController.CaptureCallbacks>()
        val events = mutableListOf<String>()
        val reports = mutableListOf<MicUplinkMonitor.Report>()
        var stop: () -> Unit = { events.add("stop") }
        var start: () -> Int = { events.add("start"); 0 }
        val controller = MicSessionController(
            lifecycle = { lifecycle.add(it) }, sending = { sending.add(it) },
            startCapture = { captures.add(it); start() }, stopCapture = { stop() },
            response = { id, ok -> sending.add { events.add("response:$id:$ok") } },
            data = { bytes, _ -> events.add("data:${bytes.size}") },
            clockMs = { 1000L }, timestampUs = { 1000000L }, report = { reports.add(it) })
        fun capture(index: Int, bytes: Int) = captures[index].data(ByteArray(bytes), bytes, 1)
        fun sendAll() { while (sending.isNotEmpty()) sending.removeFirst()() }
        fun workAll() { while (lifecycle.isNotEmpty()) lifecycle.removeFirst()() }
    }

    @Test fun `stop retires data immediately without waiting for native cleanup and orders reopen`() {
        val f = Fixture()
        f.controller.open(2); f.workAll(); f.capture(0, 4096); f.sendAll()
        assertTrue(f.controller.acknowledge(1, 1))
        f.capture(0, 4096) // posted, but not claimed for sending
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.stop = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); f.events.add("stop") }
        f.controller.close(reply = true)
        val cleanup = Thread { f.lifecycle.removeFirst()() }.apply { start() }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            // These poll-side calls must complete while native stop is still blocked.
            f.controller.open(2)
            assertFalse(f.controller.acknowledge(1, 1))
            f.capture(0, 4096)
            f.sendAll()
            assertEquals(1, f.events.count { it.startsWith("data:") })
            assertEquals(4096, f.reports.single().discarded)
        } finally { release.countDown(); cleanup.join(3000) }
        f.workAll(); f.capture(1, 4096); f.sendAll()
        assertEquals(listOf("start", "response:1:true", "data:4096", "stop", "start",
            "response:1:true", "response:2:true", "data:4096"), f.events)
    }

    @Test fun `overflow counts queued rejected and remaining read bytes without reopening residue`() {
        val f = Fixture()
        f.controller.open(1); f.workAll()
        f.capture(0, 4096) // one posted frame, no sends or ACKs
        f.capture(0, 6 * 4096 + 100) // four pending, one rejected, the rest cancelled
        f.controller.open(1) // queues after A's abort cleanup and response
        f.workAll(); f.sendAll()
        assertEquals(7 * 4096 + 100, f.reports.single().discarded)
        assertEquals(0, f.reports.single().frames)
        assertTrue(f.events.indexOf("response:1:false") < f.events.indexOf("response:2:true"))
        f.capture(0, 4096) // stale callback cannot contaminate B
        f.capture(1, 4095); f.sendAll()
        assertFalse(f.events.any { it.startsWith("data:") })
        f.capture(1, 1); f.sendAll()
        assertEquals(1, f.events.count { it == "data:4096" })
    }

    @Test fun `stop during native open never activates the retired run`() {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.start = { entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); 0 }
        f.controller.open(1)
        val opening = Thread { f.lifecycle.removeFirst()() }.apply { start() }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            f.controller.close(reply = true)
            f.controller.open(1)
        } finally { release.countDown(); opening.join(3000) }
        f.start = { 0 }
        f.workAll()
        f.capture(0, 4096)
        f.capture(1, 4096)
        f.sendAll()
        assertEquals(1, f.events.count { it == "data:4096" })
        assertTrue(f.events.indexOf("response:2:true") < f.events.indexOf("data:4096"))
    }

    @Test fun `send claim is distinct from a queue reservation and a close counts each only once`() {
        val f = Fixture()
        f.controller.open(2); f.workAll()
        f.capture(0, 3 * 4096)
        assertFalse(f.controller.acknowledge(1, 1)) // reserved but no send has begun
        f.sending.removeFirst()() // response
        f.sending.removeFirst()() // first send claimed
        assertTrue(f.controller.acknowledge(1, 1))
        f.controller.close()
        f.sendAll(); f.workAll()
        assertEquals(1, f.reports.single().frames)
        assertEquals(8192, f.reports.single().discarded)
        assertEquals(1, f.reports.single().acks)
    }
    @Test fun `rejecting an active microphone retires data and allows a fresh later open`() {
        val f = Fixture()
        f.controller.open(1); f.workAll(); f.sendAll()
        f.capture(0, 4096)
        f.controller.reject()
        assertFalse(f.captures[0].isCurrent())
        f.controller.open(1); f.workAll()
        f.captures[0].failed() // terminal event from retired A must not retire B
        assertTrue(f.captures[1].isCurrent())
        f.capture(1, 4096); f.sendAll()
        assertEquals(1, f.events.count { it == "response:1:false" })
        assertEquals(1, f.events.count { it == "data:4096" })
    }

}
