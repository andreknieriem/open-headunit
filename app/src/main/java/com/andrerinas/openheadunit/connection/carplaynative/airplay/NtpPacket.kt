package com.andrerinas.openheadunit.connection.carplaynative.airplay

/**
 * The 32-byte timing packets of the CarPlay timing port: PT 210 asks, PT 211 answers with the
 * originate, receive and transmit stamps. A stamp is a 64-bit NTP value: 32 bits of seconds, 32 of fraction.
 * Follows xcertplay airplay/NtpClock.kt, with DiPlay's rule that only the phone's answer moves the clock.
 */
object NtpPacket {
    const val SIZE = 32
    const val PT_REQUEST = 210
    const val PT_RESPONSE = 211
    private const val ORIGINATE = 8
    private const val RECEIVE = 16
    private const val TRANSMIT = 24
    private const val VERSION_BYTE = 0x80
    private const val SEQUENCE = 7

    class Response(val originate: Long, val receive: Long, val transmit: Long)

    /** Null for a packet that is too short. */
    fun type(packet: ByteArray): Int? = if (packet.size >= SIZE) packet[1].toInt() and 0xff else null

    fun request(transmit: Long): ByteArray = header(PT_REQUEST).also { write(it, TRANSMIT, transmit) }

    /** Echoes the request's transmit stamp as the originate stamp. Null unless [request] is a PT 210 packet. */
    fun response(request: ByteArray, receive: Long, transmit: Long): ByteArray? {
        if (type(request) != PT_REQUEST) return null
        val out = header(PT_RESPONSE)
        request.copyInto(out, ORIGINATE, TRANSMIT, SIZE)
        write(out, RECEIVE, receive)
        write(out, TRANSMIT, transmit)
        return out
    }

    /** The transmit stamp of a request. Null unless [packet] is a PT 210 packet. */
    fun requestTransmit(packet: ByteArray): Long? = if (type(packet) == PT_REQUEST) read(packet, TRANSMIT) else null

    fun parseResponse(packet: ByteArray): Response? =
        if (type(packet) == PT_RESPONSE) Response(read(packet, ORIGINATE), read(packet, RECEIVE), read(packet, TRANSMIT)) else null

    private fun header(type: Int): ByteArray = ByteArray(SIZE).also {
        it[0] = VERSION_BYTE.toByte()
        it[1] = type.toByte()
        it[3] = SEQUENCE.toByte()
    }

    private fun write(target: ByteArray, at: Int, value: Long) {
        for (i in 0 until 8) target[at + i] = (value ushr (56 - 8 * i)).toByte()
    }

    private fun read(source: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (source[at + i].toLong() and 0xff)
        return value
    }
}

/**
 * The clock that follows the phone's media clock. Each exchange gives an offset and a round trip;
 * the lower round trip of two exchanges counts, and only if no round trip of the last 8 was lower.
 * A first sync or a gap over 128 ms steps the clock; a smaller one moves it by an eighth.
 * Times come in as arguments: [startMonoNs] and [startWallMillis] are read at the same moment.
 */
class NtpSync(startMonoNs: Long, startWallMillis: Long) {
    private var offsetNs: Long = wallNs(startWallMillis) - startMonoNs
    private val delays = DoubleArray(DELAY_WINDOW) { Double.POSITIVE_INFINITY }
    private var delayIndex = 0
    private var picksLeft = PICK_COUNT
    private var pickRtt = Double.POSITIVE_INFINITY
    private var pickOffset = 0.0
    private var pendingT1: Long? = null

    var synced: Boolean = false
        private set

    /** Now in the phone's clock, as a 64-bit NTP stamp. */
    fun now(monoNs: Long): Long {
        val ns = monoNs + offsetNs
        val seconds = Math.floorDiv(ns, NANOS_PER_SECOND)
        val nanos = Math.floorMod(ns, NANOS_PER_SECOND)
        return (seconds shl 32) or ((nanos shl 32) / NANOS_PER_SECOND)
    }

    /** The next PT 210 packet. It replaces an unanswered one. */
    fun nextRequest(monoNs: Long): ByteArray {
        val t1 = now(monoNs)
        pendingT1 = t1
        return NtpPacket.request(t1)
    }

    /** The answer to a PT 210 packet from the phone, or null for a packet that is no request. */
    fun answer(request: ByteArray, monoNs: Long): ByteArray? = NtpPacket.response(request, now(monoNs), now(monoNs))

    /**
     * Takes a PT 211 packet. A packet from any host but the phone, one that echoes another request, or
     * one with a negative round trip changes nothing. Returns true when the clock moved.
     */
    fun onResponse(packet: ByteArray, fromPeer: Boolean, monoNs: Long): Boolean {
        if (!fromPeer) return false
        val response = NtpPacket.parseResponse(packet) ?: return false
        val t4 = now(monoNs)
        val pending = pendingT1
        if (pending == null || pending != response.originate) return false
        pendingT1 = null

        val t1 = response.originate
        val offset = ((response.receive - t1).toDouble() + (response.transmit - t4).toDouble()) * 0.5 / TWO32
        val rtt = ((t4 - t1) - (response.transmit - response.receive)).toDouble() / TWO32
        if (rtt < 0.0) return false
        if (rtt < pickRtt) {
            pickRtt = rtt
            pickOffset = offset
        }
        if (--picksLeft > 0) return false

        val chosenRtt = pickRtt
        val chosenOffset = pickOffset
        picksLeft = PICK_COUNT
        pickRtt = Double.POSITIVE_INFINITY
        val use = delays.all { chosenRtt <= it }
        delays[delayIndex] = chosenRtt
        delayIndex = (delayIndex + 1) % DELAY_WINDOW
        if (!use) return false
        apply(chosenOffset)
        return true
    }

    private fun apply(offsetSeconds: Double) {
        val stepping = !synced || Math.abs(offsetSeconds) > STEP_THRESHOLD_SEC
        val applied = if (stepping) offsetSeconds else offsetSeconds * SLEW_GAIN
        offsetNs += Math.round(applied * 1e9)
        if (stepping) {
            delays.fill(Double.POSITIVE_INFINITY)
            delayIndex = 0
            pendingT1 = null
            synced = true
        }
    }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NTP_EPOCH_OFFSET = 2_208_988_800L
        const val TWO32 = 4294967296.0
        const val STEP_THRESHOLD_SEC = 0.128
        const val SLEW_GAIN = 1.0 / 8.0
        const val DELAY_WINDOW = 8
        const val PICK_COUNT = 2

        fun wallNs(wallMillis: Long): Long =
            (Math.floorDiv(wallMillis, 1000L) + NTP_EPOCH_OFFSET) * NANOS_PER_SECOND + Math.floorMod(wallMillis, 1000L) * 1_000_000L
    }
}
