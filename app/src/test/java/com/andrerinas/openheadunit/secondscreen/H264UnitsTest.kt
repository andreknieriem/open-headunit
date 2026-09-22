package com.andrerinas.openheadunit.secondscreen

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class H264UnitsTest {

    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C)
    private val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x00)
    private val delta = byteArrayOf(0x41, 0x9A.toByte(), 0x02, 0x00)

    private fun unit(vararg nals: ByteArray, startCode: ByteArray = byteArrayOf(0, 0, 0, 1)): ByteArray =
        nals.fold(ByteArray(0)) { acc, nal -> acc + startCode + nal }

    @Test
    fun `the parameter sets are lifted out of a unit that also carries the keyframe`() {
        val au = unit(sps, pps, idr)
        assertArrayEquals(unit(sps, pps), H264Units.parameterSets(au, 0, au.size))
        assertTrue(H264Units.isKeyframe(au, 0, au.size))
    }

    @Test
    fun `three-byte start codes come back as four-byte ones`() {
        val au = unit(sps, pps, startCode = byteArrayOf(0, 0, 1))
        assertArrayEquals(unit(sps, pps), H264Units.parameterSets(au, 0, au.size))
    }

    @Test
    fun `a delta frame carries no parameter sets and is no keyframe`() {
        val au = unit(delta)
        assertNull(H264Units.parameterSets(au, 0, au.size))
        assertFalse(H264Units.isKeyframe(au, 0, au.size))
    }

    @Test
    fun `an offset into a larger buffer is honoured`() {
        val au = byteArrayOf(9, 9, 9) + unit(sps, pps) + byteArrayOf(0, 0)
        assertArrayEquals(unit(sps, pps), H264Units.parameterSets(au, 3, au.size - 5))
    }
}
