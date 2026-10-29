package com.andrerinas.openheadunit.decoder.video

import android.os.Build
import android.os.SystemClock
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
) {
    // VideoDecoder serializes create/decode on its native-work lock and retires only after that
    // operation returns. JNI renders/callbacks synchronously inside nativeDecode; stop must never
    // free its handle concurrently with that call.
    private var nativeHandle: Long = 0

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

    fun decode(buffer: ByteArray, offset: Int, size: Int, repairEligible: Boolean = true): Int {
        keyframeRendered = false
        val handle = nativeHandle
        if (handle == 0L) return 0
        val result = nativeDecode(handle, buffer, offset, size,
            SystemClock.elapsedRealtimeNanos() / 1000L, repairEligible)
        if (result < 0) return result
        keyframeRendered = result and RENDERED_KEYFRAME != 0
        return result and RENDERED_KEYFRAME.inv()
    }

    fun stop() {
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
    private fun onNativeOutputSize(width: Int, height: Int) {
        if (!isCurrent()) return
        outputWidth = width
        outputHeight = height
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
        return yuvFrameSink?.renderYuv420Frame(width, height, yPlane, yStride, uPlane, uStride, vPlane, vStride) == true
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
        private external fun nativeDecode(handle: Long, buffer: ByteArray, offset: Int, size: Int, presentationTimeUs: Long, repairEligible: Boolean): Int
        private external fun nativeRelease(handle: Long)
    }
}
