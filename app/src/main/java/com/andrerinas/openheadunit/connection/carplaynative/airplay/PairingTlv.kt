package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.codec.Tlv8

/** The TLV8 types and the error reply that pair-setup and pair-verify share. */
internal object PairingTlv {
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val STATE = 0x06
    const val ERROR = 0x07
    const val SIGNATURE = 0x0a
    private const val ERROR_AUTHENTICATION = 2

    fun state(body: Map<Int, ByteArray>): Int? = body[STATE]?.firstOrNull()?.toInt()?.and(0xff)

    /** A request state of 1, 3 or 5 answers with the next even state; anything else answers as it came. */
    fun error(requestState: Int?): ByteArray {
        val state = requestState ?: 0
        val reply = if (state == 1 || state == 3 || state == 5) state + 1 else state
        return Tlv8.encode(
            listOf(
                Tlv8.Item(STATE, byteArrayOf(reply.toByte())),
                Tlv8.Item(ERROR, byteArrayOf(ERROR_AUTHENTICATION.toByte())),
            ),
        )
    }

    fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)
}
