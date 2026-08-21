package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.CodecConfigScanner.Content
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What may and may not be flagged BUFFER_FLAG_CODEC_CONFIG.
 *
 * The case that matters is [a keyframe leading with its parameter sets is not configuration]: that
 * flag tells MediaCodec the buffer holds codec-specific data and nothing else, so a component may
 * consume it and return no picture. On the three chipset families that flagged every config packet,
 * the old first-NAL-only check answered "configuration" for exactly that unit.
 */
class CodecConfigScannerTest {

    // H.264 NAL header byte: (nal_ref_idc shl 5) or nal_unit_type.
    private val avcSps = 0x67       // type 7, ref_idc 3
    private val avcPps = 0x68       // type 8
    private val avcIdr = 0x65       // type 5
    private val avcNonIdr = 0x41    // type 1, ref_idc 2
    private val avcSei = 0x06       // type 6
    private val avcAud = 0x09       // type 9
    private val avcSpsExt = 0x6D    // type 13

    // H.265 NAL header, first byte: nal_unit_type shl 1.
    private val hevcVps = 0x40      // type 32
    private val hevcSps = 0x42      // type 33
    private val hevcPps = 0x44      // type 34
    private val hevcIdr = 0x26      // type 19, IDR_W_RADL
    private val hevcTrail = 0x02    // type 1, TRAIL_N
    private val hevcAud = 0x46      // type 35
    private val hevcSei = 0x4E      // type 39, PREFIX_SEI

    /** An Annex B access unit: a 4-byte start code and some payload per NAL. */
    private fun unit(vararg headerBytes: Int, threeByteStartCodes: Boolean = false): ByteArray {
        val out = ArrayList<Byte>()
        for (h in headerBytes) {
            if (!threeByteStartCodes) out.add(0)
            out.add(0); out.add(0); out.add(1)
            out.add(h.toByte())
            // Some payload, chosen so it contains no accidental start code.
            for (b in listOf(0x42, 0x99, 0x11, 0x77)) out.add(b.toByte())
        }
        return out.toByteArray()
    }

    private fun classifyAvc(data: ByteArray) = CodecConfigScanner.classify(data, 0, data.size, isHevc = false)
    private fun classifyHevc(data: ByteArray) = CodecConfigScanner.classify(data, 0, data.size, isHevc = true)

    // ---- the case this class exists for ----

    @Test
    fun `a keyframe leading with its parameter sets is not configuration`() {
        assertEquals(
            Content.PARAMETER_SETS_WITH_PICTURE,
            classifyAvc(unit(avcSps, avcPps, avcIdr))
        )
        assertEquals(
            Content.PARAMETER_SETS_WITH_PICTURE,
            classifyHevc(unit(hevcVps, hevcSps, hevcPps, hevcIdr))
        )
    }

    @Test
    fun `parameter sets on their own are configuration`() {
        assertEquals(Content.PARAMETER_SETS_ONLY, classifyAvc(unit(avcSps, avcPps)))
        assertEquals(Content.PARAMETER_SETS_ONLY, classifyHevc(unit(hevcVps, hevcSps, hevcPps)))
    }

    @Test
    fun `parameter sets are found behind a delimiter or an SEI`() {
        // The other half of the old check's failure: a unit that does not *lead* with a parameter set
        // was reported as carrying none, so decoders that require the flag never got it.
        assertEquals(Content.PARAMETER_SETS_ONLY, classifyAvc(unit(avcAud, avcSps, avcPps)))
        assertEquals(Content.PARAMETER_SETS_ONLY, classifyAvc(unit(avcSei, avcSps)))
        assertEquals(Content.PARAMETER_SETS_ONLY, classifyHevc(unit(hevcAud, hevcVps, hevcSps, hevcPps)))
        assertEquals(Content.PARAMETER_SETS_ONLY, classifyHevc(unit(hevcSei, hevcSps)))
    }

    @Test
    fun `an ordinary frame carries no parameter sets`() {
        assertEquals(Content.NO_PARAMETER_SETS, classifyAvc(unit(avcNonIdr)))
        assertEquals(Content.NO_PARAMETER_SETS, classifyAvc(unit(avcAud, avcNonIdr)))
        assertEquals(Content.NO_PARAMETER_SETS, classifyHevc(unit(hevcTrail)))
        assertEquals(Content.NO_PARAMETER_SETS, classifyHevc(unit(hevcAud, hevcTrail)))
    }

    @Test
    fun `an IDR with no parameter sets is still just a frame`() {
        // Nothing to configure with, so nothing to flag - and it must not be treated as config.
        assertEquals(Content.NO_PARAMETER_SETS, classifyAvc(unit(avcIdr)))
        assertEquals(Content.NO_PARAMETER_SETS, classifyHevc(unit(hevcIdr)))
    }

    // ---- details of the walk ----

    @Test
    fun `three-byte start codes are read too`() {
        assertEquals(
            Content.PARAMETER_SETS_WITH_PICTURE,
            classifyAvc(unit(avcSps, avcPps, avcIdr, threeByteStartCodes = true))
        )
        assertEquals(
            Content.PARAMETER_SETS_ONLY,
            classifyHevc(unit(hevcVps, hevcSps, threeByteStartCodes = true))
        )
    }

    @Test
    fun `the H264 SPS extensions count as parameter sets`() {
        assertEquals(Content.PARAMETER_SETS_ONLY, classifyAvc(unit(avcSpsExt)))
    }

    @Test
    fun `the answer is read against the pinned codec, because the same byte means two things`() {
        // 0x41 is an H.264 non-IDR slice; read as HEVC its type is (0x41 and 0x7E) shr 1 == 32, which
        // is a VPS. This is the documented false positive that once let a keyframe latch read an
        // ordinary P-slice as configuration - which is why the caller passes the type the decoder
        // pinned and never the user's preference.
        val ambiguous = unit(avcNonIdr)
        assertEquals(Content.NO_PARAMETER_SETS, classifyAvc(ambiguous))
        assertEquals(Content.PARAMETER_SETS_ONLY, classifyHevc(ambiguous))
    }

    @Test
    fun `a unit too short to hold a NAL is not configuration`() {
        for (size in 0..4) {
            val data = ByteArray(size)
            assertEquals(Content.NO_PARAMETER_SETS, CodecConfigScanner.classify(data, 0, size, isHevc = false))
            assertEquals(Content.NO_PARAMETER_SETS, CodecConfigScanner.classify(data, 0, size, isHevc = true))
        }
    }

    @Test
    fun `bytes outside the offset and size are not read`() {
        // The frame is a window into a larger buffer on every path that reaches this.
        val payload = unit(avcSps, avcPps)
        val padded = ByteArray(payload.size + 16)
        System.arraycopy(payload, 0, padded, 8, payload.size)
        // A keyframe sitting in the padding must not be seen.
        val trailer = unit(avcIdr)
        System.arraycopy(trailer, 0, padded, 8 + payload.size, minOf(trailer.size, 8))
        assertEquals(
            Content.PARAMETER_SETS_ONLY,
            CodecConfigScanner.classify(padded, 8, payload.size, isHevc = false)
        )
    }

    @Test
    fun `a large frame is not walked past the byte bound`() {
        // A P-frame contains no start code after its first, so an unbounded walk reads all of it -
        // 60 times a second, on the feed thread. The bound is what stops that.
        val big = ByteArray(400 * 1024) { 0x5A }
        assertEquals(Content.NO_PARAMETER_SETS, CodecConfigScanner.classify(big, 0, big.size, isHevc = false))
    }

    @Test
    fun `stopping at the bound withholds the flag rather than guessing`() {
        // Parameter sets found, and then a stretch too long to see the end of. The unit may or may not
        // contain a picture, and the safe answer is the one that does not flag it as configuration.
        val head = unit(avcSps, avcPps)
        val padded = ByteArray(CodecConfigScanner.MAX_BYTES_SCANNED * 2)
        System.arraycopy(head, 0, padded, 0, head.size)
        for (i in head.size until padded.size) padded[i] = 0x5A
        assertEquals(
            Content.PARAMETER_SETS_WITH_PICTURE,
            CodecConfigScanner.classify(padded, 0, padded.size, isHevc = false)
        )
    }

    @Test
    fun `a real parameter-set unit finishes well inside the bound`() {
        // Which is why bounding the walk cannot cost the config flag on the units that need it: SPS
        // and PPS together are tens of bytes.
        val sets = unit(avcSps, avcPps)
        org.junit.Assert.assertTrue(
            "a parameter-set unit is ${sets.size} bytes, the bound is ${CodecConfigScanner.MAX_BYTES_SCANNED}",
            sets.size < CodecConfigScanner.MAX_BYTES_SCANNED
        )
        assertEquals(Content.PARAMETER_SETS_ONLY, classifyAvc(sets))
    }

    @Test
    fun `a picture found after the parameter sets settles the answer immediately`() {
        // Sixteen headers is the bound, so a unit with more NALs than that still has to come out right
        // as long as the picture is not beyond it - which it never is, since slices follow the sets.
        val headers = IntArray(20) { if (it == 0) avcSps else if (it == 1) avcPps else avcIdr }
        assertEquals(Content.PARAMETER_SETS_WITH_PICTURE, classifyAvc(unit(*headers)))
    }
}
