package com.andrerinas.openheadunit.decoder.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.audiofx.Equalizer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AudioTrackWrapper(
    stream: Int,
    sampleRateInHz: Int,
    bitDepth: Int,
    channelCount: Int,
    private val isAac: Boolean = false,
    gain: Float,
    private val audioLatencyMultiplier: Int = AudioJitterBufferPolicy.DEFAULT_MULTIPLIER,
    private val audioQueueCapacity: Int = 0,
    private val mixer: AudioMixer? = null,
    private val channelId: Int = -1,
    private val attachHwDspEqualizer: Boolean = false,
    private val isMediaSink: Boolean = false,
    private val ownsMixer: Boolean = false
) : Thread() {

    /** The codec this sink was built for. See [AudioSinkSetupPolicy]. */
    fun builtAsAac(): Boolean = isAac

    private data class AudioChunk(
        val data: ByteArray,
        val size: Int
    )

    companion object {
        private const val AUDIO_BUFFER_POOL_LIMIT = 16
        private const val MIN_POOLED_AUDIO_BUFFER_SIZE = 4096

        /**
         * Ceiling on the computed drain wait.
         *
         * The wait is normally a couple of hundred milliseconds. The cap is for when the frame
         * accounting is off, so a bad subtraction costs a beat rather than a hang.
         */
        private const val DRAIN_CAP_MS = 1_000L
    }

    private val audioTrack: AudioTrack?
    private var decoder: MediaCodec? = null
    private var codecHandlerThread: HandlerThread? = null
    private val freeInputBuffers = LinkedBlockingQueue<Int>()
    // Named and raised: this is the thread that feeds the speaker on the AAC path, and it used to
    // run at default priority while the PCM path wrote from URGENT_AUDIO.
    private val writeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            r.run()
        }, "AacAudioWrite")
    }
    // Accept a wireless burst without holding up the callback that also supplies codec inputs.
    private val writeSemaphore = java.util.concurrent.Semaphore(SinkQueueOverflowPolicy.UNACKED_WINDOW_CHUNKS)
    private var droppedAacOutputs = 0L
    private var equalizer: Equalizer? = null

    // AAC decoder health. A failed or dead decoder drops its frames rather than playing the
    // compressed bytes as PCM, and the run loop rebuilds it within AacDecoderRecoveryPolicy's budget.
    @Volatile private var decoderFailed = false
    @Volatile private var rebuildRequested = false
    private var decoderRebuilds = 0
    private var lastRebuildMs = 0L
    private var droppedAacChunks = 0L
    private var aacFramesQueued = 0L
    private val trackChannelCount: Int = channelCount

    // Unbounded underneath and bounded by SinkQueueOverflowPolicy above. The bound is a duration
    // rather than a count, and a chunk's duration is not known until one arrives, which a
    // LinkedBlockingQueue's construction-time capacity cannot express. Holding the bound here is
    // also what lets a full queue shed its stale end instead of refusing the newest audio.
    private val dataQueue = LinkedBlockingQueue<AudioChunk>()

    /** Chunks the queue may hold. Zero is the user asking for no limit. */
    @Volatile private var queueCapacityChunks = SinkQueueOverflowPolicy.capacityChunks(audioQueueCapacity, 0)
    private val audioBufferPool = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var isRunning = true

    @Volatile
    private var currentGain: Float = gain

    fun setVolume(gain: Float) {
        currentGain = gain
        val track = audioTrack
        if (track != null) {
            try {
                val hwGain = gain.coerceAtMost(1.0f)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    track.setVolume(hwGain)
                } else {
                    @Suppress("DEPRECATION")
                    track.setStereoVolume(hwGain, hwGain)
                }
            } catch (e: Exception) {
                AppLog.e("Failed to set volume on AudioTrack", e)
            }
        } else {
            mixer?.setChannelGain(channelId, gain)
        }
    }

    private fun applyGain(buffer: ByteArray, size: Int) {
        if (currentGain <= 1.0f) return
        for (i in 0 until size - 1 step 2) {
            val low = buffer[i].toInt() and 0xFF
            val high = buffer[i + 1].toInt() // High byte handles sign
            val sample = (high shl 8) or low
            val modifiedSample = (sample * currentGain).toInt().coerceIn(-32768, 32767)
            buffer[i] = (modifiedSample and 0xFF).toByte()
            buffer[i + 1] = (modifiedSample shr 8).toByte()
        }
    }

    // Frames written, for the pre-roll trigger and the drain wait. Volatile because the AAC path
    // advances it from the write executor while the playback thread reads it, and a long is not
    // read atomically on 32-bit ABIs. Only one thread per instance increments it, so visibility is
    // the only hazard.
    @Volatile
    private var framesWritten: Long = 0
    private val bytesPerFrame: Int = channelCount * (if (bitDepth == 16) 2 else 1)
    private val sampleRate: Int = sampleRateInHz
    // The normal music format needs only a bounded PCM copy, never a device write or codec wait.
    // Feed its existing mixer bank on ingress instead of queueing it for another runnable thread.
    private val directPcm = mixer != null && !isAac && bitDepth == 16 &&
        sampleRateInHz == AudioMixer.OUTPUT_SAMPLE_RATE && channelCount == AudioMixer.OUTPUT_CHANNELS

    /** Byte size handed to the AudioTrack, recorded by [createAudioTrack] for the pre-roll target. */
    private var trackBufferBytes: Int = 0
    private var minTrackFrames: Int = 1
    private var requestedEffectiveFrames: Int = 0

    // Lifecycle changes and each nonblocking write share this lock. Never hold it while waiting
    // for writable space: an AAC writer must not race a pause/re-bank on the input thread.
    private val playbackLock = Any()

    /** Frames to bank before [android.media.AudioTrack.play]. See [AudioJitterBufferPolicy]. */
    private var prerollTargetFrames: Int = 1

    /** Frames to bank when a playing sink has to bank again. Deeper than the opening bank. */
    private var rebankTargetFrames: Int = 1

    /** What the track can hold at all, which is not what [createAudioTrack] asked for. */
    private var capacityFrames: Int = 0

    /** What the framework will keep filled, which is not the capacity either. */
    private var effectiveFrames: Int = 0

    /** Largest chunk this sink has been sent, which is what the target has to clear. */
    private var observedChunkFrames: Int = 0
    private var saidUnboundedBacklog = false

    // Underrun accounting and the re-bank it drives. A drained track never refills on its own:
    // audio arrives at real time, so without this the first underrun is permanent.
    private var underrunsTotal = 0L
    private var lastUnderrunCount = 0
    private var rebanksTotal = 0L
    private var lastRebankMs = 0L
    private val rebankTimesMs = java.util.ArrayDeque<Long>()
    @Volatile private var rebanking = false
    // The resume deadline, measured from the first audio to arrive after the park rather than from
    // the park: a sink can sit parked for minutes, and a prompt shorter than the target still plays.
    @Volatile private var rebankingSinceMs = 0L
    private var framesAtRebank = 0L
    private var droppedChunksTotal = 0L
    private var saidGivenUp = false
    private var saidFragile = false

    // Steps this sink has deepened its own cushion by. In memory for the sink's life and never
    // written to settings: it describes this link, not this head unit.
    private var deepenSteps = 0

    private val health = AudioSinkHealthMonitor(channelName(), if (mixer != null) AudioMixer.OUTPUT_SAMPLE_RATE else sampleRateInHz)

    /**
     * When audio first reached this track, for the pre-roll deadline.
     *
     * Stamped on the first write rather than at construction: a track precreated at Media Sink
     * Setup can sit idle for minutes, and a deadline measured from then has already expired when
     * the first chunk arrives.
     */
    @Volatile
    private var firstAudioMs: Long = 0L

    private val playbackStarted = AtomicBoolean(false)

    init {
        this.name = "AudioPlaybackThread"
        audioTrack = if (mixer == null) {
            createAudioTrack(stream, sampleRateInHz, bitDepth, channelCount, audioLatencyMultiplier)
        } else {
            null
        }

        if (mixer != null) {
            mixer.registerChannel(channelId, sampleRateInHz, channelCount, audioLatencyMultiplier, isMediaSink)
            mixer.setChannelGain(channelId, gain)
            if (directPcm) AppLog.i("AudioTrackWrapper: ${channelName()} PCM goes directly to the mixer bank")
        } else {
            setVolume(gain)
            audioTrack?.let { track ->
                attachHwDspEqualizerQuietly(track.audioSessionId)
                // Deliberately no play() here: started empty, the track underran on every media
                // start. [AudioPrerollPolicy] decides when enough is banked to begin.
                capacityFrames = trackCapacityFrames(track)
                effectiveFrames = trackEffectiveFrames(track)
                // Left at zero so the target starts at the time-based figure; write() raises it to
                // whatever this sink is really sent. A guessed chunk would delay a prompt for
                // nothing.
                observedChunkFrames = 0
                recomputeTarget()
                AppLog.i(
                    "AudioTrackWrapper: ${channelName()} can hold $capacityFrames frames " +
                        "(${msOf(capacityFrames.toLong())}ms) of the $trackBufferBytes bytes asked for, " +
                        "keeps ${msOf(effectiveFrames.toLong())}ms filled, banking " +
                        "${msOf(prerollTargetFrames.toLong())}ms before playing and " +
                        "${msOf(rebankTargetFrames.toLong())}ms after an underrun"
                )
                // The target is capped by the buffer, so a target short of the time-based figure
                // means the framework granted less than this sink needs however it is fed.
                val wantedFrames = AudioJitterBufferPolicy.framesFor(
                    sampleRateInHz, AudioJitterBufferPolicy.targetMsFor(audioLatencyMultiplier)
                )
                if (prerollTargetFrames < wantedFrames) {
                    AppLog.w(
                        "AudioTrackWrapper: ${channelName()} was granted only " +
                            "${msOf(capacityFrames.toLong())}ms, so it can bank " +
                            "${msOf(prerollTargetFrames.toLong())}ms of the " +
                            "${msOf(wantedFrames.toLong())}ms it wants - this sink will be fragile"
                    )
                }
            }
        }

        if (isAac && !initDecoder(sampleRateInHz, channelCount)) {
            decoderFailed = true
        }

        this.start()
    }

    private fun initDecoder(sampleRate: Int, channels: Int): Boolean {
        try {
            val mime = "audio/mp4a-latm"
            val format = MediaFormat.createAudioFormat(mime, sampleRate, channels)
            format.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)

            // CSD for RAW AAC-LC (AudioSpecificConfig)
            val csd = makeAacCsd(sampleRate, channels)
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(csd))

            decoder = MediaCodec.createDecoderByType(mime)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // Use a HandlerThread for the codec callback but set its priority to AUDIO
                // to prevent it from being starved by the video decoder.
                codecHandlerThread = object : HandlerThread("AacCodecThread") {
                    override fun onLooperPrepared() {
                        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    }
                }
                codecHandlerThread!!.start()

                val callback = object : MediaCodec.Callback() {
                    override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
                        freeInputBuffers.offer(index)
                    }

                    override fun onOutputBufferAvailable(
                        codec: MediaCodec,
                        index: Int,
                        info: MediaCodec.BufferInfo
                    ) {
                        try {
                            if (!isRunning) return
                            val outputBuffer = codec.getOutputBuffer(index)
                            if (outputBuffer != null && info.size > 0) {
                                val size = info.size
                                // Both input and output callbacks run here. Never wait for the
                                // speaker: doing so starves decoder inputs during a network burst.
                                if (writeSemaphore.tryAcquire()) {
                                    val chunk = obtainAudioBuffer(size)
                                    outputBuffer.position(info.offset)
                                    outputBuffer.get(chunk, 0, size)
                                    outputBuffer.clear()
                                    try {
                                        writeExecutor.submit {
                                            try {
                                                writeToTrack(chunk, size)
                                            } catch (e: Exception) {
                                                AppLog.e("Error writing decoded AAC to AudioTrack", e)
                                            } finally {
                                                recycleAudioBuffer(chunk)
                                                writeSemaphore.release()
                                            }
                                        }
                                    } catch (e: java.util.concurrent.RejectedExecutionException) {
                                        // The teardown got here first. The frame is the tail of a
                                        // stream that is ending, so drop it quietly.
                                        recycleAudioBuffer(chunk)
                                        writeSemaphore.release()
                                    }
                                } else {
                                    droppedAacOutputs++
                                    if (droppedAacOutputs == 1L) {
                                        AppLog.w("AudioTrackWrapper: the AAC sink cannot keep up, shedding decoded frames - see shed= on this channel's sink line for how many")
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            AppLog.e("Error processing AAC output", e)
                            if (e is InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                        } finally {
                            try {
                                codec.releaseOutputBuffer(index, false)
                            } catch (e: Exception) {
                                if (isRunning) AppLog.e("Error releasing AAC output", e)
                            }
                        }
                    }

                    override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
                        AppLog.e("AAC Codec Error", e)
                        rebuildRequested = true
                    }

                    override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                        val outRate = try { format.getInteger(MediaFormat.KEY_SAMPLE_RATE) } catch (e: Exception) { -1 }
                        val outChannels = try { format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } catch (e: Exception) { -1 }
                        if (outRate != sampleRate || outChannels != trackChannelCount) {
                            AppLog.w("AAC decoder output is $outRate Hz x $outChannels but the track plays " +
                                "$sampleRate Hz x $trackChannelCount; audio will run at the wrong speed")
                        } else {
                            AppLog.i("AAC Output Format Changed: $format")
                        }
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    val handler = Handler(codecHandlerThread!!.looper)
                    decoder!!.setCallback(callback, handler)
                } else {
                    decoder!!.setCallback(callback)
                }
            }

            decoder?.configure(format, null, null, 0)
            decoder?.start()
            AppLog.i("AAC Decoder started for $sampleRate Hz, $channels channels (Async)")
            return true
        } catch (e: Exception) {
            AppLog.e("Failed to init AAC decoder; this sink's frames will be dropped, not played as PCM", e)
            try { decoder?.release() } catch (ignored: Exception) { }
            decoder = null
            return false
        }
    }

    /** One rebuild, from the run loop: release() must never run on the codec's own callback thread. */
    private fun rebuildDecoder() {
        rebuildRequested = false
        val now = SystemClock.elapsedRealtime()
        val since = if (lastRebuildMs == 0L) Long.MAX_VALUE else now - lastRebuildMs
        if (!AacDecoderRecoveryPolicy.allowsRebuild(decoderRebuilds, since)) {
            if (!decoderFailed) {
                AppLog.w("AudioTrackWrapper: AAC decoder failed again (rebuilds=$decoderRebuilds), giving up on this sink")
            }
            decoderFailed = true
            return
        }
        decoderRebuilds++
        lastRebuildMs = now
        releaseDecoder()
        quitCodecThread()
        AppLog.w("AudioTrackWrapper: rebuilding the AAC decoder ($decoderRebuilds of ${AacDecoderRecoveryPolicy.MAX_REBUILDS})")
        decoderFailed = !initDecoder(sampleRate, trackChannelCount)
    }

    private fun releaseDecoder() {
        try {
            decoder?.stop()
        } catch (ignored: Exception) {
        }
        try {
            decoder?.release()
        } catch (e: Exception) {
            AppLog.e("Error releasing audio decoder", e)
        }
        decoder = null
        // After release() no callback of the old codec is running, so these indices are all stale.
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

    private fun dropAacChunk() {
        droppedAacChunks++
        if (droppedAacChunks == 1L) {
            AppLog.w("AudioTrackWrapper: no working AAC decoder, dropping this sink's frames")
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

    private fun msOf(frames: Long): Long =
        if (sampleRate <= 0) 0L else frames * 1000L / sampleRate

    /** The largest chunk of PCM this sink has been handed, which is what every depth is sized on. */
    private fun noteChunkFrames(pcmBytes: Int) {
        val chunkFrames = AudioJitterBufferPolicy.arrivalChunkFrames(pcmBytes, bytesPerFrame)
        if (chunkFrames > observedChunkFrames) {
            observedChunkFrames = chunkFrames
            recomputeTarget()
        }
    }

    /** The target, sized against the largest chunk this sink has actually been sent. */
    private fun recomputeTarget() {
        prerollTargetFrames = AudioJitterBufferPolicy.targetFrames(
            sampleRateInHz = sampleRate,
            arrivalChunkFrames = observedChunkFrames,
            // The track drains continuously rather than in cycles, so one frame is the quantum.
            drainQuantumFrames = 1,
            capacityFrames = capacityFrames,
            latencyMultiplier = audioLatencyMultiplier
        )
        // Applied here rather than at the re-bank, so a later chunk-size change cannot quietly undo
        // a depth this sink has already proved it needs.
        prerollTargetFrames = AudioJitterBufferPolicy.deepenedTargetFrames(
            prerollTargetFrames, sampleRate, capacityFrames, deepenSteps, audioLatencyMultiplier
        )
        rebankTargetFrames = AudioJitterBufferPolicy.rebankTargetFrames(
            prerollTargetFrames, sampleRate, capacityFrames, audioLatencyMultiplier
        )
        queueCapacityChunks = SinkQueueOverflowPolicy.capacityChunks(
            audioQueueCapacity,
            SinkQueueOverflowPolicy.chunkDurationMs(observedChunkFrames, sampleRate)
        )
        configureTrackBuffer(if (rebanking) rebankTargetFrames else prerollTargetFrames)
        // Asked once the real chunk size is known, which is the only point it can be answered. A
        // track this small breaks up whatever the user sets, so it is a condition to report.
        if (!saidFragile && observedChunkFrames > 0 && capacityFrames > 0 &&
            !AudioJitterBufferPolicy.capacityIsSufficient(capacityFrames, observedChunkFrames, 1)
        ) {
            saidFragile = true
            AppLog.w(
                "AudioTrackWrapper: ${channelName()} can hold ${msOf(capacityFrames.toLong())}ms, " +
                    "which cannot cover a ${msOf(observedChunkFrames.toLong())}ms arrival and a " +
                    "cushion together - this sink will break up whatever the latency is set to"
            )
        }
    }

    /** Reserve capacity stays large, but only the active cushion should delay playback. */
    private fun configureTrackBuffer(targetFrames: Int) {
        val track = audioTrack ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val requested = AudioBufferSizingPolicy.effectiveFrames(
            sampleRate, targetFrames, minTrackFrames, capacityFrames
        )
        if (requested == requestedEffectiveFrames) return
        try {
            val actual = track.setBufferSizeInFrames(requested)
            if (actual > 0) {
                effectiveFrames = actual
                requestedEffectiveFrames = requested
            }
        } catch (e: Exception) {
            AppLog.w("AudioTrackWrapper: cannot tune the effective buffer: ${e.message}")
        }
    }

    /**
     * Bank deeper from here on, because the cushion this sink was given keeps running out.
     *
     * The setting is the starting point, not the whole answer: a user who is stuttering will not
     * find the picker, and round 2 measured the deep end fixing what they hear. Bounded so a link
     * that is simply short cannot walk the audio behind the picture.
     */
    private fun maybeDeepenTarget() {
        val before = prerollTargetFrames
        deepenSteps++
        recomputeTarget()
        if (prerollTargetFrames <= before) {
            // Already on the ceiling or on this buffer's fill share. Put the step back so the count
            // stays the number of steps actually taken, and say nothing.
            deepenSteps--
            return
        }
        AppLog.i(
            "AudioTrackWrapper: ${channelName()} keeps underrunning at " +
                "${msOf(before.toLong())}ms, banking ${msOf(prerollTargetFrames.toLong())}ms from here"
        )
    }

    /** Written but not yet heard. The head is a 32-bit counter that wraps, hence the mask. */
    private fun depthFrames(): Long {
        val track = audioTrack ?: return 0L
        return try {
            val played = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            (framesWritten - played).coerceAtLeast(0L)
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Underruns since the last call. `getUnderrunCount` is API 24 and this ships to 16, so below
     * that a buffer that has run dry while playing is the same evidence.
     */
    private fun pollUnderruns(): Int {
        val track = audioTrack ?: return 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return try {
                val now = track.underrunCount
                val delta = now - lastUnderrunCount
                lastUnderrunCount = now
                if (delta > 0) delta else 0
            } catch (e: Exception) {
                0
            }
        }
        return if (playbackStarted.get() && framesWritten > 0 && depthFrames() == 0L) 1 else 0
    }

    private fun rebanksInLastMinute(nowMs: Long): Int {
        while (rebankTimesMs.isNotEmpty() && nowMs - rebankTimesMs.peekFirst() > 60_000L) {
            rebankTimesMs.pollFirst()
        }
        return rebankTimesMs.size
    }

    /**
     * Rebuild the cushion after an underrun. Nothing else does: the writer never outruns real time,
     * so a drained track stays drained for the rest of the session.
     */
    private fun maybeRebank(nowMs: Long) {
        if (rebanking || !playbackStarted.get()) return
        val track = audioTrack ?: return
        val newUnderruns = pollUnderruns()
        if (newUnderruns <= 0) return
        underrunsTotal += newUnderruns

        val since = if (lastRebankMs == 0L) Long.MAX_VALUE else nowMs - lastRebankMs
        val inLastMinute = rebanksInLastMinute(nowMs)
        if (!AudioUnderrunRecoveryPolicy.shouldRebank(newUnderruns, since, inLastMinute)) {
            if (AudioUnderrunRecoveryPolicy.hasGivenUp(inLastMinute) && !saidGivenUp) {
                saidGivenUp = true
                AppLog.w(
                    "AudioTrackWrapper: ${channelName()} has underrun through " +
                        "${AudioUnderrunRecoveryPolicy.MAX_REBANKS_PER_MINUTE} re-banks in a minute - " +
                        "the link is short of audio, so it is left to run shallow"
                )
            }
            return
        }

        // A delayed counter can describe a gap the current burst has already filled. Pausing a
        // healthy cushion would add a fresh audible gap to an underrun that is over.
        if (depthFrames() >= prerollTargetFrames) return
        if (AudioUnderrunRecoveryPolicy.shouldDeepen(inLastMinute)) maybeDeepenTarget()

        try {
            // pause() keeps what is already buffered; stop() would discard it.
            track.pause()
            beginRebank()
            lastRebankMs = nowMs
            rebankTimesMs.addLast(nowMs)
            rebanksTotal++
            AppLog.i(
                "AudioTrackWrapper: ${channelName()} underran, re-banking " +
                    "${msOf(rebankTargetFrames.toLong())}ms (re-bank $rebanksTotal)"
            )
        } catch (e: Exception) {
            AppLog.e("Failed to pause AudioTrack for a re-bank", e)
            rebanking = false
        }
    }

    /**
     * Resume once the cushion is back, or once the same deadline the first start uses has passed.
     * Called from both the writer and the run loop.
     *
     * The deadline is what plays a parked sink a nav prompt shorter than the target, which would
     * otherwise sit in the buffer until some later stream filled it.
     */
    private fun maybeResumeAfterRebank(framesIncoming: Int = 0) {
        if (!rebanking) return
        val track = audioTrack ?: return
        // Nothing new since the park is a sink the phone is not feeding, not a short stream.
        if (framesWritten + framesIncoming - framesAtRebank <= 0L) return
        val now = SystemClock.elapsedRealtime()
        if (rebankingSinceMs == 0L) rebankingSinceMs = now
        if (!AudioPrerollPolicy.shouldStart(
                depthFrames(),
                framesIncoming,
                rebankTargetFrames,
                now - rebankingSinceMs,
                AudioPrerollPolicy.maxWaitMs(isMediaSink)
            )
        ) return
        try {
            track.play()
            rebanking = false
            AppLog.i(
                "AudioTrackWrapper: ${channelName()} resumed with ${msOf(depthFrames())}ms banked " +
                    "after ${now - rebankingSinceMs}ms"
            )
        } catch (e: Exception) {
            AppLog.e("Failed to resume AudioTrack after a re-bank", e)
            rebanking = false
        }
    }

    /** Park the cushion: the deadline starts at the next audio, not here. See [rebankingSinceMs]. */
    private fun beginRebank() {
        rebanking = true
        rebankingSinceMs = 0L
        framesAtRebank = framesWritten
        configureTrackBuffer(rebankTargetFrames)
    }

    private fun sampleHealth(nowMs: Long) {
        val chunkMs = SinkQueueOverflowPolicy.chunkDurationMs(observedChunkFrames, sampleRate)
        warnIfUnboundedQueueIsBehind(chunkMs)
        // On the mixer path this wrapper owns no AudioTrack, and the mixer pads a starved channel
        // with zeros so the output track never underruns. Its silent cycles are the only evidence
        // a listener's gap leaves anywhere.
        val mix = mixer
        val sample = if (mix != null) {
            AudioSinkHealthMonitor.Sample(
                silentCyclesTotal = mix.silentCyclesFor(channelId),
                rebanksTotal = mix.rebanksFor(channelId),
                droppedChunksTotal = droppedChunksTotal,
                droppedFramesTotal = droppedAacChunks,
                shedFramesTotal = droppedAacOutputs,
                depthFrames = mix.depthFramesFor(channelId),
                queuedChunks = dataQueue.size,
                chunkDurationMs = chunkMs,
                queueCapacityChunks = queueCapacityChunks,
                targetFrames = mix.targetFramesFor(channelId)
            )
        } else {
            AudioSinkHealthMonitor.Sample(
                underrunsTotal = underrunsTotal,
                rebanksTotal = rebanksTotal,
                droppedChunksTotal = droppedChunksTotal,
                droppedFramesTotal = droppedAacChunks,
                shedFramesTotal = droppedAacOutputs,
                depthFrames = depthFrames().toInt(),
                queuedChunks = dataQueue.size,
                chunkDurationMs = chunkMs,
                queueCapacityChunks = queueCapacityChunks,
                capacityFrames = capacityFrames,
                effectiveFrames = effectiveFrames,
                targetFrames = prerollTargetFrames
            )
        }
        health.sample(nowMs, sample)?.let { AppLog.i("%s", it) }
    }

    /**
     * A capacity of zero is the user asking for no limit, so nothing sheds and the backlog only ever
     * showed as a chunk count. Said once: it is a setting to change, not an event to watch.
     */
    private fun warnIfUnboundedQueueIsBehind(chunkDurationMs: Int) {
        if (saidUnboundedBacklog || queueCapacityChunks > 0) return
        val behindMs = SinkQueueOverflowPolicy.backlogMs(dataQueue.size, chunkDurationMs)
        if (behindMs < SinkQueueOverflowPolicy.UNBOUNDED_BACKLOG_WARN_MS) return
        saidUnboundedBacklog = true
        AppLog.w(
            "Audio queue capacity is 0, so nothing sheds and this sink is ${behindMs}ms behind on " +
                "${dataQueue.size} queued chunks. Set \"Audio queue capacity\" to bound it."
        )
    }

    private fun writeToTrack(buffer: ByteArray) {
        writeToTrack(buffer, buffer.size)
    }

    private fun writeToTrack(buffer: ByteArray, size: Int) {
        if (mixer != null) {
            noteChunkFrames(size)
            mixer.feed(channelId, buffer, 0, size)
            framesWritten += size / bytesPerFrame
        } else {
            val track = audioTrack ?: return
            synchronized(playbackLock) { noteChunkFrames(size) }
            applyGain(buffer, size)
            val result = AudioWriteLoop.writeFully(
                size = size,
                isRunning = { isRunning },
                write = { offset, remaining ->
                    synchronized(playbackLock) {
                        // A pause or route change may interrupt a write. Recheck playback before
                        // retrying its tail, and account only for bytes the track accepted.
                        maybeStartPlayback(remaining / bytesPerFrame)
                        maybeResumeAfterRebank(remaining / bytesPerFrame)
                        val written = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            track.write(buffer, offset, remaining, AudioTrack.WRITE_NON_BLOCKING)
                        } else {
                            track.write(buffer, offset, remaining)
                        }
                        if (written > 0) framesWritten += written / bytesPerFrame
                        written
                    }
                },
                onProgress = {},
                awaitWritable = { Thread.sleep(2) }
            )
            if (result < 0 && isRunning) {
                AppLog.e("AudioTrackWrapper: ${channelName()} write failed: $result")
                isRunning = false
            }
        }
    }

    /**
     * Starts the track once [AudioPrerollPolicy] says enough is banked.
     *
     * [writeToTrack] calls it in front of every write, which is where an ordinary stream starts;
     * the run loop calls it once a pass, which is what plays a stream too short to reach its
     * target. Idempotent because the AAC path writes from the write executor while the run loop
     * turns, so both can arrive at once.
     */
    private fun maybeStartPlayback(framesIncoming: Int) {
        if (playbackStarted.get()) return
        val track = audioTrack ?: return
        val now = SystemClock.elapsedRealtime()
        if (framesIncoming > 0 && firstAudioMs == 0L) firstAudioMs = now
        if (firstAudioMs == 0L) return
        val elapsed = now - firstAudioMs
        if (!AudioPrerollPolicy.shouldStart(
                framesWritten,
                framesIncoming,
                prerollTargetFrames,
                elapsed,
                AudioPrerollPolicy.maxWaitMs(isMediaSink)
            )
        ) return

        if (!playbackStarted.compareAndSet(false, true)) return
        try {
            track.play()
            AppLog.i(
                "AudioTrackWrapper: playback started with $framesWritten frames banked " +
                    "(target $prerollTargetFrames) after ${elapsed}ms"
            )
        } catch (e: Exception) {
            playbackStarted.set(false)
            AppLog.e("Failed to start AudioTrack playback", e)
        }
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

        // Drain the queue even after isRunning is set to false
        while (isRunning || dataQueue.isNotEmpty()) {
            try {
                // Use poll to avoid blocking indefinitely if isRunning becomes false
                val waitingForStart = (firstAudioMs > 0L && !playbackStarted.get()) ||
                    (rebanking && rebankingSinceMs > 0L)
                val chunk = dataQueue.poll(if (waitingForStart) 10 else 200, TimeUnit.MILLISECONDS)
                // The fill trigger lives in writeToTrack; this call carries only the deadline, so
                // a stream too short to reach its target still gets played.
                synchronized(playbackLock) {
                    maybeStartPlayback(0)
                    val nowMs = SystemClock.elapsedRealtime()
                    maybeRebank(nowMs)
                    maybeResumeAfterRebank()
                    sampleHealth(nowMs)
                }
                if (chunk != null) {
                    try {
                        if (isAac) {
                            if (rebuildRequested) rebuildDecoder()
                            if (decoder == null || decoderFailed) {
                                dropAacChunk()
                            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                queueInput(chunk.data, chunk.size)
                            } else {
                                decodeSync(chunk.data, chunk.size)
                            }
                        } else {
                            // PCM path - direct write in this high-priority thread
                            writeToTrack(chunk.data, chunk.size)
                        }
                    } finally {
                        recycleAudioBuffer(chunk.data)
                    }
                }
            } catch (e: InterruptedException) {
                drainQueuedAudio()
                break
            } catch (e: Exception) {
                AppLog.e("Error in AudioTrackWrapper run loop", e)
                isRunning = false
            }
        }
        cleanup()
        val dropped = if (droppedAacChunks + droppedAacOutputs > 0) {
            " ($droppedAacChunks AAC frames dropped before decode, $droppedAacOutputs after)"
        } else {
            ""
        }
        AppLog.i("AudioTrackWrapper thread finished.$dropped")
    }

    @Suppress("DEPRECATION")
    private fun decodeSync(inputData: ByteArray, size: Int) {
        try {
            val dec = this.decoder ?: return
            val inputIndex = dec.dequeueInputBuffer(200000)
            if (inputIndex >= 0) {
                val inputBuffer = dec.inputBuffers[inputIndex]
                inputBuffer.clear()
                inputBuffer.put(inputData, 0, size)
                dec.queueInputBuffer(inputIndex, 0, size, nextAacPtsUs(), 0)
            }

            val info = MediaCodec.BufferInfo()
            var outputIndex = dec.dequeueOutputBuffer(info, 0)
            while (outputIndex >= 0) {
                val outputBuffer = dec.outputBuffers[outputIndex]
                val chunk = ByteArray(info.size)
                outputBuffer.position(info.offset)
                outputBuffer.get(chunk)
                writeToTrack(chunk)
                dec.releaseOutputBuffer(outputIndex, false)
                outputIndex = dec.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            AppLog.e("Error in decodeSync", e)
            rebuildRequested = true
        }
    }

    @Throws(InterruptedException::class)
    private fun queueInput(inputData: ByteArray, size: Int) {
        try {
            // Wait for input buffer (with timeout to avoid deadlock if codec dies)
            // Restore to 200ms to prevent dropping frames under load
            val inputIndex = freeInputBuffers.poll(200, TimeUnit.MILLISECONDS)

            if (inputIndex != null && inputIndex >= 0) {
                val inputBuffer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    decoder?.getInputBuffer(inputIndex)
                } else {
                    @Suppress("DEPRECATION")
                    decoder?.inputBuffers?.get(inputIndex)
                }

                inputBuffer?.clear()
                inputBuffer?.put(inputData, 0, size)
                decoder?.queueInputBuffer(inputIndex, 0, size, nextAacPtsUs(), 0)
            } else {
                AppLog.w("AAC Input Buffer timeout (200ms) - dropping frame")
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            AppLog.e("Error queuing AAC input", e)
            rebuildRequested = true
        }
    }

    private fun makeAacCsd(sampleRate: Int, channelCount: Int): ByteArray {
        val sampleRateIndex = getFrequencyIndex(sampleRate)
        val audioObjectType = 2 // AAC-LC

        // Correct packing: [AOT:5][FreqIdx:4][ChanCfg:4][...padding:3]
        val config = ((audioObjectType and 0x1F) shl 11) or
                     ((sampleRateIndex and 0x0F) shl 7) or
                     ((channelCount and 0x0F) shl 3)

        return byteArrayOf(
            ((config shr 8) and 0xFF).toByte(),
            (config and 0xFF).toByte()
        )
    }

    private fun getFrequencyIndex(sampleRate: Int): Int {
        return when (sampleRate) {
            96000 -> 0
            88200 -> 1
            64000 -> 2
            48000 -> 3
            44100 -> 4
            32000 -> 5
            24000 -> 6
            22050 -> 7
            16000 -> 8
            12000 -> 9
            11025 -> 10
            8000  -> 11
            7350  -> 12
            else  -> 4 // Default 44100
        }
    }

    private fun createAudioTrack(
        stream: Int,
        sampleRateInHz: Int,
        bitDepth: Int,
        channelCount: Int,
        multiplier: Int
    ): AudioTrack {
        val channelConfig =
            if (channelCount == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val dataFormat =
            if (bitDepth == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT

        val minBufferSize = AudioTrack.getMinBufferSize(sampleRateInHz, channelConfig, dataFormat)
        minTrackFrames = (minBufferSize / bytesPerFrame).coerceAtLeast(1)
        // Floored rather than taken straight from the multiplier: a capacity under the jitter
        // target cannot hold whatever the user set it to.
        val bufferSize = AudioBufferSizingPolicy.requestedBytes(
            minBufferSize, multiplier, sampleRateInHz, channelCount * (if (bitDepth == 16) 2 else 1)
        )

        trackBufferBytes = bufferSize

        AppLog.i("Audio stream: $stream buffer size: $bufferSize (min: $minBufferSize) sampleRateInHz: $sampleRateInHz channelCount: $channelCount")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                return modernAudioTrack(stream, sampleRateInHz, channelConfig, dataFormat, bufferSize)
            } catch (e: IllegalArgumentException) {
                // AudioAttributes.setLegacyStreamType refuses stream types it has no usage for -
                // STREAM_ACCESSIBILITY by name, and any stream a vendor added that the public
                // mapping never learned about. The deprecated constructor goes through the
                // framework's internal mapping, which does know them, so it is the way to reach a
                // head unit's own stream rather than silently landing on the media one.
                AppLog.w("AudioTrackWrapper: stream $stream has no AudioAttributes mapping " +
                        "(${e.message}), falling back to the legacy AudioTrack constructor")
            }
        }

        @Suppress("DEPRECATION")
        return AudioTrack(
            stream,
            sampleRateInHz,
            channelConfig,
            dataFormat,
            bufferSize,
            AudioTrack.MODE_STREAM
        )
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.M)
    private fun modernAudioTrack(
        stream: Int,
        sampleRateInHz: Int,
        channelConfig: Int,
        dataFormat: Int,
        bufferSize: Int
    ): AudioTrack {
        val (usage, contentType) = when (stream) {
            AudioManager.STREAM_NOTIFICATION -> Pair(
                AudioAttributes.USAGE_NOTIFICATION,
                AudioAttributes.CONTENT_TYPE_SONIFICATION
            )
            AudioManager.STREAM_VOICE_CALL -> Pair(
                AudioAttributes.USAGE_VOICE_COMMUNICATION,
                AudioAttributes.CONTENT_TYPE_SPEECH
            )
            else -> Pair(
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.CONTENT_TYPE_MUSIC
            )
        }

        // Throws for a stream with no public usage mapping; createAudioTrack catches it.
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(usage)
            .setContentType(contentType)
            .setLegacyStreamType(stream)
            .build()

        val audioFormat = AudioFormat.Builder()
            .setSampleRate(sampleRateInHz)
            .setChannelMask(channelConfig)
            .setEncoding(dataFormat)
            .build()

        val builder = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(audioFormat)
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !attachHwDspEqualizer) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        return builder.build()
    }

    /**
     * Frames the track can actually hold.
     *
     * `setBufferSizeInBytes()` is a request the framework may clamp, and the pre-roll margin is the
     * only thing keeping a write off a not-yet-playing track from blocking on the thread that would
     * start it. Ask the track where the API allows; fall back to the requested size below M.
     *
     * `getBufferSizeInFrames()` answers the *effective* size rather than the capacity, so the two
     * are read apart: this line used to print one under the other's name.
     */
    private fun trackCapacityFrames(track: AudioTrack): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val frames = try {
                track.bufferCapacityInFrames
            } catch (e: Exception) {
                0
            }
            if (frames > 0) return frames
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val frames = try {
                track.bufferSizeInFrames
            } catch (e: Exception) {
                0
            }
            if (frames > 0) return frames
        }
        return if (bytesPerFrame > 0) trackBufferBytes / bytesPerFrame else 0
    }

    /** Frames the framework will keep filled. Never above the capacity, and often below it. */
    private fun trackEffectiveFrames(track: AudioTrack): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val frames = try {
                track.bufferSizeInFrames
            } catch (e: Exception) {
                0
            }
            if (frames > 0) return frames
        }
        return capacityFrames
    }

    private fun attachHwDspEqualizerQuietly(sessionId: Int) {
        if (!attachHwDspEqualizer) return
        if (sessionId != AudioManager.AUDIO_SESSION_ID_GENERATE && sessionId > 0) {
            try {
                equalizer?.release()
                val eq = Equalizer(0, sessionId)
                eq.enabled = true
                equalizer = eq
                AppLog.i("Attached dummy Equalizer to audioSessionId $sessionId to trigger HW DSP")
            } catch (t: Throwable) {
                // Ignore if Equalizer or AudioEffect is unsupported on device
            }
        }
    }

    fun write(buffer: ByteArray, offset: Int, size: Int) {
        if (!isRunning) return

        mixer?.noteArrival(channelId, if (isAac) 1024 else size / bytesPerFrame,
            SystemClock.elapsedRealtime())

        if (directPcm) {
            // feed() finishes copying before returning: the transport reuses its decrypted array.
            // Hardware backpressure belongs solely to AudioMixer's output thread.
            mixer!!.feed(channelId, buffer, offset, size)
            framesWritten += size / bytesPerFrame
            return
        }

        var data: ByteArray? = null
        try {
            data = obtainAudioBuffer(size)
            System.arraycopy(buffer, offset, data, 0, size)
            // AAC queues encoded bytes, which divided by a PCM frame's width is not a frame count
            // and scaled every figure derived from it. The AAC sink measures at the decoder instead.
            shedStaleChunks()
            dataQueue.offer(AudioChunk(data, size))
        } catch (e: InterruptedException) {
            data?.let { recycleAudioBuffer(it) }
            Thread.currentThread().interrupt()
            AppLog.w("Interrupted while putting audio data to queue")
        }
    }

    /**
     * Make room for one more chunk by shedding the oldest, which is what nobody will miss.
     *
     * Refusing the newest was the old behaviour and it is the wrong end: it leaves the sink further
     * behind for exactly as long as the burst lasts. See [SinkQueueOverflowPolicy].
     */
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

    fun preparePlayback() { mixer?.prepareChannel(channelId) }

    /**
     * Park a sink the phone has stopped, keeping the track and any AAC decoder built.
     *
     * Android Auto stops the media sink on every pause and every assistant session, and rebuilding
     * cost a fresh pre-roll and a drain each time. The re-bank path is what restarts it, so the
     * cushion is rebuilt before anything is heard.
     */
    fun pauseForIdle() {
        if (mixer != null) {
            mixer.finishChannel(channelId)
            return
        }
        synchronized(playbackLock) { parkForIdle() }
    }

    private fun parkForIdle() {
        val track = audioTrack ?: return
        if (!playbackStarted.get() || rebanking) return
        try {
            track.pause()
            beginRebank()
            AppLog.i("AudioTrackWrapper: ${channelName()} parked with ${msOf(depthFrames())}ms buffered")
        } catch (e: Exception) {
            AppLog.e("Failed to pause an idle AudioTrack", e)
        }
    }

    fun setGain(gain: Float) {
        AppLog.d("AudioTrackWrapper: updating gain to $gain")
        setVolume(gain)
    }

    fun stopPlayback() {
        isRunning = false
        this.interrupt()
    }

    private fun cleanup() {
        drainQueuedAudio()
        audioBufferPool.clear()

        // 1. Stop the decoder, then quit the thread its callbacks run on.
        //
        // The order is the point. release() does not retract a callback message already posted to
        // that looper, so with the thread still turning a frame could reach a write executor that
        // had been shut down and throw RejectedExecutionException on every ordinary pause. Quit the
        // thread first and nothing is left to submit.
        releaseDecoder()
        quitCodecThread()

        // 2. Wait for AAC writes that were already submitted to the executor
        writeExecutor.shutdown()
        try {
            if (!writeExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                AppLog.w("Audio write executor did not terminate in time")
                writeExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            AppLog.w("Audio write executor interrupted during shutdown")
            writeExecutor.shutdownNow()
        }

        if (mixer != null) {
            mixer.unregisterChannel(channelId)
            if (ownsMixer) mixer.stop()
        }

        // 3. stop() plays out what is still buffered on a MODE_STREAM track; this only waits for
        // that to finish before release().
        //
        // It used to wait by polling playbackHeadPosition against framesWritten *after* stop(),
        // and neither exit could fire: stop() zeroes the head, and the stagnation escape was
        // guarded on `pos > 0`. Measured: the full 2500 ms budget on every teardown, long enough
        // to overlap the next track's start.
        //
        // Sampling the head *before* stop() says how much is left to play, so compute the wait.
        val track = audioTrack
        // A track that never reached its pre-roll target still holds everything written to it. It
        // is not playing, so the guard below would skip stop() and release() would discard it -
        // silence where a prompt shorter than the target used to be heard. Start it first.
        if (track != null && !playbackStarted.get() && framesWritten > 0) {
            if (playbackStarted.compareAndSet(false, true)) {
                try {
                    track.play()
                } catch (e: Exception) {
                    AppLog.e("Failed to start AudioTrack for its final drain", e)
                }
            }
        }
        if (track != null && track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            var drainMs = 0L
            try {
                val played = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                val pending = (framesWritten - played).coerceAtLeast(0L)
                drainMs = if (sampleRate > 0) {
                    (pending * 1000L / sampleRate).coerceAtMost(DRAIN_CAP_MS)
                } else {
                    0L
                }
                track.stop()
            } catch (e: Exception) {
                AppLog.e("Error stopping audio track", e)
            }

            if (drainMs > 0) {
                try {
                    Thread.sleep(drainMs)
                } catch (e: InterruptedException) {
                    // A restart, not a failure, and what is left in the buffer is stale anyway.
                    // Logged at info: reporters attach these logs, and a stack trace here misled.
                    AppLog.i("AudioTrackWrapper: ${drainMs}ms drain cut short by a restart")
                    Thread.currentThread().interrupt()
                }
            }
        }

        // 4. Release the AudioTrack
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            AppLog.e("Error releasing audio track", e)
        }
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
