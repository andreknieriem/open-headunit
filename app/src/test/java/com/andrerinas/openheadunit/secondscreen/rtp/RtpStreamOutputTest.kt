package com.andrerinas.openheadunit.secondscreen.rtp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.PortUnreachableException
import java.util.Random

class RtpStreamOutputTest {

    private class FakeSocket : RtpSocket {
        val sent = ArrayList<ByteArray>()
        var closes = 0
        var failWith: (() -> Exception)? = null
        var attempts = 0
        override val target = "10.0.0.5:5004"
        override fun open() = "fake"
        override fun send(buf: ByteArray, off: Int, len: Int) {
            attempts++
            failWith?.let { throw it() }
            sent.add(buf.copyOfRange(off, off + len))
        }
        override fun close() { closes++ }
    }

    private var now = 0L
    private val socket = FakeSocket()
    private fun output(seed: Long = 1) = RtpStreamOutput(socket, { now }, Random(seed))

    private val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1F)
    private val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte(), 0x3C)
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x00)
    private val delta = byteArrayOf(0, 0, 0, 1, 0x41, 0x9A.toByte(), 0x02, 0x00)

    private fun RtpStreamOutput.put(au: ByteArray) = onAccessUnit(au, 0, au.size)
    private fun ts(p: ByteArray) = ((p[4].toInt() and 0xFF) shl 24) or ((p[5].toInt() and 0xFF) shl 16) or
        ((p[6].toInt() and 0xFF) shl 8) or (p[7].toInt() and 0xFF)

    @Test
    fun `timestamps follow the clock at 90 kHz from a random start`() {
        val o = output().also { it.start() }
        o.put(delta)
        now = 33_000_000
        o.put(delta)
        assertEquals(2970, ts(socket.sent[1]) - ts(socket.sent[0]))
        val other = FakeSocket()
        val o2 = RtpStreamOutput(other, { now }, Random(2)).also { it.start() }
        o2.onAccessUnit(delta, 0, delta.size)
        assertNotEquals(ts(socket.sent[0]), ts(other.sent[0]))
    }

    @Test
    fun `the timestamp wraps past 32 bits without an exception`() {
        val o = RtpStreamOutput(socket, { now }, object : Random() {
            override fun nextInt() = -10
        }).also { it.start() }
        o.put(delta)
        now = 1_000_000_000
        o.put(delta)
        assertEquals(-10 + 90_000, ts(socket.sent[1]))
    }

    @Test
    fun `a socket that always fails never throws and is tried on every unit`() {
        socket.failWith = { IOException("down") }
        val o = output().also { it.start() }
        repeat(10) { o.put(delta) }
        assertEquals(10, socket.attempts)
        assertEquals(setOf("IOException"), o.failureCounts.keys)
        assertEquals(10, o.failureCounts["IOException"])
    }

    @Test
    fun `a port unreachable answer is survived by the next unit`() {
        var first = true
        socket.failWith = { if (first) { first = false; PortUnreachableException("x") } else throw IllegalStateException() }
        val o = output().also { it.start() }
        o.put(delta)
        socket.failWith = null
        o.put(delta)
        assertEquals(1, socket.sent.size)
    }

    @Test
    fun `a keyframe is asked for once and only when the first unit is not one`() {
        var asked = 0
        val o = output().also { it.onKeyframeNeeded = { asked++ }; it.start() }
        o.put(delta)
        o.put(delta)
        assertEquals(1, asked)
        asked = 0
        val o2 = output().also { it.onKeyframeNeeded = { asked++ }; it.start() }
        o2.put(sps + pps + idr)
        o2.put(delta)
        assertEquals(0, asked)
    }

    @Test
    fun `a config-only first unit followed by an IDR asks for nothing`() {
        var asked = 0
        val o = output().also { it.onKeyframeNeeded = { asked++ }; it.start() }
        o.put(sps + pps)
        o.put(idr)
        o.put(delta)
        assertEquals(0, asked)
    }

    @Test
    fun `a config-only first unit followed by a delta asks once`() {
        var asked = 0
        val o = output().also { it.onKeyframeNeeded = { asked++ }; it.start() }
        o.put(sps + pps)
        o.put(delta)
        o.put(delta)
        assertEquals(1, asked)
    }

    @Test
    fun `a send that fails with no route still sends once the route returns`() {
        socket.failWith = { java.net.SocketException("Network is unreachable") }
        val o = output().also { it.start() }
        repeat(3) { o.put(delta) }
        socket.failWith = null
        o.put(delta)
        assertEquals(1, socket.sent.size)
        assertEquals(3, o.failureCounts["SocketException"])
    }

    @Test
    fun `a report is due after ten seconds and resets the counters`() {
        val o = output().also { it.start() }
        o.put(sps + pps + idr)
        now = 9_900_000_000
        assertNull(o.reportIfDue())
        now = 10_000_000_000
        val line = o.reportIfDue()
        assertNotNull(line)
        assertTrue(line!!, line.startsWith("RTP push sent 3 packets,"))
        assertTrue(line, line.endsWith("in 10000ms, 1 keyframes carried their own SPS and PPS, 0 refreshes asked, 0 pacing pauses"))
        assertNull(o.reportIfDue())
    }

    @Test
    fun `with no keyframe the refresh asks once per interval`() {
        var asked = 0
        val o = RtpStreamOutput(socket, { now }, Random(1), 10_000).also { it.onKeyframeNeeded = { asked++ }; it.start() }
        o.put(sps + pps + idr)
        now = 9_999_000_000
        o.put(delta)
        assertEquals(0, asked)
        now = 10_000_000_000
        o.put(delta)
        now = 15_000_000_000
        o.put(delta)
        assertEquals(1, asked)
        now = 20_000_000_000
        o.put(delta)
        assertEquals(2, asked)
    }

    @Test
    fun `a keyframe restarts the refresh interval`() {
        var asked = 0
        val o = RtpStreamOutput(socket, { now }, Random(1), 10_000).also { it.onKeyframeNeeded = { asked++ }; it.start() }
        o.put(sps + pps + idr)
        now = 8_000_000_000
        o.put(idr)
        now = 17_000_000_000
        o.put(delta)
        assertEquals(0, asked)
        now = 18_000_000_000
        o.put(delta)
        assertEquals(1, asked)
    }

    @Test
    fun `the first-unit request also holds the refresh back`() {
        var asked = 0
        val o = RtpStreamOutput(socket, { now }, Random(1), 10_000).also { it.onKeyframeNeeded = { asked++ }; it.start() }
        now = 9_000_000_000
        o.put(delta)
        now = 10_000_000_000
        o.put(delta)
        assertEquals(1, asked)
        now = 19_000_000_000
        o.put(delta)
        assertEquals(2, asked)
    }

    @Test
    fun `a refresh of zero never asks after the first unit`() {
        var asked = 0
        val o = output().also { it.onKeyframeNeeded = { asked++ }; it.start() }
        o.put(sps + pps + idr)
        for (s in 1..120) { now = s * 1_000_000_000L; o.put(delta) }
        assertEquals(0, asked)
    }

    @Test
    fun `a large unit is sent in runs with a 1 ms pause between them`() {
        val pauses = ArrayList<Long>()
        val o = RtpStreamOutput(socket, { now }, Random(1), 0, { pauses.add(it) }).also { it.start() }
        o.put(sps + pps + idr)
        now = 100_000_000
        val big = byteArrayOf(0, 0, 0, 1, 0x41) + ByteArray(100_000) { 0x11 }
        o.put(big)
        val packets = socket.sent.size - 3
        assertEquals(73, packets)
        // 73 packets over 20 ms is 4 a run, so 18 pauses.
        assertEquals(18, pauses.size)
        assertTrue(pauses.all { it == 1L })
    }

    @Test
    fun `a large first unit is paced and a small unit goes out at once`() {
        val pauses = ArrayList<Long>()
        val o = RtpStreamOutput(socket, { now }, Random(1), 0, { pauses.add(it) }).also { it.start() }
        o.put(byteArrayOf(0, 0, 0, 1, 0x65) + ByteArray(100_000) { 0x11 })
        assertEquals(18, pauses.size)
        now = 100_000_000
        o.put(delta)
        assertEquals(18, pauses.size)
    }

    @Test
    fun `stop closes the socket once and later units send nothing`() {
        val o = output().also { it.start() }
        o.stop()
        o.stop()
        o.put(delta)
        assertEquals(1, socket.closes)
        assertEquals(0, socket.sent.size)
    }
}
