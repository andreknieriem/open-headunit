package com.andrerinas.openheadunit.connection.usb.hci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtekFirmwareTest {

    private fun le32(v: Long) = FirmwareFixtures.le32(v)
    private fun patch(size: Int, fill: Int) = FirmwareFixtures.patch(size, fill)
    private fun build(
        entries: List<Pair<Int, ByteArray>>, projectId: Int = 14,
        signature: ByteArray? = null, end: ByteArray? = null, offsetShift: Int = 0,
    ): ByteArray = when {
        signature != null -> FirmwareFixtures.build(entries, signature = signature)
        end != null -> FirmwareFixtures.build(entries, end = end)
        else -> FirmwareFixtures.build(entries, projectId = projectId, offsetShift = offsetShift)
    }

    private val chip1 = patch(100, 0x01)
    private val chip2 = patch(30204, 0x02)
    private val valid = build(listOf(1 to chip1, 2 to chip2))
    private val config = ByteArray(6) { 0x0C }

    private fun patchOf(r: RealtekFirmware.Result) = r as RealtekFirmware.Patch

    @Test
    fun findsChipTwoForRomVersionOne() {
        val p = patchOf(RealtekFirmware.parse(valid, config, 1))
        assertEquals(2, p.chipId)
        assertEquals(0xDFC6D922L, p.version)
        assertEquals(120, p.fragments)
        assertEquals(30204 + 6, p.payload.size)
    }

    @Test
    fun theFileVersionReplacesTheLastFourPatchBytesAndPrecedesTheConfig() {
        val p = patchOf(RealtekFirmware.parse(valid, config, 1))
        assertArrayEquals(le32(0xDFC6D922L), p.payload.copyOfRange(30200, 30204))
        assertArrayEquals(patch(30200, 0x02), p.payload.copyOfRange(0, 30200))
        assertArrayEquals(config, p.payload.copyOfRange(30204, 30210))
    }

    @Test
    fun aMissingConfigIsNotAFailure() {
        val p = patchOf(RealtekFirmware.parse(valid, null, 1))
        assertEquals(30204, p.payload.size)
        assertEquals(120, p.fragments)
    }

    @Test
    fun rejectsBadSignatureBadEndMarkerAndWrongProject() {
        assertTrue(RealtekFirmware.parse(build(listOf(2 to chip2), signature = "Realtekk".toByteArray()), config, 1) is RealtekFirmware.Reject)
        assertTrue(RealtekFirmware.parse(build(listOf(2 to chip2), end = byteArrayOf(1, 2, 3, 4)), config, 1) is RealtekFirmware.Reject)
        assertTrue(RealtekFirmware.parse(build(listOf(2 to chip2), projectId = 13), config, 1) is RealtekFirmware.Reject)
    }

    @Test
    fun rejectsARomVersionWithNoEntry() {
        assertTrue(RealtekFirmware.parse(valid, config, 5) is RealtekFirmware.Reject)
    }

    @Test
    fun rejectsATableThatRunsOffTheEnd() {
        assertTrue(RealtekFirmware.parse(build(listOf(2 to chip2), offsetShift = 100000), config, 1) is RealtekFirmware.Reject)
    }

    @Test
    fun everyTruncationRejectsWithoutThrowing() {
        for (len in valid.indices) {
            assertTrue("len=$len", RealtekFirmware.parse(valid.copyOf(len), config, 1) is RealtekFirmware.Reject)
        }
    }

    @Test
    fun aPayloadOverOneHundredTwentyEightFragmentsRejects() {
        val big = build(listOf(2 to patch(128 * 252 + 4, 0x03)))
        assertTrue(RealtekFirmware.parse(big, null, 1) is RealtekFirmware.Reject)
        val max = build(listOf(2 to patch(128 * 252, 0x03)))
        assertEquals(128, patchOf(RealtekFirmware.parse(max, null, 1)).fragments)
    }

    @Test
    fun stateOfReadsTheRomRowOnly() {
        fun v(sub: Int, rev: Int, hci: Int) = HciCodec.LocalVersion(0, hci, rev, 0x0A, 0x5D, sub)
        assertEquals(RealtekFirmware.ChipState.ROM_8761B, RealtekFirmware.stateOf(v(0x8761, 0x000B, 0x0A)))
        assertEquals(RealtekFirmware.ChipState.NO_LOAD, RealtekFirmware.stateOf(v(0xD922, 0xDFC6, 0x0A)))
        assertEquals(RealtekFirmware.ChipState.NO_LOAD, RealtekFirmware.stateOf(v(0x8761, 0x000E, 0x0A)))
    }
}
