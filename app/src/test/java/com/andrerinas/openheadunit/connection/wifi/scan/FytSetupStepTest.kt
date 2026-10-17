package com.andrerinas.openheadunit.connection.wifi.scan

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FytSetupStepTest {
    @Test fun `local timeout reports failure instead of silently cancelling the screen operation`() = runBlocking {
        val events = mutableListOf<String>()
        val steps = FytSetupStep(events::add)
        val error = runCatching {
            steps.run(FytSetupStep.Stage.OPEN_ADB) { withTimeout(10) { awaitCancellation() } }
        }.exceptionOrNull()
        assertTrue(error is FytSetupStep.Failure)
        assertEquals(FytSetupStep.Stage.OPEN_ADB, (error as FytSetupStep.Failure).stage)
        assertTrue(error.cause is TimeoutCancellationException)
        assertTrue(currentCoroutineContext().isActive)
        assertTrue(events.any { it.contains("failed") })
    }

    @Test fun `leaving the screen remains cancellation and still runs rollback`() = runBlocking {
        val events = mutableListOf<String>()
        val steps = FytSetupStep(events::add)
        val entered = CompletableDeferred<Unit>()
        var failure: Exception? = null
        var rolledBack = false
        val job = launch {
            try {
                steps.run(FytSetupStep.Stage.WAIT_SHIZUKU) { entered.complete(Unit); awaitCancellation() }
            } catch (e: Exception) { failure = e }
            finally { withContext(NonCancellable) { rolledBack = true } }
        }
        entered.await()
        job.cancelAndJoin()
        assertTrue(failure is CancellationException)
        assertTrue(rolledBack)
        assertFalse(events.any { it.contains("failed") })
    }

    @Test fun `disconnect interrupts blocking ADB approval without submitting a starter`() = runBlocking {
        val steps = FytSetupStep { }
        val entered = CompletableDeferred<Unit>()
        var submitted = false
        var interrupted = false
        val job = launch {
            steps.run(FytSetupStep.Stage.CONNECT_ADB) {
                runInterruptible(Dispatchers.IO) {
                    entered.complete(Unit)
                    try { java.util.concurrent.CountDownLatch(1).await() }
                    catch (e: InterruptedException) { interrupted = true; throw e }
                }
            }
            submitted = true
        }
        entered.await()
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(interrupted)
        assertFalse(submitted)
    }

    @Test fun `outer timeout does not become a recoverable local failure`() = runBlocking {
        val steps = FytSetupStep { }
        val error = runCatching {
            withTimeout(10) { steps.run(FytSetupStep.Stage.WAIT_SHIZUKU) { awaitCancellation() } }
        }.exceptionOrNull()
        assertTrue(error is TimeoutCancellationException)
    }

    @Test fun `failure retains original stage after successful cleanup`() = runBlocking {
        val steps = FytSetupStep { }
        val original = IOException("connection refused")
        val error = runCatching {
            try { steps.run(FytSetupStep.Stage.CONNECT_ADB) { throw original } }
            finally { steps.run(FytSetupStep.Stage.RESTORE_ADB) { } }
        }.exceptionOrNull() as FytSetupStep.Failure
        assertEquals(FytSetupStep.Stage.CONNECT_ADB, error.stage)
        assertSame(original, error.cause)
    }

    @Test fun `cleanup failure is distinguished from startup and both remain in diagnostics`() = runBlocking {
        val events = mutableListOf<String>()
        val steps = FytSetupStep(events::add)
        val error = runCatching {
            try { steps.run(FytSetupStep.Stage.START_SHIZUKU) { throw IOException("startup failed") } }
            finally { steps.run(FytSetupStep.Stage.RESTORE_ADB) { throw IOException("restore failed") } }
        }.exceptionOrNull() as FytSetupStep.Failure
        assertEquals(FytSetupStep.Stage.RESTORE_ADB, error.stage)
        assertTrue(events.any { it.startsWith("START_SHIZUKU: failed") })
        assertTrue(events.any { it.startsWith("RESTORE_ADB: failed") })
    }
}
