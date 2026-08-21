package com.andrerinas.openheadunit.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectors for the parameter-set reader.
 *
 * Each was assembled field by field from the syntax in H.264 7.3.2.1.1 / Annex E.1.1 and
 * H.265 7.3.2.2.1, with the field values chosen first and the bits emitted from them, so the
 * expectations below are the inputs rather than a recording of what the parser happens to do.
 * Emulation-prevention bytes were then inserted the way an encoder inserts them, which is why the
 * two HEVC vectors carry `00 00 03` runs - they are the reason this class does its own RBSP
 * unescaping, which the dimension-only parser it replaced did not.
 */
class ParameterSetInspectorTest {

    private fun bytes(hex: String): ByteArray =
        hex.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

    /** H.264 SPS, baseline profile, level 3.1, 1280x720, one reference frame, no VUI. */
    private val h264Baseline720p = bytes("67 42 80 1F F4 02 80 2D C8")

    /**
     * H.264 SPS, high profile, level 4.0, 1920x1080 via a bottom crop of 4 on a 1088 frame,
     * four reference frames, VUI carrying bitstream_restriction with reorder 0 / max_dec 4.
     */
    private val h264High1080pRestricted =
        bytes("67 64 00 28 AC E5 01 E0 08 9F 96 01 B4 11 08 CB")

    /**
     * H.264 SPS with pic_order_cnt_type 1, 1280x720.
     *
     * The dimension parser this class replaced walked type 1 as though it were type 0 - it read the
     * POC LSB field that only type 0 has, and then everything after it came out of the wrong bits.
     * On this exact vector it reported **176x48** for a 1280x720 stream, which is the size the codec
     * would then have been configured at.
     */
    private val h264PocType1 = bytes("67 42 00 1F D7 44 40 28 02 DC 80")

    /**
     * H.264 SPS with timing info and nal_hrd_parameters before the bitstream restriction, so the
     * hrd_parameters skip has to be exactly right or reorder/max_dec come out wrong.
     * Two reference frames, reorder 1, max_dec 3.
     */
    private val h264WithHrd = bytes(
        "67 42 00 1F F6 02 80 2D D0 80 00 01 F4 00 00 75 30 74 30 07 D2 00 7D " +
            "15 EF 7C 0D A0 80 41 12"
    )

    /** HEVC SPS, Main profile, level 3.1, 1280x720, max_dec 4, reorder 0. Contains 00 00 03 runs. */
    private val hevcMain720p =
        bytes("42 01 01 01 00 00 03 00 00 80 00 00 03 00 00 03 00 5D A0 02 80 80 2D 16 59 38")

    /**
     * HEVC SPS with two sub-layers and a conformance window: 1920x1080 from a 1088 frame, and the
     * sub-layer ordering info absent so only the top layer's values are read.
     */
    private val hevc1080pTwoSubLayers = bytes(
        "42 01 03 01 00 00 03 00 00 80 00 00 03 00 00 03 00 78 C0 00 00 03 00 00 03 00 " +
            "00 03 00 00 03 00 00 03 00 00 78 A0 03 C0 80 11 07 CB 94 67 80"
    )

    // ---- H.264 ----

    @Test
    fun `reads a baseline SPS with no VUI`() {
        val sps = ParameterSetInspector.parseH264Sps(h264Baseline720p, 0, h264Baseline720p.size)
        assertNotNull(sps)
        sps!!
        assertEquals(66, sps.profileIdc)
        assertEquals(31, sps.levelIdc)
        assertEquals(0, sps.picOrderCntType)
        assertEquals(1, sps.maxNumRefFrames)
        assertEquals(1280, sps.width)
        assertEquals(720, sps.height)
        assertFalse(sps.vuiPresent)
        assertFalse(sps.bitstreamRestrictionPresent)
        assertNull("absent must read as absent, not as zero", sps.maxNumReorderFrames)
        assertNull(sps.maxDecFrameBuffering)
    }

    @Test
    fun `reads a high profile SPS through the VUI to the bitstream restriction`() {
        val sps = ParameterSetInspector.parseH264Sps(h264High1080pRestricted, 0, h264High1080pRestricted.size)
        assertNotNull(sps)
        sps!!
        assertEquals(100, sps.profileIdc)
        assertEquals(40, sps.levelIdc)
        assertEquals(4, sps.maxNumRefFrames)
        assertEquals(1920, sps.width)
        assertEquals("the bottom crop of 4 must take 1088 down to 1080", 1080, sps.height)
        assertTrue(sps.vuiPresent)
        assertTrue(sps.bitstreamRestrictionPresent)
        assertEquals(0, sps.maxNumReorderFrames)
        assertEquals(4, sps.maxDecFrameBuffering)
    }

    @Test
    fun `pic_order_cnt_type 1 does not desync the fields behind it`() {
        val sps = ParameterSetInspector.parseH264Sps(h264PocType1, 0, h264PocType1.size)
        assertNotNull(sps)
        sps!!
        assertEquals(1, sps.picOrderCntType)
        assertEquals("the old parser read 176 here", 1280, sps.width)
        assertEquals("the old parser read 48 here", 720, sps.height)
        assertEquals(1, sps.maxNumRefFrames)
        assertFalse(sps.vuiPresent)
    }

    @Test
    fun `walks hrd_parameters without losing its place`() {
        // The one field group long enough that getting its length wrong still yields plausible
        // numbers further on. If the skip is off, reorder and max_dec come out as something else.
        val sps = ParameterSetInspector.parseH264Sps(h264WithHrd, 0, h264WithHrd.size)
        assertNotNull(sps)
        sps!!
        assertEquals(2, sps.maxNumRefFrames)
        assertEquals(1280, sps.width)
        assertEquals(720, sps.height)
        assertTrue(sps.bitstreamRestrictionPresent)
        assertEquals(1, sps.maxNumReorderFrames)
        assertEquals(3, sps.maxDecFrameBuffering)
    }

    @Test
    fun `every truncation of an SPS reads as no answer rather than a wrong one`() {
        // The reader latches an overrun rather than throwing, so the whole result is discarded when
        // any field ran past the end. A half-read SPS reported as fact would put a plausible wrong
        // number in a log we intend to make a decision from, which is worse than no number.
        for (cut in 1 until h264High1080pRestricted.size) {
            val short = h264High1080pRestricted.copyOfRange(0, cut)
            assertNull(
                "a $cut-byte prefix produced an answer",
                ParameterSetInspector.parseH264Sps(short, 0, short.size)
            )
        }
    }

    @Test
    fun `empty input is rejected`() {
        assertNull(ParameterSetInspector.parseH264Sps(ByteArray(0), 0, 0))
        assertNull(ParameterSetInspector.parseHevcSps(ByteArray(0), 0, 0))
    }

    // ---- H.265 ----

    @Test
    fun `reads an HEVC SPS past its emulation-prevention bytes`() {
        // This vector contains three inserted 0x03 bytes. A reader that does not remove them shifts
        // every field after the first one, so the exact values here are the proof the unescaping
        // works, not just that the walk is the right length.
        val sps = ParameterSetInspector.parseHevcSps(hevcMain720p, 0, hevcMain720p.size)
        assertNotNull(sps)
        sps!!
        assertEquals("Main profile", 1, sps.generalProfileIdc)
        assertEquals("level 3.1", 93, sps.generalLevelIdc)
        assertEquals("4:2:0", 1, sps.chromaFormatIdc)
        assertEquals(8, sps.bitDepthLuma)
        assertEquals(1280, sps.width)
        assertEquals(720, sps.height)
        assertEquals(4, sps.maxDecPicBuffering)
        assertEquals(0, sps.maxNumReorderPics)
    }

    @Test
    fun `reads an HEVC SPS with sub-layers and a conformance window`() {
        val sps = ParameterSetInspector.parseHevcSps(hevc1080pTwoSubLayers, 0, hevc1080pTwoSubLayers.size)
        assertNotNull(sps)
        sps!!
        assertEquals(1, sps.generalProfileIdc)
        assertEquals(120, sps.generalLevelIdc)
        assertEquals(1920, sps.width)
        assertEquals("the conformance window must take 1088 down to 1080", 1080, sps.height)
        assertEquals(6, sps.maxDecPicBuffering)
        assertEquals(2, sps.maxNumReorderPics)
    }

    @Test
    fun `a truncated HEVC SPS reads as no answer`() {
        val short = hevcMain720p.copyOfRange(0, 8)
        assertNull(ParameterSetInspector.parseHevcSps(short, 0, short.size))
    }

    // ---- the shape of the log line ----

    @Test
    fun `the H264 summary names every field a decision would rest on`() {
        val text = ParameterSetInspector.parseH264Sps(h264High1080pRestricted, 0, h264High1080pRestricted.size)!!.toString()
        for (field in listOf(
            "profile=", "level=", "poc_type=", "num_ref_frames=", "size=", "vui=",
            "bitstream_restriction=", "num_reorder_frames=", "max_dec_frame_buffering="
        )) {
            assertTrue("summary is missing $field: $text", text.contains(field))
        }
    }

    @Test
    fun `an absent restriction says absent rather than zero`() {
        // The distinction the whole diagnostic turns on: Moonlight patches the SPS precisely when
        // bitstream_restriction is missing, and "0" would read as already-optimal.
        val text = ParameterSetInspector.parseH264Sps(h264Baseline720p, 0, h264Baseline720p.size)!!.toString()
        assertTrue(text, text.contains("num_reorder_frames=absent"))
        assertTrue(text, text.contains("max_dec_frame_buffering=absent"))
    }

    @Test
    fun `the HEVC summary names every field a decision would rest on`() {
        val text = ParameterSetInspector.parseHevcSps(hevcMain720p, 0, hevcMain720p.size)!!.toString()
        for (field in listOf(
            "profile=", "level=", "chroma_format=", "bit_depth=", "size=",
            "max_dec_pic_buffering=", "max_num_reorder_pics="
        )) {
            assertTrue("summary is missing $field: $text", text.contains(field))
        }
    }
}
