package com.andrerinas.openheadunit.aap.protocol.messages

/**
 * Android Auto turns off the phone's Media audio for the Bluetooth address it is told. `skip`
 * announces SKIP_THIS_BLUETOOTH instead, on USB only: wireless keys its record on the address.
 */
object BluetoothAnnouncePolicy {
    const val SKIP_VALUE = "SKIP_THIS_BLUETOOTH"
    const val STORED_REAL = "real"
    const val STORED_SKIP = "skip"

    enum class Mode { REAL, SKIP }

    /** [carAddress] null means the Bluetooth service is left out. */
    data class Announce(val mode: Mode, val carAddress: String?, val skipNotApplied: Boolean)

    fun isSkip(stored: String?): Boolean = stored?.trim()?.lowercase() == STORED_SKIP

    fun storedValue(skip: Boolean): String = if (skip) STORED_SKIP else STORED_REAL

    fun decide(stored: String?, bluetoothAddress: String, usbSession: Boolean): Announce {
        val real = bluetoothAddress.ifEmpty { null }
        if (!isSkip(stored)) return Announce(Mode.REAL, real, false)
        if (!usbSession) return Announce(Mode.REAL, real, true)
        return Announce(Mode.SKIP, if (real == null) null else SKIP_VALUE, false)
    }
}
