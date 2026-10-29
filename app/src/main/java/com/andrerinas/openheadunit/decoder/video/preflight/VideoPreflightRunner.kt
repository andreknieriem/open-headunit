package com.andrerinas.openheadunit.decoder.video.preflight

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.DisplayMetrics
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.graphics.get
import com.andrerinas.openheadunit.decoder.video.DeviceMemoryProfile
import com.andrerinas.openheadunit.decoder.video.SoftwareYuvFrameSink
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.HeadUnitScreenConfig
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SystemOptimizer
import com.andrerinas.openheadunit.view.GlProjectionView
import com.andrerinas.openheadunit.view.IProjectionView
import com.andrerinas.openheadunit.view.ProjectionView
import com.andrerinas.openheadunit.view.TextureProjectionView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference

/** Everything the wizard needs to show and apply the result. */
data class VideoPreflightRun(
    val recommendation: VideoPreflightRecommendation,
    val backends: List<VideoPreflightBackendOutcome>,
    val staticViewMode: Settings.ViewMode,
)

/** Which clip is playing, for the progress line. */
data class VideoPreflightProgress(
    val backend: Settings.ViewMode,
    val clip: VideoPreflightClip,
    val index: Int,
    val total: Int,
)

/**
 * Plays the synthetic clips through a fresh [VideoDecoder] and the real projection view.
 *
 * This is deliberately not the running session's decoder: the wizard runs before a phone is
 * connected, and a failure here must not disturb anything the projection owns. Clips use fresh
 * decoders, but never allocate another until the previous native owner has actually retired.
 *
 * The view is the same class the projection uses (TextureView, SurfaceView or GLES), attached to
 * a small preview container. A backend that decodes but never draws is therefore a real result,
 * not a guess: it is exactly the black-screen case that started this.
 */
class VideoPreflightRunner(
    private val context: Context,
    private val settings: Settings,
    private val sizePreset: SystemOptimizer.DisplaySizePreset,
    private val portrait: Boolean,
    private val container: FrameLayout,
    private val onProgress: (VideoPreflightProgress) -> Unit,
) {
    private var retirementBlocked = false

    suspend fun run(): VideoPreflightRun = withContext(Dispatchers.Main) {
        retirementBlocked = false
        val optimizerResult = SystemOptimizer(context).calculateOptimalSettings(sizePreset, portrait)
        val staticCodec = codecOf(optimizerResult.recommendedVideoCodec)
        val staticResolution = Settings.Resolution.fromId(optimizerResult.recommendedResolutionId)
            ?: Settings.Resolution._1280x720
        val panel = panelCeiling()
        val hevc = VideoDecoder.isHevcSupported()
        val softwareHevc = settings.forceSoftwareDecoding && when (settings.softwareVideoDecoder) {
            Settings.SoftwareVideoDecoder.BUNDLED_FFMPEG ->
                VideoDecoder.isBundledDecoderAvailable(VideoDecoder.CodecType.H265)
            Settings.SoftwareVideoDecoder.DEVICE_MEDIACODEC ->
                VideoDecoder.isHevcDecoderAvailable(includeSoftware = true)
        }
        val clips = VideoPreflightClips.forPanel(
            panelCeiling = panel,
            // Include an explicit choice even before runtime caps; Auto uses the negotiated canvas.
            // The panel progression below still runs when that canvas has not been initialized yet.
            configuredResolution = Settings.Resolution.fromId(settings.resolutionId)?.codec
                ?: HeadUnitScreenConfig.negotiatedResolutionType,
            hardwareHevcSupported = hevc,
            hardwareHevcReliable = VideoDecoder.isHevcReliable(),
            storedCodec = settings.videoCodec,
            forceSoftware = settings.forceSoftwareDecoding,
            selectedSoftwareHevcAvailable = softwareHevc,
        )
        val backends = backendLadder(optimizerResult.recommendedViewMode)
        val outcomes = mutableListOf<VideoPreflightBackendOutcome>()

        for (backend in backends) {
            val preview = createPreview(backend)
            if (preview == null) {
                outcomes += noSurfaceOutcome(backend, clips.first(), "the preview could not be created")
                continue
            }
            container.removeAllViews()
            container.addView(
                preview.view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            try {
                val surface = preview.awaitSurface()
                if (surface == null) {
                    outcomes += noSurfaceOutcome(backend, clips.first(), "the surface did not appear")
                    continue
                }
                val backendOutcome = runBackend(backend, preview, surface, clips)
                outcomes += backendOutcome
                if (backendOutcome.clips.any { it.passed }) break
                val failure = backendOutcome.clips.firstOrNull()?.failure
                val rendererProblem = failure == VideoPreflightFailure.NO_SURFACE ||
                    failure == VideoPreflightFailure.NO_DRAWN_FRAMES ||
                    failure == VideoPreflightFailure.NO_FIRST_FRAME ||
                    failure == VideoPreflightFailure.FEW_FRAMES ||
                    failure == VideoPreflightFailure.BAD_MARKER
                if (!rendererProblem) break
            } finally {
                withContext(NonCancellable) {
                    preview.release()
                    container.removeAllViews()
                }
            }
        }

        val recommendation = VideoPreflightPolicy.recommend(
            staticCodec = staticCodec,
            staticResolution = staticResolution,
            staticViewMode = optimizerResult.recommendedViewMode,
            panelCeiling = panel,
            hevcSupported = hevc,
            backends = outcomes,
            allowCodecChange = !settings.forceSoftwareDecoding,
        )
        AppLog.i(
            "VideoPreflight: recommended codec=${recommendation.codec.settingsValue} " +
                "resolution=${recommendation.resolution.resName} viewMode=${recommendation.viewMode} " +
                "verified=${recommendation.verified} notes=${recommendation.notes}",
        )
        VideoPreflightRun(
            recommendation = recommendation,
            backends = outcomes,
            staticViewMode = optimizerResult.recommendedViewMode,
        )
    }

    private suspend fun runBackend(
        backend: Settings.ViewMode,
        preview: PreflightPreview,
        surface: Surface,
        clips: List<VideoPreflightClip>,
    ): VideoPreflightBackendOutcome {
        val outcomes = mutableListOf<VideoPreflightClipOutcome>()
        for ((index, clip) in clips.withIndex()) {
            onProgress(VideoPreflightProgress(backend, clip, index, clips.size))
            val played = runClip(preview, surface, clip)
            val outcome = if (retirementBlocked) played.copy(
                failure = VideoPreflightFailure.DECODER_ERROR,
                detail = "the previous decoder is still retiring; no further codecs were allocated",
            ) else played
            outcomes += outcome
            AppLog.i(
                "VideoPreflight: backend=$backend clip=${clip.id} fed=${outcome.fedFrames} " +
                    "rendered=${outcome.renderedFrames} drawn=${outcome.drawnFrames} " +
                    "samples=${outcome.samples.size} bad=${outcome.badSamples} " +
                    "failure=${outcome.failure}${outcome.detail?.let { " detail=$it" } ?: ""}",
            )
            if (!outcome.passed) break
        }
        return VideoPreflightBackendOutcome(backend, outcomes)
    }

    private suspend fun runClip(
        preview: PreflightPreview,
        surface: Surface,
        clip: VideoPreflightClip,
    ): VideoPreflightClipOutcome = withContext(Dispatchers.Default) {
        val data = loadClip(clip)
            ?: return@withContext failed(clip, VideoPreflightFailure.UNREADABLE, "the clip could not be read")
        val units = AnnexBAccessUnits.split(data, clip.codec)
        if (units.size != clip.frameCount) {
            return@withContext failed(
                clip,
                VideoPreflightFailure.UNREADABLE,
                "expected ${clip.frameCount} access units, found ${units.size}",
            )
        }

        val decoder = VideoDecoder(settings) {
            DeviceMemoryProfile.readWithOverride(context, settings.debugForceMemoryProfile)
        }
        val decoderError = AtomicReference<String?>(null)
        decoder.onDecoderError = { reason -> decoderError.set(reason) }
        // The preview renderer, not the saved renderer preference, owns this clip's output.
        decoder.softwareYuvFrameSink = preview.view as? SoftwareYuvFrameSink
        decoder.setSurface(surface)
        var fed = 0
        val samples = mutableListOf<Int>()
        var badSamples = 0
        // The preview outlives the clip, so its draw counter has to be read as a delta. Reading the
        // total would let a later clip pass on the frames an earlier one drew.
        val drawnBefore = if (preview.observesDraws) preview.drawnFrames() else -1L
        val owner = ClipOwner(decoder)
        synchronized(clipOwnerLock) {
            val previous = clipOwner
            if (previous != null && (!previous.retired || previous.decoder.hasPendingRetirement)) {
                return@withContext failed(clip, VideoPreflightFailure.DECODER_ERROR,
                    "another preflight decoder is still active or retiring")
            }
            clipOwner = owner
        }

        try {
            // Configure the codec on the IDR before the clock starts. A slow first frame is a
            // warm-up, not a dropped frame, and counting it would fail an otherwise healthy unit.
            // The parameter sets go first and on their own, the way Android Auto sends them, so
            // the codec is started and fed exactly as in a projection.
            val first = units[0]
            val config = AnnexBAccessUnits.parameterSetPrefix(data, first, clip.codec)
            if (config > 0) {
                decoder.decode(data, first.offset, config, false, clip.codec)
            }
            decoder.decode(data, first.offset + config, first.length - config, false, clip.codec)
            // The warm-up keeps feeding at the clip's rate. Plenty of decoders (MediaTek's OMX one,
            // Android's own software AVC) hold their first picture until more input arrives, which
            // the phone always provides. Waiting on a lone IDR read as "no picture" on units that
            // play Android Auto fine.
            fed = warmUp(decoder, data, units, clip, FIRST_FRAME_TIMEOUT_MS)
            if (decoder.framesRenderedThisSession == 0L) {
                return@withContext failed(
                    clip,
                    VideoPreflightFailure.NO_FIRST_FRAME,
                    decoderError.get(),
                    fed = fed,
                )
            }

            // Continue the warm-up's cadence rather than restarting it.
            val startNs = System.nanoTime() - (fed - 1) * 1_000_000_000L / clip.fps
            for (index in fed until units.size) {
                val dueNs = startNs + (index - 1) * 1_000_000_000L / clip.fps
                val waitMs = (dueNs - System.nanoTime()) / 1_000_000L
                if (waitMs > 0) delay(waitMs)
                val unit = units[index]
                decoder.decode(data, unit.offset, unit.length, false, clip.codec)
                fed++
                if (index % SAMPLE_EVERY == 0) {
                    captureMarker(preview, clip, samples) { badSamples++ }
                }
            }
            drain(decoder)
            captureMarker(preview, clip, samples) { badSamples++ }

            val rendered = decoder.framesRenderedThisSession
            val drawnAfter = if (preview.observesDraws) preview.drawnFrames() else -1L
            val drawn = if (drawnBefore >= 0 && drawnAfter >= 0) drawnAfter - drawnBefore else -1L
            val failure = VideoPreflightPolicy.classifyClip(
                frameCount = clip.frameCount,
                rendered = rendered,
                drawn = drawn,
                observesDraws = preview.observesDraws,
                samples = samples,
                badSamples = badSamples,
                decoderError = decoderError.get(),
            )
            VideoPreflightClipOutcome(
                clip = clip,
                fedFrames = fed,
                renderedFrames = rendered,
                drawnFrames = drawn,
                samples = samples,
                badSamples = badSamples,
                failure = failure,
                detail = decoderError.get(),
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(clip, VideoPreflightFailure.DECODER_ERROR, e.message, fed)
        } finally {
            withContext(NonCancellable) {
                decoder.setSurface(null)
                decoder.stop("preflight")
                owner.retired = true
                val retired = CompletableDeferred<Unit>()
                decoder.whenRetired {
                    // Completion, not the observation deadline, owns this lease. In particular
                    // a timed-out wizard must release its Activity/sink when native work returns.
                    synchronized(clipOwnerLock) {
                        if (clipOwner === owner) clipOwner = null
                    }
                    retired.complete(Unit)
                }
                // This deadline bounds observation, not native work. Keep the process-wide lease
                // when it expires so reopening the wizard cannot pile codecs behind a stuck one.
                withTimeoutOrNull(RETIREMENT_TIMEOUT_MS) { retired.await() }
                retirementBlocked = !retired.isCompleted
            }
        }
    }

    private suspend fun captureMarker(
        preview: PreflightPreview,
        clip: VideoPreflightClip,
        samples: MutableList<Int>,
        onBad: () -> Unit,
    ) {
        val bitmap = preview.capture() ?: return
        try {
            val value = readMarker(bitmap, clip, preview.contentArea(bitmap))
            if (value == null) onBad() else samples += value
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Feeds the clip at its own rate until the first picture renders or [timeoutMs] runs out.
     * Unit 0 is already in the codec; returns how many units have been fed in total.
     */
    private suspend fun warmUp(
        decoder: VideoDecoder,
        data: ByteArray,
        units: List<AnnexBAccessUnits.Unit>,
        clip: VideoPreflightClip,
        timeoutMs: Long,
    ): Int {
        var fed = 1
        val startNs = System.nanoTime()
        val deadline = startNs + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline && decoder.framesRenderedThisSession == 0L) {
            val dueNs = startNs + fed * 1_000_000_000L / clip.fps
            if (fed < units.size && System.nanoTime() >= dueNs) {
                val unit = units[fed]
                decoder.decode(data, unit.offset, unit.length, false, clip.codec)
                fed++
            }
            delay(5)
        }
        return fed
    }

    private suspend fun drain(decoder: VideoDecoder) {
        var last = decoder.framesRenderedThisSession
        var stable = 0
        val deadline = System.nanoTime() + DRAIN_TIMEOUT_MS * 1_000_000L
        while (System.nanoTime() < deadline) {
            delay(50)
            val current = decoder.framesRenderedThisSession
            if (current == last) {
                stable++
                if (stable >= 6) return
            } else {
                last = current
                stable = 0
            }
        }
    }

    /**
     * Reads the frame number. [area] is where the picture sits when the capture also shows the
     * decoder's padding (see [TextureContentArea]). Its mirror is tried too, because whether the
     * padding rows land below or above the picture depends on how the buffer was flipped, and the
     * whole capture last, in case this device's getBitmap honours the crop after all.
     *
     * A misplaced read lands on map pixels or on the marker's black panel and comes back unreadable
     * or as frame zero, so the first reading that saw a lit block wins.
     */
    private fun readMarker(
        bitmap: Bitmap,
        clip: VideoPreflightClip,
        area: TextureContentArea.Area?,
    ): Int? {
        if (bitmap.width <= 0 || bitmap.height <= 0) return null
        val whole = TextureContentArea.Area(0, 0, bitmap.width, bitmap.height)
        val mirrored = area?.copy(top = bitmap.height - area.top - area.height)
        val readings = listOfNotNull(area, mirrored, whole).distinct().mapNotNull { box ->
            // Stretched like the preview draws it, not fitted: see FrameMarker.
            FrameMarker.read(
                luma = { x, y -> luma(bitmap[x, y]) },
                geometry = clip.marker,
                maxX = bitmap.width,
                maxY = bitmap.height,
                scaleX = box.width.toDouble() / clip.width,
                scaleY = box.height.toDouble() / clip.height,
                offsetX = box.left,
                offsetY = box.top,
            )
        }
        val reading = readings.firstOrNull { it.frame > 0 } ?: readings.firstOrNull() ?: return null
        return reading.frame.takeIf { it in 0 until clip.frameCount }
    }

    private fun luma(argb: Int): Int {
        val red = (argb shr 16) and 0xFF
        val green = (argb shr 8) and 0xFF
        val blue = argb and 0xFF
        return (red * 299 + green * 587 + blue * 114) / 1000
    }

    private fun loadClip(clip: VideoPreflightClip): ByteArray? = runCatching {
        context.resources.openRawResource(clip.rawResource).use { it.readBytes() }
    }.getOrNull()

    private fun failed(
        clip: VideoPreflightClip,
        failure: VideoPreflightFailure,
        detail: String? = null,
        fed: Int = 0,
    ): VideoPreflightClipOutcome = VideoPreflightClipOutcome(
        clip = clip,
        fedFrames = fed,
        renderedFrames = 0,
        drawnFrames = -1,
        samples = emptyList(),
        badSamples = 0,
        failure = failure,
        detail = detail,
    )

    private fun noSurfaceOutcome(
        backend: Settings.ViewMode,
        clip: VideoPreflightClip,
        detail: String,
    ): VideoPreflightBackendOutcome = VideoPreflightBackendOutcome(
        backend,
        listOf(failed(clip, VideoPreflightFailure.NO_SURFACE, detail)),
    )

    private fun createPreview(mode: Settings.ViewMode): PreflightPreview? = when (mode) {
        Settings.ViewMode.TEXTURE -> {
            val view = TextureProjectionView(container.context).apply {
                appliesProjectionScale = false
            }
            // Read with the bitmap, on the same thread, so both describe the same frame.
            var transform: FloatArray? = null
            preview(
                view = view,
                projection = view,
                observesDraws = true,
                capture = {
                    withContext(Dispatchers.Main) {
                        transform = view.textureTransform()
                        view.captureBitmap()
                    }
                },
                drawn = { view.drawnFrames() },
                contentArea = { bitmap ->
                    transform?.let { TextureContentArea.fromTransform(it, bitmap.width, bitmap.height) }
                },
            )
        }
        Settings.ViewMode.GLES -> {
            val view = GlProjectionView(container.context)
            preview(
                view = view,
                projection = view,
                observesDraws = true,
                capture = { withContext(Dispatchers.Default) { view.captureBitmap() } },
                drawn = { view.drawnFrames() },
            )
        }
        Settings.ViewMode.SURFACE -> {
            val view = ProjectionView(container.context).apply {
                ownsDecoder = false
                appliesProjectionScale = false
            }
            preview(
                view = view,
                projection = view,
                observesDraws = false,
                capture = { withContext(Dispatchers.Default) { view.captureBitmap() } },
                drawn = { -1L },
            )
        }
    }

    private fun preview(
        view: View,
        projection: IProjectionView,
        observesDraws: Boolean,
        capture: suspend () -> Bitmap?,
        drawn: () -> Long,
        contentArea: (Bitmap) -> TextureContentArea.Area? = { null },
    ): PreflightPreview {
        val surface = CompletableDeferred<Surface?>()
        val callbacks = object : IProjectionView.Callbacks {
            override fun onSurfaceCreated(surface_: Surface) {
                surface.complete(surface_)
            }

            override fun onSurfaceDestroyed(surface_: Surface) {
                if (!surface.isCompleted) surface.complete(null)
            }

            override fun onSurfaceChanged(surface_: Surface, width: Int, height: Int) = Unit
        }
        projection.addCallback(callbacks)
        return PreflightPreview(
            view = view,
            observesDraws = observesDraws,
            capture = capture,
            contentArea = contentArea,
            drawnFrames = drawn,
            awaitSurface = {
                withTimeoutOrNull(SURFACE_TIMEOUT_MS) { surface.await() }
            },
            release = {
                projection.removeCallback(callbacks)
                (view.parent as? FrameLayout)?.removeView(view)
            },
        )
    }

    private fun backendLadder(static: Settings.ViewMode): List<Settings.ViewMode> {
        val order = listOf(Settings.ViewMode.TEXTURE, Settings.ViewMode.SURFACE, Settings.ViewMode.GLES)
        return listOf(static) + order.filter { it != static }
    }

    private fun panelCeiling(): Settings.Resolution {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> context.display?.getRealMetrics(metrics)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 ->
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .defaultDisplay.getRealMetrics(metrics)
            else ->
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                    .defaultDisplay.getMetrics(metrics)
        }
        return SystemOptimizer.panelCeiling(
            metrics.widthPixels,
            metrics.heightPixels,
            VideoDecoder.isHevcSupported(),
        )
    }

    private fun codecOf(value: String): VideoDecoder.CodecType =
        if (value.contains("265")) VideoDecoder.CodecType.H265 else VideoDecoder.CodecType.H264

    private class PreflightPreview(
        val view: View,
        val observesDraws: Boolean,
        val capture: suspend () -> Bitmap?,
        val contentArea: (Bitmap) -> TextureContentArea.Area?,
        val drawnFrames: () -> Long,
        val awaitSurface: suspend () -> Surface?,
        val release: () -> Unit,
    )

    private class ClipOwner(val decoder: VideoDecoder) {
        @Volatile var retired = false
    }

    companion object {
        private val clipOwnerLock = Any()
        private var clipOwner: ClipOwner? = null
        private const val RETIREMENT_TIMEOUT_MS = 1_000L
        private const val SURFACE_TIMEOUT_MS = 3_000L
        private const val FIRST_FRAME_TIMEOUT_MS = 5_000L
        private const val DRAIN_TIMEOUT_MS = 2_000L
        private const val SAMPLE_EVERY = 10
    }
}
