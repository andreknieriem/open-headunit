package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Bluetooth
import com.andrerinas.openheadunit.aap.protocol.proto.Common.MessageStatus
import com.andrerinas.openheadunit.aap.protocol.proto.Control.BluetoothPairingMethod
import org.junit.Assert.*
import org.junit.Test

class GalBluetoothPairingTest {
    private val car = "AA:BB:CC:DD:EE:01"
    private val phone = "AA:BB:CC:DD:EE:02"
    private val pin = BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN

    @Test fun `success requires a bond on the exact advertised local adapter`() {
        val bonded = GalBluetoothPairing.response(phone, pin, car, car.lowercase(), true, setOf(phone.lowercase()), setOf(phone))
        assertEquals(GalBluetoothPairing.Result(MessageStatus.STATUS_SUCCESS, true), bonded)
        for (local in listOf(null, "02:00:00:00:00:00", phone)) {
            assertEquals(MessageStatus.STATUS_BLUETOOTH_UNAVAILABLE,
                GalBluetoothPairing.response(phone, pin, car, local, true, setOf(phone), setOf(phone)).status)
        }
        assertEquals(MessageStatus.STATUS_BLUETOOTH_UNAVAILABLE,
            GalBluetoothPairing.response(phone, pin, car, car, false, setOf(phone), setOf(phone)).status)
    }

    @Test fun `unbonded or unsupported requests never return success false`() {
        val waiting = GalBluetoothPairing.response(phone, pin, car, car, true, emptySet(), null)
        assertEquals(MessageStatus.STATUS_BLUETOOTH_PAIRING_DELAYED, waiting.status)
        assertFalse(waiting.alreadyPaired)
        for (peers in listOf(null, emptySet(), setOf(car))) {
            assertEquals(MessageStatus.STATUS_BLUETOOTH_PAIRING_DELAYED,
                GalBluetoothPairing.response(phone, pin, car, car, true, setOf(phone), peers).status)
        }
        assertEquals(MessageStatus.STATUS_BLUETOOTH_INVALID_ADDRESS,
            GalBluetoothPairing.response("invalid", pin, car, car, true, emptySet(), null).status)
        assertEquals(MessageStatus.STATUS_BLUETOOTH_INVALID_PAIRING_METHOD,
            GalBluetoothPairing.response(phone, BluetoothPairingMethod.BLUETOOTH_PAIRING_OOB,
                car, car, true, emptySet(), null).status)
    }

    @Test fun `response carries the required false field and preserves negative status`() {
        val response = Bluetooth.BluetoothPairingResponse.newBuilder()
            .setStatus(MessageStatus.STATUS_BLUETOOTH_PAIRING_DELAYED).setAlreadyPaired(false).build()
        val parsed = Bluetooth.BluetoothPairingResponse.parseFrom(response.toByteArray())
        assertTrue(parsed.hasAlreadyPaired())
        assertEquals(MessageStatus.STATUS_BLUETOOTH_PAIRING_DELAYED, parsed.status)
        val success = Bluetooth.BluetoothPairingResponse.newBuilder()
            .setStatus(MessageStatus.STATUS_SUCCESS).setAlreadyPaired(true).build()
        assertArrayEquals(byteArrayOf(8, 0, 16, 1), success.toByteArray())
    }
}
