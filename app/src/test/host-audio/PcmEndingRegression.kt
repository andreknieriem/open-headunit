package com.andrerinas.openheadunit.decoder.audio

import android.media.AudioTrack
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The synthetic ending is output work: focus/drain must not finish at bank depth zero. */
internal fun pcmEndingRegression() {
    val atTail = CountDownLatch(1)
    val resume = CountDownLatch(1)
    val gated = AtomicBoolean()
    val createdBefore = AudioTrack.created.size
    lateinit var mixer: AudioMixer
    mixer = AudioMixer(canRender = {
        if (AudioTrack.created.size > createdBefore && AudioTrack.created.last().samples.size >= 960 &&
            mixer.depthFramesFor(5) == 0 && gated.compareAndSet(false, true)) {
            atTail.countDown()
            check(resume.await(3, TimeUnit.SECONDS))
        }
        true
    })
    val state = mixer.registerChannel(5, 48000, 2)
    val pcm = ByteArray(1920) { if (it % 2 == 0) 0xe8.toByte() else 3 } // 480 frames at 1000
    mixer.feed(state, pcm, 0, pcm.size)
    mixer.finishChannel(state)
    try {
        mixer.start()
        check(atTail.await(3, TimeUnit.SECONDS)) { "mixer skipped the pending final ramp" }
        val track = AudioTrack.created.last()
        check(track.samples.size == 960)
        check(track.samples.last().toInt() == 1000)
        check(!mixer.isQuiescent(state, SystemClock.elapsedRealtime() + 10_000))
        resume.countDown()
        val deadline = System.nanoTime() + 3_000_000_000L
        while (track.samples.size < 1920 || state.writePending) {
            check(System.nanoTime() < deadline) { "mixer did not write the final ramp" }
            Thread.sleep(2)
        }
        check(track.samples[960].toInt() == 995)
        check(track.samples[1438].toInt() == 0)
        check(mixer.isQuiescent(state, SystemClock.elapsedRealtime() + 10_000))
    } finally {
        resume.countDown()
        mixer.stop()
    }
    println("PASS pending ending keeps the real mixer active and is written before quiescence")
}
