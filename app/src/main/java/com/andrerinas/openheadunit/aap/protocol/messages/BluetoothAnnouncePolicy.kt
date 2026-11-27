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

    /**
     * [carAddress] null means the Bluetooth service is left out.
     *
     * [withheldForMapsAudioOnly] separates the two reasons for an omission so the log can say which
     * one happened: an unconfigured address, or the owner's navigation-only choice. Without it the
     * only visible symptom of either is a session with no hands-free link.
     */
    data class Announce(
        val mode: Mode,
        val carAddress: String?,
        val skipNotApplied: Boolean,
        val withheldForMapsAudioOnly: Boolean = false,
    )

    fun isSkip(stored: String?): Boolean = stored?.trim()?.lowercase() == STORED_SKIP

    fun storedValue(skip: Boolean): String = if (skip) STORED_SKIP else STORED_REAL

    /**
     * Maps-only wins over everything, including a configured address and the skip mode: the point
     * of that setting is that Android Auto keeps telephony disabled, so there is no hands-free
     * address to announce.
     */
    fun decide(
        stored: String?,
        bluetoothAddress: String,
        usbSession: Boolean,
        mapsAudioOnly: Boolean = false,
    ): Announce {
        if (mapsAudioOnly) {
            return Announce(Mode.REAL, null, false, withheldForMapsAudioOnly = true)
        }
        val real = bluetoothAddress.ifEmpty { null }
        if (!isSkip(stored)) return Announce(Mode.REAL, real, false)
        if (!usbSession) return Announce(Mode.REAL, real, true)
        return Announce(Mode.SKIP, if (real == null) null else SKIP_VALUE, false)
    }
}
