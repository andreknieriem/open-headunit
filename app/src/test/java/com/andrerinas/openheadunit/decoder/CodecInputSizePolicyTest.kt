package com.andrerinas.openheadunit.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The buffer request that decides how much graphics memory a decoder's input port takes.
 *
 * The measurement this exists for: a 2 MB request at 1280x720 was answered with eight buffers of
 * that size - 16 MB - on a unit with a 12-20 MB Java heap.
 */
class CodecInputSizePolicyTest {

    private val mb = 1024 * 1024
    private val h265Cap = 2 * mb
    private val h265CapAbove1080p = 8 * mb
    private val h264LegacyCap = 1 * mb

    @Test
    fun `720p asks for well under the tier it used to take`() {
        val size = CodecInputSizePolicy.maxInputSizeFor(1280, 720, h265Cap)
        // 80 x 45 macroblocks, 921600 samples, 3/(2*2) of that.
        assertEquals(691200, size)
        assertTrue("must be a real saving over the 2MB tier", size < h265Cap / 2)
    }

    @Test
    fun `1080p stays inside the tier and still saves`() {
        val size = CodecInputSizePolicy.maxInputSizeFor(1920, 1080, h265Cap)
        // 120 x 68 macroblocks - 1080 rounds up to 1088 - so 2088960 samples.
        assertEquals(1566720, size)
        assertTrue(size < h265Cap)
    }

    @Test
    fun `800x480 saves too, and the floor does not bind there`() {
        val size = CodecInputSizePolicy.maxInputSizeFor(800, 480, h265Cap)
        assertEquals(288000, size)
        assertTrue(
            "the floor must not decide the answer at the smallest resolution Android Auto negotiates",
            size > CodecInputSizePolicy.MIN_INPUT_SIZE_BYTES
        )
    }

    @Test
    fun `4K needs most of its tier, which is why the tier exists`() {
        val size = CodecInputSizePolicy.maxInputSizeFor(3840, 2160, h265CapAbove1080p)
        assertEquals(6220800, size)
        assertTrue(size < h265CapAbove1080p)
    }

    @Test
    fun `the cap is never exceeded, whatever the resolution claims`() {
        // A stream that claims 8K on a device whose tier is 2MB must still get 2MB, not 25MB.
        assertEquals(h265Cap, CodecInputSizePolicy.maxInputSizeFor(7680, 4320, h265Cap))
        assertEquals(h264LegacyCap, CodecInputSizePolicy.maxInputSizeFor(1920, 1080, h264LegacyCap))
    }

    @Test
    fun `unknown dimensions leave the cap alone`() {
        // Shrinking a buffer on a guess is how a working unit stops working.
        for (bad in listOf(0 to 720, 1280 to 0, 0 to 0, -1 to -1)) {
            assertEquals(
                "dimensions ${bad.first}x${bad.second} must not shrink anything",
                h265Cap,
                CodecInputSizePolicy.maxInputSizeFor(bad.first, bad.second, h265Cap)
            )
        }
    }

    @Test
    fun `a tiny picture still gets a usable buffer`() {
        val size = CodecInputSizePolicy.maxInputSizeFor(320, 240, h265Cap)
        assertEquals(CodecInputSizePolicy.MIN_INPUT_SIZE_BYTES, size)
    }

    @Test
    fun `the floor cannot push a request past its cap`() {
        // A cap smaller than the floor is a device tier we have measured and must respect.
        val tinyCap = 128 * 1024
        assertEquals(tinyCap, CodecInputSizePolicy.maxInputSizeFor(320, 240, tinyCap))
        assertEquals(tinyCap, CodecInputSizePolicy.maxInputSizeFor(1920, 1080, tinyCap))
    }

    @Test
    fun `the request never grows with resolution beyond its cap and never shrinks with it`() {
        var previous = 0
        for (height in listOf(240, 480, 600, 720, 1080)) {
            val width = height * 16 / 9
            val size = CodecInputSizePolicy.maxInputSizeFor(width, height, h265CapAbove1080p)
            assertTrue("size must be monotonic in resolution", size >= previous)
            previous = size
        }
    }
}
