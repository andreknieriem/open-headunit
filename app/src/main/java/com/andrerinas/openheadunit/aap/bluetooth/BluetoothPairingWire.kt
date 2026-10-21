package com.andrerinas.openheadunit.aap.bluetooth

import com.andrerinas.openheadunit.aap.AapBluetoothControl
import com.andrerinas.openheadunit.aap.AapMessage
import com.andrerinas.openheadunit.aap.protocol.proto.Bluetooth

/** Malformed optional Bluetooth traffic must not terminate the shared audio/video connection. */
internal object BluetoothPairingWire {
    sealed interface Input {
        data class Request(val address: String, val method: Int) : Input
        data class Result(val status: Int) : Input
        data object Malformed : Input
        data object Unsupported : Input
    }
    fun decode(message: AapMessage): Input {
        // Validate ordering before subtraction so invalid integer bounds cannot wrap around.
        if (message.dataOffset < 0 || message.size < message.dataOffset || message.size > message.data.size) return Input.Malformed
        val length = message.size - message.dataOffset
        if (length > 512) return Input.Malformed
        return try {
            when (message.type) {
                AapBluetoothControl.PAIRING_REQUEST -> {
                    val value = message.parse(Bluetooth.PairingRequest.newBuilder()).build()
                    Input.Request(value.phoneAddress, value.pairingMethod)
                }
                AapBluetoothControl.AUTHENTICATION_RESULT -> {
                    val value = message.parse(Bluetooth.AuthenticationResult.newBuilder()).build()
                    Input.Result(value.status)
                }
                else -> Input.Unsupported
            }
        } catch (_: com.google.protobuf.InvalidProtocolBufferException) { Input.Malformed }
        catch (_: com.google.protobuf.UninitializedMessageException) { Input.Malformed }
    }
}
