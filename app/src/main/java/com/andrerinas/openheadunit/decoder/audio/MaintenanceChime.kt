package com.andrerinas.openheadunit.decoder.audio

import android.content.Context
import android.media.AudioManager
import android.os.SystemClock
import androidx.media.AudioAttributesCompat
import androidx.media.AudioFocusRequestCompat
import androidx.media.AudioManagerCompat
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The short two-note chime that comes with the maintenance banner over Android Auto.
 *
 * It plays on its own thread through the same output stream as the projection's music, so it
 * follows the volume the driver already set. The music ducks around it: Android Auto's own
 * music through [MediaDuck] (the system leaves an app's own players alone), and any other app
 * through transient may-duck focus, which is skipped on units where taking focus is known to
 * pause the phone (see PlaybackFocusPolicy). Everything is released when the chime ends.
 */
object MaintenanceChime {

    private const val TAG = "MaintenanceChime"
    private const val SAMPLE_RATE = 48_000
    private const val AMPLITUDE = 0.22
    /** Time for the music to ease down before the first note. */
    private const val DUCK_LEAD_MS = 120L
    /** Time after the last sample for the device buffer to drain before the music comes back. */
    private const val TAIL_MS = 350L
    private const val WRITE_TIMEOUT_MS = 3_000L
    private const val CHUNK_SHORTS = 960

    private val playing = AtomicBoolean(false)

    /** Built on first use and kept: about 60 KB, so the chime never allocates while driving. */
    private val tone: ShortArray by lazy { buildTone() }

    fun play(context: Context, settings: Settings) {
        if (!playing.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val stream = settings.mediaAudioStream
        val takeFocus = !settings.playbackFocusSelfDefeating
        Thread({
            try {
                playNow(appContext, stream, takeFocus)
            } catch (e: Exception) {
                AppLog.w("$TAG: the chime did not play: ${e.message}")
            } finally {
                playing.set(false)
            }
        }, TAG).start()
    }

    private fun playNow(context: Context, stream: Int, takeFocus: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val focus = if (takeFocus && audioManager != null) requestFocus(audioManager, stream) else null
        MediaDuck.active = true
        var output: AudioTrackPcmOutput? = null
        try {
            Thread.sleep(DUCK_LEAD_MS)
            output = AudioTrackPcmOutput(stream, false)
            output.start()
            writeAll(output, tone)
            Thread.sleep(tone.size / 2 * 1000L / SAMPLE_RATE + TAIL_MS)
        } finally {
            output?.let { runCatching { it.close() } }
            MediaDuck.active = false
            if (focus != null && audioManager != null) {
                runCatching { AudioManagerCompat.abandonAudioFocusRequest(audioManager, focus) }
            }
        }
    }

    private fun requestFocus(audioManager: AudioManager, stream: Int): AudioFocusRequestCompat? = try {
        val request = AudioFocusRequestCompat.Builder(AudioManagerCompat.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributesCompat.Builder().setLegacyStreamType(stream).build())
            .setOnAudioFocusChangeListener { }
            .build()
        val granted = AudioManagerCompat.requestAudioFocus(audioManager, request) ==
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        if (granted) request else null
    } catch (e: Exception) {
        AppLog.w("$TAG: no ducking focus for the chime: ${e.message}")
        null
    }

    private fun writeAll(output: PcmOutput, samples: ShortArray) {
        var offset = 0
        val deadline = SystemClock.elapsedRealtime() + WRITE_TIMEOUT_MS
        while (offset < samples.size && SystemClock.elapsedRealtime() < deadline) {
            val written = output.write(samples, offset, min(CHUNK_SHORTS, samples.size - offset))
            if (written < 0) error("the output rejected audio (code $written)")
            if (written == 0) Thread.sleep(5) else offset += written
        }
    }

    /**
     * Two soft bell notes, the second a fifth above, each with a quick attack and a decay, then
     * a little silence so the last note leaves the device buffer whole. Stereo PCM16.
     */
    internal fun buildTone(): ShortArray {
        val notes = arrayOf(doubleArrayOf(880.0, 0.0, 0.22), doubleArrayOf(1318.5, 0.16, 0.42))
        val seconds = 0.16 + 0.42 + 0.15
        val frames = (SAMPLE_RATE * seconds).toInt()
        val samples = ShortArray(frames * 2)
        val attackFrames = SAMPLE_RATE * 0.006
        for (frame in 0 until frames) {
            val t = frame.toDouble() / SAMPLE_RATE
            var value = 0.0
            for (note in notes) {
                val since = t - note[1]
                if (since < 0 || since > note[2]) continue
                val attack = min(1.0, since * SAMPLE_RATE / attackFrames)
                // Reaches silence at the end of the note, so it never cuts off with a click.
                val decay = exp(-since * 7.0) * (1.0 - since / note[2])
                val phase = 2.0 * PI * note[0] * since
                value += (sin(phase) + 0.25 * sin(2.0 * phase)) * attack * decay
            }
            val sample = (value * AMPLITUDE * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767).toShort()
            samples[frame * 2] = sample
            samples[frame * 2 + 1] = sample
        }
        return samples
    }
}
