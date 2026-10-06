package com.andrerinas.openheadunit.connection.wifi.modes.helper

import com.andrerinas.openheadunit.connection.ConnectionAdmission
import com.andrerinas.openheadunit.connection.prepareAndPublish
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NearbyAdmissionTest {
    private class Candidate { var closed = false }

    @Test fun retiredHandoffCannotReplaceNewConnectionAfterTeardownWait() = runBlocking {
        val guard = NearbyAttemptGuard()
        val old = guard.begin("phone")
        val admission = ConnectionAdmission { action -> guard.run(old, action = action) }
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val candidate = Candidate()
        val replacement = Candidate()
        var active = Candidate()
        var state = "connected"
        val handoff = async {
            admission.prepareAndPublish(
                { entered.complete(Unit); finish.await() }, { candidate }, {},
                { active = it; state = "old"; true }, { it.closed = true },
            )
        }
        entered.await()
        guard.retire {}
        guard.begin("phone")
        active = replacement
        finish.complete(Unit)
        assertFalse(handoff.await())
        assertSame(replacement, active)
        assertEquals("connected", state)
        assertFalse(replacement.closed)
        assertTrue(candidate.closed)
    }

    @Test fun cancelledPreparationClosesOnlyItsCandidateAfterReplacementIsPublished() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val candidate = Candidate()
        val replacement = Candidate()
        var active = Candidate()
        var state = "connected"
        val job = launch {
            ConnectionAdmission.UNRESTRICTED.prepareAndPublish(
                {}, { candidate }, {
                    entered.complete(Unit)
                    withContext(NonCancellable) { finish.await() }
                }, { active = it; state = "old"; true }, { it.closed = true },
            )
        }
        entered.await()
        job.cancel()
        active = replacement
        finish.complete(Unit)
        job.join()
        assertSame(replacement, active)
        assertEquals("connected", state)
        assertFalse(replacement.closed)
        assertTrue(candidate.closed)
    }

    @Test fun cancelledPreparationWithoutReplacementLeavesNoConnectingState() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val candidate = Candidate()
        var active: Candidate? = null
        var state = "disconnected"
        val job = launch {
            ConnectionAdmission.UNRESTRICTED.prepareAndPublish(
                {}, { candidate }, { entered.complete(Unit); awaitCancellation() },
                { active = it; state = "connected"; true }, { it.closed = true },
            )
        }
        entered.await()
        job.cancelAndJoin()
        assertNull(active)
        assertEquals("disconnected", state)
        assertTrue(candidate.closed)
    }

    @Test fun cancelledTeardownWaitDoesNotEvenAllocateCandidate() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var created = false
        val job = launch {
            ConnectionAdmission.UNRESTRICTED.prepareAndPublish(
                { entered.complete(Unit); withContext(NonCancellable) { finish.await() } },
                { created = true; Candidate() }, {}, { true }, { it.closed = true },
            )
        }
        entered.await()
        job.cancel()
        finish.complete(Unit)
        job.join()
        assertFalse(created)
    }

    @Test fun cancellationAfterTransferDoesNotDisposePublishedConnection() = runBlocking {
        val candidate = Candidate()
        var active: Candidate? = null
        val job = launch {
            val owner = currentCoroutineContext().job
            assertTrue(ConnectionAdmission.UNRESTRICTED.prepareAndPublish(
                {}, { candidate }, {},
                { active = it; owner.cancel(); true },
                { it.closed = true },
            ))
        }
        job.join()
        assertSame(candidate, active)
        assertFalse(candidate.closed)
    }
}
