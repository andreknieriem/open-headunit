package com.andrerinas.openheadunit.aap.bluetooth

import com.andrerinas.openheadunit.aap.protocol.proto.Common.MessageStatus
import java.util.Locale

/** Serialized by the Bluetooth worker, separate from the socket/media reader. */
internal class BluetoothPairingSession(private val port: Port) {
    enum class Bond { NONE, BONDING, BONDED, UNAVAILABLE }
    interface Port {
        fun bond(address: String): Bond
        fun reply(status: MessageStatus, alreadyPaired: Boolean)
        fun authentication(value: String)
        fun now(): Long
    }
    private var phone: String? = null
    private var deadline = 0L
    private var sentKey: Int? = null
    private var closed = false

    fun request(address: String, method: Int, receivedAt: Long = port.now(), isCurrent: () -> Boolean = { true }) {
        fun current() = !closed && isCurrent() && port.now() - receivedAt in 0 until TIMEOUT_MS
        if (!current()) return
        val target = normalize(address)
        if (target == null) {
            port.reply(MessageStatus.STATUS_BLUETOOTH_INVALID_ADDRESS, false)
            return
        }
        if (method != NUMERIC_COMPARISON) {
            port.reply(MessageStatus.STATUS_BLUETOOTH_INVALID_PAIRING_METHOD, false)
            return
        }
        expire()
        if (phone != null && phone != target) {
            port.reply(MessageStatus.STATUS_BLUETOOTH_PAIRING_DELAYED, false)
            return
        }
        val bond = port.bond(target)
        // A Binder call can outlive the phone's request timer or be superseded by a retry.
        if (!current()) return
        // The existing authentication window has its own deadline. A fresh retry can
        // remain valid while its Binder query outlives that window; renew only from
        // the positive bond result below, never answer against an expired window.
        expire()
        if (phone == target) {
            // A retry must not retire a valid challenge window during old-key cleanup.
            // Only repeat a positive answer when the current record still confirms it.
            if (bond == Bond.BONDED) port.reply(MessageStatus.STATUS_SUCCESS, true)
            return
        }
        when (bond) {
            Bond.NONE, Bond.UNAVAILABLE -> {
                clear()
                port.reply(MessageStatus.STATUS_BLUETOOTH_UNAVAILABLE, false)
            }
            Bond.BONDING -> {
                // Do not advance the phone's pairing FSM while another attempt is in progress.
                port.reply(MessageStatus.STATUS_BLUETOOTH_PAIRING_DELAYED, false)
            }
            Bond.BONDED -> {
                if (phone == null) {
                    phone = target
                    deadline = port.now() + TIMEOUT_MS
                    sentKey = null
                }
                // Only a positive record from THIS adapter admits assistance. Public Android
                // APIs collapse some Binder failures into BOND_NONE. Never send SUCCESS/false:
                // AA can use it to remove its old phone-side bond on Android <=10.
                // The phone can have forgotten us even when our record still exists. Keep this
                // window for a fresh platform pairing challenge after already_paired=true too.
                port.reply(MessageStatus.STATUS_SUCCESS, true)
            }
        }
    }

    fun comparison(address: String, key: Int) {
        if (closed) return
        expire()
        if (phone == null || phone != normalize(address) || key !in 0..999999) return
        // The protected pairing challenge itself is positive evidence of authentication.
        // Do not perform another Binder query here: an old key may already have been removed,
        // and Android can publish this event before its BONDING state notification.
        if (sentKey == key) return
        sentKey = key
        // AA compares exactly six decimal characters, including leading zeroes, with its own
        // platform pairing key. OHU leaves the HU's normal confirmation dialog to the user.
        port.authentication(String.format(Locale.ROOT, "%06d", key))
    }

    fun authenticationResult(status: Int) {
        if (closed) return
        expire()
        // The phone reports its authentication API result. This is not a bond/HFP success
        // event; only the platform bond state can complete our pending attempt.
        if (status != MessageStatus.STATUS_SUCCESS_VALUE) clear()
    }

    fun bondChanged(address: String, bond: Bond, previous: Bond? = null) {
        if (closed || phone != normalize(address)) return
        // A stale link key can disappear (BONDED -> NONE) before the phone starts a new
        // challenge. Retire only a completed bond or a failed *pairing attempt*, not that cleanup.
        if (bond == Bond.BONDED || (bond == Bond.NONE && previous == Bond.BONDING)) clear()
    }

    fun close() { closed = true; clear() }
    private fun expire() { if (phone != null && port.now() >= deadline) clear() }
    private fun clear() { phone = null; sentKey = null; deadline = 0L }

    companion object {
        const val NUMERIC_COMPARISON = 2 // AAP pairing method; Android's variant uses a separate enum.
        const val TIMEOUT_MS = 120_000L
        fun normalize(value: String): String? {
            if (!Regex("[0-9a-fA-F]{2}(:[0-9a-fA-F]{2}){5}").matches(value)) return null
            val normalized = value.uppercase(Locale.ROOT)
            return normalized.takeUnless { it in setOf("00:00:00:00:00:00", "FF:FF:FF:FF:FF:FF", "02:00:00:00:00:00") }
        }
    }
}
