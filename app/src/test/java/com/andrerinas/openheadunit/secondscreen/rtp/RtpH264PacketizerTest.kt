package com.andrerinas.openheadunit.secondscreen.rtp

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtpH264PacketizerTest {

    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C)
    private val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x00)
    private val delta = byteArrayOf(0x41, 0x9A.toByte(), 0x02, 0x00)
    private val ssrc = 0x11223344

    private val sent = ArrayList<ByteArray>()
    private fun packetizer(seq: Int = 0) =
        RtpH264Packetizer({ b, o, l -> sent.add(b.copyOfRange(o, o + l)) }, seq, ssrc)

    private fun unit(vararg nals: ByteArray, startCode: ByteArray = byteArrayOf(0, 0, 0, 1)): ByteArray =
        nals.fold(ByteArray(0)) { acc, nal -> acc + startCode + nal }

    private fun RtpH264Packetizer.put(au: ByteArray, ts: Int = 1000) = packetize(au, 0, au.size, ts)

    private fun seq(p: ByteArray) = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
    private fun ts(p: ByteArray) = ((p[4].toInt() and 0xFF) shl 24) or ((p[5].toInt() and 0xFF) shl 16) or
        ((p[6].toInt() and 0xFF) shl 8) or (p[7].toInt() and 0xFF)
    private fun marker(p: ByteArray) = (p[1].toInt() and 0x80) != 0
    private fun nalType(p: ByteArray) = p[12].toInt() and 0x1F
    private fun payload(p: ByteArray) = p.copyOfRange(12, p.size)

    private fun bigNal(header: Int, size: Int) = ByteArray(size) { if (it == 0) header.toByte() else (it % 251).toByte() }

    @Test
    fun `every header reads version 2, payload type 96 and the SSRC`() {
        packetizer().put(unit(sps, pps, bigNal(0x65, 5000)))
        for (p in sent) {
            assertEquals(0x80, p[0].toInt() and 0xFF)
            assertEquals(96, p[1].toInt() and 0x7F)
            assertEquals(ssrc, ((p[8].toInt() and 0xFF) shl 24) or ((p[9].toInt() and 0xFF) shl 16) or
                ((p[10].toInt() and 0xFF) shl 8) or (p[11].toInt() and 0xFF))
        }
    }

    @Test
    fun `the sequence rises by one and wraps at 16 bits`() {
        packetizer(65534).put(unit(sps, pps, idr))
        assertEquals(listOf(65534, 65535, 0), sent.map { seq(it) })
    }

    @Test
    fun `one unit shares a timestamp and only its last datagram has the marker`() {
        packetizer().put(unit(sps, pps, bigNal(0x65, 5000)), ts = 777)
        assertTrue(sent.size > 3)
        assertTrue(sent.all { ts(it) == 777 })
        assertEquals(listOf(sent.size - 1), sent.indices.filter { marker(sent[it]) })
    }

    @Test
    fun `a small NAL is one datagram holding the NAL without its start code`() {
        packetizer().put(unit(delta))
        assertEquals(1, sent.size)
        assertArrayEquals(delta, payload(sent[0]))
        sent.clear()
        packetizer().put(unit(delta, startCode = byteArrayOf(0, 0, 1)))
        assertArrayEquals(delta, payload(sent[0]))
    }

    @Test
    fun `1388 bytes fit one datagram and 1389 need FU-A`() {
        packetizer().put(unit(bigNal(0x41, 1388)))
        assertEquals(1, sent.size)
        assertEquals(1400, sent[0].size)
        sent.clear()
        packetizer().put(unit(bigNal(0x41, 1389)))
        assertEquals(2, sent.size)
        assertTrue(sent.all { nalType(it) == 28 })
    }

    @Test
    fun `FU-A fragments rebuild the NAL byte for byte`() {
        val nal = bigNal(0x65, 100_000)
        packetizer().put(unit(nal))
        assertTrue(sent.all { it.size <= 1400 })
        for ((i, p) in sent.withIndex()) {
            assertEquals((0x65 and 0xE0) or 28, p[12].toInt() and 0xFF)
            assertEquals(5, p[13].toInt() and 0x1F)
            assertEquals(i == 0, (p[13].toInt() and 0x80) != 0)
            assertEquals(i == sent.size - 1, (p[13].toInt() and 0x40) != 0)
        }
        val rebuilt = byteArrayOf(nal[0]) + sent.fold(ByteArray(0)) { acc, p -> acc + p.copyOfRange(14, p.size) }
        assertArrayEquals(nal, rebuilt)
    }

    @Test
    fun `the reference idc of the NAL survives in the indicator`() {
        packetizer().put(unit(bigNal(0x25, 3000)))
        assertEquals(0x3C, sent[0][12].toInt() and 0xFF)
    }

    @Test
    fun `a lone IDR after an earlier keyframe gets the cached SPS and PPS first`() {
        val p = packetizer()
        p.put(unit(sps, pps, idr), ts = 1)
        sent.clear()
        p.put(unit(idr), ts = 2)
        assertEquals(listOf(7, 8, 5), sent.map { nalType(it) })
        assertTrue(sent.all { ts(it) == 2 })
        assertEquals(listOf(false, false, true), sent.map { marker(it) })
    }

    @Test
    fun `a unit with its own SPS and PPS sends exactly one of each`() {
        val p = packetizer()
        p.put(unit(sps, pps, idr))
        assertEquals(listOf(7, 8, 5), sent.map { nalType(it) })
    }

    @Test
    fun `a delta unit gets no parameter sets`() {
        val p = packetizer()
        p.put(unit(sps, pps, idr))
        sent.clear()
        p.put(unit(delta))
        assertEquals(listOf(1), sent.map { nalType(it) })
    }

    @Test
    fun `a lone IDR before any SPS goes out alone`() {
        packetizer().put(unit(idr))
        assertEquals(listOf(5), sent.map { nalType(it) })
    }

    @Test
    fun `the counters follow the units`() {
        val p = packetizer()
        p.put(unit(sps, pps, idr))
        p.put(unit(idr))
        p.put(unit(delta))
        assertEquals(2, p.keyframes)
        assertEquals(1, p.keyframesWithOwnParameterSets)
        assertEquals(sent.size.toLong(), p.packets)
        assertEquals(sent.sumOf { it.size }.toLong(), p.bytes)
        p.resetCounters()
        assertEquals(0, p.keyframes)
        assertEquals(0L, p.packets)
    }

    @Test
    fun `an empty unit and a unit without a start code send nothing`() {
        val p = packetizer()
        p.put(ByteArray(0))
        p.put(byteArrayOf(1, 2, 3, 4, 5))
        assertFalse(sent.isNotEmpty())
    }
}
