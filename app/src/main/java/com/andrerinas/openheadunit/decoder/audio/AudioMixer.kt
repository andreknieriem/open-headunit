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
    private val onRecoveryActivity: () -> Unit = {},
    private val matchingMode: AudioTimeCorrectionMode = AudioTimeCorrectionMode.OHU
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
                           val onOutputStopped: () -> Unit, matchingMode: AudioTimeCorrectionMode = AudioTimeCorrectionMode.OHU,
                           onReady: () -> Unit = {},
                           createEngine: () -> TimeStretchEngine = { NativeTimeStretchEngine(matchingMode) }) {
        val buffer = AdaptivePcmBuffer(latencyMultiplier = multiplier, isMediaSink = isMediaSink,
            nativeTimeCorrection = matchingMode != AudioTimeCorrectionMode.OHU)
        val timeStretch = if (matchingMode == AudioTimeCorrectionMode.OHU) null else
            TimeStretchWorker(matchingMode, buffer, SystemClock::elapsedRealtime, onReady,
                { AppLog.w("Audio time correction: $it") }, createEngine)
        // The worker publishes idle only after its last bank write. Read it first:
        // reading an empty bank first could miss a tail delivered between the two reads.
        fun isIdle() = timeStretch?.isIdle() != false && buffer.isIdle()
        val format = PcmInputFormat(rate, channels)
        val converter = PcmConverter()
        @Volatile var gain = 1f
        @Volatile var warmupUntilMs = 0L
        @Volatile var preparedMs = -1L
        @Volatile var firstPcmMs = -1L
        @Volatile var writePending = false
        @Volatile var lastWriteCompletedMs = -1L
        var lastOutputFrame = -1L
        var lastOutputEpoch = -1L
        var lastRealEndFrame = -1L
        var lastRealEpoch = -1L
        @Volatile var outputConsumed = true
        var activeThisCycle = false
        var timeStretchFailureReported = false
        var startupReported = false
        var reportedRebanks = 0L
        var reportedRebankEntries = 0L
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
    private var bankRecoveryAnnounced = false // Output-owner thread only.
    @Volatile private var output: RecoveringPcmOutput? = null
    @Volatile private var outputDrainMs = 40L
    @Volatile private var recoveryPending = false
    @Volatile private var outputUnavailableSinceMs = -1L
    private val mixBuffer = IntArray(SHORTS_PER_CYCLE)
    private val mediaBuffer = IntArray(SHORTS_PER_CYCLE)
    private val channelBuffer = ShortArray(SHORTS_PER_CYCLE)
    private val outputBuffer = ShortArray(SHORTS_PER_CYCLE)
    private val renderDeadline = PcmRenderDeadline()
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
                    channels.values.forEach { it.timeStretch?.close() }
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
        channels.values.forEach { it.timeStretch?.close() }
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
                        onOutputStopped: () -> Unit = {}, allowNativeTimeCorrection: Boolean = true): Channel {
        val effectiveMode = matchingMode.forSink(isMediaSink, allowNativeTimeCorrection) {
            NativeTimeStretch.supported
        }
        if (effectiveMode != matchingMode)
            AppLog.i("Audio time correction: selected=${matchingMode.preferenceValue}, effective=ohu (non-media, AAC or unavailable native engine)")
        return Channel(sampleRate, channelCount, latencyMultiplier, isMediaSink, onOutputStopped, effectiveMode,
            { if (feedSignal.availablePermits() == 0) feedSignal.release() }).also {
            channels[channel] = it
            channelSnapshot = channels.values.toTypedArray()
        }
    }
    @Synchronized internal fun unregisterChannel(channel: Int, state: Channel) {
        state.timeStretch?.close()
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
        synchronized(state) {
            if (state.timeStretch != null) state.timeStretch.finish() else state.buffer.finish()
        }
        if (feedSignal.availablePermits() == 0) feedSignal.release()
    }
    fun isRunning(): Boolean = running.get()
    /** Estimated device drain, after actual write completion, never just bank consumption. */
    internal fun isQuiescent(state: Channel, nowMs: Long): Boolean =
        // Terminal recovery parks retained audio. It must not hold the car radio's focus forever.
        (output?.isParked == true && outputUnavailableSinceMs >= 0 &&
            nowMs - outputUnavailableSinceMs >= 1000) ||
        isOutputDrained(state, nowMs)

    // Parking can release focus temporarily without discarding its registration. Permanent
    // native retirement must use actual drain, so retained PCM can still reacquire focus.
    private fun isOutputDrained(state: Channel, nowMs: Long): Boolean =
        (!state.writePending && state.outputConsumed && state.isIdle() && !recoveryPending &&
            (state.lastWriteCompletedMs < 0 || nowMs - state.lastWriteCompletedMs >= outputDrainMs) &&
            !state.writePending)
    /** Called on the output owner after device-consumption publication, even when lifecycle will WAIT.
     * A native failure before the first output must release its owner; a later failure
     * must keep focus until both the ready bank and already-written device tail drain.
     */
    internal fun retireFailedTimeStretch(state: Channel, nowMs: Long) {
        // Tuning can retain staging after the last recoveryPending snapshot. Only the
        // output owner may check this live state; focus polling keeps using the snapshot.
        if (state.timeStretch?.failure != null && !state.timeStretchFailureReported &&
            isOutputDrained(state, nowMs) && output?.hasPendingRecovery != true) {
            state.timeStretchFailureReported = true
            recordDiagnostic(nowMs, "native time correction failed; ready/device tail drained; " +
                "local audio restart or reconnect required", warning = true)
            try { state.onOutputStopped() }
            catch (e: Exception) { AppLog.e("AudioMixer: native owner retirement failed", e) }
        }
    }

    /** Native tail can reach the bank after temporary focus release, before a mixed
     * block exists. Request focus outside writeFully too, but only when output cooldown
     * permits a retry. Keep one request in flight while the main thread acquires focus.
     */
    internal fun requestBankedRecovery(device: RecoveringPcmOutput, states: Array<Channel> = channelSnapshot) {
        if (device.isParked) bankRecoveryAnnounced = false
        if (!bankRecoveryAnnounced && device.retryReady && states.any { it.buffer.depthFrames() > 0 }) {
            bankRecoveryAnnounced = true
            onRecoveryActivity()
        }
    }

    fun depthFramesFor(channel: Int): Int = channels[channel]?.buffer?.depthFrames() ?: 0

    /** Called at transport ingress, before codec and playback scheduling can distort timing. */
    internal fun noteArrival(state: Channel, inputFrames: Int, nowMs: Long) {
        // Native PCM queues carry the original arrival timestamp. Advance bank segments
        // on their ordered worker, after the previous engine tail, not at transport ingress.
        if (state.timeStretch == null)
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
            if (state.timeStretch != null) state.timeStretch.offer(state.converter.samples, shorts, now)
            else state.buffer.write(state.converter.samples, shorts, now)
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
        if (duckMediaForSpeech) mediaBuffer.fill(0)
        var activeChannels = 0
        var speechActive = false
        var boosted = false
        for (state in cycleChannels) {
            state.timeStretch?.let { state.buffer.observeTimeCorrection(it.policy, nowMs, outputBurstFrames) }
            val active = state.buffer.render(channelBuffer, nowMs, outputBurstFrames)
            state.timeStretch?.spaceAvailable()
            state.activeThisCycle = active
            val gain = state.gain
            if (active && gain > 0f) activeChannels++
            speechActive = speechActive || (active && !state.isMediaSink && gain > 0f)
            boosted = boosted || (active && gain > 1f)
            // Render even inactive/muted banks so Stop tails, PLC and reserve accounting
            // keep advancing. Only their zero contribution to the sum can be skipped.
            if (!active || gain == 0f) continue
            val destination = if (duckMediaForSpeech && state.isMediaSink) mediaBuffer else mixBuffer
            if (gain == 1f) {
                // PCM16 integers are represented exactly by Float, so unity multiplication
                // and conversion can be replaced with integer addition without rounding.
                for (i in destination.indices) destination[i] += channelBuffer[i].toInt()
            } else {
                for (i in destination.indices) destination[i] += (channelBuffer[i] * gain).toInt()
            }
        }
        ducking.setSpeechActive(duckMediaForSpeech && speechActive)
        limiter.setEnabled(activeChannels > 1 || boosted)
        if (limiter.isSettledDry && (!duckMediaForSpeech || ducking.isSettledUnity)) {
            if (duckMediaForSpeech) {
                for (i in mixBuffer.indices) {
                    // Preserve the unity Float round trip: large media sums need not be
                    // exactly representable, even though individual PCM16 samples are.
                    val sample = mixBuffer[i] + mediaBuffer[i].toFloat().toInt()
                    outputBuffer[i] = sample.coerceIn(-32768, 32767).toShort()
                }
            } else {
                for (i in mixBuffer.indices)
                    outputBuffer[i] = mixBuffer[i].coerceIn(-32768, 32767).toShort()
            }
            return outputBuffer
        }
        var mediaGain = 1f
        for (i in mixBuffer.indices) {
            if (i % OUTPUT_CHANNELS == 0) {
                if (duckMediaForSpeech) mediaGain = ducking.nextGain()
                // Keep advancing the limiter through its release after another channel
                // ends. A currently solitary contributor does not imply a dry envelope yet.
                limiter.advance()
            }
            if (duckMediaForSpeech) mixBuffer[i] += (mediaBuffer[i] * mediaGain).toInt()
            // Moving a normal media sink through this renderer must not compress its music.
            outputBuffer[i] = limiter.process(mixBuffer[i])
        }
        return outputBuffer
    }

    internal fun renderWaitMs(device: PcmOutput, nowMs: Long, renderWorkMs: Long,
                              cycleChannels: Array<Channel> = channelSnapshot): Long {
        val epoch = device.outputEpoch
        var realEnd = -1L
        for (state in cycleChannels) {
            if (state.lastRealEpoch == epoch && state.buffer.wouldConceal())
                realEnd = maxOf(realEnd, state.lastRealEndFrame)
        }
        // Read/observe the clock even without a shortage, so a cold track is never
        // mistaken for a running one. This runs only on the output owner thread.
        val played = device.playedFrames()
        return renderDeadline.waitMs(nowMs, epoch, device.writtenFrames, played,
            realEnd, epoch, device.bufferFrames, device.hasPendingRecovery, renderWorkMs)
    }

    internal fun recordRealOutput(device: PcmOutput, epochBefore: Long, writtenBefore: Long,
                                  recovered: Boolean, cycleChannels: Array<Channel>) {
        // Replayed staging PCM and device replacement break this block's frame mapping.
        // PLC and ending ramps belong to focus/drain accounting, but not to real reserve.
        val written = device.writtenFrames
        val known = !recovered && epochBefore == device.outputEpoch && writtenBefore >= 0 &&
            written - writtenBefore == SAMPLES_PER_CYCLE.toLong()
        for (state in cycleChannels) {
            if (!known) {
                state.lastRealEndFrame = -1
                state.lastRealEpoch = -1
            } else if (state.buffer.lastRenderRealFrames > 0) {
                state.lastRealEndFrame = writtenBefore + state.buffer.lastRenderRealFrames
                state.lastRealEpoch = epochBefore
            }
        }
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
        var previousMixWorkMaxMs = 0L
        var writeMaxMs = 0L
        var renderWaits = 0L
        while (running.get()) {
            val now = SystemClock.elapsedRealtime()
            if (lastCycleMs >= 0) loopGapMaxMs = maxOf(loopGapMaxMs, now - lastCycleMs)
            lastCycleMs = now
            if (now >= nextPcmDiagnosticMs) {
                for ((id, state) in channels) {
                    state.buffer.takeStarvation()?.let { event ->
                        recordDiagnostic(now, "PCM starvation channel=$id at=${event.atMs}ms " +
                            "reportDelay=${(now - event.atMs).coerceAtLeast(0)}ms " +
                            "available=${event.availableFrames} target=${event.targetFrames} frames " +
                            "lastArrivalAgeMs=${event.lastArrivalAgeMs ?: "unknown"} " +
                            "lastPcmAgeMs=${event.lastPcmAgeMs ?: "unknown"} " +
                            "outputBudget=${event.outputBudgetFrames} frames", warning = true)
                    }
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
                    state.buffer.takeReserveWindow(now)?.let { reserve ->
                        recordDiagnostic(now, "PCM reserve channel=$id window=${reserve.windowMs}ms " +
                            "boundary=${if (reserve.stopped) "Stop" else "periodic"} skippedWindows=${reserve.skippedWindows} " +
                            "renderCycles=${reserve.cycles} postRenderMin=${reserve.minimumFrames} " +
                            "postRenderMax=${reserve.maximumFrames} " +
                            "${if (reserve.stopped) "depthAtStop" else "depth"}=${reserve.depthFrames} " +
                            "target=${reserve.targetFrames} frames " +
                            "underrunEventsTotal=${reserve.underrunEvents} rebankEntriesTotal=${reserve.rebankEntries} " +
                            "concealedFramesTotal=${reserve.concealedFrames} compressedFramesTotal=${reserve.compressedFrames}", remember = false)
                    }
                    val rebanks = state.buffer.rebanks
                    val rebankEntries = state.buffer.rebankEntries
                    val dropped = state.buffer.droppedFrames
                    val concealed = state.buffer.concealedFrames
                    if (rebanks != state.reportedRebanks || rebankEntries != state.reportedRebankEntries || dropped != state.reportedDropped ||
                        concealed != state.reportedConcealed) {
                        recordDiagnostic(now, "PCM channel=$id source=${state.rate}Hz/${state.channels}ch " +
                            "target=${state.buffer.targetFrames()} depth=${state.buffer.depthFrames()} frames " +
                            "arrivalGapMax=${state.buffer.maxArrivalGapMs()}ms pcmGapMax=${state.buffer.maxPcmGapMs()}ms " +
                            "rebanksDelta=${rebanks - state.reportedRebanks} " +
                            "rebankEntriesDelta=${rebankEntries - state.reportedRebankEntries} " +
                            "concealedDelta=${concealed - state.reportedConcealed} " +
                            "droppedDelta=${dropped - state.reportedDropped}", warning = true)
                        state.reportedRebanks = rebanks
                        state.reportedRebankEntries = rebankEntries
                        state.reportedDropped = dropped
                        state.reportedConcealed = concealed
                    }
                }
                nextPcmDiagnosticMs = now + 1000
            }
            // Playback-head reads belong to the output owner. Focus polling reads only this
            // snapshot, never the device while it may be replaced or released.
            val played = device.playedFrames()
            channels.values.forEach {
                if (it.lastOutputFrame >= 0) {
                    // Reopening during start or buffer tuning also changes the frame clock.
                    // An old device's marker cannot hold the replacement's focus indefinitely.
                    it.outputConsumed = device.writtenFrames < 0 ||
                        it.lastOutputEpoch != device.outputEpoch || played >= it.lastOutputFrame
                }
            }
            channels.values.forEach { retireFailedTimeStretch(it, now) }
            val idle = channels.values.all { it.isIdle() }
            if (!idle && !canRender()) {
                requestBankedRecovery(device)
                feedSignal.tryAcquire(10, TimeUnit.MILLISECONDS)
                continue
            }
            bankRecoveryAnnounced = false
            val warming = device.hasPendingRecovery || channels.values.any { now < it.warmupUntilMs }
            when (lifecycle.update(now, idle, warming, hasReceivedAudio.get(),
                device.bufferFrames * 1000L / OUTPUT_SAMPLE_RATE + 20,
                channels.values.all { it.outputConsumed })) {
                MixerOutputLifecycle.Action.WAIT -> {
                    lastCycleMs = -1L
                    feedSignal.tryAcquire(200, TimeUnit.MILLISECONDS)
                    continue
                }
                MixerOutputLifecycle.Action.START -> device.start()
                MixerOutputLifecycle.Action.PAUSE -> {
                    device.pause()
                    renderDeadline.reset()
                    // These markers drained; API 19+ pause/flush also resets the frame clock.
                    channels.values.forEach { it.lastOutputFrame = -1L }
                    continue
                }
                MixerOutputLifecycle.Action.WRITE -> Unit
            }
            val cycleChannels = channelSnapshot
            val waitMs = renderWaitMs(device, now, maxOf(mixWorkMaxMs, previousMixWorkMaxMs), cycleChannels)
            if (waitMs > 0) {
                // All channels share the same output clock. Keep their PCM in the banks
                // while queued sound plays; never insert silence in the other channels.
                renderWaits++
                feedSignal.tryAcquire(waitMs, TimeUnit.MILLISECONDS)
                continue
            }
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
            val writtenBeforeWrite = device.writtenFrames
            val replayBeforeWrite = device.recoveryProgressSamples
            var replayAnnounced = false
            var writeRetryDelayMs = 1L
            mixWorkMaxMs = maxOf(mixWorkMaxMs, writeStartMs - now)
            val result = AudioWriteLoop.writeFully(SHORTS_PER_CYCLE, { running.get() },
                write@{ offset, remaining ->
                    writeRetryDelayMs = 1L
                    val replay = device.hasPendingRecovery
                    // No real PCM is in this block or the recovery prefix. Let the normal idle
                    // lifecycle finish instead of reopening a dead route just to write silence.
                    // Later banked/arriving PCM can still use the pending cooldown retry.
                    if (device.isTerminal && !replay && cycleChannels.none { it.activeThisCycle }) {
                        // A missing device supplies no backpressure. Pace even keep-active
                        // mixers, and let arriving PCM wake the owner before the next cycle.
                        feedSignal.tryAcquire(MIX_INTERVAL_MS, TimeUnit.MILLISECONDS)
                        return@write remaining
                    }
                    if (device.isParked) replayAnnounced = false
                    val retainedAudio = replay || (device.retryReady && cycleChannels.any { it.activeThisCycle })
                    if (retainedAudio && !device.isParked && !replayAnnounced && !canRender()) {
                        // A recovered prefix is audio activity even when this cycle is silence.
                        // A cooldown retry must reacquire focus even without another Start.
                        onRecoveryActivity()
                        replayAnnounced = true
                    }
                    val written = if ((!replay && cycleChannels.none { it.activeThisCycle }) || canRender())
                        device.write(outputBuffer, offset, remaining).also {
                            if (it == 0) writeRetryDelayMs = device.writeRetryDelayMs.coerceIn(1L, 4L)
                        } else {
                        feedSignal.tryAcquire(if (outputUnavailableSinceMs >= 0) 200 else 10,
                            TimeUnit.MILLISECONDS)
                        0
                    }
                    if (written > 0) outputUnavailableSinceMs = -1L
                    if (written == -6 && device.isTerminal) {
                        replayAnnounced = false
                        if (device.isParked && outputUnavailableSinceMs < 0)
                            outputUnavailableSinceMs = SystemClock.elapsedRealtime()
                        // Keep outage age across failed retry bursts; otherwise each attempt
                        // postpones focus release despite making no playback progress.
                        // Retain this mixed block and all channel banks across terminal failure.
                        // Cooldown or a new Start rearms the owner without replacing the sink.
                        feedSignal.tryAcquire(200, TimeUnit.MILLISECONDS)
                        0
                    } else written
                },
                {}, { Thread.sleep(writeRetryDelayMs) })
            writeMaxMs = maxOf(writeMaxMs, SystemClock.elapsedRealtime() - writeStartMs)
            check(result >= 0) { "${device.name} write failed: $result" }
            val writeCompletedMs = SystemClock.elapsedRealtime()
            outputDrainMs = device.bufferFrames * 1000L / OUTPUT_SAMPLE_RATE + 20
            recoveryPending = device.hasPendingRecovery
            val recovered = epochBeforeWrite != device.outputEpoch ||
                replayBeforeWrite != device.recoveryProgressSamples
            recordRealOutput(device, epochBeforeWrite, writtenBeforeWrite, recovered, cycleChannels)
            if (recovered) lifecycle.outputProgress(writeCompletedMs)
            cycleChannels.forEach {
                if (it.activeThisCycle || recovered) {
                    it.lastWriteCompletedMs = writeCompletedMs
                    it.lastOutputFrame = device.writtenFrames
                    it.lastOutputEpoch = device.outputEpoch
                    it.outputConsumed = it.lastOutputFrame < 0
                }
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
                    "loopGapMax=${loopGapMaxMs}ms mixWorkMax=${mixWorkMaxMs}ms writeMax=${writeMaxMs}ms " +
                    "renderWaits=$renderWaits", remember = false)
                for ((id, state) in channels) {
                    state.timeStretch?.let { native ->
                        AudioDiagnostics.report(now, "AudioMixer: id=$diagnosticId channel=$id native " +
                            "accepted=${native.acceptedFrames} submitted=${native.submittedFrames} " +
                            "produced=${native.producedFrames} delivered=${native.deliveredFrames} " +
                            "rejected=${native.rejectedFrames} failure=${native.failure}", remember = false)
                    }
                    AudioDiagnostics.report(now, "AudioMixer: id=$diagnosticId channel=$id target=${state.buffer.targetFrames() * 1000L / OUTPUT_SAMPLE_RATE}ms " +
                        "depth=${state.buffer.depthFrames() * 1000L / OUTPUT_SAMPLE_RATE}ms " +
                        "arrivalGapMax=${state.buffer.maxArrivalGapMs()}ms pcmGapMax=${state.buffer.maxPcmGapMs()}ms " +
                        "concealedFrames=${state.buffer.concealedFrames} staleFrames=${state.buffer.droppedFrames} " +
                        "compressedFrames=${state.buffer.compressedFrames}", remember = false)
                }
                // Keep the previous reporting window's render cost across the boundary;
                // a slow cycle must not lose its scheduling allowance when counters roll.
                previousMixWorkMaxMs = mixWorkMaxMs
                loopGapMaxMs = 0; mixWorkMaxMs = 0; writeMaxMs = 0; renderWaits = 0
                nextReportMs = now + 10_000L
            }
        }
    }

    private fun recordDiagnostic(nowMs: Long, message: String, warning: Boolean = false, remember: Boolean = true) {
        val line = "AudioMixer: id=$diagnosticId $message"
        AudioDiagnostics.report(nowMs, line, warning, remember)
    }
}
