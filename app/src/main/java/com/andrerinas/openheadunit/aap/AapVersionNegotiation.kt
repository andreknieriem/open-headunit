package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Common
import com.andrerinas.openheadunit.aap.protocol.proto.Control

/**
 * The version response the phone sends before the SSL handshake, which this app used to discard.
 *
 * Payload after the 6 byte AAP header is major, minor and status as big-endian shorts, then an
 * optional VersionResponseOptions proto carrying the connection parameters the phone wants us to
 * use. The version in it is the phone's own (current phones answer 1.7 whatever we ask for). Which
 * messages the phone sends follows the version we asked for instead: it sends the navigation state
 * with lanes only to a head unit that asked for 1.6 or later (see FullNavigationProfile).
 */
object AapVersionNegotiation {

    /** What Messages.versionRequest announces. */
    const val ANNOUNCED_MAJOR = 1
    const val ANNOUNCED_MINOR = 2

    /** The minor version that added the 1.6 message set (the navigation state with lanes among them). */
    const val FEATURE_MINOR_1_6 = 6

    data class Result(
        val major: Int,
        val minor: Int,
        val status: Int,
        val requestedConfig: Control.ConnectionConfiguration?
    ) {
        /**
         * Only -1(STATUS_NO_COMPATIBLE_VERSION) refuses negotiation. Phones also return
         * statuses other than 0(STATUS_SUCCESS) on working sessions; TLS must still be tried.
         */
        val accepted: Boolean get() = status != Common.MessageStatus.STATUS_NO_COMPATIBLE_VERSION_VALUE

        /** True when the phone's own version has the 1.6 message set (says nothing about what we asked for). */
        val supports16: Boolean get() = major > 1 || (major == 1 && minor >= FEATURE_MINOR_1_6)

        val statusName: String
            get() = Common.MessageStatus.forNumber(status)?.name ?: "UNKNOWN($status)"
    }

    /**
     * [frame] is the whole received frame including the 6 byte header, [length] the bytes read.
     * Returns null for an incomplete response or a mismatched envelope.
     */
    fun parse(frame: ByteArray, length: Int): Result? {
        if (length < 12 || length > frame.size) return null
        fun u16(i: Int) = ((frame[i].toInt() and 0xFF) shl 8) or (frame[i + 1].toInt() and 0xFF)
        // Length covers type + body, excluding the four-byte transport header. A read may
        // contain several frames; callers must pass exactly one complete response.
        if (frame[0] != 0.toByte() || frame[1] != 3.toByte() ||
            u16(4) != 2 || u16(2) != length - 4) return null
        val status = u16(10)
        var config: Control.ConnectionConfiguration? = null
        if (length > 12) {
            config = try {
                Control.VersionResponseOptions
                    .parseFrom(frame.copyOfRange(12, length))
                    .takeIf { it.hasConnectionConfiguration() }
                    ?.connectionConfiguration
            } catch (_: Exception) {
                // Trailing bytes that are not the options proto: the version is still good.
                null
            }
        }
        // Signed 16-bit MessageStatus: -1(STATUS_NO_COMPATIBLE_VERSION) arrives as 0xFFFF.
        val signed = if (status >= 0x8000) status - 0x10000 else status
        return Result(u16(6), u16(8), signed, config)
    }
}
