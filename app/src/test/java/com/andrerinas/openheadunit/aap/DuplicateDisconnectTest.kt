package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.CommManager.ConnectionState
import com.andrerinas.openheadunit.utils.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

class DuplicateDisconnectTest {
    private class QueuedIo : CoroutineDispatcher() {
        val tasks = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
    }

    private fun field(manager: CommManager, name: String, value: Any) {
        CommManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
    }

    @Test fun linkLossAndTransportQuitDeliverOneEndAndPreservePlaybackSnapshot() = runBlocking {
        val manager = mock(CommManager::class.java, CALLS_REAL_METHODS)
        val states = MutableStateFlow<ConnectionState>(ConnectionState.TransportStarted)
        val io = QueuedIo()
        val cleanupScope = CoroutineScope(SupervisorJob() + io)
        field(manager, "_connectionState", states)
        field(manager, "transportLifecycleLock", Any())
        field(manager, "_scope", cleanupScope)
        field(manager, "settings", mock(Settings::class.java))
        var callbacks = 0
        var lastPlaying: Boolean? = true
        var wasPlaying = false
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            states.collect { state ->
                if (state is ConnectionState.Disconnected) {
                    callbacks++
                    // The service consumes this playback snapshot once at onDisconnected.
                    wasPlaying = lastPlaying == true
                    lastPlaying = null
                }
            }
        }
        try {
            manager.disconnectForLinkLoss(0)
            yield() // Main consumes D1 while IO cleanup remains queued.
            val ended = states.value
            assertEquals(1, callbacks)
            assertTrue(wasPlaying)
            val quit = CommManager::class.java.getDeclaredMethod("transportedQuited", AapTransport::class.java, Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }
            quit.invoke(manager, mock(AapTransport::class.java), false)
            yield()
            manager.disconnectForLinkLoss(0)
            manager.disconnect(honorKillOnDisconnect = false)
            yield()
            assertSame(ended, states.value)
            assertEquals(1, callbacks)
            assertTrue(wasPlaying)
            assertEquals("Duplicate exits must not queue competing cleanup jobs", 1, io.tasks.size)
        } finally {
            observer.cancelAndJoin()
            cleanupScope.cancel()
        }
    }

    @Test fun concurrentTerminalPublishersChooseOneOwnerAndNextAttemptGetsANewEnd() {
        val manager = mock(CommManager::class.java, CALLS_REAL_METHODS)
        val states = MutableStateFlow<ConnectionState>(ConnectionState.TransportStarted)
        field(manager, "_connectionState", states)
        field(manager, "transportLifecycleLock", Any())
        val io = QueuedIo()
        val cleanupScope = CoroutineScope(SupervisorJob() + io)
        field(manager, "_scope", cleanupScope)
        field(manager, "settings", mock(Settings::class.java))
        val pool = Executors.newFixedThreadPool(4)
        try {
            val start = CountDownLatch(1)
            val results = (0 until 20).map {
                pool.submit { start.await(); manager.disconnect(sendByeBye = false,
                    isUserExit = false, honorKillOnDisconnect = false) }
            }
            start.countDown()
            results.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, io.tasks.size)
            val previous = states.value
            assertTrue(previous is ConnectionState.Disconnected)
            // Model admission of the next attempt while keeping physical cleanup queued.
            field(manager, "disconnectRequested", false)
            states.value = ConnectionState.Connecting
            manager.disconnect(sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false)
            assertEquals(2, io.tasks.size)
            assertTrue(states.value is ConnectionState.Disconnected)
            assertNotSame(previous, states.value)
        } finally { pool.shutdownNow(); cleanupScope.cancel() }
    }
}
