package com.andrerinas.openheadunit.decoder.video

import com.andrerinas.openheadunit.decoder.video.VideoDecoder.CodecType
import com.andrerinas.openheadunit.decoder.video.preflight.AnnexBAccessUnits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class CodecTypeSelectionPolicyTest {

    // The parser's baseline AVC and Main HEVC vectors; the HEVC SPS contains escaped zero runs.
    private val avcSps = bytes("67 42 80 1F F4 02 80 2D C8")
    private val hevcSps = bytes("42 01 01 01 00 00 03 00 00 80 00 00 03 00 00 03 00 5D A0 02 80 80 2D 16 59 38")

    @Test
    fun `parsed SPS overrides the opposite announcement in either direction`() {
        for (startCodeLength in 3..4) {
            assertSelection(nal(avcSps, startCodeLength), CodecType.H265, CodecType.H264)
            assertSelection(nal(hevcSps, startCodeLength), CodecType.H264, CodecType.H265)
        }
    }

    @Test
    fun `agreeing SPS and announcement keep the negotiated codec`() {
        assertSelection(nal(avcSps), CodecType.H264, CodecType.H264)
        assertSelection(nal(hevcSps), CodecType.H265, CodecType.H265)
    }

    @Test
    fun `slice and PPS header overlaps cannot switch the announced codec`() {
        for (payload in listOf(
            "41 01 80", // AVC non-IDR, misread as HEVC VPS by the old single-byte heuristic.
            "65 01 80", // AVC IDR; no parameter-set evidence.
            "44 01 C0", // HEVC PPS; must not be treated as AVC slice evidence either.
            "28 01 80", // HEVC IDR_N_LP, misread as AVC PPS by the old heuristic.
            "68 CE 06 E2", // AVC PPS alone is not sufficient evidence.
            "40 01 0C 01 FF FF", // VPS header/reserved bits without profile-tier-level are insufficient.
            "09 F0", // AVC access-unit delimiter.
            "4E 01 80", // HEVC prefix SEI.
        )) {
            assertNoEvidence(nal(bytes(payload)))
        }
    }

    @Test
    fun `ambiguous slices before real SPS do not hijack the first packet`() {
        val avc = nal(bytes("41 01 80"), 3) + nal(bytes("65 01 80")) + nal(avcSps, 3)
        val hevc = nal(bytes("28 01 80")) + nal(bytes("44 01 C0"), 3) + nal(hevcSps)
        assertSelection(avc, CodecType.H265, CodecType.H264)
        assertSelection(hevc, CodecType.H264, CodecType.H265)
    }

    @Test
    fun `conflicting parsed SPS evidence retains either session announcement`() {
        assertNoEvidence(nal(avcSps) + nal(hevcSps, 3))
        assertNoEvidence(nal(hevcSps, 3) + nal(avcSps))
    }

    @Test
    fun `repeated SPS from the same codec is not conflicting evidence`() {
        assertSelection(nal(avcSps) + nal(avcSps, 3), CodecType.H265, CodecType.H264)
        assertSelection(nal(hevcSps, 3) + nal(hevcSps), CodecType.H264, CodecType.H265)
    }

    @Test
    fun `truncated SPS never borrows bytes from the following NAL`() {
        for (payload in listOf(avcSps.copyOf(6), hevcSps.copyOf(15))) {
            assertNoEvidence(nal(payload))
            assertNoEvidence(nal(payload) + nal(bytes("65 FF FF FF FF FF FF FF FF FF FF FF"), 3))
        }
        for (payload in listOf("", "67", "42", "42 01", "67 42 80", "42 01 01")) {
            assertNoEvidence(nal(bytes(payload)))
        }
    }

    @Test
    fun `malformed SPS headers and bodies are not strong evidence`() {
        val malformed = listOf(
            avcSps.copyOf().apply { this[0] = 0xE7.toByte() }, // forbidden_zero_bit
            avcSps.copyOf().apply { this[0] = 0x07 }, // nal_ref_idc must not be zero
            avcSps.copyOf().apply { this[1] = 0x00 }, // unsupported profile
            avcSps.copyOf().apply { this[2] = 0x83.toByte() }, // reserved bits
            hevcSps.copyOf().apply { this[0] = 0xC2.toByte() }, // forbidden_zero_bit
            hevcSps.copyOf().apply { this[1] = 0x00 }, // zero temporal_id_plus1
            hevcSps.copyOf().apply { this[1] = 0x02 }, // SPS TemporalId must be zero
            hevcSps.copyOf().apply { this[2] = 0x0F }, // invalid sub-layer count
            bytes("67 42 80 1F 00 00 00 00"),
            bytes("42 01 00 00 00 00 00 00"),
        )
        for (payload in malformed) assertNoEvidence(nal(payload))
    }

    @Test
    fun `a malformed opposite SPS does not hide valid evidence`() {
        assertSelection(nal(bytes("42 01")) + nal(avcSps), CodecType.H265, CodecType.H264)
        assertSelection(nal(bytes("67 42")) + nal(hevcSps), CodecType.H264, CodecType.H265)
    }

    @Test
    fun `only complete start codes and SPS inside the specified window count`() {
        val prefix = nal(hevcSps)
        val selected = nal(avcSps, 3)
        val data = prefix + selected + nal(hevcSps)
        assertEquals(CodecType.H264, CodecTypeSelectionPolicy.detect(data, prefix.size, selected.size))
        assertNull(CodecTypeSelectionPolicy.detect(data, prefix.size, selected.size - 3))
        assertNull(CodecTypeSelectionPolicy.detect(data, prefix.size + 1, selected.size - 1))
        assertNull(CodecTypeSelectionPolicy.detect(data, prefix.size, 2))
        // A start code in the backing array cannot complete a truncated header within the window.
        assertNull(CodecTypeSelectionPolicy.detect(data, prefix.size, 3))
    }

    @Test
    fun `invalid and empty windows have no evidence including overflowing sizes`() {
        val data = nal(avcSps)
        for ((offset, size) in listOf(
            -1 to data.size,
            0 to -1,
            0 to 0,
            0 to data.size + 1,
            1 to Int.MAX_VALUE,
            Int.MAX_VALUE to 1,
            data.size to 0,
            data.size to 1,
        )) {
            assertNull("offset=$offset size=$size", CodecTypeSelectionPolicy.detect(data, offset, size))
        }
        assertNoEvidence(byteArrayOf())
    }

    @Test
    fun `raw NAL data without Annex B framing cannot override the announcement`() {
        assertNoEvidence(avcSps)
        assertNoEvidence(hevcSps)
        assertNoEvidence(bytes("00 00 00 00 00 01"))
    }

    @Test
    fun `shipped AVC and HEVC configuration packets select their real codecs`() {
        for ((name, codec) in listOf(
            "preflight_h264_480p.h264" to CodecType.H264,
            "preflight_h264_720p.h264" to CodecType.H264,
            "preflight_h264_1080p.h264" to CodecType.H264,
            "preflight_h265_1080p.h265" to CodecType.H265,
        )) {
            val data = File("src/main/res/raw/$name").readBytes()
            val first = AnnexBAccessUnits.split(data, codec).first()
            val prefix = AnnexBAccessUnits.parameterSetPrefix(data, first, codec)
            val detected = CodecTypeSelectionPolicy.detect(data, first.offset, prefix)
            val opposite = if (codec == CodecType.H264) CodecType.H265 else CodecType.H264
            assertEquals(name, codec, detected)
            assertEquals(name, codec, CodecTypeSelectionPolicy.select(detected, opposite))
        }
    }

    @Test
    fun `a separately delivered shipped HEVC VPS selects HEVC before its SPS arrives`() {
        val vps = shippedHevcVps()
        for (startCodeLength in 3..4) {
            assertSelection(nal(vps, startCodeLength), CodecType.H264, CodecType.H265)
        }
        // A valid-looking body cannot turn AVC's 0x41 non-IDR header into an HEVC VPS.
        assertNoEvidence(nal(vps.copyOf().apply { this[0] = 0x41 }))
    }

    @Test
    fun `a VPS needs valid base layer fields and complete profile tier level evidence`() {
        val vps = shippedHevcVps()
        // Two NAL header bytes and sixteen RBSP bytes are needed even before ordering syntax.
        // Emulation-prevention bytes only increase the encoded length.
        for (length in 0..18) assertNoEvidence(nal(vps.copyOf(length)))
        for ((index, value) in listOf(
            0 to 0xC0, // forbidden_zero_bit
            1 to 0x00, // zero temporal_id_plus1
            1 to 0x09, // non-base-layer nuh_layer_id
            2 to 0x00, // missing internal/available base-layer flags
            3 to 0x00, // single sub-layer requires temporal nesting
            3 to 0x0F, // max_sub_layers_minus1 may not be seven
            4 to 0xFE, // reserved_0xffff_16bits
            5 to 0xFE,
            6 to 0x00, // unspecified profile is not strong evidence
        )) {
            assertNoEvidence(nal(vps.copyOf().apply { this[index] = value.toByte() }))
        }
        val escape = (2 until vps.size - 1).first {
            vps[it - 2] == 0.toByte() && vps[it - 1] == 0.toByte() && vps[it] == 3.toByte()
        }
        assertNoEvidence(nal(vps.copyOf().apply { this[escape + 1] = 4 }))
        assertNoEvidence(nal(vps.copyOf(10)) + nal(bytes("65 FF FF FF FF FF FF FF FF FF")))
    }

    @Test
    fun `VPS evidence respects the packet window and conflicting AVC evidence`() {
        val vps = nal(shippedHevcVps())
        val avc = nal(avcSps)
        val data = avc + vps + avc
        assertEquals(CodecType.H265, CodecTypeSelectionPolicy.detect(data, avc.size, vps.size))
        assertNull(CodecTypeSelectionPolicy.detect(data, avc.size, 10))
        assertNoEvidence(vps + avc)
        assertNoEvidence(avc + vps)
        assertSelection(vps + nal(hevcSps), CodecType.H264, CodecType.H265)
    }

    private fun shippedHevcVps(): ByteArray {
        val data = File("src/main/res/raw/preflight_h265_1080p.h265").readBytes()
        val header = if (data[2] == 1.toByte()) 3 else 4
        assertEquals(0x40, data[header].toInt() and 0xFF)
        assertEquals(0x01, data[header + 1].toInt() and 0xFF)
        val end = (header + 2 until data.size - 3).first {
            data[it] == 0.toByte() && data[it + 1] == 0.toByte() &&
                (data[it + 2] == 1.toByte() ||
                    (data[it + 2] == 0.toByte() && data[it + 3] == 1.toByte()))
        }
        return data.copyOfRange(header, end)
    }

    private fun assertSelection(data: ByteArray, announced: CodecType, expected: CodecType) {
        val detected = CodecTypeSelectionPolicy.detect(data, 0, data.size)
        assertEquals(expected, detected)
        assertEquals(expected, CodecTypeSelectionPolicy.select(detected, announced))
    }

    private fun assertNoEvidence(data: ByteArray) {
        val detected = CodecTypeSelectionPolicy.detect(data, 0, data.size)
        assertNull(detected)
        for (announced in listOf(CodecType.H264, CodecType.H265)) {
            assertEquals(announced, CodecTypeSelectionPolicy.select(detected, announced))
        }
    }

    private fun nal(payload: ByteArray, startCodeLength: Int = 4): ByteArray =
        (if (startCodeLength == 3) byteArrayOf(0, 0, 1) else byteArrayOf(0, 0, 0, 1)) + payload

    private fun bytes(hex: String): ByteArray =
        if (hex.isEmpty()) byteArrayOf() else hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()
}
