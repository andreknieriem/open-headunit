package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Passive identification for firmware whose Bluetooth bridge is only a candidate.
 *
 * HCT QCM6125 Android 13 fscsdkbw123 contains libzbt-main.so loading and RFCOMM callback
 * registration code; the library need not be installed or active on every matching unit.
 * In the separately inspected Dasaita ZLink 5.4.36 library, FoxServerInit opens TCP 3152.
 * Disassembly: server loop 0x69ac calls FoxServerWait (accept), then 0x541c packs/sends
 * InitInfo, before 0x6710 reads client requests. This unsolicited greeting is the basis
 * for the passive check; it does not replace the existing carrier's RequestInit sequence.
 * The app uses its normal INTERNET permission; the system daemon retains UART ownership.
 *
 * On unconfirmed candidates, send neither RequestInit nor reconnect: do not issue commands
 * before identifying the daemon. The established marker path keeps its existing RequestInit.
 * A port accepting connections is insufficient, especially when ZLink holds its one
 * client slot. Only a complete InitInfo selects ZBT automatically. This identifies
 * the protocol, not the peer's authenticity or successful over-the-air communication.
 */
object ZbtIdentityProbe {
    enum class Outcome { IDENTIFIED, REFUSED, UNCONFIRMED }

    private const val MAX_GREETING_BYTES = 4096
    private const val MAX_FRAMES = 16
    private val MAC = Regex("(?:[0-9a-fA-F]{12}|(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2})")

    fun isIdentity(msgId: Int, body: ByteArray): Boolean {
        if (msgId != ZbtMessages.INIT_INFO || body.size > MAX_GREETING_BYTES) return false
        val record = ZbtMessages.parse(body) ?: return false
        return record.long(1) == ZbtMessages.INIT_INFO.toLong() &&
            !record.string(2).isNullOrBlank() && record.bytes(3) != null &&
            record.string(4)?.matches(MAC) == true
    }

    /** A single bounded attempt. Failure leaves the existing Android route available. */
    fun probe(
        host: String = ZbtByteChannel.HOST,
        port: Int = ZbtByteChannel.PORT,
        budgetMs: Int = 3000,
        keepTrying: () -> Boolean = { true }
    ): Outcome {
        require(budgetMs > 0)
        val deadline = System.nanoTime() + budgetMs.toLong() * 1_000_000L
        fun remainingMs(): Int {
            if (!keepTrying()) throw IOException("probe cancelled")
            val left = deadline - System.nanoTime()
            if (left <= 0) throw IOException("greeting deadline expired")
            return ((left + 999_999L) / 1_000_000L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        }
        return try {
            Socket().use { socket ->
                try {
                    socket.connect(InetSocketAddress(host, port), remainingMs())
                } catch (_: ConnectException) {
                    // A booting daemon can start listening shortly. Retry only this case;
                    // connecting repeatedly to a busy single-client daemon is not harmless.
                    return Outcome.REFUSED
                }
                val input = socket.getInputStream()
                fun read(size: Int): ByteArray {
                    val bytes = ByteArray(size)
                    var offset = 0
                    while (offset < size) {
                        socket.soTimeout = remainingMs()
                        val count = input.read(bytes, offset, size - offset)
                        if (count < 0) throw IOException("greeting ended early")
                        offset += count
                    }
                    return bytes
                }
                repeat(MAX_FRAMES) {
                    val header = read(ZbtFraming.HEADER_SIZE)
                    val size = ZbtFraming.decodeBodySize(header)
                    if (size !in 0..MAX_GREETING_BYTES) return Outcome.UNCONFIRMED
                    val body = read(size)
                    if (isIdentity(ZbtFraming.decodeMsgId(header), body)) {
                        return if (keepTrying()) Outcome.IDENTIFIED else Outcome.UNCONFIRMED
                    }
                }
                Outcome.UNCONFIRMED
            }
        } catch (_: IOException) {
            Outcome.UNCONFIRMED
        } catch (_: SecurityException) {
            // Vendor SELinux rules may differ even when the same daemon file is installed.
            Outcome.UNCONFIRMED
        }
    }
}
