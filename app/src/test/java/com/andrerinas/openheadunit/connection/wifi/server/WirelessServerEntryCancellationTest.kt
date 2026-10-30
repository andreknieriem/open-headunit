package com.andrerinas.openheadunit.connection.wifi.server

import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.CommManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.net.Socket

class WirelessServerEntryCancellationTest {
    @Test fun `accepted socket closes when service cancellation prevents handoff entry`() = runBlocking {
        val service = mock(AapService::class.java)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        `when`(service.serviceScope).thenReturn(scope)
        val server = WirelessServer(false, service, mock(WirelessServerHistory::class.java))
        val socket = Socket()
        try {
            scope.cancel()
            server.handOffClient(socket, mock(CommManager::class.java)).join()
            assertTrue("a cancelled child must not leave an accepted peer waiting indefinitely", socket.isClosed)
        } finally {
            scope.cancel()
            socket.close()
        }
    }
}
