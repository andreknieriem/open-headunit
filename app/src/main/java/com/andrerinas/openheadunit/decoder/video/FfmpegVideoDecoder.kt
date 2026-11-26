package com.andrerinas.openheadunit.decoder.video

import android.os.Build
import android.view.Surface
import androidx.annotation.Keep
import com.andrerinas.openheadunit.utils.AppLog
import java.nio.ByteBuffer

@Keep
class FfmpegVideoDecoder(
    private val codecType: VideoDecoder.CodecType,
    private val surface: Surface?,
    private val yuvFrameSink: SoftwareYuvFrameSink?,
    private val width: Int,
    private val height: Int,
    private val isCurrent: () -> Boolean = { true },
    private val beforeRender: PresentationGate? = null,
) {
    fun interface PresentationGate { fun beforeRender(presentationTimeUs: Long): Boolean }

    // JNI renders/callbacks run synchronously inside nativeDecode. VideoDecoder retires a started
    // instance only after its worker returns; the monitor still keeps stop from freeing the
    // handle concurrently with a native call.
    private var nativeHandle: Long = 0
    // Both callbacks run synchronously on the same decoder worker, for one AVFrame at a time.
    private var submissionSequence = 0L

    val isStarted: Boolean
        get() = nativeHandle != 0L

    /** Output-side evidence from the most recent decode, not just an input IDR header. */
    var keyframeRendered: Boolean = false
        private set

    var outputWidth: Int = 0
        private set
    var outputHeight: Int = 0
        private set

    fun start(): Boolean {
        if (!libraryLoaded) {
            AppLog.e("FFmpeg ${codecType.displayName} decoder native library is not loaded")
            return false
        }
        if (!isAvailable(codecType)) {
            AppLog.e("FFmpeg ${codecType.displayName} decoder is not available. Package FFmpeg headers/libs for this ABI.")
            return false
        }
        if (yuvFrameSink == null && surface?.isValid != true) {
            AppLog.e("FFmpeg ${codecType.displayName} decoder cannot start: surface is invalid")
            return false
        }

        val threadCount = recommendedThreadCount()
        AppLog.i("FFmpeg ${codecType.displayName} decoder thread count: $threadCount")
        nativeHandle = nativeCreate(codecType.ffmpegCodecId, surface, this, yuvFrameSink != null, width, height, threadCount)
        if (nativeHandle == 0L) {
            AppLog.e("FFmpeg ${codecType.displayName} decoder failed to initialize")
            return false
        }
        return true
    }

    @Synchronized fun decode(buffer: ByteArray, offset: Int, size: Int, presentationTimeUs: Long = System.nanoTime() / 1000L): Int {
        keyframeRendered = false
        val handle = nativeHandle
        if (handle == 0L) return 0
        val result = nativeDecode(handle, buffer, offset, size, presentationTimeUs)
        if (result < 0) return result
        keyframeRendered = result and RENDERED_KEYFRAME != 0
        return result and RENDERED_KEYFRAME.inv()
    }

    /**
     * Clears the handle and output evidence, then releases native resources on the caller thread.
     * Native decoding and its synchronous callbacks finish before release.
     */
    @Synchronized fun stop() {
        val handle = nativeHandle
        nativeHandle = 0
        keyframeRendered = false
        outputWidth = 0
        outputHeight = 0
        if (handle != 0L && libraryLoaded) {
            nativeRelease(handle)
        }
    }

    private fun recommendedThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return cores.coerceIn(2, 6)
    }

    @Keep
    @Suppress("unused")
    private fun canSubmitNativeWindowFrame(): Boolean = isCurrent()

    @Keep
    @Suppress("unused")
    private fun onNativeOutputSize(width: Int, height: Int) {
        if (!isCurrent()) return
        outputWidth = width
        outputHeight = height
    }

    /**
     * Worker-side pacing and final reservation, after decode and before native presentation.
     * A granted frame owns the following native/GL operation even if retirement then completes;
     * the gate holds no controller/timing monitor across that operation.
     */
    @Keep
    @Suppress("unused")
    private fun onNativeBeforeRender(presentationTimeUs: Long): Boolean {
        // Reserve before checking ownership: an admitted old worker may be delayed in native
        // conversion, but it must never acquire a sequence newer than its replacement.
        submissionSequence = yuvFrameSink?.reserveFrameSequence() ?: 0L
        return beforeRender?.beforeRender(presentationTimeUs) ?: true
    }

    @Keep
    @Suppress("unused")
    private fun onNativeYuv420Frame(
        width: Int,
        height: Int,
        yPlane: ByteBuffer,
        yStride: Int,
        uPlane: ByteBuffer,
        uStride: Int,
        vPlane: ByteBuffer,
        vStride: Int
    ): Boolean {
        if (!isCurrent()) return false
        // Native planes are borrowed for this call only; the sink must finish or cancel every
        // reader before returning, including when teardown interrupts this worker.
        return yuvFrameSink?.renderYuv420Frame(
            width, height, yPlane, yStride, uPlane, uStride, vPlane, vStride, submissionSequence
        ) == true
    }

    companion object {
        // Matches the native positive-result flag; negative values remain decode failures.
        private const val RENDERED_KEYFRAME = 1 shl 30
        private val libraryLoaded: Boolean = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            false
        } else try {
            System.loadLibrary("hur_soft_video")
            true
        } catch (e: UnsatisfiedLinkError) {
            AppLog.e("Failed to load hur_soft_video", e)
            false
        }

        fun isAvailable(codecType: VideoDecoder.CodecType): Boolean {
            return libraryLoaded && nativeIsAvailable(codecType.ffmpegCodecId)
        }

        private external fun nativeIsAvailable(codecId: Int): Boolean
        private external fun nativeCreate(codecId: Int, surface: Surface?, callback: FfmpegVideoDecoder, useYuvCallback: Boolean, width: Int, height: Int, threadCount: Int): Long
        private external fun nativeDecode(handle: Long, buffer: ByteArray, offset: Int, size: Int, presentationTimeUs: Long): Int
        private external fun nativeRelease(handle: Long)
    }
}
