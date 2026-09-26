package com.andrerinas.openheadunit.decoder.audio

import android.media.AudioManager
import android.os.Process
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A network bank per channel, followed by one independently tuned device output buffer.
 * Static-focus mode shares an instance; other modes keep one instance per routed stream. */
class AudioMixer(
    private val stream: Int = AudioManager.STREAM_MUSIC,
    private val attachHwDspEqualizer: Boolean = false,
    private val audioLatencyMultiplier: Int = AudioJitterBufferPolicy.DEFAULT_MULTIPLIER,
    private val preferAAudio: Boolean = false,
    private val keepOutputActive: Boolean = false
) {
    companion object {
        const val OUTPUT_SAMPLE_RATE = 48000
        const val OUTPUT_CHANNELS = 2
        const val OUTPUT_BIT_DEPTH = 16
        private const val MIX_INTERVAL_MS = 10L
        private const val SAMPLES_PER_CYCLE = (OUTPUT_SAMPLE_RATE * MIX_INTERVAL_MS / 1000).toInt()
        private const val SHORTS_PER_CYCLE = SAMPLES_PER_CYCLE * OUTPUT_CHANNELS
        private const val OUTPUT_WARMUP_MS = 1000L
        private val nextDiagnosticId = AtomicInteger()
    }

    private class Channel(val rate: Int, val channels: Int, multiplier: Int, isMediaSink: Boolean) {
        val buffer = AdaptivePcmBuffer(latencyMultiplier = multiplier, isMediaSink = isMediaSink)
        @Volatile var gain = 1f
        @Volatile var warmupUntilMs = 0L
        @Volatile var preparedMs = -1L
        @Volatile var firstPcmMs = -1L
        var startupReported = false
        var reportedRebanks = 0L
        var reportedDropped = 0L
        var reportedConcealed = 0L
    }
    private val channels = ConcurrentHashMap<Int, Channel>()
    private val diagnosticId = nextDiagnosticId.incrementAndGet()
    private val running = AtomicBoolean(false)
    private val hasReceivedAudio = AtomicBoolean(false)
    private val feedSignal = Semaphore(0)
    private var mixThread: Thread? = null
    private var output: PcmOutput? = null
    private val mixBuffer = IntArray(SHORTS_PER_CYCLE)
    private val channelBuffer = ShortArray(SHORTS_PER_CYCLE)
    private val outputBuffer = ShortArray(SHORTS_PER_CYCLE)
    private val conversionBuffer = ThreadLocal<ShortArray>()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            output = AudioOutputFactory.create(stream, attachHwDspEqualizer, preferAAudio)
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
                        latencyMultiplier: Int = audioLatencyMultiplier, isMediaSink: Boolean = false) {
        channels[channel] = Channel(sampleRate, channelCount, latencyMultiplier, isMediaSink)
    }
    fun unregisterChannel(channel: Int) { channels.remove(channel) }
    fun prepareChannel(channel: Int) {
        val state = channels[channel] ?: return
        val now = SystemClock.elapsedRealtime()
        if (state.firstPcmMs < 0) state.preparedMs = now
        state.warmupUntilMs = now + OUTPUT_WARMUP_MS
        if (feedSignal.availablePermits() == 0) feedSignal.release()
    }
    fun finishChannel(channel: Int) {
        channels[channel]?.let {
            it.warmupUntilMs = 0
            if (it.firstPcmMs < 0) it.preparedMs = -1L
            it.buffer.finish()
        }
    }
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
        if (state.rate == OUTPUT_SAMPLE_RATE && state.channels == OUTPUT_CHANNELS) {
            // The ordinary media path needs no resampling or floating-point work.
            Pcm16Stereo.decode(data, offset, frames, converted)
        } else for (frame in 0 until frames) {
            val position = frame.toLong() * state.rate
            val low = (position / OUTPUT_SAMPLE_RATE).toInt().coerceAtMost(inputFrames - 1)
            val high = minOf(low + 1, inputFrames - 1)
            val fraction = (position % OUTPUT_SAMPLE_RATE).toFloat() / OUTPUT_SAMPLE_RATE
            for (ch in 0 until OUTPUT_CHANNELS) {
                val a = sample(low, ch)
                converted[frame * OUTPUT_CHANNELS + ch] = (a + (sample(high, ch) - a) * fraction).toInt().toShort()
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (state.firstPcmMs < 0) state.firstPcmMs = now
        state.buffer.write(converted, shorts, now)
        state.warmupUntilMs = 0
        hasReceivedAudio.set(true)
        if (feedSignal.availablePermits() == 0) feedSignal.release()
    }

    private fun softClip(sample: Int): Short {
        val s = sample.coerceIn(-98304, 98304)
        return when {
            s > 20480 -> { val d = s - 20480; (20480 + d * 12287 / (d + 24574)).toShort() }
            s < -20480 -> { val d = -s - 20480; (-(20480 + d * 12287 / (d + 24574))).toShort() }
            else -> s.toShort()
        }
    }

    private fun renderBurstFrames(device: PcmOutput): Int {
        // AAudio exposes its producer queue; AudioTrack only exposes its effective write budget.
        val staging = device.stagingBufferFrames
        return maxOf(device.burstFrames, if (staging > 0) staging else device.bufferFrames)
    }

    private fun mixLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val device = output ?: return
        var tuningBurst = device.burstFrames
        var tuningMinimum = device.minimumBufferFrames
        var tuningBackend = device.name
        var policy = OutputBufferPolicy(OUTPUT_SAMPLE_RATE, tuningBurst, tuningMinimum)
        var previousOutputXruns = device.underruns
        policy.update(SystemClock.elapsedRealtime(), previousOutputXruns)
        var requestedFrames = policy.targetFrames
        device.setBufferFrames(requestedFrames)
        var renderBurst = renderBurstFrames(device)
        recordDiagnostic(SystemClock.elapsedRealtime(), "opened ${device.name}, stream=$stream, capacity=${device.capacityFrames} frames, " +
            "effective=${device.bufferFrames} frames, staging=${device.stagingBufferFrames}, " +
            "burst=${device.burstFrames}, minimum=$tuningMinimum, maximum=${policy.maximumFrames}, cycle=${MIX_INTERVAL_MS}ms")
        val lifecycle = MixerOutputLifecycle(keepOutputActive)
        var nextReportMs = SystemClock.elapsedRealtime() + 10_000L
        var nextTuneMs = 0L
        var nextPcmDiagnosticMs = 0L
        while (running.get()) {
            val now = SystemClock.elapsedRealtime()
            if (now >= nextPcmDiagnosticMs) {
                for ((id, state) in channels) {
                    val rebanks = state.buffer.rebanks
                    val dropped = state.buffer.droppedFrames
                    val concealed = state.buffer.concealedFrames
                    if (rebanks != state.reportedRebanks || dropped != state.reportedDropped ||
                        concealed != state.reportedConcealed) {
                        recordDiagnostic(now, "PCM channel=$id source=${state.rate}Hz/${state.channels}ch " +
                            "target=${state.buffer.targetFrames()} depth=${state.buffer.depthFrames()} frames " +
                            "arrivalGapMax=${state.buffer.maxArrivalGapMs()}ms " +
                            "rebanksDelta=${rebanks - state.reportedRebanks} " +
                            "concealedDelta=${concealed - state.reportedConcealed} " +
                            "droppedDelta=${dropped - state.reportedDropped}", warning = true)
                        state.reportedRebanks = rebanks
                        state.reportedDropped = dropped
                        state.reportedConcealed = concealed
                    }
                }
                nextPcmDiagnosticMs = now + 1000
            }
            val idle = channels.values.all { it.buffer.isIdle() }
            val warming = channels.values.any { now < it.warmupUntilMs }
            when (lifecycle.update(now, idle, warming, hasReceivedAudio.get(),
                device.bufferFrames * 1000L / OUTPUT_SAMPLE_RATE + 20)) {
                MixerOutputLifecycle.Action.WAIT -> {
                    feedSignal.tryAcquire(200, TimeUnit.MILLISECONDS)
                    continue
                }
                MixerOutputLifecycle.Action.START -> device.start()
                MixerOutputLifecycle.Action.PAUSE -> {
                    device.pause()
                    continue
                }
                MixerOutputLifecycle.Action.WRITE -> Unit
            }
            mixBuffer.fill(0)
            var activeChannels = 0
            var boosted = false
            for ((id, state) in channels) {
                val active = state.buffer.render(channelBuffer, now, renderBurst)
                if (active && !state.startupReported && state.firstPcmMs >= 0) {
                    state.startupReported = true
                    val readyMs = SystemClock.elapsedRealtime()
                    val requestWait = if (state.preparedMs >= 0) state.firstPcmMs - state.preparedMs else -1L
                    recordDiagnostic(readyMs, "first PCM channel=$id requestToPcm=${requestWait}ms " +
                        "pcmToRender=${readyMs - state.firstPcmMs}ms target=${state.buffer.targetFrames()} " +
                        "depth=${state.buffer.depthFrames()} outputBudget=${device.bufferFrames} frames")
                }
                if (active) activeChannels++
                val gain = state.gain
                boosted = boosted || (active && gain > 1f)
                for (i in mixBuffer.indices) mixBuffer[i] += (channelBuffer[i] * gain).toInt()
            }
            for (i in mixBuffer.indices) {
                // Moving a normal media sink through this renderer must not compress its music.
                outputBuffer[i] = if (activeChannels > 1 || boosted) softClip(mixBuffer[i])
                    else mixBuffer[i].coerceIn(-32768, 32767).toShort()
            }
            val result = AudioWriteLoop.writeFully(SHORTS_PER_CYCLE, { running.get() },
                { offset, remaining -> device.write(outputBuffer, offset, remaining) }, {}, { Thread.sleep(1) })
            check(result >= 0) { "${device.name} write failed: $result" }
            if (now >= nextTuneMs) {
                val burst = device.burstFrames
                val minimum = device.minimumBufferFrames
                if (burst != tuningBurst || minimum != tuningMinimum || device.name != tuningBackend) {
                    // AAudio can grant a different callback size; fallback can replace the device.
                    // Rebase both the burst quantum and the xrun baseline on the current output.
                    tuningBurst = burst
                    tuningMinimum = minimum
                    tuningBackend = device.name
                    policy = OutputBufferPolicy(OUTPUT_SAMPLE_RATE, burst, minimum)
                    requestedFrames = -1
                }
                val xruns = device.underruns
                val target = policy.update(now, xruns)
                if (target != requestedFrames || xruns != previousOutputXruns) {
                    requestedFrames = target
                    device.setBufferFrames(target)
                    recordDiagnostic(now, "output ${device.name} requested=$target effective=${device.bufferFrames} " +
                        "staging=${device.stagingBufferFrames} burst=$burst minimum=$minimum " +
                        "stableFloor=${policy.stableFloorFrames} maximum=${policy.maximumFrames} " +
                        "xruns=$xruns producerUnderruns=${device.producerUnderruns}",
                        warning = xruns > previousOutputXruns)
                }
                renderBurst = renderBurstFrames(device)
                previousOutputXruns = xruns
                nextTuneMs = now + 100
            }
            if (now >= nextReportMs) {
                AppLog.i("AudioMixer: id=$diagnosticId ${device.name} effective=${device.bufferFrames} frames, " +
                    "staging=${device.stagingBufferFrames}, xruns=${device.underruns}, " +
                    "producerUnderruns=${device.producerUnderruns}, burst=${device.burstFrames}, " +
                    "requested=$requestedFrames, stableFloor=${policy.stableFloorFrames}, maximum=${policy.maximumFrames}")
                for ((id, state) in channels) {
                    AppLog.i("AudioMixer: id=$diagnosticId channel=$id target=${state.buffer.targetFrames() * 1000L / OUTPUT_SAMPLE_RATE}ms " +
                        "depth=${state.buffer.depthFrames() * 1000L / OUTPUT_SAMPLE_RATE}ms " +
                        "arrivalGapMax=${state.buffer.maxArrivalGapMs()}ms " +
                        "concealedFrames=${state.buffer.concealedFrames} staleFrames=${state.buffer.droppedFrames} " +
                        "compressedFrames=${state.buffer.compressedFrames}")
                }
                nextReportMs = now + 10_000L
            }
        }
    }

    private fun recordDiagnostic(nowMs: Long, message: String, warning: Boolean = false) {
        val line = "AudioMixer: id=$diagnosticId $message"
        AudioDiagnostics.record(nowMs, line)
        if (warning) AppLog.w(line) else AppLog.i(line)
    }
}
