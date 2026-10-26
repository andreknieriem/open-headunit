package com.andrerinas.openheadunit.aap.protocol.messages

import com.andrerinas.openheadunit.aap.protocol.proto.Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType as Resolution
import com.andrerinas.openheadunit.aap.protocol.proto.Media.MediaCodecType
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoCodecNegotiationPolicyTest {
    private val h264 = MediaCodecType.MEDIA_CODEC_VIDEO_H264_BP
    private val h265 = MediaCodecType.MEDIA_CODEC_VIDEO_H265

    private fun select(
        preference: String,
        resolution: Resolution = Resolution._1920x1080,
        forceSoftware: Boolean = false,
        softwareAvailable: Boolean = false,
        supported: Boolean = false,
        reliable: Boolean = false
    ) = VideoCodecNegotiationPolicy.select(
        preference, resolution, forceSoftware, softwareAvailable, supported, reliable
    )

    @Test
    fun `forced software H264 does not advertise HEVC merely because FFmpeg supports it`() {
        for (resolution in listOf(Resolution._1920x1080, Resolution._2560x1440,
                Resolution._1440x2560, Resolution._3840x2160)) {
            assertEquals(h264, select("H.264", resolution, forceSoftware = true, softwareAvailable = true))
        }
    }

    @Test
    fun `explicit forced software H265 accepts an available selected decoder without hardware HEVC`() {
        // The selected capability is supplied by FFmpeg or Device MediaCodec in ServiceDiscovery.
        for (resolution in listOf(Resolution._1920x1080, Resolution._2560x1440,
                Resolution._1440x2560, Resolution._3840x2160)) {
            assertEquals(h265, select("H.265", resolution, forceSoftware = true, softwareAvailable = true))
        }
    }

    @Test
    fun `unavailable HEVC downgrades explicit H265 even when software was requested`() {
        for (forceSoftware in listOf(false, true)) {
            for (resolution in listOf(Resolution._1920x1080, Resolution._2560x1440,
                    Resolution._1440x2560, Resolution._3840x2160)) {
                assertEquals(h264, select("H.265", resolution, forceSoftware = forceSoftware))
            }
        }
    }

    @Test
    fun `software availability is not an implicit opt in to software HEVC`() {
        assertEquals(h264, select("H.265", softwareAvailable = true))
        assertEquals(h264, select("Auto", Resolution._2560x1440, forceSoftware = true, softwareAvailable = true))
        assertEquals(h264, select("Auto", Resolution._3840x2160, forceSoftware = true, softwareAvailable = true))
    }

    @Test
    fun `explicit H265 permits supported hardware even when it is not reliable for Auto`() {
        assertEquals(h265, select("H.265", supported = true))
        assertEquals(h265, select("H.265", Resolution._2560x1440, supported = true))
        assertEquals(h264, select("Auto", Resolution._3840x2160, supported = true))
    }

    @Test
    fun `Auto keeps lower resolutions on H264 even with reliable hardware HEVC`() {
        for (resolution in listOf(Resolution._1280x720, Resolution._1920x1080, Resolution._1080x1920)) {
            assertEquals(h264, select("Auto", resolution, supported = true, reliable = true))
        }
    }

    @Test
    fun `Auto uses HEVC for landscape 4K only with reliable hardware`() {
        assertEquals(h265, select("Auto", Resolution._3840x2160, supported = true, reliable = true))
        assertEquals(h264, select("Auto", Resolution._3840x2160, supported = true))
        // Preserve the current landscape-only 4K rule rather than silently broadening negotiation.
        assertEquals(h264, select("Auto", Resolution._2160x3840, supported = true, reliable = true))
    }

    @Test
    fun `both 1440p orientations enforce reliable HEVC including an H264 preference`() {
        for (resolution in listOf(Resolution._2560x1440, Resolution._1440x2560)) {
            for (preference in listOf("Auto", "H.264", "H.265")) {
                assertEquals(h265, select(preference, resolution, supported = true, reliable = true))
            }
            assertEquals(h264, select("Auto", resolution, supported = true))
            assertEquals(h264, select("H.264", resolution, supported = true))
        }
    }

    @Test
    fun `H264 at 4K is not changed into Auto by hardware capability`() {
        assertEquals(h264, select("H.264", Resolution._3840x2160, supported = true, reliable = true))
    }

    @Test
    fun `unrecognized preference retains the H264 default`() {
        assertEquals(h264, select("unknown", supported = true, reliable = true))
    }
}
