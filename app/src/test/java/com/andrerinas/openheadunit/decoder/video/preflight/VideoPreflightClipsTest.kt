package com.andrerinas.openheadunit.decoder.video.preflight

import com.andrerinas.openheadunit.aap.protocol.proto.Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType as Resolution
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPreflightClipsTest {
    private val avcLadder = listOf(
        VideoPreflightClip.H264_480P,
        VideoPreflightClip.H264_720P,
        VideoPreflightClip.H264_1080P,
    )

    private fun clips(
        panel: Settings.Resolution = Settings.Resolution._1920x1080,
        configured: Resolution = Resolution._1920x1080,
        preference: String = "Auto",
        forceSoftware: Boolean = false,
        softwareAvailable: Boolean = false,
        hardwareSupported: Boolean = false,
        hardwareReliable: Boolean = false,
    ) = VideoPreflightClips.forPanel(
        panelCeiling = panel,
        configuredResolution = configured,
        hardwareHevcSupported = hardwareSupported,
        hardwareHevcReliable = hardwareReliable,
        storedCodec = preference,
        forceSoftware = forceSoftware,
        selectedSoftwareHevcAvailable = softwareAvailable,
    )

    @Test
    fun `a 1080p panel tests the three h264 sizes even when current resolution is lower`() {
        assertEquals(
            avcLadder,
            clips(configured = Resolution._800x480, hardwareSupported = true, hardwareReliable = true),
        )
    }

    @Test
    fun `a 480p panel only tests 480p for an AVC session`() {
        assertEquals(
            listOf(VideoPreflightClip.H264_480P),
            clips(
                panel = Settings.Resolution._800x480,
                configured = Resolution._800x480,
                hardwareSupported = true,
                hardwareReliable = true,
            ),
        )
    }

    @Test
    fun `reliable 1440p HEVC is tested regardless of stored codec or software availability`() {
        for (configured in listOf(Resolution._2560x1440, Resolution._1440x2560)) {
            for (preference in listOf("H.264", "Auto", "H.265")) {
                for (forceSoftware in listOf(false, true)) {
                    for (softwareAvailable in listOf(false, true)) {
                        val actual = clips(
                            panel = Settings.Resolution._2560x1440,
                            configured = configured,
                            preference = preference,
                            forceSoftware = forceSoftware,
                            softwareAvailable = softwareAvailable,
                            hardwareSupported = true,
                            hardwareReliable = true,
                        )
                        // An unavailable software backend must fail the real HEVC clip, not omit it.
                        assertEquals(
                            "$configured/$preference/software=$forceSoftware/available=$softwareAvailable",
                            avcLadder + VideoPreflightClip.H265_1080P,
                            actual,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `software capability only opts into HEVC for explicit forced software H265`() {
        for (preference in listOf("H.264", "Auto", "H.265")) {
            for (forceSoftware in listOf(false, true)) {
                for (softwareAvailable in listOf(false, true)) {
                    val actual = clips(
                        panel = Settings.Resolution._2560x1440,
                        configured = Resolution._2560x1440,
                        preference = preference,
                        forceSoftware = forceSoftware,
                        softwareAvailable = softwareAvailable,
                    )
                    assertEquals(
                        "$preference/software=$forceSoftware/available=$softwareAvailable",
                        preference == "H.265" && forceSoftware && softwareAvailable,
                        VideoPreflightClip.H265_1080P in actual,
                    )
                    assertEquals(avcLadder, actual.filter { it.codec == VideoPreflightClip.H264_480P.codec })
                }
            }
        }
    }

    @Test
    fun `explicit hardware HEVC is still tested when software is forced but unavailable`() {
        for (preference in listOf("H.264", "Auto", "H.265")) {
            for (forceSoftware in listOf(false, true)) {
                for (softwareAvailable in listOf(false, true)) {
                    val actual = clips(
                        preference = preference,
                        forceSoftware = forceSoftware,
                        softwareAvailable = softwareAvailable,
                        hardwareSupported = true,
                        hardwareReliable = false,
                    )
                    assertEquals(
                        "$preference/software=$forceSoftware/available=$softwareAvailable",
                        preference == "H.265",
                        VideoPreflightClip.H265_1080P in actual,
                    )
                }
            }
        }
    }

    @Test
    fun `configured 1440p is not replaced by a 4K panel ceiling`() {
        for (configured in listOf(Resolution._2560x1440, Resolution._1440x2560)) {
            val actual = clips(
                panel = Settings.Resolution._3840x2160,
                configured = configured,
                preference = "H.264",
                forceSoftware = true,
                softwareAvailable = true,
                hardwareSupported = true,
                hardwareReliable = true,
            )
            // H264 at 4K stays AVC, but this session's 1440p selection forces HEVC.
            assertEquals(avcLadder + VideoPreflightClip.H265_1080P, actual)
        }
        assertEquals(
            avcLadder,
            clips(
                panel = Settings.Resolution._3840x2160,
                configured = Resolution._3840x2160,
                preference = "H.264",
                forceSoftware = true,
                softwareAvailable = true,
                hardwareSupported = true,
                hardwareReliable = true,
            ),
        )
    }

    @Test
    fun `configured landscape 4K Auto tests HEVC even with a lower panel ceiling`() {
        for (configured in listOf(Resolution._3840x2160, Resolution._2160x3840)) {
            for (preference in listOf("H.264", "Auto", "H.265")) {
                val actual = clips(
                    configured = configured,
                    preference = preference,
                    forceSoftware = true,
                    softwareAvailable = false,
                    hardwareSupported = true,
                    hardwareReliable = true,
                )
                assertEquals(
                    "$configured/$preference",
                    preference == "H.265" || (preference == "Auto" && configured == Resolution._3840x2160),
                    VideoPreflightClip.H265_1080P in actual,
                )
            }
        }
    }

    @Test
    fun `a lower current resolution does not skip the software high panel progression`() {
        val actual = clips(
            panel = Settings.Resolution._2560x1440,
            configured = Resolution._800x480,
            preference = "H.264",
            forceSoftware = true,
            softwareAvailable = true,
            hardwareSupported = true,
            hardwareReliable = true,
        )
        assertEquals(avcLadder + VideoPreflightClip.H265_1080P, actual)
    }

    @Test
    fun `high panel still explores an explicit hardware HEVC upgrade without reliable Auto support`() {
        for (preference in listOf("H.264", "Auto")) {
            val actual = clips(
                panel = Settings.Resolution._2560x1440,
                configured = Resolution._800x480,
                preference = preference,
                hardwareSupported = true,
                hardwareReliable = false,
            )
            assertTrue(VideoPreflightClip.H265_1080P in actual)
        }
    }

    @Test
    fun `unreliable hardware does not force software AVC or Auto into HEVC`() {
        for (preference in listOf("H.264", "Auto")) {
            for (softwareAvailable in listOf(false, true)) {
                val actual = clips(
                    panel = Settings.Resolution._2560x1440,
                    configured = Resolution._2560x1440,
                    preference = preference,
                    forceSoftware = true,
                    softwareAvailable = softwareAvailable,
                    hardwareSupported = true,
                    hardwareReliable = false,
                )
                assertFalse(VideoPreflightClip.H265_1080P in actual)
            }
        }
    }
}
