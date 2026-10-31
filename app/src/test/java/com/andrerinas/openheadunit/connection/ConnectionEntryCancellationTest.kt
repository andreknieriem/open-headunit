package com.andrerinas.openheadunit.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mock
import java.net.Socket

class ConnectionEntryCancellationTest {
    @Test fun `cancellation before IO entry closes handed socket without revoking pending Exit`() = runBlocking {
        val manager = mock(CommManager::class.java, CALLS_REAL_METHODS)
        val policy = AppExitPolicy()
        val exit = policy.request(killProcess = false, userStop = true, requiresKillOnDisconnect = false)
        CommManager::class.java.getDeclaredField("transportLifecycleLock")
            .apply { isAccessible = true }.set(manager, Any())
        CommManager::class.java.getDeclaredField("appExitPolicy")
            .apply { isAccessible = true }.set(manager, policy)
        CommManager::class.java.getDeclaredField("connectionIoDispatcher")
            .apply { isAccessible = true }.set(manager, kotlinx.coroutines.Dispatchers.IO)
        val socket = Socket()
        try {
            // UND starts the real public entry, but its IO context cannot execute cancelled work.
            val connecting = async(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                manager.connect(socket, ticket = manager.appConnectionTicket())
            }
            try {
                connecting.await()
                fail("cancelled connection entry must not return as an admitted connection")
            } catch (_: CancellationException) {}
            assertTrue("the callee owns the socket even when its IO body never starts", socket.isClosed)
            assertTrue("unentered work must not acquire app ownership", policy.owns(exit.id, false))
        } finally {
            socket.close()
        }
    }
}
