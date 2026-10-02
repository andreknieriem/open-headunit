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

/**
 * A network bank per channel, followed by one independently tuned device output buffer.
 *
 * Static-focus mode mixes music, assistant and navigation into one 48 kHz PCM16 stereo output.
 * Keeping that output alive avoids vendor head units changing volume/routing when separate
 * AudioTracks are created or destroyed for each prompt. Other modes keep separate instances
 * for their routed streams, so the user's routing and hardware DSP choices remain meaningful.
 *
 * Each channel banks against its own latency preference; the device buffer is tuned against
 * output scheduling and underruns. A capped guidance preference must not size the music output
 * just because guidance happened to be the first sink set up. Network reserve and hardware
 * capacity are different budgets, and increasing the latter cannot repair missing input PCM.
 */
class AudioMixer(
    private val stream: Int = AudioManager.STREAM_MUSIC,
    private val attachHwDspEqualizer: Boolean = false,
    private val audioLatencyMultiplier: Int = AudioJitterBufferPolicy.DEFAULT_MULTIPLIER,
    private val preferAAudio: Boolean = false,
    private val keepOutputActive: Boolean = false,
    private val duckMediaForSpeech: Boolean = false,
    private val canRender: () -> Boolean = { true },
    private val onRecoveryActivity: () -> Unit = {}
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

    internal class Channel(val rate: Int, val channels: Int, multiplier: Int, val isMediaSink: Boolean,
                           val onOutputStopped: () -> Unit) {
        val buffer = AdaptivePcmBuffer(latencyMultiplier = multiplier, isMediaSink = isMediaSink)
        val format = PcmInputFormat(rate, channels)
        val converter = PcmConverter()
        @Volatile var gain = 1f
        @Volatile var warmupUntilMs = 0L
        @Volatile var preparedMs = -1L
        @Volatile var firstPcmMs = -1L
        @Volatile var writePending = false
        @Volatile var lastWriteCompletedMs = -1L
        var activeThisCycle = false
        var startupReported = false
        var reportedRebanks = 0L
        var reportedDropped = 0L
        var reportedConcealed = 0L
    }
    private val channels = ConcurrentHashMap<Int, Channel>()
    @Volatile private var channelSnapshot = emptyArray<Channel>()
    private val diagnosticId = nextDiagnosticId.incrementAndGet()
    private val running = AtomicBoolean(false)
    private val hasReceivedAudio = AtomicBoolean(false)
    private val feedSignal = Semaphore(0)
    private var mixThread: Thread? = null
    @Volatile private var output: RecoveringPcmOutput? = null
    @Volatile private var outputDrainMs = 40L
    @Volatile private var recoveryPending = false
    @Volatile private var outputUnavailableSinceMs = -1L
    private val mixBuffer = IntArray(SHORTS_PER_CYCLE)
    private val mediaBuffer = IntArray(SHORTS_PER_CYCLE)
    private val channelBuffer = ShortArray(SHORTS_PER_CYCLE)
    private val outputBuffer = ShortArray(SHORTS_PER_CYCLE)
    private val limiter = MixerSoftClipper()
    private val ducking = MixerDuckingEnvelope(OUTPUT_SAMPLE_RATE)

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            val createOutput = { AudioOutputFactory.create(stream, attachHwDspEqualizer, preferAAudio) }
            output = RecoveringPcmOutput(null, createOutput, SystemClock::elapsedRealtime) {
                recordDiagnostic(SystemClock.elapsedRealtime(), it, warning = true)
            }
            mixThread = Thread({
                try { mixLoop() }
                catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                catch (e: Exception) { AppLog.e("AudioMixer: output stopped", e) }
                finally {
                    running.set(false)
                    retireOutputOwners()
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

    @Synchronized internal fun requestStop() {
        running.set(false)
        mixThread?.interrupt()
        retireOutputOwners()
        channels.clear()
        channelSnapshot = emptyArray()
    }

    private fun retireOutputOwners() {
        channelSnapshot.forEach {
            try { it.onOutputStopped() }
            catch (e: Exception) { AppLog.e("AudioMixer: retirement notification failed", e) }
        }
    }

    fun stop() {
        requestStop()
        try { mixThread?.join(1000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        // Only the output thread closes its stream. A timed-out join must not free a native
        // handle while write() is using it.
    }

    @Synchronized internal fun registerChannel(channel: Int, sampleRate: Int, channelCount: Int,
                        latencyMultiplier: Int = audioLatencyMultiplier, isMediaSink: Boolean = false,
                        onOutputStopped: () -> Unit = {}): Channel {
        return Channel(sampleRate, channelCount, latencyMultiplier, isMediaSink, onOutputStopped).also {
            channels[channel] = it
            channelSnapshot = channels.values.toTypedArray()
        }
    }
    @Synchronized internal fun unregisterChannel(channel: Int, state: Channel) {
        if (channels.remove(channel, state)) channelSnapshot = channels.values.toTypedArray()
    }
    internal fun resetConverter(state: Channel) {
        synchronized(state) { state.converter.reset() }
    }
    internal fun prepareChannel(state: Channel) {
        outputUnavailableSinceMs = -1L
        output?.preparePlayback()
        val now = SystemClock.elapsedRealtime()
        if (state.firstPcmMs < 0) state.preparedMs = now
        state.warmupUntilMs = now + OUTPUT_WARMUP_MS
        if (feedSignal.availablePermits() == 0) feedSignal.release()
    }
    internal fun finishChannel(state: Channel) {
        state.warmupUntilMs = 0
        if (state.firstPcmMs < 0) state.preparedMs = -1L
        state.buffer.finish()
    }
    fun isRunning(): Boolean = running.get()
    /** Estimated device drain, after actual write completion, never just bank consumption. */
    internal fun isQuiescent(state: Channel, nowMs: Long): Boolean =
        // Terminal recovery parks retained audio. It must not hold the car radio's focus forever.
        (output?.isParked == true && outputUnavailableSinceMs >= 0 &&
            nowMs - outputUnavailableSinceMs >= 1000) ||
        (!state.writePending && state.buffer.isIdle() && !recoveryPending &&
            (state.lastWriteCompletedMs < 0 || nowMs - state.lastWriteCompletedMs >= outputDrainMs) &&
            !state.writePending)
    fun depthFramesFor(channel: Int): Int = channels[channel]?.buffer?.depthFrames() ?: 0

    /** Called at transport ingress, before codec and playback scheduling can distort timing. */
    internal fun noteArrival(state: Channel, inputFrames: Int, nowMs: Long) {
        state.buffer.noteArrival(nowMs, (inputFrames.toLong() * OUTPUT_SAMPLE_RATE / state.rate).toInt())
    }

    /** PCM16 little endian -> 48kHz stereo, using reusable per-producer storage. */
    internal fun feed(state: Channel, data: ByteArray, offset: Int, length: Int,
                      format: PcmInputFormat = state.format) {
        // A wrapper retains its exact registration. A retired codec can neither feed nor
        // unregister a replacement channel that happens to reuse the same protocol id.
        synchronized(state) {
            val shorts = state.converter.convert(data, offset, length, format)
            if (shorts == 0) return
            val now = SystemClock.elapsedRealtime()
            if (state.firstPcmMs < 0) state.firstPcmMs = now
            state.buffer.write(state.converter.samples, shorts, now)
            state.warmupUntilMs = 0
            hasReceivedAudio.set(true)
            if (feedSignal.availablePermits() == 0) feedSignal.release()
        }
    }

    private fun renderBurstFrames(device: PcmOutput): Int {
        // AAudio exposes its producer queue; AudioTrack only exposes its effective write budget.
        val staging = device.stagingBufferFrames
        return maxOf(device.burstFrames, if (staging > 0) staging else device.bufferFrames)
    }

    /** Render-clock DSP, independent of Android scheduling and device I/O. Output storage is reused. */
    internal fun renderCycle(nowMs: Long, outputBurstFrames: Int, cycleChannels: Array<Channel> = channelSnapshot): ShortArray {
        mixBuffer.fill(0)
        mediaBuffer.fill(0)
        var activeChannels = 0
        var speechActive = false
        var boosted = false
        for (state in cycleChannels) {
            val active = state.buffer.render(channelBuffer, nowMs, outputBurstFrames)
            state.activeThisCycle = active
            val gain = state.gain
            if (active && gain > 0f) activeChannels++
            speechActive = speechActive || (active && !state.isMediaSink && gain > 0f)
            boosted = boosted || (active && gain > 1f)
            val destination = if (duckMediaForSpeech && state.isMediaSink) mediaBuffer else mixBuffer
            for (i in destination.indices) destination[i] += (channelBuffer[i] * gain).toInt()
        }
        ducking.setSpeechActive(duckMediaForSpeech && speechActive)
        limiter.setEnabled(activeChannels > 1 || boosted)
        var mediaGain = 1f
        for (i in mixBuffer.indices) {
            if (i % OUTPUT_CHANNELS == 0) { mediaGain = ducking.nextGain(); limiter.advance() }
            mixBuffer[i] += (mediaBuffer[i] * mediaGain).toInt()
            // Moving a normal media sink through this renderer must not compress its music.
            outputBuffer[i] = limiter.process(mixBuffer[i])
        }
        return outputBuffer
    }

    private fun mixLoop() {
        requestAudioThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val device = output ?: return
        var tuningBurst = device.burstFrames
        var tuningMinimum = device.minimumBufferFrames
        var tuningBackend = device.name
        var policy = OutputBufferPolicy(OUTPUT_SAMPLE_RATE, tuningBurst, tuningMinimum)
        var previousOutputXruns = device.underruns
        policy.update(SystemClock.elapsedRealtime(), previousOutputXruns)
        var requestedFrames = policy.targetFrames
        val bufferTuner = OutputBufferTuner()
        bufferTuner.update(device, requestedFrames)
        var renderBurst = renderBurstFrames(device)
        recordDiagnostic(SystemClock.elapsedRealtime(), "opened ${device.name}, stream=$stream, capacity=${device.capacityFrames} frames, " +
            "effective=${device.bufferFrames} frames, staging=${device.stagingBufferFrames}, " +
            "burst=${device.burstFrames}, minimum=$tuningMinimum, maximum=${policy.maximumFrames}, cycle=${MIX_INTERVAL_MS}ms")
        val lifecycle = MixerOutputLifecycle(keepOutputActive)
        var nextReportMs = SystemClock.elapsedRealtime() + 10_000L
        var nextTuneMs = 0L
        var nextPcmDiagnosticMs = 0L
        var lastCycleMs = -1L
        var loopGapMaxMs = 0L
        var mixWorkMaxMs = 0L
        var writeMaxMs = 0L
        while (running.get()) {
            val now = SystemClock.elapsedRealtime()
            if (lastCycleMs >= 0) loopGapMaxMs = maxOf(loopGapMaxMs, now - lastCycleMs)
            lastCycleMs = now
            if (now >= nextPcmDiagnosticMs) {
                for ((id, state) in channels) {
                    state.buffer.takeOverflow()?.let { loss ->
                        recordDiagnostic(now, "PCM overflow channel=$id negotiatedSource=${state.rate}Hz/${state.channels}ch " +
                            "at=${loss.atMs}ms reportDelay=${now - loss.atMs}ms " +
                            "incoming=${loss.incomingFrames} depthBefore=${loss.depthBeforeFrames} " +
                            "capacity=${loss.capacityFrames} oldDropped=${loss.oldFramesDropped} " +
                            "incomingDropped=${loss.incomingFramesDropped} target=${loss.targetFrames} frames " +
                            "offeredBefore=${loss.offeredBeforeFrames} consumed=${loss.consumedFrames} " +
                            "compressed=${loss.compressedFrames} overflowBefore=${loss.overflowBeforeFrames} " +
                            "resetDiscarded=${loss.resetDiscardedFrames} " +
                            "lastRead=${loss.lastReadMs}ms started=${loss.started} " +
                            "rebanking=${loss.rebanking} ended=${loss.ended}", warning = true)
                    }
                    val rebanks = state.buffer.rebanks
                    val dropped = state.buffer.droppedFrames
                    val concealed = state.buffer.concealedFrames
                    if (rebanks != state.reportedRebanks || dropped != state.reportedDropped ||
                        concealed != state.reportedConcealed) {
                        recordDiagnostic(now, "PCM channel=$id source=${state.rate}Hz/${state.channels}ch " +
                            "target=${state.buffer.targetFrames()} depth=${state.buffer.depthFrames()} frames " +
                            "arrivalGapMax=${state.buffer.maxArrivalGapMs()}ms pcmGapMax=${state.buffer.maxPcmGapMs()}ms " +
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
            if (!idle && !canRender()) {
                feedSignal.tryAcquire(10, TimeUnit.MILLISECONDS)
                continue
            }
            val warming = device.hasPendingRecovery || channels.values.any { now < it.warmupUntilMs }
            when (lifecycle.update(now, idle, warming, hasReceivedAudio.get(),
                device.bufferFrames * 1000L / OUTPUT_SAMPLE_RATE + 20)) {
                MixerOutputLifecycle.Action.WAIT -> {
                    lastCycleMs = -1L
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
            val cycleChannels = channelSnapshot
            cycleChannels.forEach { it.writePending = true }
            renderCycle(now, renderBurst, cycleChannels)
            for ((id, state) in channels) {
                if (state.activeThisCycle && !state.startupReported && state.firstPcmMs >= 0) {
                    state.startupReported = true
                    val readyMs = SystemClock.elapsedRealtime()
                    val requestWait = if (state.preparedMs >= 0) state.firstPcmMs - state.preparedMs else -1L
                    recordDiagnostic(readyMs, "first PCM channel=$id requestToPcm=${requestWait}ms " +
                        "pcmToRender=${readyMs - state.firstPcmMs}ms target=${state.buffer.targetFrames()} " +
                        "depth=${state.buffer.depthFrames()} outputBudget=${device.bufferFrames} frames")
                }
            }
            val writeStartMs = SystemClock.elapsedRealtime()
            val epochBeforeWrite = device.outputEpoch
            val replayBeforeWrite = device.recoveryProgressSamples
            var replayAnnounced = false
            mixWorkMaxMs = maxOf(mixWorkMaxMs, writeStartMs - now)
            val result = AudioWriteLoop.writeFully(SHORTS_PER_CYCLE, { running.get() },
                { offset, remaining ->
                    if (!device.isParked) outputUnavailableSinceMs = -1L
                    val replay = device.hasPendingRecovery
                    if (replay && !device.isTerminal && !replayAnnounced && !canRender()) {
                        // A recovered prefix is audio activity even when this cycle is silence.
                        // Exhausted output remains parked until Start rearms it.
                        onRecoveryActivity()
                        replayAnnounced = true
                    }
                    val written = if ((!replay && cycleChannels.none { it.activeThisCycle }) || canRender())
                        device.write(outputBuffer, offset, remaining) else {
                        feedSignal.tryAcquire(if (outputUnavailableSinceMs >= 0) 200 else 10,
                            TimeUnit.MILLISECONDS)
                        0
                    }
                    if (written == -6 && device.isTerminal) {
                        if (device.isParked && outputUnavailableSinceMs < 0)
                            outputUnavailableSinceMs = SystemClock.elapsedRealtime()
                        // Start may have arrived inside the final failed open. That failure
                        // cannot mark its already-rearmed successor as permanently unavailable.
                        if (!device.isParked) outputUnavailableSinceMs = -1L
                        // Retain this mixed block and all channel banks across terminal failure.
                        // A new Start can rearm the owner without a packet-driven sink replacement.
                        feedSignal.tryAcquire(200, TimeUnit.MILLISECONDS)
                        0
                    } else written
                },
                {}, { Thread.sleep(1) })
            writeMaxMs = maxOf(writeMaxMs, SystemClock.elapsedRealtime() - writeStartMs)
            check(result >= 0) { "${device.name} write failed: $result" }
            val writeCompletedMs = SystemClock.elapsedRealtime()
            outputDrainMs = device.bufferFrames * 1000L / OUTPUT_SAMPLE_RATE + 20
            recoveryPending = device.hasPendingRecovery
            val recovered = epochBeforeWrite != device.outputEpoch ||
                replayBeforeWrite != device.recoveryProgressSamples
            if (recovered) lifecycle.outputProgress(writeCompletedMs)
            cycleChannels.forEach {
                if (it.activeThisCycle || recovered) it.lastWriteCompletedMs = writeCompletedMs
                it.writePending = false
            }
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
                if (bufferTuner.update(device, target,
                        force = target != requestedFrames || xruns != previousOutputXruns)) {
                    requestedFrames = target
                    recordDiagnostic(now, "output ${device.name} requested=$target effective=${device.bufferFrames} " +
                        "staging=${device.stagingBufferFrames} burst=$burst minimum=$minimum " +
                        "stableFloor=${policy.stableFloorFrames} maximum=${policy.maximumFrames} " +
                        "xruns=${if (device.underrunsSupported) xruns.toString() else "N/A"} " +
                        "producerUnderruns=${device.producerUnderruns}",
                        warning = xruns > previousOutputXruns)
                }
                renderBurst = renderBurstFrames(device)
                previousOutputXruns = xruns
                nextTuneMs = now + 100
            }
            if (now >= nextReportMs) {
                AudioDiagnostics.report(now, "AudioMixer: id=$diagnosticId ${device.name} effective=${device.bufferFrames} frames, " +
                    "staging=${device.stagingBufferFrames}, xruns=${if (device.underrunsSupported) device.underruns.toString() else "N/A"}, " +
                    "producerUnderruns=${device.producerUnderruns}, burst=${device.burstFrames}, " +
                    "requested=$requestedFrames, stableFloor=${policy.stableFloorFrames}, maximum=${policy.maximumFrames}, " +
                    "loopGapMax=${loopGapMaxMs}ms mixWorkMax=${mixWorkMaxMs}ms writeMax=${writeMaxMs}ms", remember = false)
                for ((id, state) in channels) {
                    AudioDiagnostics.report(now, "AudioMixer: id=$diagnosticId channel=$id target=${state.buffer.targetFrames() * 1000L / OUTPUT_SAMPLE_RATE}ms " +
                        "depth=${state.buffer.depthFrames() * 1000L / OUTPUT_SAMPLE_RATE}ms " +
                        "arrivalGapMax=${state.buffer.maxArrivalGapMs()}ms pcmGapMax=${state.buffer.maxPcmGapMs()}ms " +
                        "concealedFrames=${state.buffer.concealedFrames} staleFrames=${state.buffer.droppedFrames} " +
                        "compressedFrames=${state.buffer.compressedFrames}", remember = false)
                }
                loopGapMaxMs = 0; mixWorkMaxMs = 0; writeMaxMs = 0
                nextReportMs = now + 10_000L
            }
        }
    }

    private fun recordDiagnostic(nowMs: Long, message: String, warning: Boolean = false) {
        val line = "AudioMixer: id=$diagnosticId $message"
        AudioDiagnostics.report(nowMs, line, warning)
    }
}
