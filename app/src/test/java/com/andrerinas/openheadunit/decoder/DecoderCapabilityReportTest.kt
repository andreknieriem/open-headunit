package com.andrerinas.openheadunit.decoder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the two halves of [DecoderCapabilityReport] that need no device: the verdict and the name
 * heuristic. The queries themselves need `MediaCodecList` and are exercised on hardware.
 */
class DecoderCapabilityReportTest {

    private fun capability(
        sizeSupported: Boolean = true,
        rateSupported: Boolean = true,
        sustains: Boolean? = true,
    ) = DecoderCapabilityReport.Capability(
        codecName = "c2.qti.hevc.decoder",
        mimeType = "video/hevc",
        width = 2560,
        height = 1440,
        fps = 60,
        sizeSupported = sizeSupported,
        rateSupported = rateSupported,
        sustains = sustains,
        lowLatency = true,
        adaptivePlayback = true,
        supportedWidths = "[64, 4096]",
        supportedHeights = "[64, 4096]",
    )

    @Test
    fun `a decoder that claims all three is adequate`() {
        assertTrue(capability().adequate)
    }

    @Test
    fun `an unknown performance point does not count against the decoder`() {
        // Below API 29 there are no performance points at all. Treating "we could not ask" as "no"
        // would flag every pre-Android-10 unit that is working perfectly well.
        assertTrue(capability(sustains = null).adequate)
    }

    @Test
    fun `a performance point that says no is decisive`() {
        // The case worth catching: the component accepts the format but does not claim to sustain
        // the rate, which is exactly the gap areSizeAndRateSupported cannot express.
        assertFalse(capability(sustains = false).adequate)
    }

    @Test
    fun `size or rate failing is enough on its own`() {
        assertFalse(capability(sizeSupported = false).adequate)
        assertFalse(capability(rateSupported = false).adequate)
    }

    @Test
    fun `the summary carries every number a report needs`() {
        val text = capability(sustains = false).toString()
        listOf(
            "codec=c2.qti.hevc.decoder",
            "mime=video/hevc",
            "target=2560x1440@60",
            "sizeSupported=true",
            "rateSupported=true",
            "sustains=false",
            "featureLowLatency=true",
        ).forEach { assertTrue("missing '$it' in: $text", text.contains(it)) }
    }

    @Test
    fun `an unknown performance point prints as unknown, not null`() {
        assertTrue(capability(sustains = null).toString().contains("sustains=unknown"))
    }

    @Test
    fun `software components are recognised by the same names VideoDecoder uses`() {
        listOf(
            "OMX.google.hevc.decoder",
            "c2.android.hevc.decoder",
            "OMX.ffmpeg.hevc.decoder",
            "OMX.qcom.video.decoder.sw.hevc",
            "some.software.decoder",
        ).forEach { assertTrue(it, DecoderCapabilityReport.isSoftwareName(it)) }
    }

    @Test
    fun `hardware components are not`() {
        listOf(
            "c2.qti.hevc.decoder",
            "OMX.MTK.VIDEO.DECODER.HEVC",
            "OMX.rk.video_decoder.hevc",
        ).forEach { assertFalse(it, DecoderCapabilityReport.isSoftwareName(it)) }
    }

    @Test
    fun `a missing fps setting falls back to a named default rather than zero`() {
        assertEquals(30, DecoderCapabilityReport.DEFAULT_FPS)
    }
}
