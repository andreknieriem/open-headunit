package com.andrerinas.openheadunit.input

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.annotation.RequiresApi
import com.andrerinas.openheadunit.utils.AppLog

/**
 * A started player ranks this app first for media keys on API 26 and later. The silence is in the
 * samples: a track muted by volume reads as inactive and ranks nothing.
 */
@RequiresApi(26)
class KeyLearnSilentTrack {
    private var track: AudioTrack? = null

    /** True when the track plays or already exists. A failed start releases it and returns false. */
    fun start(): Boolean {
        if (track != null) return true
        var t: AudioTrack? = null
        try {
            val frames = SAMPLE_RATE / 10
            val built = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(frames * 2)
                .build()
            t = built
            val written = built.write(ByteArray(frames * 2), 0, frames * 2)
            check(written == frames * 2) { "short write $written" }
            val loop = built.setLoopPoints(0, frames, -1)
            check(loop == AudioTrack.SUCCESS) { "loop points $loop" }
            built.play()
            track = built
            return true
        } catch (e: Exception) {
            AppLog.w("KeyLearnSilentTrack: could not start: ${e.message}")
            try { t?.release() } catch (_: Exception) {}
            return false
        }
    }

    fun stop() {
        val t = track ?: return
        track = null
        try {
            t.stop()
        } catch (_: Exception) {
        }
        t.release()
    }

    private companion object {
        const val SAMPLE_RATE = 8000
    }
}
