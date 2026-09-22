package com.andrerinas.openheadunit.secondscreen.ms912x

import com.andrerinas.openheadunit.secondscreen.ms912x.Ms912xFrameConverter.Fit
import com.andrerinas.openheadunit.secondscreen.ms912x.Ms912xFrameConverter.Plane
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Ms912xFrameConverterTest {

    /** A width x height I420 picture where every pixel carries the same Y, U and V. */
    private fun flat(width: Int, height: Int, y: Int, u: Int, v: Int): Triple<Plane, Plane, Plane> = Triple(
        Plane(ByteArray(width * height) { y.toByte() }, width, 1),
        Plane(ByteArray(width / 2 * height / 2) { u.toByte() }, width / 2, 1),
        Plane(ByteArray(width / 2 * height / 2) { v.toByte() }, width / 2, 1),
    )

    @Test
    fun `a picture the mode's size converts one to one into UYVY`() {
        val y = Plane(byteArrayOf(10, 11, 12, 13, 20, 21, 22, 23), 4, 1)
        val u = Plane(byteArrayOf(100, 101), 2, 1)
        val v = Plane(byteArrayOf(-56, -55), 2, 1)
        val out = ByteArray(4 * 2 * 2)
        Ms912xFrameConverter().convert(y, u, v, 4, 2, Ms912xWireFormat.YUV422, out, 4, 4, 2, stretch = false)
        assertArrayEquals(
            byteArrayOf(100, 10, -56, 11, 101, 12, -55, 13, 100, 20, -56, 21, 101, 22, -55, 23),
            out,
        )
    }

    @Test
    fun `interleaved chroma with a pixel stride of two reads the same`() {
        val y = Plane(byteArrayOf(10, 11, 12, 13), 4, 1)
        val uv = byteArrayOf(100, -56, 101, -55)
        val u = Plane(uv, 4, 2)
        val v = Plane(uv.copyOfRange(1, uv.size) + 0, 4, 2)
        val out = ByteArray(8)
        Ms912xFrameConverter().convert(y, u, v, 4, 1, Ms912xWireFormat.YUV422, out, 4, 4, 1, stretch = false)
        assertArrayEquals(byteArrayOf(100, 10, -56, 11, 101, 12, -55, 13), out)
    }

    @Test
    fun `studio white and black come out as full-range BGR`() {
        for ((luma, expected) in listOf(235 to 255, 16 to 0)) {
            val (y, u, v) = flat(2, 2, luma, 128, 128)
            val out = ByteArray(2 * 2 * 3)
            Ms912xFrameConverter().convert(y, u, v, 2, 2, Ms912xWireFormat.RGB888, out, 2, 2, 2, stretch = false)
            assertEquals(expected, out[0].toInt() and 0xFF)
            assertEquals(expected, out[1].toInt() and 0xFF)
            assertEquals(expected, out[2].toInt() and 0xFF)
        }
    }

    @Test
    fun `pure red lands in the last byte, because the chip reads BGR`() {
        // BT.601 limited-range red: Y 81, U 90, V 240. The fork's fallback computed this wrongly.
        val (y, u, v) = flat(2, 2, 81, 90, 240)
        val out = ByteArray(2 * 2 * 3)
        Ms912xFrameConverter().convert(y, u, v, 2, 2, Ms912xWireFormat.RGB888, out, 2, 2, 2, stretch = false)
        assertEquals(0, out[0].toInt() and 0xFF, 2.0)
        assertEquals(0, out[1].toInt() and 0xFF, 2.0)
        assertEquals(255, out[2].toInt() and 0xFF, 2.0)
    }

    private fun assertEquals(expected: Int, actual: Int, tolerance: Double) =
        org.junit.Assert.assertEquals(expected.toDouble(), actual.toDouble(), tolerance)

    @Test
    fun `a narrower picture is pillarboxed on an even offset`() {
        assertEquals(Fit(960, 720, 160, 0), Ms912xFrameConverter.fit(4, 3, 1280, 720, stretch = false))
        // (1366 - 1024) / 2 = 171 would split a chroma pair.
        assertEquals(170, Ms912xFrameConverter.fit(1024, 768, 1366, 768, stretch = false).offsetX)
        assertEquals(Fit(1280, 720, 0, 0), Ms912xFrameConverter.fit(4, 3, 1280, 720, stretch = true))
    }

    @Test
    fun `the letterbox around a small picture is black in UYVY`() {
        val (y, u, v) = flat(2, 2, 200, 50, 60)
        val out = ByteArray(8 * 2 * 2)
        Ms912xFrameConverter().convert(y, u, v, 2, 2, Ms912xWireFormat.YUV422, out, 8, 8, 2, stretch = false)
        // A 2x2 picture in an 8x2 frame starts at pixel 2, byte 4, and the rest stays black.
        assertArrayEquals(byteArrayOf(-128, 16, -128, 16), out.copyOfRange(0, 4))
        assertArrayEquals(byteArrayOf(50, -56, 60, -56), out.copyOfRange(4, 8))
        assertArrayEquals(byteArrayOf(-128, 16, -128, 16), out.copyOfRange(12, 16))
    }
}
