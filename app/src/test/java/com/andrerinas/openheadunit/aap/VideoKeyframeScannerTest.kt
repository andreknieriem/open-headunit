package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoKeyframeScannerTest {

    /** Builds an access unit from (nalHeaderByte, payloadBytes) pairs, 4-byte start codes. */
    private fun accessUnit(vararg nals: Int): ByteArray {
        val out = ArrayList<Byte>()
        for (header in nals) {
            out.add(0); out.add(0); out.add(0); out.add(1)
            out.add(header.toByte())
            repeat(3) { out.add(0x42) }
        }
        return out.toByteArray()
    }

    private fun scan(data: ByteArray, isHevc: Boolean) =
        VideoKeyframeScanner.containsKeyframe(data, 0, data.size, isHevc)

    // --- H.264 -----------------------------------------------------------------------------

    @Test
    fun `an h264 idr slice is a keyframe`() {
        assertTrue(scan(accessUnit(0x65), isHevc = false)) // nal_ref_idc=3, type 5
    }

    @Test
    fun `an h264 idr behind its parameter sets is still found`() {
        // What the phone actually sends: SPS (7), PPS (8), then the IDR slice.
        assertTrue(scan(accessUnit(0x67, 0x68, 0x65), isHevc = false))
    }

    @Test
    fun `an h264 non-idr slice is not a keyframe`() {
        assertFalse(scan(accessUnit(0x41), isHevc = false)) // type 1
    }

    @Test
    fun `parameter sets alone are not a keyframe`() {
        // A config-only message carries no picture, so it repairs nothing on its own.
        assertFalse(scan(accessUnit(0x67, 0x68), isHevc = false))
    }

    // --- H.265 -----------------------------------------------------------------------------

    @Test
    fun `hevc irap types are keyframes across the whole range`() {
        for (type in 16..21) {
            assertTrue("HEVC NAL type $type", scan(accessUnit(type shl 1), isHevc = true))
        }
    }

    @Test
    fun `an hevc trailing picture is not a keyframe`() {
        assertFalse(scan(accessUnit(0x02), isHevc = true)) // TRAIL_R, type 1
    }

    @Test
    fun `an hevc idr behind vps sps pps is still found`() {
        assertTrue(scan(accessUnit(32 shl 1, 33 shl 1, 34 shl 1, 19 shl 1), isHevc = true))
    }

    // --- The mismatch that froze a session once --------------------------------------------

    @Test
    fun `reading an h264 idr as hevc can go either way, which is why the pinned type is passed`() {
        // The header byte carries nal_ref_idc as well as the type, and the two codecs read different
        // bits of it. 0x65 (the IDR the phone actually sends, nal_ref_idc=3) reads as HEVC type 50
        // and is correctly rejected - but 0x25, the same IDR at nal_ref_idc=1, reads as HEVC type 18
        // and would be accepted. So a wrong codec type gives a wrong answer in both directions.
        // That is the failure 66e59b2c hit by reading headers against the user's preference; the
        // caller passes the decoder's pinned type instead. Pinned here so the reason stays visible.
        assertTrue(scan(accessUnit(0x65), isHevc = false))
        assertFalse(scan(accessUnit(0x65), isHevc = true))

        assertTrue(scan(accessUnit(0x25), isHevc = false))
        assertTrue(scan(accessUnit(0x25), isHevc = true))
    }

    // --- Framing -----------------------------------------------------------------------------

    @Test
    fun `three byte start codes are recognised`() {
        val data = byteArrayOf(0, 0, 1, 0x65, 0x42, 0x42)
        assertTrue(VideoKeyframeScanner.containsKeyframe(data, 0, data.size, isHevc = false))
    }

    @Test
    fun `an offset into a larger buffer is honoured`() {
        val prefix = byteArrayOf(0x11, 0x22)
        val data = prefix + accessUnit(0x65)
        assertTrue(
            VideoKeyframeScanner.containsKeyframe(data, prefix.size, data.size - prefix.size, isHevc = false)
        )
        // The same bytes below the offset must not be reachable.
        assertFalse(VideoKeyframeScanner.containsKeyframe(data, 0, prefix.size, isHevc = false))
    }

    @Test
    fun `a truncated or empty access unit is not a keyframe`() {
        assertFalse(VideoKeyframeScanner.containsKeyframe(ByteArray(0), 0, 0, isHevc = false))
        assertFalse(VideoKeyframeScanner.containsKeyframe(byteArrayOf(0, 0, 1), 0, 3, isHevc = false))
    }

    @Test
    fun `a keyframe buried past the header cap is not scanned for`() {
        // Bounding the walk is deliberate - a keyframe access unit leads with its parameter sets, so
        // anything this deep is not one. Pinned here so the bound cannot be widened silently.
        val nals = IntArray(9) { if (it == 8) 0x65 else 0x41 }
        assertFalse(scan(accessUnit(*nals), isHevc = false))
    }
}
