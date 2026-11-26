package com.andrerinas.openheadunit.connection.carplaynative.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Ntb16CodecTest {
    @Test
    fun parseCanReadBlockFromLargerBuffer() {
        val frame = byteArrayOf(0x33, 0x33, 0, 0, 0, 1, 0x86.toByte(), 0xdd.toByte())
        val block = Ntb16Codec.build(frame, 7)
        val buffer = ByteArray(11) + block + ByteArray(3)

        val parsed = Ntb16Codec.parse(buffer, 11, block.size)

        assertArrayEquals(frame, parsed.single())
    }

    @Test
    fun largestDatagramKeepsBlockLengthWithinU16() {
        val block = Ntb16Codec.build(ByteArray(65_507), 0x1234)

        assertEquals(65_535, block.size)
        assertEquals(0xff, block[8].toInt() and 0xff)
        assertEquals(0xff, block[9].toInt() and 0xff)
    }

    @Test(expected = IllegalArgumentException::class)
    fun datagramThatWouldOverflowBlockLengthIsRejected() {
        Ntb16Codec.build(ByteArray(65_508), 0x1234)
    }

    @Test
    fun overlappingDatagramsCannotMultiplyTheBlockIntoGigabytes() {
        val entries = 1_000
        val tableEnd = NDP_OFFSET + 8 + entries * 4
        val block = ByteArray(65_535)
        header(block, NDP_OFFSET)
        ndp(block, NDP_OFFSET, entries * 4 + 8, 0)
        repeat(entries) { putEntry(block, NDP_OFFSET + 8 + it * 4, tableEnd, block.size - tableEnd) }

        val parsed = Ntb16Codec.parse(block)

        val total = parsed.sumOf { it.size.toLong() }
        assertTrue("parsed ${parsed.size} datagrams totalling $total bytes from a ${block.size}-byte block", total <= block.size)
    }

    @Test(timeout = 10_000)
    fun ndpThatPointsBackAtItselfIsRejected() {
        val block = ByteArray(4_096)
        header(block, NDP_OFFSET)
        ndp(block, NDP_OFFSET, 16, NDP_OFFSET)
        putEntry(block, NDP_OFFSET + 8, 40, 20)

        val parsed = Ntb16Codec.parse(block)

        assertTrue("parsed ${parsed.size} datagrams from an NDP chain that loops", parsed.isEmpty())
    }

    @Test(timeout = 10_000)
    fun twoNdpsThatPointAtEachOtherAreRejected() {
        val second = NDP_OFFSET + 32
        val block = ByteArray(second + 32)
        header(block, NDP_OFFSET)
        ndp(block, NDP_OFFSET, 16, second)
        putEntry(block, NDP_OFFSET + 8, second + 16, 8)
        ndp(block, second, 16, NDP_OFFSET) // back to the first
        putEntry(block, second + 8, second + 24, 8)

        val parsed = Ntb16Codec.parse(block)

        assertTrue("parsed ${parsed.size} datagrams from an NDP chain that loops", parsed.isEmpty())
    }

    @Test
    fun theBridgeQueueLimitOfDatagramsPerBlockIsTheMostABlockMayHold() {
        for ((entries, expected) in listOf(256 to 256, 257 to 0)) {
            val dataStart = NDP_OFFSET + 8 + entries * 4 + 4
            val block = ByteArray(dataStart + entries * 4)
            header(block, NDP_OFFSET)
            ndp(block, NDP_OFFSET, 8 + entries * 4 + 4, 0)
            repeat(entries) { putEntry(block, NDP_OFFSET + 8 + it * 4, dataStart + it * 4, 4) }

            assertEquals("$entries entries", expected, Ntb16Codec.parse(block).size)
        }
    }

    @Test
    fun manyDistinctDatagramsInOneNdpAreAllReturned() {
        val datagrams = 10
        val block = ByteArray(NDP_OFFSET + 8 + datagrams * 4 + 4 + datagrams * 20)
        header(block, NDP_OFFSET)
        ndp(block, NDP_OFFSET, 8 + datagrams * 4 + 4, 0)
        val dataStart = NDP_OFFSET + 8 + datagrams * 4 + 4
        repeat(datagrams) {
            block.fill((it + 1).toByte(), dataStart + it * 20, dataStart + (it + 1) * 20)
            putEntry(block, NDP_OFFSET + 8 + it * 4, dataStart + it * 20, 20)
        }

        val parsed = Ntb16Codec.parse(block)

        assertEquals(datagrams, parsed.size)
        parsed.forEachIndexed { index, datagram -> assertArrayEquals(ByteArray(20) { (index + 1).toByte() }, datagram) }
    }

    @Test
    fun chainOfDistinctNdpsIsFollowed() {
        val second = NDP_OFFSET + 32
        val block = ByteArray(second + 32)
        header(block, NDP_OFFSET)
        ndp(block, NDP_OFFSET, 16, second)
        putEntry(block, NDP_OFFSET + 8, second + 16, 8)
        ndp(block, second, 16, 0)
        putEntry(block, second + 8, second + 24, 8)
        block.fill(1, second + 16, second + 24)
        block.fill(2, second + 24, second + 32)

        val parsed = Ntb16Codec.parse(block)

        assertEquals(2, parsed.size)
        assertArrayEquals(ByteArray(8) { 1 }, parsed[0])
        assertArrayEquals(ByteArray(8) { 2 }, parsed[1])
    }

    private fun header(block: ByteArray, ndpIndex: Int) {
        put(block, 0, Ntb16Codec.NTH16_SIG, 4)
        put(block, 4, 12, 2)
        put(block, 8, block.size, 2)
        put(block, 10, ndpIndex, 2)
    }

    private fun ndp(block: ByteArray, at: Int, length: Int, next: Int) {
        put(block, at, Ntb16Codec.NDP16_SIG, 4)
        put(block, at + 4, length, 2)
        put(block, at + 6, next, 2)
    }

    private fun putEntry(block: ByteArray, at: Int, index: Int, length: Int) {
        put(block, at, index, 2)
        put(block, at + 2, length, 2)
    }

    private fun put(block: ByteArray, at: Int, value: Int, bytes: Int) {
        repeat(bytes) { block[at + it] = (value ushr (8 * it)).toByte() }
    }

    private companion object {
        const val NDP_OFFSET = 12
    }
}
