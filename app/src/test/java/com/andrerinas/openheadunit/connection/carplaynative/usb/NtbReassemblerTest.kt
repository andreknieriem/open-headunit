package com.andrerinas.openheadunit.connection.carplaynative.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NtbReassemblerTest {
    private var now = 0L
    private fun reassembler(packet: Int = 512, log: MutableList<String> = ArrayList()) = NtbReassembler(packet, { now }) { log.add(it) }
    private fun frame(size: Int, seed: Int = 1) = ByteArray(size) { (it + seed).toByte() }
    private fun NtbReassembler.frames() = generateSequence { takeFrame() }.toList()

    @Test fun oneBlockGivesOneFrame() {
        val r = reassembler()
        r.append(Ntb16Codec.build(frame(100), 0))
        assertArrayEquals(frame(100), r.takeFrame())
        assertNull(r.takeFrame())
    }

    @Test fun aBlockCutIntoSingleBytesIsJoined() {
        val r = reassembler()
        Ntb16Codec.build(frame(60), 1).forEach { r.append(byteArrayOf(it)) }
        assertEquals(1, r.frames().size)
    }

    @Test fun twoBlocksInOneReadGiveTwoFramesInOrder() {
        val r = reassembler()
        r.append(Ntb16Codec.build(frame(50, 1), 0) + Ntb16Codec.build(frame(70, 2), 1))
        assertEquals(listOf(50, 70), r.frames().map { it.size })
    }

    @Test fun aBlockOnAPacketBoundaryIsFollowedByItsZeroPadEvenWhenItArrivesLater() {
        val r = reassembler()
        val block = Ntb16Codec.build(frame(484), 0)
        assertEquals(513, block.size)
        r.append(block.copyOf(512))
        assertEquals(1, r.frames().size)
        r.append(byteArrayOf(0))
        r.append(Ntb16Codec.build(frame(30), 1))
        assertEquals(listOf(30), r.frames().map { it.size })
        assertEquals(0, r.resyncs)
    }

    @Test fun aBlockOnAPacketBoundaryWithNoPadIsAlsoFine() {
        val r = reassembler()
        r.append(Ntb16Codec.build(frame(484), 0).copyOf(512))
        r.append(Ntb16Codec.build(frame(30), 1))
        assertEquals(listOf(484, 30), r.frames().map { it.size })
        assertEquals(0, r.resyncs)
    }

    @Test fun theFullSpeedPacketSizeFollowsTheEndpoint() {
        val r = reassembler(packet = 64)
        // 92 bytes is not a multiple of 64, 28 + 36 is: its pad is optional only at 64.
        val block = Ntb16Codec.build(frame(36), 0).copyOf(64)
        r.append(block + byteArrayOf(0) + Ntb16Codec.build(frame(10), 1))
        assertEquals(listOf(36, 10), r.frames().map { it.size })
    }

    @Test fun garbageBeforeABlockIsSkippedAndCounted() {
        val log = ArrayList<String>()
        val r = reassembler(log = log)
        r.append(ByteArray(40) { 0x55 } + Ntb16Codec.build(frame(80), 0))
        assertEquals(listOf(80), r.frames().map { it.size })
        assertEquals(1, r.resyncs)
        assertEquals(1, log.size)
    }

    @Test fun aHeaderLikeGarbageWithoutRoomForABlockWaitsForMoreBytes() {
        val r = reassembler()
        val block = Ntb16Codec.build(frame(80), 0)
        r.append(byteArrayOf(9, 9, 9) + block.copyOf(5))
        r.append(block.copyOfRange(5, block.size))
        assertEquals(listOf(80), r.frames().map { it.size })
    }

    @Test fun damageThatKeepsComingFailsTheLink() {
        val r = reassembler()
        try {
            repeat(20) { r.append(ByteArray(30) { 0x55 } + Ntb16Codec.build(frame(20), 0)); now += 10 }
            fail()
        } catch (_: IphoneUsbException.Protocol) { }
    }

    @Test fun damageSpreadOverTimeDoesNot() {
        val r = reassembler()
        repeat(40) { r.append(ByteArray(30) { 0x55 } + Ntb16Codec.build(frame(20), 0)); r.frames(); now += 1000 }
        assertTrue(r.resyncs >= 40)
    }

    @Test fun anUnreadQueueHasABound() {
        val r = reassembler()
        try { repeat(300) { r.append(Ntb16Codec.build(frame(20), it)) }; fail() } catch (_: IphoneUsbException.Protocol) { }
    }
}
