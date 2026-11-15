package com.andrerinas.openheadunit.maintenance

/**
 * Reads the trip counters out of the CAN box lines a Reglink unit writes to the system log, one
 * line per packet the box sends:
 *
 *     CANBox[IN ] type:0x33, data:01,04,00,16,00,74,00,5e,00,16,46,01,16,15,03
 *
 * As observed on a Reglink unit with a Raise box in a Honda (checked against the dashboard):
 * - type 0x33, first byte 01: bytes 8 to 10 are TRIP A, big endian, in tenths; bit 7 of byte 13
 *   is set when the dashboard counts in miles.
 * - type 0x33, first byte 05: bytes 2 and 3 are TRIP B, big endian, in tenths.
 * A counter of all ones means the box has no value.
 *
 * The box repeats these packets about every second. A reading goes out when a counter changes,
 * and at least every [HEARTBEAT_MS] otherwise so the app knows the box is still talking.
 *
 * Not thread safe: one instance per reading thread. It keeps its byte buffer, so a line costs no
 * allocation beyond the line itself.
 */
class ReglinkTripDecoder {

    private val bytes = IntArray(MAX_BYTES)

    private var tripB: Long? = null
    private var tripBAtMs = 0L
    private var last: TripReading? = null
    private var lastAtMs = 0L

    /** The reading to deliver for this line, or null when there is nothing new. */
    fun onLine(line: String, nowMs: Long): TripReading? {
        val typeAt = line.indexOf(TYPE_33)
        if (typeAt < 0) return null
        val dataAt = line.indexOf(DATA, typeAt)
        if (dataAt < 0) return null
        val count = parseBytes(line, dataAt + DATA.length)
        if (count < 1) return null
        when (bytes[0]) {
            SUB_TRIP_B -> {
                if (count < 4) return null
                val value = (bytes[2].toLong() shl 8) or bytes[3].toLong()
                tripB = if (value == 0xFFFFL) null else value
                tripBAtMs = nowMs
                return null
            }
            SUB_TRIP_A -> {
                if (count < 14) return null
                val value = (bytes[8].toLong() shl 16) or (bytes[9].toLong() shl 8) or bytes[10].toLong()
                val reading = TripReading(
                    tripATenths = if (value == 0xFFFFFFL) null else value,
                    tripBTenths = if (nowMs - tripBAtMs <= TRIP_B_FRESH_MS) tripB else null,
                    miles = bytes[13] and 0x80 != 0,
                )
                if (reading.tripATenths == null && reading.tripBTenths == null) return null
                if (reading == last && nowMs - lastAtMs < HEARTBEAT_MS) return null
                last = reading
                lastAtMs = nowMs
                return reading
            }
        }
        return null
    }

    /** Fills [bytes] from comma separated hex, returning how many; -1 for anything else. */
    private fun parseBytes(line: String, start: Int): Int {
        var count = 0
        var value = -1
        for (i in start until line.length) {
            val c = line[i]
            val digit = Character.digit(c, 16)
            when {
                digit >= 0 -> {
                    value = if (value < 0) digit else value * 16 + digit
                    if (value > 0xFF) return -1
                }
                c == ',' -> {
                    if (value < 0 || count >= MAX_BYTES) return -1
                    bytes[count++] = value
                    value = -1
                }
                c == ' ' || c == '\r' -> Unit
                else -> return -1
            }
        }
        if (value >= 0) {
            if (count >= MAX_BYTES) return -1
            bytes[count++] = value
        }
        return count
    }

    companion object {
        /** A packet a little longer than any seen, so a longer variant still parses. */
        const val MAX_BYTES = 64

        /** Without a change, a reading still goes out this often. */
        const val HEARTBEAT_MS = 5_000L

        /** TRIP B arrives in its own packet; older than this, it is left out of a reading. */
        const val TRIP_B_FRESH_MS = 10_000L

        /** The log tag the unit writes the box packets under. */
        const val LOG_TAG = "Reglink-Script"

        private const val TYPE_33 = "type:0x33"
        private const val DATA = "data:"
        private const val SUB_TRIP_A = 0x01
        private const val SUB_TRIP_B = 0x05
    }
}
