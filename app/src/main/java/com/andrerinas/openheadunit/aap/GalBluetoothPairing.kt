package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Common
import com.andrerinas.openheadunit.aap.protocol.proto.Control

/** Bond state is not evidence of HFP support or an active call-audio connection. */
internal object GalBluetoothPairing {
    data class Result(val status: Common.MessageStatus, val alreadyPaired: Boolean = false)
    private val address = Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}")
    fun validAddress(value: String?) = value != null && address.matches(value) &&
        value != "02:00:00:00:00:00" && value != "00:00:00:00:00:00"

    fun response(phone: String, method: Control.BluetoothPairingMethod, advertised: String?,
                 local: String?, enabled: Boolean, bonded: Set<String>, handsFreePeers: Set<String>?): Result {
        if (!validAddress(phone)) return Result(Common.MessageStatus.STATUS_BLUETOOTH_INVALID_ADDRESS)
        if (method != Control.BluetoothPairingMethod.BLUETOOTH_PAIRING_NUMERIC_COMPARISON &&
            method != Control.BluetoothPairingMethod.BLUETOOTH_PAIRING_PIN)
            return Result(Common.MessageStatus.STATUS_BLUETOOTH_INVALID_PAIRING_METHOD)
        // A configured vehicle MAC can be a separate radio. Its bond database is not ours;
        // Android's redacted address is likewise insufficient evidence to answer success.
        if (!enabled || !validAddress(local) || !local.equals(advertised, ignoreCase = true))
            return Result(Common.MessageStatus.STATUS_BLUETOOTH_UNAVAILABLE)
        // SUCCESS can trigger HFP retries and eventual unpairing on old phones. Only
        // confirm an already working call link, never infer HFP readiness from a bond.
        return if (bonded.any { it.equals(phone, ignoreCase = true) } &&
            handsFreePeers?.any { it.equals(phone, ignoreCase = true) } == true)
            Result(Common.MessageStatus.STATUS_SUCCESS, true)
        else Result(Common.MessageStatus.STATUS_BLUETOOTH_PAIRING_DELAYED)
    }
}
