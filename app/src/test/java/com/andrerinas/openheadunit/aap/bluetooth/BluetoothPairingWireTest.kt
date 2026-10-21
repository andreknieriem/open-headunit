package com.andrerinas.openheadunit.aap.bluetooth

import com.andrerinas.openheadunit.aap.AapMessage
import com.andrerinas.openheadunit.aap.protocol.Channel
import org.junit.Assert.*
import org.junit.Test

class BluetoothPairingWireTest {
    private fun message(type: Int, bytes: ByteArray) = AapMessage(Channel.ID_BTH, 0, type, 0, bytes.size, bytes)
    @Test fun incompleteAndCorruptOptionalServiceMessagesAreDropped() {
        for (type in listOf(0x8001, 0x8004)) {
            for (bytes in listOf(byteArrayOf(), byteArrayOf(0x0a, 127, 65), byteArrayOf(8), byteArrayOf(0xff.toByte()))) {
                assertEquals(BluetoothPairingWire.Input.Malformed, BluetoothPairingWire.decode(message(type, bytes)))
            }
        }
        assertEquals(BluetoothPairingWire.Input.Malformed,
            BluetoothPairingWire.decode(message(0x8001, ByteArray(513))))
    }
    @Test fun authenticationResultAndUnknownTypeAreDistinct() {
        assertEquals(BluetoothPairingWire.Input.Result(0), BluetoothPairingWire.decode(message(0x8004, byteArrayOf(8,0))))
        assertEquals(BluetoothPairingWire.Input.Unsupported, BluetoothPairingWire.decode(message(0x8003, byteArrayOf())))
    }
    @Test fun requestDecodesOnlyItsFramedPayload() {
        val address = "12:34:56:78:9A:BC"
        val payload = com.andrerinas.openheadunit.aap.protocol.proto.Bluetooth.PairingRequest.newBuilder()
            .setPhoneAddress(address).setPairingMethod(2).build().toByteArray()
        val bytes = byteArrayOf(0x80.toByte(), 1) + payload + byteArrayOf(0xff.toByte())
        val framed = AapMessage(Channel.ID_BTH, 0, 0x8001, 2, 2 + payload.size, bytes)
        assertEquals(BluetoothPairingWire.Input.Request(address, 2), BluetoothPairingWire.decode(framed))
    }

    @Test fun invalidBoundsCannotOverflowIntoAnAcceptedLength() {
        for ((offset, size) in listOf(-1 to 1, 2 to 1, Int.MAX_VALUE to Int.MIN_VALUE, 0 to -1, 0 to 4)) {
            val input = AapMessage(Channel.ID_BTH, 0, 0x8001, offset, size, byteArrayOf(8, 0))
            assertEquals(BluetoothPairingWire.Input.Malformed, BluetoothPairingWire.decode(input))
        }
    }
}
