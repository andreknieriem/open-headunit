package com.andrerinas.openheadunit.decoder.audio

import android.media.AudioManager
import android.os.Process
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** A network bank per channel, followed by one independently tuned device output buffer.
 * Static-focus mode shares an instance; other modes keep one instance per routed stream. */
class AudioMixer(
    private val stream: Int = AudioManager.STREAM_MUSIC,
    private val attachHwDspEqualizer: Boolean = false,
    private val audioLatencyMultiplier: Int = AudioJitterBufferPolicy.DEFAULT_MULTIPLIER
) {
    companion object {
        const val OUTPUT_SAMPLE_RATE = 48000
        const val OUTPUT_CHANNELS = 2
        const val OUTPUT_BIT_DEPTH = 16
        private const val MIX_INTERVAL_MS = 10L
        private const val SAMPLES_PER_CYCLE = (OUTPUT_SAMPLE_RATE * MIX_INTERVAL_MS / 1000).toInt()
        private const val SHORTS_PER_CYCLE = SAMPLES_PER_CYCLE * OUTPUT_CHANNELS
    }

    private class Channel(val rate: Int, val channels: Int, multiplier: Int) {
        val buffer = AdaptivePcmBuffer(latencyMultiplier = multiplier)
        @Volatile var gain = 1f
    }
    private val channels = ConcurrentHashMap<Int, Channel>()
    private val running = AtomicBoolean(false)
    private val hasReceivedAudio = AtomicBoolean(false)
    private var mixThread: Thread? = null
    private var output: PcmOutput? = null
    private val mixBuffer = IntArray(SHORTS_PER_CYCLE)
    private val channelBuffer = ShortArray(SHORTS_PER_CYCLE)
    private val outputBuffer = ShortArray(SHORTS_PER_CYCLE)
    private val conversionBuffer = ThreadLocal<ShortArray>()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            output = AudioTrackPcmOutput(stream, attachHwDspEqualizer)
            mixThread = Thread({
                try { mixLoop() }
                catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                catch (e: Exception) { AppLog.e("AudioMixer: output stopped", e) }
                finally {
                    running.set(false)
                    try { output?.close() } catch (e: Exception) { AppLog.e("AudioMixer: close failed", e) }
                    output = null
                }
            }, "AudioMixer-$stream").also { it.start() }
        } catch (e: Exception) {
            running.set(false)
            output?.close()
            output = null
            throw e
        }
    }

    fun stop() {
        running.set(false)
        mixThread?.interrupt()
        try { mixThread?.join(1000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        // Only the output thread closes its stream. A timed-out join must not free a native
        // handle while write() is using it.
        channels.clear()
    }

    fun registerChannel(channel: Int, sampleRate: Int, channelCount: Int,
                        latencyMultiplier: Int = audioLatencyMultiplier) {
        channels[channel] = Channel(sampleRate, channelCount, latencyMultiplier)
    }
    fun unregisterChannel(channel: Int) { channels.remove(channel) }
    fun finishChannel(channel: Int) { channels[channel]?.buffer?.finish() }
    fun setChannelGain(channel: Int, gain: Float) { channels[channel]?.gain = gain }
    fun getChannelGain(channel: Int): Float = channels[channel]?.gain ?: 1f
    fun hasChannel(channel: Int): Boolean = channels.containsKey(channel)
    fun isRunning(): Boolean = running.get()
    fun targetFramesFor(channel: Int): Int = channels[channel]?.buffer?.targetFrames() ?: 0
    fun depthFramesFor(channel: Int): Int = channels[channel]?.buffer?.depthFrames() ?: 0
    fun silentCyclesFor(channel: Int): Long = channels[channel]?.buffer?.silentCycles ?: 0L
    fun rebanksFor(channel: Int): Long = channels[channel]?.buffer?.rebanks ?: 0L

    /** Called at transport ingress, before codec and playback scheduling can distort timing. */
    fun noteArrival(channel: Int, inputFrames: Int, nowMs: Long) {
        val state = channels[channel] ?: return
        state.buffer.noteArrival(nowMs, (inputFrames.toLong() * OUTPUT_SAMPLE_RATE / state.rate).toInt())
    }

    /** PCM16 little endian -> 48kHz stereo, using reusable per-producer storage. */
    fun feed(channel: Int, data: ByteArray, offset: Int, length: Int) {
        val state = channels[channel] ?: return
        val inputFrames = length / (state.channels * 2)
        if (inputFrames <= 0) return
        val frames = (inputFrames.toLong() * OUTPUT_SAMPLE_RATE / state.rate).toInt()
        val shorts = frames * OUTPUT_CHANNELS
        var converted = conversionBuffer.get()
        if (converted == null || converted.size < shorts) {
            converted = ShortArray(maxOf(shorts, 12288))
            conversionBuffer.set(converted)
        }
        fun sample(frame: Int, ch: Int): Int {
            val index = offset + (frame * state.channels + ch.coerceAtMost(state.channels - 1)) * 2
            return (data[index].toInt() and 0xff) or (data[index + 1].toInt() shl 8)
        }
        for (frame in 0 until frames) {
            val position = frame.toLong() * state.rate
            val low = (position / OUTPUT_SAMPLE_RATE).toInt().coerceAtMost(inputFrames - 1)
            val high = minOf(low + 1, inputFrames - 1)
            val fraction = (position % OUTPUT_SAMPLE_RATE).toFloat() / OUTPUT_SAMPLE_RATE
            for (ch in 0 until OUTPUT_CHANNELS) {
                val a = sample(low, ch)
                converted[frame * OUTPUT_CHANNELS + ch] = (a + (sample(high, ch) - a) * fraction).toInt().toShort()
            }
        }
        state.buffer.write(converted, shorts, SystemClock.elapsedRealtime())
        hasReceivedAudio.set(true)
    }

    private fun softClip(sample: Int): Short {
        val s = sample.coerceIn(-98304, 98304)
        return when {
            s > 20480 -> { val d = s - 20480; (20480 + d * 12287 / (d + 24574)).toShort() }
            s < -20480 -> { val d = -s - 20480; (-(20480 + d * 12287 / (d + 24574))).toShort() }
            else -> s.toShort()
        }
    }

    private fun mixLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val device = output ?: return
        val policy = OutputBufferPolicy(OUTPUT_SAMPLE_RATE, device.burstFrames)
        var requestedFrames = policy.targetFrames
        device.setBufferFrames(requestedFrames)
        AppLog.i("AudioMixer: ${device.name}, stream=$stream, capacity=${device.capacityFrames} frames, " +
            "effective=${device.bufferFrames} frames, burst=${device.burstFrames}, cycle=${MIX_INTERVAL_MS}ms")
        while (running.get() && !hasReceivedAudio.get()) Thread.sleep(MIX_INTERVAL_MS)
        if (!running.get()) return
        device.start()
        var nextReportMs = SystemClock.elapsedRealtime() + 10_000L
        var nextTuneMs = 0L
        while (running.get()) {
            val now = SystemClock.elapsedRealtime()
            mixBuffer.fill(0)
            for (state in channels.values) {
                state.buffer.render(channelBuffer, now)
                val gain = state.gain
                for (i in mixBuffer.indices) mixBuffer[i] += (channelBuffer[i] * gain).toInt()
            }
            for (i in mixBuffer.indices) outputBuffer[i] = softClip(mixBuffer[i])
            val result = AudioWriteLoop.writeFully(SHORTS_PER_CYCLE, { running.get() },
                { offset, remaining -> device.write(outputBuffer, offset, remaining) }, {}, { Thread.sleep(1) })
            check(result >= 0) { "${device.name} write failed: $result" }
            if (now >= nextTuneMs) {
                val target = policy.update(now, device.underruns)
                if (target != requestedFrames) {
                    requestedFrames = target
                    device.setBufferFrames(target)
                }
                nextTuneMs = now + 100
            }
            if (now >= nextReportMs) {
                AppLog.i("AudioMixer: ${device.name} effective=${device.bufferFrames} frames, xruns=${device.underruns}")
                for ((id, state) in channels) {
                    AppLog.i("AudioMixer: channel=$id target=${state.buffer.targetFrames() * 1000L / OUTPUT_SAMPLE_RATE}ms " +
                        "depth=${state.buffer.depthFrames() * 1000L / OUTPUT_SAMPLE_RATE}ms " +
                        "concealedFrames=${state.buffer.concealedFrames} staleFrames=${state.buffer.droppedFrames}")
                }
                nextReportMs = now + 10_000L
            }
        }
    }
}
