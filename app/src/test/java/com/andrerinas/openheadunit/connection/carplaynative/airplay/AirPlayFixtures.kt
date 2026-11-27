package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaybox.BoxAudioSink
import com.andrerinas.openheadunit.connection.carplaybox.BoxVideoSink
import com.andrerinas.openheadunit.connection.carplaybox.CarPlayBoxAudioFormat
import com.andrerinas.openheadunit.connection.carplaynative.CarPlaySigner
import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.CopyOnWriteArrayList

/** Records every call so a test can read what the server fed the decoders. */
internal class RecordingVideoSink : BoxVideoSink {
    val decoded = CopyOnWriteArrayList<ByteArray>()
    var hook: ((String) -> Unit)? = null
    var stopped = 0

    override fun decode(buf: ByteArray, off: Int, len: Int) { decoded.add(buf.copyOfRange(off, off + len)) }
    override fun hookKeyframeRequests(request: (String) -> Unit) { hook = request }
    override fun unhookKeyframeRequests() { hook = null }
    override fun stop(reason: String) { stopped++ }
}

internal class RecordingAudioSink : BoxAudioSink {
    val configured = CopyOnWriteArrayList<Pair<Int, CarPlayBoxAudioFormat.Format>>()
    val written = CopyOnWriteArrayList<Pair<Int, ByteArray>>()
    var opened = 0
    var closed = 0

    override fun open() { opened++ }
    override fun configure(channel: Int, format: CarPlayBoxAudioFormat.Format) { configured.add(channel to format) }
    override fun write(channel: Int, buf: ByteArray, off: Int, len: Int) { written.add(channel to buf.copyOfRange(off, off + len)) }
    override fun close() { closed++ }
}

/** A signer with a fixed certificate and a fixed signature. The tests of AuthSetup check the real ones. */
internal class StubSigner : CarPlaySigner {
    override fun protocolMajor() = 3
    override fun certificate() = byteArrayOf(0x30, 0x03, 0x01, 0x01, 0x00)
    override fun sign(challenge: ByteArray) = ByteArray(64) { 7 }
}

/** Collects the AppLog lines while a test runs. */
internal class LogCapture : AppLog.Logger, AutoCloseable {
    val lines = CopyOnWriteArrayList<String>()
    private val previous = AppLog.LOGGER

    init { AppLog.LOGGER = this }

    override fun println(priority: Int, tag: String, msg: String) { lines.add(msg) }
    override fun close() { AppLog.LOGGER = previous }

    fun count(part: String) = lines.count { it.contains(part) }
}

internal fun awaitTrue(timeoutMs: Long = 3000, condition: () -> Boolean): Boolean {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (condition()) return true
        Thread.sleep(10)
    }
    return condition()
}

/** H.264 bytes for the tests: a baseline SPS of a given size, a PPS and an avcC record around them. */
internal object H264Fixtures {
    private class Bits {
        private val out = java.io.ByteArrayOutputStream()
        private var current = 0
        private var count = 0

        fun bit(b: Int) { current = (current shl 1) or b; if (++count == 8) { out.write(current); current = 0; count = 0 } }
        fun u(value: Int, bits: Int) { for (i in bits - 1 downTo 0) bit((value shr i) and 1) }
        fun ue(value: Int) {
            val v = value + 1
            val length = 32 - Integer.numberOfLeadingZeros(v)
            repeat(length - 1) { bit(0) }
            u(v, length)
        }
        fun finish(): ByteArray { bit(1); while (count != 0) bit(0); return out.toByteArray() }
    }

    /** A NAL unit with its header byte. Width and height are multiples of 16. */
    fun sps(width: Int, height: Int): ByteArray {
        val b = Bits()
        b.u(66, 8); b.u(0xc0, 8); b.u(30, 8)
        b.ue(0); b.ue(0); b.ue(0); b.ue(0); b.ue(1); b.bit(0)
        b.ue(width / 16 - 1); b.ue(height / 16 - 1)
        b.bit(1); b.bit(1); b.bit(0); b.bit(0)
        return byteArrayOf(0x67) + b.finish()
    }

    val pps = byteArrayOf(0x68, 0xce.toByte(), 0x3c, 0x80.toByte())

    fun avcc(sps: ByteArray, pps: ByteArray = this.pps, lengthSize: Int = 4): ByteArray =
        byteArrayOf(1, sps[1], sps[2], sps[3], (0xfc or (lengthSize - 1)).toByte(), 0xe1.toByte()) +
            byteArrayOf((sps.size shr 8).toByte(), sps.size.toByte()) + sps +
            byteArrayOf(1, (pps.size shr 8).toByte(), pps.size.toByte()) + pps
}
