package com.andrerinas.openheadunit.connection.wifi.modes.helper

import com.andrerinas.openheadunit.connection.ConnectionAdmission
import com.andrerinas.openheadunit.connection.ConnectionArbiter
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Owner
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Tier
import com.andrerinas.openheadunit.connection.prepareAndPublish
import com.andrerinas.openheadunit.connection.withClaim
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class NearbyClaimAdmissionTest {
    private class Candidate { var closed = false }
    private var logger: AppLog.Logger? = null
    private lateinit var clock: () -> Long

    @Before fun setUp() {
        logger = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) = Unit
        }
        clock = ConnectionArbiter.clock
        ConnectionArbiter.reset()
        ConnectionArbiter.clock = { 1000L }
        // Model an IO claim while the Main-thread wireless-stop action is still pending.
        ConnectionArbiter.actions = null
    }

    @After fun tearDown() {
        ConnectionArbiter.reset()
        ConnectionArbiter.clock = clock
        logger?.let { AppLog.LOGGER = it }
    }

    @Test fun preemptionRejectsCandidateBeforeNearbyStopRuns() = runBlocking {
        val guard = NearbyAttemptGuard()
        val attempt = guard.begin("phone")
        val claim = requireNotNull(ConnectionArbiter.claim(Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK, "Nearby A"))
        val admission = ConnectionAdmission { action -> guard.run(attempt, action = action) }.withClaim(claim)
        val ready = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val candidate = Candidate()
        val replacement = Candidate()
        var active: Candidate? = null
        var state = "disconnected"
        val handoff = async {
            admission.prepareAndPublish(
                {}, { candidate }, { ready.complete(Unit); finish.await() },
                { active?.closed = true; active = it; state = "A"; true }, { it.closed = true },
            )
        }
        ready.await()
        val newer = requireNotNull(ConnectionArbiter.claim(Tier.USER, Owner.MANUAL, "manual B"))
        active = replacement
        state = "connected"
        // No retire/cancel has run: only the claim ownership can reject A here.
        assertTrue(guard.run(attempt) {})
        finish.complete(Unit)
        assertFalse(handoff.await())
        assertSame(replacement, active)
        assertEquals("connected", state)
        assertFalse(replacement.closed)
        assertTrue(candidate.closed)
        assertTrue(ConnectionArbiter.holds(newer))
    }

    @Test fun higherPriorityClaimCannotSplitOwnershipCheckAndTransfer() {
        val claim = requireNotNull(ConnectionArbiter.claim(Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK, "Nearby"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val claiming = CountDownLatch(1)
        val claimed = CountDownLatch(1)
        val publish = thread {
            ConnectionAdmission.UNRESTRICTED.withClaim(claim).run {
                entered.countDown()
                release.await()
            }
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val preempt = thread {
            claiming.countDown()
            ConnectionArbiter.claim(Tier.USER, Owner.MANUAL, "manual")
            claimed.countDown()
        }
        try {
            assertTrue(claiming.await(2, TimeUnit.SECONDS))
            assertFalse(claimed.await(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
            publish.join(2000)
            preempt.join(2000)
        }
        assertEquals(0L, claimed.count)
    }
}
