package com.andrerinas.openheadunit.connection

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SettingsRestartRecoveryTest {
    @Test fun `USB failure after deadline is evaluated after its owning attempt completes`() = runBlocking {
        for (success in listOf(false, true)) {
            val saved = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART)
            var state: CommManager.ConnectionState = saved
            var pending: Job? = null
            val finish = CompletableDeferred<Unit>()
            val awaiting = CompletableDeferred<Unit>()
            var rearms = 0
            val recovery = launch {
                SettingsRestartRecovery.run(0, { saved.acceptsSettingsRestart(state) }, {
                    state = CommManager.ConnectionState.Connecting
                    pending = this@runBlocking.launch {
                        finish.await()
                        state = if (success) CommManager.ConnectionState.Connected
                            else CommManager.ConnectionState.Disconnected(settingsRetryOwner = saved)
                    }
                }, { rearms++ },
                    awaitRetryCompletion = { awaiting.complete(Unit); pending?.join() },
                    isRetryInFlight = { pending?.isActive == true })
            }
            awaiting.await()
            assertEquals(0, rearms)
            finish.complete(Unit)
            recovery.join()
            assertEquals(if (success) 0 else 1, rearms)
        }
    }

    @Test fun `refused dial or missing USB device falls back once without another explicit retry`() = runBlocking {
        var retries = 0
        var rearms = 0
        SettingsRestartRecovery.run(0, { true }, { retries++ }, { rearms++ })
        assertEquals(1, retries)
        assertEquals(1, rearms)
    }

    @Test fun `connection that starts or fails normally supersedes the Save fallback`() = runBlocking {
        for (replacement in listOf(
            CommManager.ConnectionState.Connecting,
            CommManager.ConnectionState.Disconnected(),
            CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART),
        )) {
            val saved = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART)
            var state: CommManager.ConnectionState = saved
            var rearms = 0
            SettingsRestartRecovery.run(0, { state === saved }, { state = replacement }, { rearms++ })
            assertEquals(0, rearms)
        }
    }

    @Test fun `fallback rechecks identity when retry is still suspended`() = runBlocking {
        var current = true
        var rearms = 0
        SettingsRestartRecovery.run(0, { current }, { current = false; yield() }, { rearms++ })
        assertEquals(0, rearms)
    }

    @Test fun `service cancellation cancels a pending fallback`() = runBlocking {
        var rearms = 0
        val started = CompletableDeferred<Unit>()
        val job = launch {
            SettingsRestartRecovery.run(30_000, { true }, { started.complete(Unit); awaitCancellation() }, { rearms++ })
        }
        started.await()
        job.cancelAndJoin()
        assertEquals(0, rearms)
    }

    @Test fun `obsolete state cannot send a retry or rearm`() = runBlocking {
        SettingsRestartRecovery.run(0, { false }, { fail("obsolete retry") }, { fail("obsolete rearm") })
    }
    @Test fun `new Self launch invalidates the entire fallback while AAP state is unchanged`() = runBlocking {
        val savedState = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART)
        val currentState: CommManager.ConnectionState = savedState
        val savedLaunch = Job()
        var currentLaunch: Job = savedLaunch
        val listener = java.net.ServerSocket(0)
        try {
            SettingsRestartRecovery.run(0,
                { currentState === savedState && currentLaunch === savedLaunch },
                { currentLaunch = Job(); yield() },
                { listener.close() })
            assertFalse(listener.isClosed)
            assertTrue(currentLaunch.isActive)
        } finally {
            listener.close()
            savedLaunch.cancel()
            currentLaunch.cancel()
        }
    }

}
