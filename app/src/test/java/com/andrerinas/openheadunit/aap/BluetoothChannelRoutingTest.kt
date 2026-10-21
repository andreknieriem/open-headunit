package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.kotlin.mock

class BluetoothChannelRoutingTest {
    private class Control : AapControl {
        var calls = 0
        var closed = false
        override fun execute(message: AapMessage): Int { calls++; return 0 }
        override fun close() { closed = true }
    }
    @Test fun bluetoothTypesDoNotReachMediaAndControlFlagsStillRouteGenerically() {
        val service = Control(); val media = Control(); val bt = Control()
        val gateway = AapControlGateway(mock(), service, media, Control(), Control(), bt)
        for (type in listOf(0x8001, 0x8004)) {
            gateway.execute(AapMessage(Channel.ID_BTH, 0, type, 2, 2, byteArrayOf(0,0)))
        }
        assertEquals(2, bt.calls); assertEquals(0, media.calls)
        gateway.execute(AapMessage(Channel.ID_BTH, AapMessageFraming.FLAG_BIT_CONTROL.toByte(),
            11, 2, 2, byteArrayOf(0,0)))
        assertEquals(1, service.calls); assertEquals(2, bt.calls)
        gateway.close(); assertEquals(true, bt.closed)
    }
}
