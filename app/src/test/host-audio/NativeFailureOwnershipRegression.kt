package com.andrerinas.openheadunit.decoder.audio

import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Process
import com.andrerinas.openheadunit.aap.AapAudio
import com.andrerinas.openheadunit.aap.AapMessage
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.utils.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The real protocol/decoder/focus owners surround an injected, deterministic DSP failure.
 * Holding the mixer lets Start and DATA arrive while the old ready tail is still banked.
 * No JNI or physical audio device is needed to observe the ownership/retirement boundary.
 */
internal fun nativeFailureOwnershipRegression() {
    fun get(target: Any, name: String): Any? =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target)
    fun set(target: Any, name: String, value: Any) =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!predicate()) { check(System.nanoTime() < deadline) { message }; Thread.sleep(2) }
    }
    val sdk = Build.VERSION.SDK_INT
    try {
        for (api in listOf(16, 19, 21, 33)) for (reconnect in listOf(false, true)) {
            Build.VERSION.SDK_INT = api
            Handler.reset()
            val decoder = AudioDecoder()
            val manager = AudioManager()
            var audio = AapAudio(decoder, manager, Settings())
            val mixerEntered = CountDownLatch(1)
            val releaseMixer = CountDownLatch(1)
            Process.hook = { name ->
                if (name.startsWith("AudioMixer")) {
                    mixerEntered.countDown()
                    check(releaseMixer.await(3, TimeUnit.SECONDS))
                }
            }
            val opened = AtomicInteger()
            val closed = AtomicInteger()
            var injected: AudioMixer.Channel? = null
            val id = Channel.ID_AUD
            val packet = AapMessage(id, Media.MsgType.MEDIA_MESSAGE_DATA_VALUE,
                ByteArray(8 + 480 * 4) { if (it >= 8 && it % 2 == 0) 64 else 0 })
            try {
                audio.noteSinkCodec(id, Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM_VALUE)
                audio.precreateAudioTrack(id)
                check(mixerEntered.await(1, TimeUnit.SECONDS))
                val track = checkNotNull(decoder.getTrack(id))
                val mixer = get(track, "mixer") as AudioMixer
                val original = get(track, "mixerChannel") as AudioMixer.Channel
                val state = AudioMixer.Channel(original.rate, original.channels, 2, true,
                    original.onOutputStopped, AudioTimeCorrectionMode.WSOLA, createEngine = {
                        opened.incrementAndGet()
                        object : TimeStretchEngine {
                            var supplied = false
                            var returned = false
                            override fun process(pcm: TimeStretchBlock, frames: Int, tempo: Double) { supplied = true }
                            override fun receive(pcm: TimeStretchBlock): Int {
                                check(supplied)
                                if (returned) throw IllegalStateException("fixture failure after ready PCM")
                                returned = true
                                repeat(480) { pcm[it] = 5000 }
                                return 240
                            }
                            override fun finish() = error("failed engine must not be reused for Stop")
                            override fun finished() = false
                            override fun close() { check(closed.incrementAndGet() == 1) }
                        }
                    }).also { injected = it }
                // Replace only the DSP fixture, retaining the wrapper's real playback owner
                // and the registration callback installed by AudioDecoder/AapAudio.
                set(track, "mixerChannel", state)
                @Suppress("UNCHECKED_CAST")
                val states = get(mixer, "channels") as java.util.concurrent.ConcurrentHashMap<Int, AudioMixer.Channel>
                states[id] = state
                set(mixer, "channelSnapshot", arrayOf(state))
                val lease = get(audio, "playbackLease") as PlaybackFocusLease
                audio.preparePlayback(id)
                Handler.runAll()
                check(manager.focus.size == 1)
                check(audio.process(packet))
                val worker = checkNotNull(state.timeStretch)
                await("native failure closes the engine without waiting for teardown") {
                    worker.failure != null && closed.get() == 1
                }
                worker.awaitClosed()
                check(state.buffer.depthFrames() == 240 && worker.deliveredFrames == 240L)
                check(!state.timeStretchFailureReported && !state.isIdle())
                val activityBefore = lease.snapshot()
                check(activityBefore.containsKey(id))
                val requestsBefore = manager.requestThreads.size
                repeat(3) {
                    audio.preparePlayback(id)
                    check(audio.process(packet))
                    Handler.runAll()
                }
                check(lease.snapshot() == activityBefore) { "failed Start/DATA renewed focus before tail drain" }
                check(manager.requestThreads.size == requestsBefore)
                check(decoder.getTrack(id) === track && opened.get() == 1)
                check(worker.acceptedFrames == 480L && state.buffer.depthFrames() == 240)
                check(state.warmupUntilMs == 0L) { "failed Start warmed output again" }

                val outputsBefore = AudioTrack.created.size
                Process.hook = null
                releaseMixer.countDown()
                await("old ready tail plays despite terminal native failure") {
                    AudioTrack.created.drop(outputsBefore).any { output -> output.samples.any { it > 0 } }
                }
                await("failed owner retires after its device tail drains") { lease.snapshot().isEmpty() }
                await("retirement reaches the main-thread focus release") {
                    Handler.runAll()
                    manager.focus.isEmpty()
                }
                check(state.buffer.depthFrames() == 0)
                val requestsAfterDrain = manager.requestThreads.size
                repeat(3) {
                    audio.preparePlayback(id)
                    check(audio.process(packet))
                    audio.stopAudio(id)
                    Handler.runAll()
                }
                check(lease.snapshot().isEmpty() && manager.focus.isEmpty())
                check(manager.requestThreads.size == requestsAfterDrain) { "failed stream reacquired focus after drain" }
                check(decoder.getTrack(id) === track && opened.get() == 1 && closed.get() == 1)
                check(worker.acceptedFrames == 480L)

                // Only a local audio restart or a new session retires the failed wrapper.
                // The normal PCM path then uses the new owner and can play again.
                if (reconnect) {
                    audio.releaseAllFocus()
                    Handler.runAll()
                    audio = AapAudio(decoder, manager, Settings())
                    audio.noteSinkCodec(id, Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM_VALUE)
                } else audio.restartAudio()
                Handler.runAll()
                val freshOutputStart = AudioTrack.created.size
                audio.precreateAudioTrack(id)
                val replacement = checkNotNull(decoder.getTrack(id))
                check(replacement !== track && replacement.playbackOwner !== track.playbackOwner)
                audio.preparePlayback(id)
                check(audio.process(packet))
                audio.stopAudio(id)
                Handler.runAll()
                await("explicit restart publishes a playable fresh owner") {
                    AudioTrack.created.drop(freshOutputStart).any { output -> output.samples.any { it > 0 } }
                }
                check(opened.get() == 1 && closed.get() == 1)
            } finally {
                Process.hook = null
                releaseMixer.countDown()
                audio.releaseAllFocus()
                Handler.runAll()
                decoder.stop()
                injected?.timeStretch?.let { it.close(); it.awaitClosed() }
                Handler.reset()
            }
            println("PASS API $api native failure keeps old tail, rejects protocol retries without focus, and recovers on ${if (reconnect) "reconnect" else "local restart"}")
        }

        // Tuning happens after the mixer publishes recoveryPending. A configuration
        // failure can retain accepted staging and outlast the old device drain deadline.
        run {
            var clock = 100L
            var retired = 0
            val lease = PlaybackFocusLease()
            val owner = Any()
            val id = Channel.ID_AUD
            lease.register(id, owner)
            check(lease.complete(checkNotNull(lease.activity(id, owner, clock))))
            val mixer = AudioMixer(3)
            val state = AudioMixer.Channel(48000, 2, 2, false, {
                retired++
                lease.retire(id, owner)
            }, AudioTimeCorrectionMode.WSOLA, createEngine = {
                throw IllegalStateException("fixture native failure")
            })
            class StagingOutput(private val failTuning: Boolean) : PcmOutput {
                val samples = ArrayList<Short>()
                var closed = 0
                override val name = "tuning recovery fixture"
                override val capacityFrames = 1920
                override val bufferFrames = 960
                override val burstFrames = 480
                override val underruns = 0
                override fun setBufferFrames(frames: Int): Int {
                    if (!failTuning) return frames
                    clock = 2000
                    return -6
                }
                override fun start() {}
                override fun pause() {}
                override fun write(data: ShortArray, offset: Int, count: Int): Int {
                    val accepted = if (failTuning) count else minOf(count, 2)
                    repeat(accepted) { samples.add(data[offset + it]) }
                    return accepted
                }
                override fun close() { closed++ }
                override fun closeForRecovery(): ShortArray {
                    close()
                    return if (failTuning) samples.toShortArray() else ShortArray(0)
                }
            }
            val staging = StagingOutput(true)
            val replacement = StagingOutput(false)
            var routeReady = false
            val device = RecoveringPcmOutput(staging, {
                check(routeReady) { "route temporarily absent" }
                replacement
            }, { clock }, {})
            val worker = checkNotNull(state.timeStretch)
            try {
                set(mixer, "output", device)
                val tail = shortArrayOf(1200, -1200, 2400, -2400)
                check(device.write(tail, 0, tail.size) == tail.size)
                worker.offer(ShortArray(960), 960, clock)
                worker.awaitClosed()
                check(worker.failure != null && state.isIdle())
                state.lastWriteCompletedMs = clock
                state.outputConsumed = true
                check(!device.hasPendingRecovery && get(mixer, "recoveryPending") == false)
                device.setBufferFrames(1920)
                check(clock == 2000L && device.hasPendingRecovery && staging.closed == 1)
                check(get(mixer, "recoveryPending") == false) { "fixture must leave the pre-tuning snapshot stale" }
                mixer.retireFailedTimeStretch(state, clock)
                check(retired == 0 && !state.timeStretchFailureReported) { "tuning recovery retired retained staging" }

                // Temporary focus release must not remove the registration needed to
                // replay the retained tail, even though the failed native bank is empty.
                check(lease.release(id, lease.snapshot().getValue(id)))
                clock += 300
                routeReady = true
                check(lease.complete(checkNotNull(lease.activity(id, owner, clock))))
                check(lease.canRender(clock))
                val silence = ShortArray(960)
                check(device.write(silence, 0, silence.size) == 0) // Reopen only.
                check(device.hasPendingRecovery)
                mixer.retireFailedTimeStretch(state, clock)
                check(retired == 0)
                check(device.write(silence, 0, silence.size) == 0) // Partial retained prefix.
                check(device.hasPendingRecovery && replacement.samples == tail.take(2))
                mixer.retireFailedTimeStretch(state, clock)
                check(retired == 0)
                check(device.write(silence, 0, silence.size) == 0) // Remaining retained prefix.
                check(!device.hasPendingRecovery && replacement.samples == tail.toList())
                check(device.recoveryProgressSamples == tail.size.toLong())
                state.lastWriteCompletedMs = clock
                state.outputConsumed = false
                mixer.retireFailedTimeStretch(state, clock + 1000)
                check(retired == 0) { "replayed tail still needs device consumption" }
                state.outputConsumed = true
                mixer.retireFailedTimeStretch(state, clock)
                check(retired == 0) { "replayed tail still needs its drain interval" }
                mixer.retireFailedTimeStretch(state, clock + 1000)
                mixer.retireFailedTimeStretch(state, clock + 1001)
                check(retired == 1 && state.timeStretchFailureReported && lease.snapshot().isEmpty())
                check(lease.activity(id, owner, clock + 1002) == null)
            } finally {
                worker.close()
                worker.awaitClosed()
                device.close()
            }
            println("PASS tuning recovery retains failed owner through focus reacquisition and exact tail replay, then retires once")
        }
    } finally { Build.VERSION.SDK_INT = sdk }
}
