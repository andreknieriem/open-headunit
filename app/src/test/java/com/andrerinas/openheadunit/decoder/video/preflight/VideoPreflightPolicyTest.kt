package com.andrerinas.openheadunit.decoder.video.preflight

import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoPreflightPolicyTest {

    private val h264 = VideoDecoder.CodecType.H264
    private val h265 = VideoDecoder.CodecType.H265

    @Test
    fun `a full h264 pass keeps 1080p and reports it as verified`() {
        val result = recommend(
            panel = Settings.Resolution._1920x1080,
            staticResolution = Settings.Resolution._1920x1080,
            backends = listOf(
                backend(
                    Settings.ViewMode.TEXTURE,
                    pass(VideoPreflightClip.H264_480P),
                    pass(VideoPreflightClip.H264_720P),
                    pass(VideoPreflightClip.H264_1080P),
                ),
            ),
        )

        assertEquals(h264, result.codec)
        assertEquals(Settings.Resolution._1920x1080, result.resolution)
        assertEquals(Settings.ViewMode.TEXTURE, result.viewMode)
        assertTrue(result.verified)
        assertTrue(result.notes.isEmpty())
    }

    @Test
    fun `h265 above full hd keeps the panel ceiling and says it was not directly tested`() {
        val result = recommend(
            panel = Settings.Resolution._2560x1440,
            staticCodec = h265,
            staticResolution = Settings.Resolution._2560x1440,
            backends = listOf(
                backend(
                    Settings.ViewMode.TEXTURE,
                    pass(VideoPreflightClip.H264_480P),
                    pass(VideoPreflightClip.H264_720P),
                    pass(VideoPreflightClip.H264_1080P),
                    pass(VideoPreflightClip.H265_1080P),
                ),
            ),
        )

        assertEquals(h265, result.codec)
        assertEquals(Settings.Resolution._2560x1440, result.resolution)
        assertTrue(VideoPreflightNote.HIGH_RES_UNVERIFIED in result.notes)
        assertTrue(result.verified)
    }

    @Test
    fun `a failed h265 above full hd drops to 1080p h264`() {
        val result = recommend(
            panel = Settings.Resolution._2560x1440,
            staticCodec = h265,
            staticResolution = Settings.Resolution._2560x1440,
            backends = listOf(
                backend(
                    Settings.ViewMode.TEXTURE,
                    pass(VideoPreflightClip.H264_480P),
                    pass(VideoPreflightClip.H264_720P),
                    pass(VideoPreflightClip.H264_1080P),
                    fail(VideoPreflightClip.H265_1080P),
                ),
            ),
        )

        assertEquals(h264, result.codec)
        assertEquals(Settings.Resolution._1920x1080, result.resolution)
        assertTrue(VideoPreflightNote.H265_FAILED in result.notes)
        assertTrue(VideoPreflightNote.RESOLUTION_CAPPED in result.notes)
    }

    @Test
    fun `a failed 1080p caps the resolution at 720p`() {
        val result = recommend(
            panel = Settings.Resolution._1920x1080,
            staticResolution = Settings.Resolution._1920x1080,
            backends = listOf(
                backend(
                    Settings.ViewMode.TEXTURE,
                    pass(VideoPreflightClip.H264_480P),
                    pass(VideoPreflightClip.H264_720P),
                    fail(VideoPreflightClip.H264_1080P, VideoPreflightFailure.FEW_FRAMES),
                ),
            ),
        )

        assertEquals(Settings.Resolution._1280x720, result.resolution)
        assertTrue(VideoPreflightNote.RESOLUTION_CAPPED in result.notes)
    }

    @Test
    fun `a backend that draws nothing is replaced by one that did`() {
        val result = recommend(
            panel = Settings.Resolution._1920x1080,
            staticResolution = Settings.Resolution._1920x1080,
            backends = listOf(
                backend(
                    Settings.ViewMode.TEXTURE,
                    fail(VideoPreflightClip.H264_480P, VideoPreflightFailure.NO_DRAWN_FRAMES),
                ),
                backend(
                    Settings.ViewMode.GLES,
                    pass(VideoPreflightClip.H264_480P),
                    pass(VideoPreflightClip.H264_720P),
                    pass(VideoPreflightClip.H264_1080P),
                ),
            ),
        )

        assertEquals(Settings.ViewMode.GLES, result.viewMode)
        assertEquals(Settings.Resolution._1920x1080, result.resolution)
        assertTrue(VideoPreflightNote.RENDERER_CHANGED in result.notes)
    }

    @Test
    fun `nothing that passed leaves the stored settings alone`() {
        val result = recommend(
            panel = Settings.Resolution._1920x1080,
            staticResolution = Settings.Resolution._1920x1080,
            backends = listOf(
                backend(
                    Settings.ViewMode.TEXTURE,
                    fail(VideoPreflightClip.H264_480P, VideoPreflightFailure.DECODER_ERROR),
                ),
            ),
        )

        assertEquals(h264, result.codec)
        assertEquals(Settings.Resolution._1920x1080, result.resolution)
        assertEquals(Settings.ViewMode.TEXTURE, result.viewMode)
        assertFalse(result.verified)
        assertTrue(VideoPreflightNote.NOT_TESTED in result.notes)
    }

    @Test
    fun `a pass without a readable picture is still useful but not verified`() {
        val result = recommend(
            panel = Settings.Resolution._1920x1080,
            staticResolution = Settings.Resolution._1920x1080,
            backends = listOf(
                backend(
                    Settings.ViewMode.SURFACE,
                    pass(VideoPreflightClip.H264_480P, samples = emptyList()),
                    pass(VideoPreflightClip.H264_720P, samples = emptyList()),
                    pass(VideoPreflightClip.H264_1080P, samples = emptyList()),
                ),
            ),
        )

        assertEquals(Settings.Resolution._1920x1080, result.resolution)
        assertFalse(result.verified)
        assertTrue(VideoPreflightNote.PICTURE_UNVERIFIED in result.notes)
    }

    @Test
    fun `a stored h265 that works on a full hd panel still recommends h264`() {
        val result = recommend(
            panel = Settings.Resolution._1920x1080,
            staticResolution = Settings.Resolution._1920x1080,
            backends = listOf(
                backend(
                    Settings.ViewMode.TEXTURE,
                    pass(VideoPreflightClip.H264_480P),
                    pass(VideoPreflightClip.H264_720P),
                    pass(VideoPreflightClip.H264_1080P),
                    pass(VideoPreflightClip.H265_1080P),
                ),
            ),
        )

        assertEquals(h264, result.codec)
        assertEquals(Settings.Resolution._1920x1080, result.resolution)
        assertFalse(VideoPreflightNote.H265_FAILED in result.notes)
    }

    @Test
    fun `a stored h265 that fails is called out and replaced`() {
        val result = recommend(
            panel = Settings.Resolution._1920x1080,
            staticResolution = Settings.Resolution._1920x1080,
            backends = listOf(
                backend(
                    Settings.ViewMode.TEXTURE,
                    pass(VideoPreflightClip.H264_480P),
                    pass(VideoPreflightClip.H264_720P),
                    pass(VideoPreflightClip.H264_1080P),
                    fail(VideoPreflightClip.H265_1080P),
                ),
            ),
        )

        assertEquals(h264, result.codec)
        assertEquals(Settings.Resolution._1920x1080, result.resolution)
        assertTrue(VideoPreflightNote.H265_FAILED in result.notes)
    }

    @Test
    fun `forced software keeps the stored codec when h265 was not tested`() {
        val result = VideoPreflightPolicy.recommend(
            staticCodec = h265,
            staticResolution = Settings.Resolution._2560x1440,
            staticViewMode = Settings.ViewMode.TEXTURE,
            panelCeiling = Settings.Resolution._2560x1440,
            hevcSupported = true,
            backends = listOf(
                backend(
                    Settings.ViewMode.TEXTURE,
                    pass(VideoPreflightClip.H264_480P),
                    pass(VideoPreflightClip.H264_720P),
                    pass(VideoPreflightClip.H264_1080P),
                ),
            ),
            allowCodecChange = false,
        )

        assertEquals(h265, result.codec)
        assertEquals(Settings.Resolution._2560x1440, result.resolution)
        assertFalse(VideoPreflightNote.H265_FAILED in result.notes)
        assertFalse(VideoPreflightNote.RESOLUTION_CAPPED in result.notes)
        assertTrue(result.verified)
    }

    @Test
    fun `forced session HEVC failure is reported without changing software recommendations`() {
        for (preference in listOf("H.264", "Auto")) {
            for (softwareAvailable in listOf(false, true)) {
                val panel = Settings.Resolution._2560x1440
                val clips = VideoPreflightClips.forPanel(
                    panelCeiling = panel,
                    configuredResolution = panel.codec!!,
                    hardwareHevcSupported = true,
                    hardwareHevcReliable = true,
                    storedCodec = preference,
                    forceSoftware = true,
                    selectedSoftwareHevcAvailable = softwareAvailable,
                )
                val outcomes = clips.map { clip ->
                    if (clip.codec == h265) fail(clip, VideoPreflightFailure.DECODER_ERROR)
                    else pass(clip)
                }
                val result = VideoPreflightPolicy.recommend(
                    staticCodec = h264,
                    staticResolution = panel,
                    staticViewMode = Settings.ViewMode.TEXTURE,
                    panelCeiling = panel,
                    hevcSupported = true,
                    backends = listOf(VideoPreflightBackendOutcome(Settings.ViewMode.TEXTURE, outcomes)),
                    allowCodecChange = false,
                )

                assertTrue("$preference/available=$softwareAvailable", VideoPreflightNote.H265_FAILED in result.notes)
                assertEquals(h264, result.codec)
                assertEquals(panel, result.resolution)
                assertFalse(VideoPreflightNote.RESOLUTION_CAPPED in result.notes)
            }
        }
    }

    private fun recommend(
        panel: Settings.Resolution,
        staticResolution: Settings.Resolution,
        backends: List<VideoPreflightBackendOutcome>,
        staticCodec: VideoDecoder.CodecType = h264,
    ): VideoPreflightRecommendation = VideoPreflightPolicy.recommend(
        staticCodec = staticCodec,
        staticResolution = staticResolution,
        staticViewMode = Settings.ViewMode.TEXTURE,
        panelCeiling = panel,
        hevcSupported = true,
        backends = backends,
    )

    private fun backend(
        viewMode: Settings.ViewMode,
        vararg clips: VideoPreflightClipOutcome,
    ): VideoPreflightBackendOutcome = VideoPreflightBackendOutcome(viewMode, clips.toList())

    private fun pass(
        clip: VideoPreflightClip,
        samples: List<Int> = listOf(0, 75, 149),
    ): VideoPreflightClipOutcome = VideoPreflightClipOutcome(
        clip = clip,
        fedFrames = clip.frameCount,
        renderedFrames = clip.frameCount.toLong(),
        drawnFrames = clip.frameCount.toLong(),
        samples = samples,
        badSamples = 0,
        failure = VideoPreflightFailure.NONE,
    )

    private fun fail(
        clip: VideoPreflightClip,
        failure: VideoPreflightFailure = VideoPreflightFailure.BAD_MARKER,
    ): VideoPreflightClipOutcome = VideoPreflightClipOutcome(
        clip = clip,
        fedFrames = clip.frameCount,
        renderedFrames = 100,
        drawnFrames = 100,
        samples = emptyList(),
        badSamples = 1,
        failure = failure,
    )
}
