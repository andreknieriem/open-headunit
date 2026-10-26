package com.andrerinas.openheadunit.decoder.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.Equalizer
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Output owns only the device buffer. Called exclusively by the mixer thread, including close. */
internal interface PcmOutput {
    val name: String
    val capacityFrames: Int
    val bufferFrames: Int // effective device + any callback staging; used for drain deadlines
    val burstFrames: Int
    val underruns: Int
    val underrunsSupported: Boolean get() = true
    val stagingBufferFrames: Int get() = 0
    val producerUnderruns: Int get() = 0
    /** Output-local counters; -1 means unavailable (AAudio callback silence uses another clock). */
    val writtenFrames: Long get() = -1L
    fun playedFrames(): Long = -1L
    val minimumBufferFrames: Int get() = 960
    /** Progress flushing PCM accepted by a previous software staging queue, not the current write. */
    val recoveryProgressSamples: Long get() = 0
    val outputEpoch: Long get() = 0
    val hasPendingRecovery: Boolean get() = false
    /** Owner-thread hint after a zero write. It never substitutes for the next admission check. */
    val writeRetryDelayMs: Long get() = 1L
    fun setBufferFrames(frames: Int): Int
    fun start()
    fun pause()
    fun write(data: ShortArray, offset: Int, count: Int): Int
    fun close()
    /** Close on the owner thread, then transfer only software PCM never consumed by hardware. */
    fun closeForRecovery(): ShortArray { close(); return ShortArray(0) }
}

internal class AudioTrackPcmOutput(stream: Int, attachHwDsp: Boolean) : PcmOutput {
    private val track: AudioTrack
    private var equalizer: Equalizer? = null
    private val requestedBytes: Int
    private val position = PcmPlaybackPosition()
    private val retryPacer = AudioTrackWritePacer()
    override val writeRetryDelayMs: Long get() = retryPacer.delayMs
    // Lollipop exposes nonblocking writes for ByteBuffer, but not yet for short[]. Keep one
    // native-order conversion buffer per output, rather than allocating on the mixer thread.
    private val byteBuffer = if (Build.VERSION.SDK_INT in 21..22)
        ByteBuffer.allocateDirect(1920).order(ByteOrder.nativeOrder()) else null
    private val shortBuffer = byteBuffer?.asShortBuffer()
    override val minimumBufferFrames: Int
    override val name = "AudioTrack"
    override val burstFrames = 480 // tuning step, not a measured HAL burst

    init {
        val minimum = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "No supported stereo PCM output: $minimum" }
        minimumBufferFrames = AudioTrackBufferSizing.minimumFrames(minimum)
        requestedBytes = AudioTrackBufferSizing.allocationBytes(minimumBufferFrames, Build.VERSION.SDK_INT >= 24)
        track = createTrack(stream, attachHwDsp)
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            error("AudioTrack did not initialize")
        }
        if (attachHwDsp) {
            try { equalizer = Equalizer(0, track.audioSessionId).also { it.enabled = true } } catch (_: Exception) { }
        }
    }

    private fun createTrack(stream: Int, attachHwDsp: Boolean): AudioTrack {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                val builder = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setLegacyStreamType(stream).build())
                    .setAudioFormat(AudioFormat.Builder().setSampleRate(48000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setBufferSizeInBytes(requestedBytes)
                if (Build.VERSION.SDK_INT >= 26 && !attachHwDsp) builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                return builder.build()
            } catch (_: IllegalArgumentException) {
                // AudioAttributes has no public usage mapping for some legacy stream ids,
                // including STREAM_ACCESSIBILITY and vendor-added DSP routes. The deprecated
                // constructor uses the framework's internal mapping and can still reach them.
                // Falling back to STREAM_MUSIC instead would silently change the selected route.
            }
        }
        @Suppress("DEPRECATION")
        return LegacyAudioTrack(stream, requestedBytes)
    }

    // The framework may clamp the requested allocation. Capacity is storage; bufferFrames is
    // the effective playback budget and may be smaller. Read them separately where supported
    // so tuning and drain deadlines use what was granted, not a misleading requested size.
    override val capacityFrames: Int get() = if (Build.VERSION.SDK_INT >= 24) track.bufferCapacityInFrames else bufferFrames
    override val bufferFrames: Int get() = if (Build.VERSION.SDK_INT >= 23) track.bufferSizeInFrames else legacyBufferFrames()
    override val underrunsSupported: Boolean get() = Build.VERSION.SDK_INT >= 24
    override val underruns: Int get() = if (underrunsSupported) track.underrunCount else 0
    override fun setBufferFrames(frames: Int): Int {
        // AOSP ClientProxy::setBufferSizeInFrames (including Android 8.1 and 17) only
        // clamps to 16 frames and capacity; it does not enforce the mixer's full-block
        // requirement. Retain the platform estimate here as well as in the tuning policy,
        // since fallback/recovery can apply a request made for another output backend.
        // A restored ClientProxy initially exposes its full allocation as the effective
        // size. That is not a new minimum: let OutputBufferTuner reapply the safe target.
        if (Build.VERSION.SDK_INT < 24) return bufferFrames
        return track.setBufferSizeInFrames(maxOf(frames, minimumBufferFrames))
    }
    override fun start() {
        retryPacer.reset()
        if (Build.VERSION.SDK_INT >= 31) track.setStartThresholdInFrames(bufferFrames)
        track.play()
    }
    override val writtenFrames: Long get() =
        if (Build.VERSION.SDK_INT < 21 || position.isValid) position.writtenFrames else -1L
    override fun playedFrames(): Long = try { position.played(track.playbackHeadPosition) }
        catch (_: IllegalStateException) { -1L }

    private fun legacyBufferFrames(): Int =
        (track as LegacyAudioTrack).allocatedFrames.coerceAtLeast(1)

    override fun pause() {
        retryPacer.reset()
        track.pause()
        // AOSP 4.1-4.3 AudioFlinger defers flush/reset until presentation completes. A fast
        // start can cancel that reset while the client already expects a zero head. Pause
        // preserves the shared counters instead. The mixer has consumed all real PCM here;
        // at most one native buffer of trailing silence remains for the next resume.
        // API 19+ explicitly rebases the client position at start after flush.
        if (Build.VERSION.SDK_INT >= 19) {
            track.flush()
            position.reset()
        }
    }
    override fun write(data: ShortArray, offset: Int, count: Int): Int {
        // API 16-20 has no public nonblocking write. With one writer, the native
        // allocation minus submitted-but-unconsumed frames is a conservative admission bound.
        // A full track returns 0 here, keeping the outer stall/recovery loop runnable.
        val head = track.playbackHeadPosition
        val played = position.played(head)
        // Admission on API 16-20 requires a trustworthy ring position. In particular, 4.1-4.3
        // native restore may insert uncounted silence: rebasing would falsely free that space.
        // API 21+ has nonblocking writes, so a vendor/direct-output head anomaly only disables
        // frame-based drain for this output; it must not interrupt otherwise healthy playback.
        if (Build.VERSION.SDK_INT < 21)
            check(played >= 0) { "AudioTrack playback head reset or exceeded submitted PCM" }
        val size = if (Build.VERSION.SDK_INT >= 21) count else
            minOf(count, position.writableFrames(bufferFrames, head) * 2)
        if (Build.VERSION.SDK_INT < 21 && size == 0 && count > 0) {
            retryPacer.full(position.writtenFrames - played)
            return 0
        }
        if (size == 0) retryPacer.reset()
        if (size == 0) return 0
        val written = when {
            Build.VERSION.SDK_INT >= 23 -> track.write(data, offset, size, AudioTrack.WRITE_NON_BLOCKING)
            Build.VERSION.SDK_INT >= 21 -> writeLollipop(data, offset, size)
            else -> track.write(data, offset, size)
        }
        if (written == 0 && played >= 0) retryPacer.full(position.writtenFrames - played)
        else retryPacer.reset()
        if (written > 0) position.written(written.toLong() / 2)
        return written
    }
    private fun writeLollipop(data: ShortArray, offset: Int, count: Int): Int {
        val bytes = checkNotNull(byteBuffer)
        val shorts = checkNotNull(shortBuffer)
        val size = minOf(count, shorts.capacity())
        shorts.clear()
        shorts.put(data, offset, size)
        bytes.position(0)
        bytes.limit(size * 2)
        // AOSP 5.0/5.1 native_write_native_bytes passes blocking=false through writeToTrack
        // to ClientProxy::kNonBlocking. It returns BYTES, unlike the short[] overload.
        // Recopy only the caller's unaccepted suffix on the next attempt; retain negative errors.
        val written = track.write(bytes, size * 2, AudioTrack.WRITE_NON_BLOCKING)
        // This JNI path can return raw errno values: -2 must not masquerade as the
        // mixer's caller-contract error and terminate its thread for a device failure.
        return when { written == -2 -> -6; written < 0 -> written; else -> written / 2 }
    }
    override fun close() {
        try { track.pause(); track.flush() } finally { equalizer?.release(); track.release() }
    }
}

/** getNativeFrameCount is a protected SDK API since API 3, not reflection into a hidden API.
 * AOSP 4.1-5.1 createTrack_l can enlarge the requested buffer for the selected output route.
 * Expose that actual size until API 23's public bufferSizeInFrames is available.
 * See frameworks/av android-4.3_r2 media/libmedia/AudioTrack.cpp, createTrack_l/obtainBuffer:
 * native capacity and source-frame consumption share the same producer/consumer ring. */
@Suppress("DEPRECATION")
private class LegacyAudioTrack(stream: Int, bytes: Int) : AudioTrack(
    stream, 48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
    bytes, AudioTrack.MODE_STREAM
) {
    val allocatedFrames: Int get() = nativeFrameCount
}
