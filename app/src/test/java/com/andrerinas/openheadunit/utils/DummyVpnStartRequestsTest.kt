package com.andrerinas.openheadunit.utils

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class DummyVpnStartRequestsTest {
    @Test fun stopRejectsDeliveredButNotYetExecutedConfiguration() {
        val requests = DummyVpnStartRequests()
        val queued = requests.issue()
        var tunnel = true // Existing SESSION tunnel is bound when Self requests another config.
        requests.stop { tunnel = false }
        assertFalse(requests.runIfCurrent(queued) { tunnel = true })
        assertFalse(tunnel)
        val fresh = requests.issue()
        assertFalse(requests.runIfCurrent(queued) { fail("old command replaced a new request") })
        assertTrue(requests.runIfCurrent(fresh) { tunnel = true })
        assertTrue(tunnel)
    }

    @Test fun onlyLatestRequestInThisProcessMayEstablish() {
        val requests = DummyVpnStartRequests()
        val old = requests.issue()
        val latest = requests.issue()
        assertFalse(requests.runIfCurrent(old) { fail("superseded configuration") })
        assertFalse(requests.runIfCurrent(null) { fail("missing token") })
        assertFalse(DummyVpnStartRequests().runIfCurrent(latest) { fail("previous process token") })
        assertTrue(requests.runIfCurrent(latest) {})
    }

    @Test fun stopCannotMissATunnelBetweenValidationAndEstablish() {
        val requests = DummyVpnStartRequests()
        val token = requests.issue()
        val entered = CountDownLatch(1)
        val finishStart = CountDownLatch(1)
        var tunnel = false
        val starter = thread {
            requests.runIfCurrent(token) {
                entered.countDown()
                check(finishStart.await(5, TimeUnit.SECONDS))
                tunnel = true
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val stopper = thread { requests.stop { tunnel = false } }
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (stopper.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, stopper.state)
        } finally { finishStart.countDown() }
        starter.join(5000); stopper.join(5000)
        assertFalse(starter.isAlive || stopper.isAlive)
        assertFalse(tunnel)
    }
}
