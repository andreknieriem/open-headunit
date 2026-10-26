package com.andrerinas.openheadunit.decoder.video.preflight

import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.utils.Settings

/** Why a clip did not pass. The log carries the detail; this is what the wizard can act on. */
enum class VideoPreflightFailure {
    NONE,
    NO_SURFACE,
    DECODER_ERROR,
    NO_FIRST_FRAME,
    FEW_FRAMES,
    NO_DRAWN_FRAMES,
    BAD_MARKER,
    UNREADABLE,
}

/** What happened to one clip on one rendering backend. */
data class VideoPreflightClipOutcome(
    val clip: VideoPreflightClip,
    val fedFrames: Int,
    val renderedFrames: Long,
    val drawnFrames: Long,
    val samples: List<Int>,
    val badSamples: Int,
    val failure: VideoPreflightFailure,
    val detail: String? = null,
) {
    val passed: Boolean get() = failure == VideoPreflightFailure.NONE
}

/** All the clips played on one backend, in the order they were played. */
data class VideoPreflightBackendOutcome(
    val viewMode: Settings.ViewMode,
    val clips: List<VideoPreflightClipOutcome>,
)

enum class VideoPreflightNote {
    /** The H.265 clip was played and did not come through, so H.264 is the answer. */
    H265_FAILED,

    /** The highest resolution this unit actually played is below what the panel asks for. */
    RESOLUTION_CAPPED,

    /** The backend the static scan picked did not draw, so the test moved to another one. */
    RENDERER_CHANGED,

    /** H.265 played at 1080p, but the panel's own resolution is above that and was not tested. */
    HIGH_RES_UNVERIFIED,

    /** The decoder produced frames but this Android version cannot read the picture back. */
    PICTURE_UNVERIFIED,

    /** Nothing passed, so the stored settings are left alone. */
    NOT_TESTED,
}

/**
 * The settings the test can defend, and why it chose them.
 *
 * [verified] is false when the decoder produced frames but the picture could not be read back,
 * which is the SurfaceView case below Android 7.0. The recommendation is still usable; it just
 * cannot claim the frames were the right ones.
 */
data class VideoPreflightRecommendation(
    val codec: VideoDecoder.CodecType,
    val resolution: Settings.Resolution,
    val viewMode: Settings.ViewMode,
    val notes: Set<VideoPreflightNote>,
    val verified: Boolean,
)

/**
 * Turns what the clips did into a recommendation.
 *
 * The rules are deliberately conservative. H.265 is only recommended above Full HD, where Android
 * Auto forces it, and only when its clip passed; everything else gets H.264 at the highest
 * resolution that actually played. A failed backend is only replaced by one that played a clip,
 * so the result is always something the test saw work (or the stored settings when nothing did).
 *
 * With [allowCodecChange] false (forced software decoding), the static codec and resolution stay
 * unchanged. HEVC may still be tested to expose a configured-session failure, but that diagnostic
 * does not authorize changing the software codec or increasing its resolution.
 */
object VideoPreflightPolicy {

    fun recommend(
        staticCodec: VideoDecoder.CodecType,
        staticResolution: Settings.Resolution,
        staticViewMode: Settings.ViewMode,
        panelCeiling: Settings.Resolution,
        hevcSupported: Boolean,
        backends: List<VideoPreflightBackendOutcome>,
        allowCodecChange: Boolean = true,
    ): VideoPreflightRecommendation {
        val allClips = backends.flatMap { it.clips }
        val passedH264 = allClips.filter {
            it.clip.codec == VideoDecoder.CodecType.H264 && it.passed
        }
        if (passedH264.isEmpty()) {
            return VideoPreflightRecommendation(
                codec = staticCodec,
                resolution = staticResolution,
                viewMode = staticViewMode,
                notes = setOf(VideoPreflightNote.NOT_TESTED),
                verified = false,
            )
        }

        val bestH264 = passedH264.maxByOrNull { it.clip.width * it.clip.height }!!
        val workingBackend = backends.firstOrNull { backend ->
            backend.clips.any { it.clip.codec == VideoDecoder.CodecType.H264 && it.passed }
        }?.viewMode ?: staticViewMode

        val h265Outcomes = allClips.filter { it.clip.codec == VideoDecoder.CodecType.H265 }
        val h265Passed = h265Outcomes.any { it.passed }
        val h265Failed = h265Outcomes.any { !it.passed }
        val panelNeedsH265 = panelCeiling.width > 1920

        val notes = mutableSetOf<VideoPreflightNote>()
        var codec = staticCodec
        var resolution = staticResolution
        if (allowCodecChange) {
            codec = VideoDecoder.CodecType.H264
            resolution = resolutionForWidth(bestH264.clip.width)
            if (resolution.width > panelCeiling.width) resolution = panelCeiling

            if (panelNeedsH265 && hevcSupported && h265Passed) {
                codec = VideoDecoder.CodecType.H265
                resolution = panelCeiling
                notes += VideoPreflightNote.HIGH_RES_UNVERIFIED
            }
            if (resolution.width < staticResolution.width) {
                notes += VideoPreflightNote.RESOLUTION_CAPPED
            }
        }
        if (h265Failed) notes += VideoPreflightNote.H265_FAILED
        if (workingBackend != staticViewMode) {
            notes += VideoPreflightNote.RENDERER_CHANGED
        }

        // A clip that passed without a readable picture proves the decoder, not the picture.
        val verified = passedH264.any { it.samples.isNotEmpty() && it.badSamples == 0 }
        if (!verified) notes += VideoPreflightNote.PICTURE_UNVERIFIED

        return VideoPreflightRecommendation(
            codec = codec,
            resolution = resolution,
            viewMode = workingBackend,
            notes = notes,
            verified = verified,
        )
    }

    /** Frames the decoder may lose over a clip before it counts as a fault. */
    const val ALLOWED_DROPS = 5

    /** The smallest advance the frame markers must show across a clip. */
    const val MIN_SAMPLE_ADVANCE = 10

    /**
     * Judges one clip from what the decoder rendered and what the view put on screen.
     *
     * [drawn] is -1 when the view cannot count its draws. A view that drew nothing is "no
     * picture"; one that drew most of what the decoder rendered is not. A TextureView merges
     * frames that arrive between two draws of a busy UI thread, so a slow unit draws a few frames
     * fewer than it decoded while the picture plays fine. Only losing more than a tenth of them
     * counts, and then as missing frames, which is what the user would see.
     */
    fun classifyClip(
        frameCount: Int,
        rendered: Long,
        drawn: Long,
        observesDraws: Boolean,
        samples: List<Int>,
        badSamples: Int,
        decoderError: String?,
    ): VideoPreflightFailure {
        if (decoderError != null) return VideoPreflightFailure.DECODER_ERROR
        if (rendered == 0L) return VideoPreflightFailure.NO_FIRST_FRAME
        if (rendered < frameCount - ALLOWED_DROPS) return VideoPreflightFailure.FEW_FRAMES
        if (observesDraws && drawn >= 0) {
            if (drawn == 0L) return VideoPreflightFailure.NO_DRAWN_FRAMES
            if (drawn < rendered - displayAllowance(frameCount)) return VideoPreflightFailure.FEW_FRAMES
        }
        if (badSamples > 0) return VideoPreflightFailure.BAD_MARKER
        if (!markersPlausible(samples, frameCount)) return VideoPreflightFailure.BAD_MARKER
        return VideoPreflightFailure.NONE
    }

    private fun displayAllowance(frameCount: Int): Int = maxOf(ALLOWED_DROPS, frameCount / 10)

    private fun markersPlausible(samples: List<Int>, frameCount: Int): Boolean {
        if (samples.size < 3) return true
        if (samples.any { it !in 0 until frameCount }) return false
        for (index in 1 until samples.size) {
            if (samples[index] < samples[index - 1]) return false
        }
        return samples.last() - samples.first() >= MIN_SAMPLE_ADVANCE
    }

    /** The resolution a clip of this width represents. Higher panels keep their own ceiling. */
    fun resolutionForWidth(width: Int): Settings.Resolution = when {
        width <= Settings.Resolution._800x480.width -> Settings.Resolution._800x480
        width <= Settings.Resolution._1280x720.width -> Settings.Resolution._1280x720
        width <= Settings.Resolution._1920x1080.width -> Settings.Resolution._1920x1080
        width <= Settings.Resolution._2560x1440.width -> Settings.Resolution._2560x1440
        else -> Settings.Resolution._3840x2160
    }
}
