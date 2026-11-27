package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue
import com.andrerinas.openheadunit.connection.carplaynative.crypto.CarPlayCrypto

/**
 * The keys of the data and event channels, all from the pair-verify secret.
 * Follows xcertplay airplay/AirPlaySession.kt and LIVI (https://github.com/f-io/LIVI) `session_keys.rs`.
 */
object StreamKeys {
    /** The phone reads what we write with [writeKey], and the reverse. */
    class EventKeys(val readKey: ByteArray, val writeKey: ByteArray)

    private const val EVENTS_SALT = "Events-Salt"
    private const val EVENTS_READ_INFO = "Events-Read-Encryption-Key"
    private const val EVENTS_WRITE_INFO = "Events-Write-Encryption-Key"
    private const val DATA_SALT = "DataStream-Salt"
    private const val DATA_OUTPUT_INFO = "DataStream-Output-Encryption-Key"

    /** The key of a screen or audio stream that the phone sends. [connectionId] is [connectionIdText]. */
    fun dataStream(shared: ByteArray, connectionId: String): ByteArray =
        CarPlayCrypto.hkdfSha512(shared, ascii(DATA_SALT + connectionId), ascii(DATA_OUTPUT_INFO))

    fun events(shared: ByteArray): EventKeys = EventKeys(
        readKey = CarPlayCrypto.hkdfSha512(shared, ascii(EVENTS_SALT), ascii(EVENTS_READ_INFO)),
        writeKey = CarPlayCrypto.hkdfSha512(shared, ascii(EVENTS_SALT), ascii(EVENTS_WRITE_INFO)),
    )

    /** The `streamConnectionID` as the phone reads it: an unsigned decimal. Null when the field is missing or no number. */
    fun connectionIdText(value: PlistValue?): String? = when (value) {
        is PlistValue.Integer -> java.lang.Long.toUnsignedString(value.value)
        is PlistValue.Text -> value.value
        is PlistValue.Real -> if (value.value == Math.rint(value.value) && Math.abs(value.value) < 1e18) value.value.toLong().toString() else null
        else -> null
    }

    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)
}
