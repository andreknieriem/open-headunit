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
    val bufferFrames: Int
    val burstFrames: Int
    val underruns: Int
    fun setBufferFrames(frames: Int): Int
    fun start()
    fun write(data: ShortArray, offset: Int, count: Int): Int
    fun close()
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
                // Vendor stream ids without an AudioAttributes mapping still use the old constructor.
            }
        }
        @Suppress("DEPRECATION")
        return AudioTrack(stream, 48000, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT,
            requestedBytes, AudioTrack.MODE_STREAM)
    }

    override val capacityFrames: Int get() = if (Build.VERSION.SDK_INT >= 24) track.bufferCapacityInFrames else requestedBytes / 4
    override val bufferFrames: Int get() = if (Build.VERSION.SDK_INT >= 23) track.bufferSizeInFrames else requestedBytes / 4
    override val underruns: Int get() = if (Build.VERSION.SDK_INT >= 24) track.underrunCount else 0
    override fun setBufferFrames(frames: Int): Int =
        if (Build.VERSION.SDK_INT >= 24) track.setBufferSizeInFrames(frames) else bufferFrames
    override fun start() {
        if (Build.VERSION.SDK_INT >= 31) track.setStartThresholdInFrames(bufferFrames)
        track.play()
    }
    override fun write(data: ShortArray, offset: Int, count: Int): Int =
        if (Build.VERSION.SDK_INT >= 23) track.write(data, offset, count, AudioTrack.WRITE_NON_BLOCKING)
        else track.write(data, offset, count)
    override fun close() {
        try { track.pause(); track.flush() } finally { equalizer?.release(); track.release() }
    }
}
