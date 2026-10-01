package com.andrerinas.openheadunit.decoder.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.audiofx.Equalizer
import android.os.Build

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
    val minimumBufferFrames: Int get() = 960 // AudioTrack producer retains its 20ms safety floor
    /** Progress flushing PCM accepted by a previous software staging queue, not the current write. */
    val recoveryProgressSamples: Long get() = 0
    val outputEpoch: Long get() = 0
    val hasPendingRecovery: Boolean get() = false
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
    override val name = "AudioTrack"
    override val burstFrames = 480 // framework does not expose the burst size through AudioTrack

    init {
        val minimum = AudioTrack.getMinBufferSize(48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "No supported stereo PCM output: $minimum" }
        requestedBytes = maxOf(minimum, if (Build.VERSION.SDK_INT >= 24) 48000 * 4 * 400 / 1000 else 3840)
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
        return AudioTrack(stream, 48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            requestedBytes, AudioTrack.MODE_STREAM)
    }

    // The framework may clamp the requested allocation. Capacity is storage; bufferFrames is
    // the effective playback budget and may be smaller. Read them separately where supported
    // so tuning and drain deadlines use what was granted, not a misleading requested size.
    override val capacityFrames: Int get() = if (Build.VERSION.SDK_INT >= 24) track.bufferCapacityInFrames else requestedBytes / 4
    override val bufferFrames: Int get() = if (Build.VERSION.SDK_INT >= 23) track.bufferSizeInFrames else requestedBytes / 4
    override val underrunsSupported: Boolean get() = Build.VERSION.SDK_INT >= 24
    override val underruns: Int get() = if (underrunsSupported) track.underrunCount else 0
    override fun setBufferFrames(frames: Int): Int =
        if (Build.VERSION.SDK_INT >= 24) track.setBufferSizeInFrames(frames) else bufferFrames
    override fun start() {
        if (Build.VERSION.SDK_INT >= 31) track.setStartThresholdInFrames(bufferFrames)
        track.play()
    }
    override fun pause() { track.pause(); track.flush() }
    override fun write(data: ShortArray, offset: Int, count: Int): Int =
        if (Build.VERSION.SDK_INT >= 23) track.write(data, offset, count, AudioTrack.WRITE_NON_BLOCKING)
        else track.write(data, offset, count)
    override fun close() {
        try { track.pause(); track.flush() } finally { equalizer?.release(); track.release() }
    }
}
