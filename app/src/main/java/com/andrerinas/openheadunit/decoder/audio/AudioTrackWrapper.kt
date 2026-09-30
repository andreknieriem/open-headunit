package com.andrerinas.openheadunit.decoder.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Owns one negotiated PCM16/AAC sink. PCM feeds the bank without a playback thread;
 * only AAC owns a decode thread. Device I/O and DSP belong to AudioMixer/PcmOutput. */
class AudioTrackWrapper(
    sampleRateInHz: Int,
    bitDepth: Int,
    channelCount: Int,
    private val isAac: Boolean = false,
    gain: Float,
    audioLatencyMultiplier: Int = AudioJitterBufferPolicy.DEFAULT_MULTIPLIER,
    private val audioQueueCapacity: Int = 0,
    private val mixer: AudioMixer,
    private val channelId: Int = -1,
    isMediaSink: Boolean = false,
    private val ownsMixer: Boolean = false,
    private val onPcmActivity: () -> Unit = {},
    internal val playbackOwner: Any = Any(),
    private val onOwnerRetired: () -> Unit = {},
    private val isAdts: Boolean = false
) {
    init {
        require(bitDepth == 16) { "The advertised audio sinks require PCM16" }
        require(!isAdts || isAac) { "ADTS framing requires AAC" }
    }

    private data class AudioChunk(val data: ByteArray, val size: Int,
                                  val codecConfig: AacCodecConfig,
                                  val enqueuedMs: Long = SystemClock.elapsedRealtime())
    private data class CodecInput(val codec: MediaCodec, val index: Int)
    companion object {
        private const val AUDIO_BUFFER_POOL_LIMIT = 16
        private const val MIN_POOLED_AUDIO_BUFFER_SIZE = 4096
    }

    private val sampleRate = sampleRateInHz
    private val trackChannelCount = channelCount
    private val bytesPerFrame = channelCount * 2
    private val mixerChannel = mixer.registerChannel(channelId, sampleRate, channelCount,
        audioLatencyMultiplier, isMediaSink, onOwnerRetired).also { it.gain = gain }
    private var ownerRetired = false
    @Volatile private var isRunning = true
    @Volatile private var decoder: MediaCodec? = null
    private var codecHandlerThread: HandlerThread? = null
    private val codecOutputLock = Any()
    private val freeInputBuffers = LinkedBlockingQueue<CodecInput>()
    private var outputFormatState = AacOutputFormat(sampleRate, channelCount, true)
    @Volatile private var decoderFailed = false
    private val aacRecovery = AacDecoderRecovery()
    private val progressWatchdog = AacProgressWatchdog()
    @Volatile private var inputClosed = false
    @Volatile private var stopReceivedMs = -1L
    @Volatile private var handoffInFlight = false
    @Volatile private var lastPcmActivityMs = -1L
    @Volatile private var submitInFlight = false
    @Volatile private var lastOutputCopiedMs = -1L
    @Volatile private var lastOutputPtsUs = -1L
    private var aacFramesQueued = 0L
    private val syncBufferInfo = MediaCodec.BufferInfo()
    private var syncInputBuffers: Array<java.nio.ByteBuffer>? = null
    private var syncOutputBuffers: Array<java.nio.ByteBuffer>? = null
    private var maximumQueueWaitMs = 0L
    private var maximumDecodeCallMs = 0L
    private var nextPipelineReportMs = 0L
    @Volatile private var incomingAacConfig = if (isAac) AacCodecConfig.default(sampleRate, channelCount) else null
    private var decoderConfig = incomingAacConfig
    private val dataQueue = LinkedBlockingQueue<AudioChunk>()
    private val inputSignal = java.util.concurrent.Semaphore(0)
    private val audioBufferPool = LinkedBlockingQueue<ByteArray>()
    // CSD validation restricts AAC-LC to 1024-frame access units. Queue limits describe encoded
    // input duration, not the number or size of decoded output callbacks.
    private val chunkDurationMs = SinkQueueOverflowPolicy.chunkDurationMs(1024, sampleRate)
    private val queueCapacityChunks = SinkQueueOverflowPolicy.capacityChunks(audioQueueCapacity, chunkDurationMs)
    @Volatile private var droppedChunksTotal = 0L
    private var saidUnboundedBacklog = false
    private val health = AudioSinkHealthMonitor(channelName(), AudioMixer.OUTPUT_SAMPLE_RATE)
    private val decodeThread: Thread?

    init {
        if (isAac && !initDecoder(sampleRate, channelCount)) {
            decoderFailed = true
            aacRecovery.request()
        }
        decodeThread = if (isAac) Thread(::runDecoder, "AacDecode-$channelId").also { it.start() } else null
    }

    fun builtCodec(): AudioSinkCodec = when {
        !isAac -> AudioSinkCodec.PCM
        isAdts -> AudioSinkCodec.AAC_LC_ADTS
        else -> AudioSinkCodec.AAC_LC
    }
    fun isPlaybackHealthy(): Boolean = isRunning && mixer.isRunning()
    fun setGain(gain: Float) { mixerChannel.gain = gain }

    private fun initDecoder(sampleRate: Int, channels: Int): Boolean {
        try {
            val mime = "audio/mp4a-latm"
            val format = MediaFormat.createAudioFormat(mime, sampleRate, channels)
            if (isAdts) format.setInteger(MediaFormat.KEY_IS_ADTS, 1)
            format.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            // CSD for RAW AAC-LC (AudioSpecificConfig)
            val csd = checkNotNull(decoderConfig).copyBytes()
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))

            val formatState = AacOutputFormat(sampleRate, channels, true)
            outputFormatState = formatState
            decoder = MediaCodec.createDecoderByType(mime)

            // API 21/22 cannot select a callback handler: the default looper may belong to
            // the blocking transport reader. Use the synchronous pump until API 23.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Use a HandlerThread for the codec callback but set its priority to AUDIO
                // to prevent it from being starved by the video decoder.
                codecHandlerThread = object : HandlerThread("AacCodecThread") {
                    override fun onLooperPrepared() {
                        requestAudioThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    }
                }
                codecHandlerThread!!.start()

                val callback = object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        if (isRunning && decoder === codec) freeInputBuffers.offer(CodecInput(codec, index))
                    }

                    override fun onOutputBufferAvailable(
                        codec: MediaCodec,
                        index: Int,
                        info: MediaCodec.BufferInfo
                    ) {
                        // BufferInfo belongs to MediaCodec and may be reused after release.
                        val size = info.size
                        val flags = info.flags
                        val ptsUs = info.presentationTimeUs
                        val offset = info.offset
                        var pcm: ByteArray? = null
                        val pcmFormat = formatState.format
                        try {
                            if (!isRunning || decoder !== codec || pcmFormat == null ||
                                size <= 0 || flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
                            val outputBuffer = codec.getOutputBuffer(index) ?: return
                            pcm = obtainAudioBuffer(size)
                            outputBuffer.position(offset)
                            outputBuffer.get(pcm, 0, size)
                            noteOutputCopied(codec, ptsUs)
                        } catch (e: Exception) {
                            pcm?.let { recycleAudioBuffer(it) }
                            pcm = null
                            AppLog.e("Error copying AAC output", e)
                        } finally {
                            try {
                                // The codec regains its slot before PCM conversion or output work.
                                codec.releaseOutputBuffer(index, false)
                            } catch (e: Exception) {
                                if (isRunning) AppLog.e("Error releasing AAC output", e)
                            }
                        }
                        val chunk = pcm ?: return
                        val capturedFormat = pcmFormat ?: return
                        try {
                            synchronized(codecOutputLock) {
                                if (isRunning && decoder === codec && formatState.acceptsOutput) {
                                    writeToMixer(chunk, size, capturedFormat)
                                }
                            }
                        } catch (e: Exception) {
                            AppLog.e("Error feeding decoded AAC to mixer", e)
                        } finally {
                            recycleAudioBuffer(chunk)
                        }
                    }

                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                        synchronized(codecOutputLock) {
                            if (decoder !== codec) return
                            AppLog.e("AAC Codec Error", e)
                            formatState.update(-1, -1, -1)
                            aacRecovery.request()
                        }
                    }

                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                        synchronized(codecOutputLock) {
                            if (decoder === codec) verifyOutputFormat(format, formatState)
                        }
                    }
                }

                val handler = Handler(codecHandlerThread!!.looper)
                decoder!!.setCallback(callback, handler)
            }

            decoder?.configure(format, null, null, 0)
            decoder?.start()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                syncInputBuffers = decoder!!.inputBuffers
                @Suppress("DEPRECATION")
                syncOutputBuffers = decoder!!.outputBuffers
            }
            AppLog.i("AAC Decoder started for $sampleRate Hz, $channels channels " +
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) "(Async)" else "(Sync)")
            return true
        } catch (e: Exception) {
            AppLog.e("Failed to init AAC decoder; retaining unsubmitted input for recovery", e)
            try { decoder?.release() } catch (ignored: Exception) { }
            decoder = null
            return false
        }
    }

    private fun verifyOutputFormat(format: MediaFormat, state: AacOutputFormat) {
        val outRate = try { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (_: Exception) { -1 }
        val outChannels = try { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (_: Exception) { -1 }
        // Older codecs omit this key and produce PCM16. A present but unreadable key is invalid.
        val encoding = if (!format.containsKey("pcm-encoding")) null else
            try { format.getInteger("pcm-encoding") } catch (_: Exception) { -1 }
        if (!state.update(outRate, outChannels, encoding)) {
            AppLog.w("AAC output rejected: $outRate Hz x $outChannels encoding=$encoding; " +
                "requires supported PCM16/float mono/stereo")
            aacRecovery.request()
        } else {
            AppLog.i("AAC Output Format Changed: $format")
        }
    }

    /** One rebuild, from the run loop: release() must never run on the codec's own callback thread. */
    private fun rebuildDecoder() {
        when (aacRecovery.next(SystemClock.elapsedRealtime())) {
            AacDecoderRecovery.Action.NONE -> return
            AacDecoderRecovery.Action.WAIT -> { decoderFailed = true; return }
            AacDecoderRecovery.Action.STOP -> {
                decoderFailed = true
                AppLog.w("AudioTrackWrapper: AAC retry budget exhausted; waiting for a new playback request")
                return
            }
            AacDecoderRecovery.Action.REBUILD -> Unit
        }
        releaseDecoder()
        quitCodecThread()
        AppLog.w("AudioTrackWrapper: rebuilding AAC decoder (${aacRecovery.attempts} of ${AacDecoderRecoveryPolicy.MAX_REBUILDS})")
        decoderFailed = !initDecoder(sampleRate, trackChannelCount)
        if (decoderFailed) aacRecovery.request()
    }

    private fun releaseDecoder() {
        val oldDecoder = synchronized(codecOutputLock) {
            decoder.also {
                decoder = null
                progressWatchdog.reset()
                mixer.resetConverter(mixerChannel)
            }
        }
        syncInputBuffers = null
        syncOutputBuffers = null
        try {
            oldDecoder?.stop()
        } catch (ignored: Exception) {
        }
        try {
            oldDecoder?.release()
        } catch (e: Exception) {
            AppLog.e("Error releasing audio decoder", e)
        }
        // Callback messages may still be queued on an old handler. Input slots also carry their
        // owner so one that races this clear can never address a newly created codec.
        freeInputBuffers.clear()
    }

    private fun quitCodecThread() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                codecHandlerThread?.quitSafely()
            } else {
                codecHandlerThread?.quit()
            }
            codecHandlerThread = null
        } catch (e: Exception) {
            AppLog.e("Error quitting codec thread", e)
        }
    }

    /** 1024 samples per AAC-LC access unit: a monotonic stamp for decoders that drop on 0. */
    private fun nextAacPtsUs(): Long {
        val pts = if (sampleRate > 0) aacFramesQueued * 1024L * 1_000_000L / sampleRate else 0L
        aacFramesQueued++
        return pts
    }

    private fun channelName(): String =
        if (channelId < 0) "AUDIO" else com.andrerinas.openheadunit.aap.protocol.Channel.name(channelId)

    private fun writeToMixer(buffer: ByteArray, size: Int, format: PcmInputFormat? = null) {
        feedPcm(buffer, 0, size, format ?: mixerChannel.format)
    }

    private fun feedPcm(buffer: ByteArray, offset: Int, size: Int, format: PcmInputFormat) {
        handoffInFlight = true
        try {
            lastPcmActivityMs = SystemClock.elapsedRealtime()
            onPcmActivity()
            mixer.feed(mixerChannel, buffer, offset, size, format)
        } finally { handoffInFlight = false }
    }

    /** AAC quiet tail is PARKED_UNCONFIRMED, not proven decoder EOS. Keep codec and late PCM. */
    internal fun isQuiescent(nowMs: Long): Boolean = inputClosed && !handoffInFlight &&
        (aacRecovery.isExhausted() || synchronized(dataQueue) { !submitInFlight && dataQueue.isEmpty() }) &&
        (!isAac || nowMs - maxOf(stopReceivedMs, lastPcmActivityMs, lastOutputCopiedMs) >= 1000) &&
        mixer.isQuiescent(mixerChannel, nowMs) && !handoffInFlight && inputClosed

    private fun noteOutputCopied(source: MediaCodec, ptsUs: Long) {
        val copiedMs = SystemClock.elapsedRealtime()
        synchronized(codecOutputLock) {
            // Retirement/reset can occur while a previous callback is copying its output.
            if (!isRunning || decoder !== source) return
            lastOutputCopiedMs = copiedMs
            lastOutputPtsUs = ptsUs
            progressWatchdog.outputCopied()
        }
    }

    private fun runDecoder() {
        requestAudioThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var pending: AudioChunk? = null
        try {
            while (isRunning) {
                if (aacRecovery.pending) rebuildDecoder()
                val synchronous = Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                if (synchronous && !decoderFailed) decoder?.let { drainSyncOutput(it) }
                synchronized(dataQueue) {
                    if (pending == null) pending = dataQueue.poll()
                    submitInFlight = pending != null
                }
                val now = SystemClock.elapsedRealtime()
                sampleHealth(now)
                if (!decoderFailed && !aacRecovery.pending && !aacRecovery.isExhausted() &&
                    progressWatchdog.stalled(now, pending != null || dataQueue.isNotEmpty(), inputClosed)) {
                    AudioDiagnostics.report(now, "AAC no progress channel=$channelId lastPtsUs=$lastOutputPtsUs; rebuilding", warning = true)
                    aacRecovery.request()
                    rebuildDecoder()
                }
                val chunk = pending
                if (chunk == null) {
                    inputSignal.tryAcquire(if (synchronous) 10 else 50, TimeUnit.MILLISECONDS)
                    continue
                }
                val startedMs = SystemClock.elapsedRealtime()
                maximumQueueWaitMs = maxOf(maximumQueueWaitMs, startedMs - chunk.enqueuedMs)
                if (!chunk.codecConfig.sameAs(checkNotNull(decoderConfig))) {
                    releaseDecoder()
                    quitCodecThread()
                    decoderConfig = chunk.codecConfig
                    aacRecovery.reset()
                    decoderFailed = !initDecoder(sampleRate, trackChannelCount)
                    if (decoderFailed) aacRecovery.request()
                }
                if (aacRecovery.pending) rebuildDecoder()
                if (decoder == null || decoderFailed) {
                    // Keep the unsubmitted AU through cooldown/terminal state. Stop is not abort.
                    Thread.sleep(50)
                    continue
                }
                val submitted = if (synchronous) decodeSync(chunk.data, chunk.size)
                    else queueInput(chunk.data, chunk.size)
                if (submitted) {
                    recycleAudioBuffer(chunk.data)
                    pending = null
                    submitInFlight = false
                }
                val finishedMs = SystemClock.elapsedRealtime()
                maximumDecodeCallMs = maxOf(maximumDecodeCallMs, finishedMs - startedMs)
                if (finishedMs >= nextPipelineReportMs) {
                    AudioDiagnostics.report(finishedMs, "AAC pipeline channel=$channelId " +
                        "queueWaitMax=${maximumQueueWaitMs}ms decodeCallMax=${maximumDecodeCallMs}ms " +
                        "queued=${dataQueue.size} droppedInput=$droppedChunksTotal")
                    maximumQueueWaitMs = 0
                    maximumDecodeCallMs = 0
                    nextPipelineReportMs = finishedMs + 10_000
                }
            }
        } catch (_: InterruptedException) {
            // Only teardown aborts pending input, not protocol Stop.
        } catch (e: Exception) {
            AppLog.e("AAC decode loop stopped", e)
        } finally {
            pending?.let { recycleAudioBuffer(it.data) }
            submitInFlight = false
            isRunning = false
            cleanup()
        }
    }

    private fun sampleHealth(nowMs: Long) {
        if (!saidUnboundedBacklog && queueCapacityChunks == 0 &&
            SinkQueueOverflowPolicy.backlogMs(dataQueue.size, chunkDurationMs) >= SinkQueueOverflowPolicy.UNBOUNDED_BACKLOG_WARN_MS) {
            saidUnboundedBacklog = true
            AppLog.w("AAC channel=$channelId has an unbounded input queue behind playback")
        }
        val state = mixerChannel.buffer
        health.sample(nowMs, AudioSinkHealthMonitor.Sample(
            silentCyclesTotal = state.silentCycles, rebanksTotal = state.rebanks,
            droppedChunksTotal = droppedChunksTotal, droppedFramesTotal = state.droppedFrames,
            depthFrames = state.depthFrames(), queuedChunks = dataQueue.size,
            chunkDurationMs = chunkDurationMs, queueCapacityChunks = queueCapacityChunks,
            targetFrames = state.targetFrames()
        ))?.let { AppLog.i("%s", it) }
    }

    @Suppress("DEPRECATION")
    private fun decodeSync(inputData: ByteArray, size: Int): Boolean {
        try {
            val dec = decoder ?: return false
            progressWatchdog.inputWaiting(SystemClock.elapsedRealtime())
            val inputIndex = AacSyncPump.awaitInput(SystemClock::elapsedRealtime,
                { isRunning && decoder === dec && !aacRecovery.pending },
                { drainSyncOutput(dec) }, { timeoutUs -> dec.dequeueInputBuffer(timeoutUs) })
            if (inputIndex < 0) return false
            val inputBuffer = (syncInputBuffers ?: dec.inputBuffers.also { syncInputBuffers = it })[inputIndex]
            inputBuffer.clear()
            inputBuffer.put(inputData, 0, size)
            progressWatchdog.inputAccepted(SystemClock.elapsedRealtime())
            dec.queueInputBuffer(inputIndex, 0, size, nextAacPtsUs(), 0)
            drainSyncOutput(dec)
            return true
        } catch (e: Exception) {
            AppLog.e("Error in decodeSync", e)
            aacRecovery.request()
            return false
        }
    }

    @Suppress("DEPRECATION")
    private fun drainSyncOutput(dec: MediaCodec) {
        try {
            // Bound one pass so a broken codec cannot monopolize the input thread.
            repeat(64) {
                if (!isRunning || decoder !== dec || aacRecovery.pending) return
                val index = dec.dequeueOutputBuffer(syncBufferInfo, 0)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> verifyOutputFormat(dec.outputFormat, outputFormatState)
                    index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> syncOutputBuffers = dec.outputBuffers
                    index >= 0 -> {
                        val size = syncBufferInfo.size
                        val flags = syncBufferInfo.flags
                        val ptsUs = syncBufferInfo.presentationTimeUs
                        val format = outputFormatState.format
                        var pcm: ByteArray? = null
                        try {
                            try {
                                if (outputFormatState.acceptsOutput && size > 0 &&
                                    flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                    val buffers = syncOutputBuffers ?: dec.outputBuffers.also { syncOutputBuffers = it }
                                    val buffer = buffers[index]
                                    pcm = obtainAudioBuffer(size)
                                    buffer.position(syncBufferInfo.offset)
                                    buffer.get(pcm, 0, size)
                                    noteOutputCopied(dec, ptsUs)
                                }
                            } finally {
                                // Free codec capacity before PCM output can block on a slow HAL.
                                dec.releaseOutputBuffer(index, false)
                            }
                            pcm?.let { writeToMixer(it, size, format) }
                        } finally {
                            pcm?.let { recycleAudioBuffer(it) }
                        }
                    }
                    else -> return
                }
            }
        } catch (e: Exception) {
            AppLog.e("Error draining synchronous AAC output", e)
            aacRecovery.request()
        }
    }

    @Throws(InterruptedException::class)
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.M)
    private fun queueInput(inputData: ByteArray, size: Int): Boolean {
        try {
            val dec = decoder ?: return false
            progressWatchdog.inputWaiting(SystemClock.elapsedRealtime())
            val deadline = SystemClock.elapsedRealtime() + 200
            var input = freeInputBuffers.poll(200, TimeUnit.MILLISECONDS)
            while (input != null && input.codec !== dec) {
                input = freeInputBuffers.poll((deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
            }
            val index = input?.index ?: return false
            val buffer = checkNotNull(dec.getInputBuffer(index))
            buffer.clear()
            buffer.put(inputData, 0, size)
            progressWatchdog.inputAccepted(SystemClock.elapsedRealtime())
            dec.queueInputBuffer(index, 0, size, nextAacPtsUs(), 0)
            return true
        } catch (e: InterruptedException) { throw e }
        catch (e: Exception) {
            AppLog.e("Error queuing AAC input", e)
            aacRecovery.request()
            return false
        }
    }

    fun configureAac(buffer: ByteArray, offset: Int, size: Int) {
        if (!isAac || !isRunning) return
        val config = AacCodecConfig.parse(buffer, offset, size, sampleRate, trackChannelCount)
        if (config == null) {
            AppLog.w("AudioTrackWrapper: ignoring invalid or incompatible AAC configuration on ${channelName()}")
            return
        }
        if (!config.sameAs(checkNotNull(incomingAacConfig))) incomingAacConfig = config
    }

    fun write(buffer: ByteArray, offset: Int, size: Int) {
        if (!isRunning) return
        mixer.noteArrival(mixerChannel, if (isAac) 1024 else size / bytesPerFrame, SystemClock.elapsedRealtime())
        if (!isAac) {
            // The transport can reuse its decrypted array immediately after this bounded copy.
            feedPcm(buffer, offset, size, mixerChannel.format)
            return
        }
        val data = obtainAudioBuffer(size)
        System.arraycopy(buffer, offset, data, 0, size)
        shedStaleChunks()
        dataQueue.offer(AudioChunk(data, size, checkNotNull(incomingAacConfig)))
        if (inputSignal.availablePermits() == 0) inputSignal.release()
    }

    private fun shedStaleChunks() {
        val capacity = queueCapacityChunks
        if (capacity <= 0) return
        while (dataQueue.size >= capacity) {
            val stale = dataQueue.poll() ?: return
            recycleAudioBuffer(stale.data)
            droppedChunksTotal++
            if (droppedChunksTotal == 1L) {
                AppLog.w(
                    "Audio queue is full at $capacity chunks, shedding the oldest to keep the " +
                        "sink current - see dropped= on this channel's sink line for how many"
                )
            }
        }
    }

    fun preparePlayback() {
        if (inputClosed && synchronized(dataQueue) { !submitInFlight && dataQueue.isEmpty() }) {
            progressWatchdog.idleClosedTail()
        }
        inputClosed = false
        if (isAac) aacRecovery.retryIfExhausted()
        mixer.prepareChannel(mixerChannel)
    }

    fun pauseForIdle() {
        stopReceivedMs = SystemClock.elapsedRealtime()
        inputClosed = true
        mixer.finishChannel(mixerChannel)
    }

    @Synchronized fun stopPlayback() {
        retirePlaybackOwner()
        if (!isRunning) return
        isRunning = false
        if (decodeThread != null) decodeThread.interrupt() else cleanup()
    }

    private fun cleanup() {
        retirePlaybackOwner()
        releaseDecoder()
        quitCodecThread()
        drainQueuedAudio()
        audioBufferPool.clear()
        mixer.unregisterChannel(channelId, mixerChannel)
        if (ownsMixer) mixer.requestStop()
    }

    @Synchronized private fun retirePlaybackOwner() {
        if (ownerRetired) return
        ownerRetired = true
        try { onOwnerRetired() }
        catch (e: Exception) { AppLog.e("AudioTrackWrapper: retirement notification failed", e) }
    }

    private fun obtainAudioBuffer(size: Int): ByteArray {
        while (true) {
            val pooled = audioBufferPool.poll() ?: return ByteArray(maxOf(size, MIN_POOLED_AUDIO_BUFFER_SIZE))
            if (pooled.size >= size) return pooled
        }
    }

    private fun recycleAudioBuffer(buffer: ByteArray) {
        if (audioBufferPool.size < AUDIO_BUFFER_POOL_LIMIT) {
            audioBufferPool.offer(buffer)
        }
    }

    private fun drainQueuedAudio() {
        while (true) {
            val chunk = dataQueue.poll() ?: break
            recycleAudioBuffer(chunk.data)
        }
    }
}
