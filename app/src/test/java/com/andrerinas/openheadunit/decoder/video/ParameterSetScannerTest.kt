package com.andrerinas.openheadunit.decoder.video

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Random

class ParameterSetScannerTest {
    private data class Hit(val header: Int, val length: Int, val kind: Int)

    private fun scan(b: ByteArray, off: Int, size: Int, hevc: Boolean): List<Hit> {
        val out = ArrayList<Hit>()
        ParameterSetScanner.forEachParameterSet(b, off, size, hevc) { h, l, k -> out.add(Hit(h, l, k)) }
        return out
    }

    /** Verbatim copy of the byte-by-byte walk that the scanner replaced. */
    private fun oldScan(buffer: ByteArray, offset: Int, size: Int, hevc: Boolean): List<Hit> {
        val out = ArrayList<Hit>()
        var currentPos = offset
        val limit = offset + size
        while (currentPos < limit - 3) {
            var nalStart = -1
            var startCodeLen = 0
            for (i in currentPos until limit - 3) {
                if (buffer[i].toInt() == 0 && buffer[i + 1].toInt() == 0) {
                    if (buffer[i + 2].toInt() == 0 && buffer[i + 3].toInt() == 1) {
                        nalStart = i; startCodeLen = 4; break
                    } else if (buffer[i + 2].toInt() == 1) {
                        nalStart = i; startCodeLen = 3; break
                    }
                }
            }
            if (nalStart != -1) {
                var nalEnd = limit
                for (j in (nalStart + startCodeLen) until limit - 3) {
                    if (buffer[j].toInt() == 0 && buffer[j + 1].toInt() == 0 &&
                        (buffer[j + 2].toInt() == 1 || (buffer[j + 2].toInt() == 0 && buffer[j + 3].toInt() == 1))) {
                        nalEnd = j; break
                    }
                }
                val header = nalStart + startCodeLen
                if (header < nalEnd) {
                    val first = buffer[header].toInt() and 0xFF
                    val kind = if (hevc) (first and 0x7E) shr 1 else first and 0x1F
                    val parameterSet = if (hevc) kind in 32..34 else kind == 7 || kind == 8
                    if (parameterSet) out.add(Hit(header, nalEnd - header, kind))
                }
                currentPos = nalEnd
            } else break
        }
        return out
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun same(b: ByteArray, hevc: Boolean): List<Hit> {
        val expected = oldScan(b, 0, b.size, hevc)
        assertEquals(expected, scan(b, 0, b.size, hevc))
        return expected
    }

    @Test
    fun differentialAgainstTheOldWalk() {
        val rnd = Random(20261105)
        val pool = intArrayOf(0x00, 0x00, 0x00, 0x01, 0x01, 0x03, 0x41, 0x65, 0x67, 0x68, 0x26, 0x42, 0xFF)
        repeat(50_000) {
            val arr = ByteArray(rnd.nextInt(601)) { pool[rnd.nextInt(pool.size)].toByte() }
            val off = if (arr.isEmpty()) 0 else minOf(rnd.nextInt(9), arr.size)
            val size = if (rnd.nextBoolean()) arr.size - off else rnd.nextInt(arr.size - off + 1)
            val hevc = rnd.nextBoolean()
            assertEquals(oldScan(arr, off, size, hevc), scan(arr, off, size, hevc))
        }
    }

    @Test
    fun h264SpsPpsIdr() {
        val b = bytes(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 0, 1, 0x65, 9, 9)
        assertEquals(listOf(Hit(4, 3, 7), Hit(10, 2, 8)), same(b, false))
    }

    @Test
    fun audAndSeiFirst() {
        val b = bytes(0, 0, 0, 1, 0x09, 0xF0, 0, 0, 1, 0x06, 5, 0, 0, 0, 1, 0x67, 7, 7)
        assertEquals(listOf(Hit(15, 3, 7)), same(b, false))
    }

    @Test
    fun h265VpsSpsPps() {
        val b = bytes(0, 0, 0, 1, 0x40, 1, 5, 0, 0, 0, 1, 0x42, 1, 6, 0, 0, 0, 1, 0x44, 1, 7)
        assertEquals(listOf(Hit(4, 3, 32), Hit(11, 3, 33), Hit(18, 3, 34)), same(b, true))
    }

    @Test
    fun parameterSetAfterASlice() {
        val b = bytes(0, 0, 1, 0x41, 4, 4, 0, 0, 1, 0x67, 8, 8, 8)
        assertEquals(listOf(Hit(9, 4, 7)), same(b, false))
    }

    @Test
    fun bigSliceThenParameterSet() {
        val b = ByteArray(65_536 + 8)
        b[2] = 1; b[3] = 0x65
        for (i in 4 until 65_536) b[i] = 0x11
        val p = 65_536
        b[p + 2] = 1; b[p + 3] = 0x67; b[p + 4] = 1; b[p + 5] = 2; b[p + 6] = 3; b[p + 7] = 4
        assertEquals(listOf(Hit(p + 3, 5, 7)), same(b, false))
    }

    @Test
    fun lastSetWithNoSliceAfterIt() {
        val b = bytes(0, 0, 1, 0x68, 1, 2, 3)
        assertEquals(listOf(Hit(3, 4, 8)), same(b, false))
    }

    @Test
    fun bothPrefixLengthsAtEverySkipAlignment() {
        for (pad in 0..6) {
            for (zeros in intArrayOf(2, 3)) {
                val v = IntArray(pad) { 0x41 } + IntArray(zeros) { 0 } + intArrayOf(1, 0x67, 5, 5, 5)
                val hits = same(bytes(*v), false)
                assertEquals(1, hits.size)
                assertEquals(pad + zeros + 1, hits[0].header)
            }
        }
    }

    @Test
    fun fiveByteZeroRun() {
        val b = bytes(0, 0, 0, 0, 1, 0x67, 1, 1)
        assertEquals(listOf(Hit(5, 3, 7)), same(b, false))
    }
}
