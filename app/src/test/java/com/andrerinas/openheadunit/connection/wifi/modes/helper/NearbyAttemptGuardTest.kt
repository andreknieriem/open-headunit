package com.andrerinas.openheadunit.connection.wifi.modes.helper

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class NearbyAttemptGuardTest {
    @Test fun delayedPublicationCannotRunAfterStop() {
        val guard = NearbyAttemptGuard()
        val attempt = guard.begin("phone")
        guard.retire {}
        assertFalse(guard.run(attempt) { fail("retired tunnel published") })
    }

    @Test fun reusedEndpointDoesNotReviveOldCallbacks() {
        val guard = NearbyAttemptGuard()
        val old = guard.begin("phone")
        val current = guard.begin("phone")
        assertFalse(guard.run(old) { fail("old callback accepted") })
        assertTrue(guard.run(current) {})
        assertFalse(guard.run(current, "other phone") { fail("foreign stream accepted") })
    }

    @Test fun staleFailureCannotRetireReplacement() {
        val guard = NearbyAttemptGuard()
        val old = guard.begin("old")
        val current = guard.begin("current")
        guard.run(old) { guard.retire {} }
        assertTrue(guard.run(current) {})
    }

    @Test fun synchronousFailureMayRetireItsOwnAttempt() {
        val guard = NearbyAttemptGuard()
        val attempt = guard.begin("phone")
        guard.run(attempt) { guard.retire { assertSame(attempt, it) } }
        assertFalse(guard.run(attempt) { fail() })
    }

    @Test fun stopCannotSplitResourcePublication() {
        val guard = NearbyAttemptGuard()
        val attempt = guard.begin("phone")
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val publisher = thread {
            guard.run(attempt) {
                events.add("allocate")
                entered.countDown()
                assertTrue(finish.await(5, TimeUnit.SECONDS))
                events.add("publish")
            }
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val stopper = thread { guard.retire { events.add("close"); stopped.countDown() } }
        assertFalse(stopped.await(50, TimeUnit.MILLISECONDS))
        finish.countDown()
        publisher.join(5000)
        stopper.join(5000)
        assertEquals(listOf("allocate", "publish", "close"), events)
    }
}
