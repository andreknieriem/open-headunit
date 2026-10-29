package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Bluetooth
import com.andrerinas.openheadunit.aap.protocol.proto.Common.MessageStatus
import com.andrerinas.openheadunit.aap.protocol.proto.Control.BluetoothPairingMethod
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException

class GalBluetoothControlTest {
    private val request get() = AapMessage(Channel.ID_BTH, 0x8001,
        Bluetooth.BluetoothPairingRequest.newBuilder().setPhoneAddress("AA:BB:CC:DD:EE:02")
            .setPairingMethod(BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN).build())

    private class Fixture {
        val jobs = mutableListOf<Runnable>()
        val replies = mutableListOf<Pair<AapMessage, () -> Boolean>>()
        val worker = mock<ExecutorService>()
        var lookups = 0
        var failure = false
        val control = AapControlBluetooth(mock(), { message, live -> replies += message to live }, worker,
            lookup = { _, address ->
                lookups++
                assertEquals("AA:BB:CC:DD:EE:01", address)
                if (failure) throw SecurityException("permission denied")
                GalBluetoothPairing.Result(MessageStatus.STATUS_SUCCESS, true)
            })
        init { doAnswer { jobs += it.getArgument<Runnable>(0); null }.whenever(worker).execute(any()) }
        fun advertise() = control.advertise("AA:BB:CC:DD:EE:01")
        fun drain() { while (jobs.isNotEmpty()) jobs.removeAt(0).run() }
        fun response() = replies.single().first.parse(Bluetooth.BluetoothPairingResponse.newBuilder()).build()
    }

    @Test fun `unadvertised requests are inert and repeated requests cannot grow the Binder queue`() {
        val f = Fixture()
        f.control.execute(request)
        assertTrue(f.jobs.isEmpty())
        f.advertise()
        repeat(20) { f.control.execute(request) }
        assertEquals(1, f.jobs.size)
        assertEquals(0, f.lookups)
        f.drain()
        assertEquals(1, f.lookups)
        assertEquals(MessageStatus.STATUS_SUCCESS, f.response().status)
        assertTrue(f.response().alreadyPaired)
        assertTrue(f.replies.single().second())
    }

    @Test fun `channel close cancels queued lookup and invalidates an already queued response`() {
        val f = Fixture()
        f.advertise()
        f.control.execute(request)
        f.control.closeChannel()
        f.drain()
        assertEquals(0, f.lookups)
        assertTrue(f.replies.isEmpty())
        f.control.execute(request)
        f.drain()
        assertEquals(1, f.lookups)
        assertTrue(f.replies.single().second())
        f.control.closeChannel()
        assertFalse(f.replies.single().second())
    }

    @Test fun `permission failure is unavailable not paired and releases request admission`() {
        val f = Fixture()
        f.advertise()
        f.failure = true
        f.control.execute(request)
        f.drain()
        assertEquals(MessageStatus.STATUS_BLUETOOTH_UNAVAILABLE, f.response().status)
        assertFalse(f.response().alreadyPaired)
        assertTrue(f.response().hasAlreadyPaired())
        f.control.execute(request)
        assertEquals(1, f.jobs.size)
    }

    @Test fun `executor rejection releases admission and shutdown makes queued sends stale`() {
        val f = Fixture()
        f.advertise()
        doThrow(RejectedExecutionException()).whenever(f.worker).execute(any())
        f.control.execute(request)
        doAnswer { f.jobs += it.getArgument<Runnable>(0); null }.whenever(f.worker).execute(any())
        f.control.execute(request)
        f.drain()
        assertEquals(1, f.lookups)
        f.control.shutdown()
        assertFalse(f.replies.single().second())
        verify(f.worker).shutdownNow()
    }
}
