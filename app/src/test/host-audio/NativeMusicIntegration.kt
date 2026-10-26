package com.andrerinas.openheadunit.decoder.audio

import android.media.AudioManager
import android.os.Build
import android.os.SystemClock

/** Optional integration with the actual JNI adapters, not a fake TimeStretchEngine.
 * Build the host library with native-time-stretch/CMakeLists.txt and supply its directory
 * through java.library.path. Android device/output APIs remain host test doubles.
 */
internal fun nativeMusicIntegration() {
    if (System.getenv("OHU_TEST_NATIVE_DSP") != "1") return
    val originalSdk = Build.VERSION.SDK_INT
    fun waitFor(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!predicate()) {
            check(System.nanoTime() < deadline) { message }
            Thread.sleep(1)
        }
    }
    fun constantPcm(frames: Int, channels: Int, sample: Int) = ByteArray(frames * channels * 2).also { bytes ->
        for (offset in bytes.indices step 2) {
            bytes[offset] = sample.toByte()
            bytes[offset + 1] = (sample shr 8).toByte()
        }
    }
    fun duckGain(mixer: AudioMixer): Float {
        val envelope = AudioMixer::class.java.getDeclaredField("ducking")
            .apply { isAccessible = true }.get(mixer)
        return MixerDuckingEnvelope::class.java.getDeclaredField("gain")
            .apply { isAccessible = true }.getFloat(envelope)
    }
    try {
        Build.VERSION.SDK_INT = 16
        check(NativeTimeStretch.supported) {
            "OHU_TEST_NATIVE_DSP requires the host JNI library; fallback would hide a missing native path"
        }
        for (sdk in listOf(16, 19, 21, 36)) for (mode in AudioTimeCorrectionMode.entries.filter { it != AudioTimeCorrectionMode.OHU }) {
            Build.VERSION.SDK_INT = sdk
            val mixer = AudioMixer(stream = AudioManager.STREAM_MUSIC, duckMediaForSpeech = true, matchingMode = mode)
            var worker: TimeStretchWorker? = null
            try {
                // All roles share STREAM_MUSIC. Register guidance first to catch selecting
                // by output stream or first registration instead of the protocol sink role.
                val speech = mixer.registerChannel(5, 16000, 1, isMediaSink = false)
                val music = mixer.registerChannel(4, 48000, 2, isMediaSink = true)
                val system = mixer.registerChannel(6, 16000, 1, isMediaSink = false)
                val aac = mixer.registerChannel(7, 48000, 2, isMediaSink = true, allowNativeTimeCorrection = false)
                check(speech.timeStretch == null && system.timeStretch == null && aac.timeStretch == null)
                val native = checkNotNull(music.timeStretch) { "$mode API $sdk silently fell back to OHU" }

                worker = native

                val musicPcm = constantPcm(24000, 2, 10000)
                mixer.feed(music, musicPcm, 0, musicPcm.size)
                waitFor("$mode API $sdk did not process music input") {
                    check(native.failure == null) { native.failure!! }
                    native.submittedFrames == 24000L
                }
                val beforeStop = native.producedFrames
                mixer.finishChannel(music)
                waitFor("$mode API $sdk did not deliver its final output") {
                    check(native.failure == null) { native.failure!! }
                    native.isIdle()
                }
                check(native.acceptedFrames == 24000L && native.rejectedFrames == 0L)
                check(native.producedFrames > beforeStop) { "$mode produced no final tail" }
                check(native.deliveredFrames == native.producedFrames)
                check(music.buffer.depthFrames().toLong() == native.deliveredFrames)

                val speechPcm = constantPcm(320, 1, 1000)
                val systemPcm = constantPcm(160, 1, 500)
                mixer.feed(speech, speechPcm, 0, speechPcm.size)
                mixer.feed(system, systemPcm, 0, systemPcm.size)
                mixer.finishChannel(speech)
                mixer.finishChannel(system)
                val now = SystemClock.elapsedRealtime()
                var renderedMusicFrames = 0L
                var nonzero = false
                repeat(80) { cycle ->
                    val output = mixer.renderCycle(now + cycle * 10, 480)
                    nonzero = nonzero || output.any { it != 0.toShort() }
                    renderedMusicFrames += music.buffer.lastRenderRealFrames
                    if (cycle == 0) check(kotlin.math.abs(duckGain(mixer) - .4f) < .0001f) {
                        "shared guidance did not duck native music"
                    }
                }
                check(nonzero && renderedMusicFrames == native.deliveredFrames) {
                    "$mode API $sdk did not render every delivered native frame"
                }
                check(music.isIdle() && speech.isIdle() && system.isIdle())
                check(music.buffer.droppedFrames == 0L && music.buffer.compressedFrames == 0L)
                check(duckGain(mixer) == 1f) { "guidance tail left native music ducked" }
                println("PASS actual JNI $mode API $sdk: music-only worker, final tail, shared prompt duck/release")
            } finally {
                mixer.stop()
                worker?.awaitClosed()
            }
        }
    } finally {
        Build.VERSION.SDK_INT = originalSdk
    }
}

fun main() {
    check(NativeTimeStretch.supported) { "Native feedback regression requires the real JNI library" }
    nativeTempoFeedbackRegression()
}

/** Preserve the arrival schedule while allowing a parked worker to resume on each render.
 * Actual JNI output, not requested tempo alone, closes this feedback loop. */
private fun nativeTempoFeedbackRegression() {
    for ((packetFrames, cadenceUs) in listOf(19200 to 10000L, 960 to 10500L, 19200 to 10500L)) {
        val clock = java.util.concurrent.atomic.AtomicLong()
        val generation = java.util.concurrent.atomic.AtomicLong()
        val workerGeneration = java.util.concurrent.atomic.AtomicLong(-1)
        val bank = AdaptivePcmBuffer(latencyMultiplier = 16, isMediaSink = true, nativeTimeCorrection = true)
        var expectedFrames = 0.0
        val worker = TimeStretchWorker(AudioTimeCorrectionMode.PHASE_VOCODER_SHORT, bank,
            {
                val observedGeneration = generation.get()
                clock.get().also { workerGeneration.set(observedGeneration) }
            }, {}, {}, {
                val engine = NativeTimeStretchEngine(AudioTimeCorrectionMode.PHASE_VOCODER_SHORT)
                object : TimeStretchEngine {
                    override fun bind(input: TimeStretchBlock, output: TimeStretchBlock) = engine.bind(input, output)
                    override fun process(pcm: TimeStretchBlock, frames: Int, tempo: Double) {
                        check(tempo in 0.8..1.2)
                        engine.process(pcm, frames, tempo)
                        expectedFrames += frames / tempo
                    }
                    override fun receive(pcm: TimeStretchBlock) = engine.receive(pcm)
                    override fun finish() = engine.finish()
                    override fun finished() = engine.finished()
                    override fun close() = engine.close()
                }
            })
        fun field(name: String) = TimeStretchWorker::class.java.getDeclaredField(name).apply { isAccessible = true }
        val processing = field("processing")
        val waiting = field("waitingForRender")
        val thread = field("thread").get(worker) as Thread
        fun settle() {
            val deadline = System.nanoTime() + 3_000_000_000L
            while (worker.submittedFrames < worker.acceptedFrames || processing.getBoolean(worker)) {
                check(worker.failure == null) { worker.failure!! }
                // Input and post-render phases can share a timestamp. Require a
                // callback from this phase followed by parking, not an old wait.
                if (waiting.getBoolean(worker) && workerGeneration.get() >= generation.get() &&
                    thread.state == Thread.State.TIMED_WAITING) return
                check(System.nanoTime() < deadline) { "native feedback worker did not settle" }
                Thread.sleep(0, 100000)
            }
        }
        val pcm = ShortArray(packetFrames * 2) {
            (12000 * kotlin.math.sin(it / 2 * 2 * Math.PI * 440 / 48000)).toInt().toShort()
        }
        val output = ShortArray(960)
        var offered = 0L
        var rendered = 0L
        var maximumDepth = 0
        try {
            for (us in 0L until 120_000_000L step cadenceUs) {
                clock.set(us / 1000)
                generation.incrementAndGet()
                val due = us * 48000 / 1_000_000
                while (offered <= due) {
                    worker.offer(pcm, pcm.size, clock.get())
                    offered += packetFrames
                    settle()
                }
                bank.observeTimeCorrection(worker.policy, clock.get(), 480)
                bank.render(output, clock.get(), 480)
                rendered += bank.lastRenderRealFrames
                generation.incrementAndGet()
                worker.spaceAvailable()
                settle()
                if (us >= 60_000_000) maximumDepth = maxOf(maximumDepth, bank.depthFrames())
            }
            check(maximumDepth < bank.targetFrames() + maxOf(packetFrames, 5760)) {
                "burst delivery pinned native playback near capacity: $maximumDepth / ${bank.targetFrames()}"
            }
            if (cadenceUs == 10500L) check(bank.rebanks == 0L && bank.concealedFrames == 0L)
            worker.finish()
            var drained = false
            for (tick in 0 until 2000) {
                clock.addAndGet(10)
                bank.observeTimeCorrection(worker.policy, clock.get(), 480)
                bank.render(output, clock.get(), 480)
                rendered += bank.lastRenderRealFrames
                generation.incrementAndGet()
                worker.spaceAvailable()
                settle()
                if (worker.isIdle() && bank.depthFrames() == 0) { drained = true; break }
            }
            check(drained && worker.failure == null)
            check(worker.acceptedFrames == offered && worker.submittedFrames == offered)
            check(worker.rejectedFrames == 0L && bank.droppedFrames == 0L)
            check(worker.producedFrames == kotlin.math.round(expectedFrames).toLong())
            check(rendered == worker.deliveredFrames && worker.deliveredFrames == worker.producedFrames)
            println("PASS actual JNI feedback packet=$packetFrames cadenceUs=$cadenceUs: bounded ready depth, exact corrected duration and complete Stop tail")
        } finally { worker.close(); worker.awaitClosed() }
    }
}
