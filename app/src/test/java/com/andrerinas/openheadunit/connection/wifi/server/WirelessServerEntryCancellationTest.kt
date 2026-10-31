package com.andrerinas.openheadunit.connection.wifi.server

import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.ConnectionOperation
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.withAppExitDispatcherForTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.net.Socket

class WirelessServerEntryCancellationTest {
    @Test fun `accepted socket closes when service cancellation prevents handoff entry`() = runBlocking {
        val service = mock(AapService::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        `when`(service.serviceScope).thenReturn(scope)
        val comm = mock(CommManager::class.java, CALLS_REAL_METHODS).withAppExitDispatcherForTest()
        val operation = ConnectionOperation(comm.appConnectionTicket(), null)
        assertTrue("the handoff must have valid authority before service cancellation", comm.isCurrent(operation))
        val server = WirelessServer(false, service, mock(WirelessServerHistory::class.java), operation)
        val socket = Socket()
        try {
            scope.cancel()
            val delivery = server.handOffClient(socket, comm, operation)
            delivery.join()
            assertTrue("the service's cancelled scope must cancel the handoff child", delivery.isCancelled)
            assertTrue("a cancelled child must not leave an accepted peer waiting indefinitely", socket.isClosed)
        } finally {
            scope.cancel()
            socket.close()
        }
    }
}
