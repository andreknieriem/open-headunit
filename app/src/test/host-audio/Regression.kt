package com.andrerinas.openheadunit.decoder.audio

import android.media.*
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)
private fun waitFor(message: String, condition: () -> Boolean) {
    val deadline = System.nanoTime() + 3_000_000_000L
    while (!condition()) { check(System.nanoTime() < deadline) { message }; Thread.sleep(2) }
}
private fun channel(m: AudioMixer) = (field(m, "channels") as Map<*, *>)[5] as AudioMixer.Channel
private fun wrapper(m: AudioMixer, aac: Boolean = true, rate: Int = 48000, channels: Int = 2) =
    AudioTrackWrapper(rate, 16, channels, aac, 1f, 2, 0, m, 5, true, false)
private fun finish(w: AudioTrackWrapper) { w.stopPlayback(); val t = field(w, "decodeThread") as Thread?; t?.join(3000); check(t?.isAlive != true) }
private fun pcm(frames: Int) = ByteArray(frames * 4) { if (it % 2 == 0) 64 else 0 }
private val fixtureOwner = Any()
private fun focusLease() = PlaybackFocusLease().apply {
    register(4, fixtureOwner); register(5, fixtureOwner)
}
private fun PlaybackFocusLease.activity(channel: Int, nowMs: Long) = activity(channel, fixtureOwner, nowMs)

fun main() {
    pcmRateChangeTailRegression()
    mixedCodecRegression(staticFocus = true)
    mixedCodecRegression(staticFocus = false)
    audioSettingsRegression()
    aacProgressOwnershipRegression(csdReplacement = true)
    aacProgressOwnershipRegression(csdReplacement = false)
    handoffRetirementBeforePublicationRegression()
    drainedProtocolReleaseRegression()
    protocolFocusHandoffClosureRegression()
    protocolPlaybackFocusRegression()
    staticSessionFocusRegression()
    protocolFocusIdentityRegression()
    throwingSessionClosureRegression()
    protocolSessionClosureRegression()
    delayedSetupRegression(true)
    delayedSetupRegression(false)
    retiredSessionCommandsRegression()
    codecFramingRegression()
    retiredBeforeRegistrationRegression()
    priorityFallbackRegression()
    setupReplacementFocusRegression()
    sessionRecoveryRegression(reconnect = true)
    sessionRecoveryRegression(reconnect = false)
    ownerIsolationRegression()
    fatalOwnerRegression()
    rearmedDrainRegression(startDuringFailure = true)
    rearmedDrainRegression(startDuringFailure = false)
    terminalReplayFocusRegression()
    replayActivityRegression()
    focusPostingRegression()
    replayDrainRegression()
    closedTailRegression()
    val d = AudioDecoder()
    d.start(5, 3, 48000, 16, 2, true, 1f, 2, 0, true)
    val old = d.getTrack(5)!!
    val codec = field(old, "decoder") as MediaCodec
    val entered = CountDownLatch(1); val release = CountDownLatch(1)
    codec.stopHook = { entered.countDown(); release.await() }
    d.stop(5)
    check(entered.await(2, TimeUnit.SECONDS))
    d.start(5, 3, 48000, 16, 2, false, 1f, 2, 0, true)
    val fresh = d.getTrack(5)!!
    val mixer = field(d, "mixer") as AudioMixer
    val registration = channel(mixer)
    release.countDown(); (field(old, "decodeThread") as Thread).join(3000)
    check(channel(mixer) === registration && d.getTrack(5) === fresh)
    d.stop(); finish(fresh)
    println("PASS actual wrapper cleanup retains replacement registration")

    AudioTrack.failMinimum = true
    val createdBefore = AudioTrack.created.size
    val logsBefore = com.andrerinas.openheadunit.utils.AppLog.lines.size
    val startup = AudioDecoder()
    startup.start(5, 3, 48000, 16, 2, staticAudioFocus = false)
    val recovered = startup.getTrack(5)!!
    recovered.write(pcm(240), 0, 960)
    recovered.pauseForIdle()
    waitFor("first open failure") {
        com.andrerinas.openheadunit.utils.AppLog.lines.drop(logsBefore).any { "output reopen failed" in it }
    }
    check(startup.getTrack(5) === recovered)
    AudioTrack.failMinimum = false
    waitFor("scheduled reopen without another packet") { AudioTrack.created.size > createdBefore }
    val output = AudioTrack.created.last()
    waitFor("short prompt reaches recreated output") { output.samples.any { it.toInt() != 0 } }
    startup.stop(); finish(recovered)
    println("PASS retained short prompt and scheduled open retry without subsequent DATA")

    val bank = AudioMixer(audioLatencyMultiplier = 2)
    val aac = wrapper(bank)
    val aacCodec = field(aac, "decoder") as MediaCodec
    repeat(31) { aac.write(ByteArray(64), 0, 64) }
    waitFor("31 codec inputs") { aacCodec.queued == 31 }
    repeat(31) { aacCodec.emit(pcm(1024)) }
    check(bank.depthFramesFor(5) == 31 * 1024)
    finish(aac)
    println("PASS 31 decoded callbacks reach actual bank without a second writer queue")

    val direct = AudioMixer(audioLatencyMultiplier = 2)
    val speech = wrapper(direct, false, 16000, 1)
    speech.write(ByteArray(320), 0, 320); speech.pauseForIdle()
    check(direct.depthFramesFor(5) == 480)
    check(!channel(direct).buffer.isIdle())
    check(channel(direct).buffer.render(ShortArray(960), SystemClock.elapsedRealtime()))
    finish(speech)
    println("PASS direct 16k PCM precedes Stop and renders its short tail")

    for (sdk in listOf(17, 19, 21, 22)) {
        Build.VERSION.SDK_INT = sdk
        val legacy = wrapper(AudioMixer())
        val syncCodec = field(legacy, "decoder") as MediaCodec
        check(syncCodec.callback == null)
        legacy.write(ByteArray(64), 0, 64)
        waitFor("API $sdk synchronous input") { syncCodec.queued == 1 }
        finish(legacy)
    }
    Build.VERSION.SDK_INT = 33
    println("PASS API 17/19/21/22 AAC input uses the synchronous owner thread")

    // A short input must survive the old 200ms timeout even when Stop has arrived.
    MediaCodec.provideInputs = false
    val blockedBank = AudioMixer()
    val blocked = wrapper(blockedBank)
    val blockedCodec = field(blocked, "decoder") as MediaCodec
    blocked.write(ByteArray(64), 0, 64)
    blocked.pauseForIdle()
    Thread.sleep(300)
    check(blockedCodec.queued == 0)
    check(!blocked.isQuiescent(SystemClock.elapsedRealtime() + 2000))
    blockedCodec.callback!!.onInputBufferAvailable(blockedCodec, 0)
    waitFor("retained AAC input submission after timeout and Stop") { blockedCodec.queued == 1 }
    blockedCodec.emit(pcm(1024))
    check(blockedBank.depthFramesFor(5) == 1024)
    finish(blocked)
    MediaCodec.provideInputs = true
    println("PASS Stop does not discard an AU waiting beyond the old input timeout")

    // Bank consumption is not output completion while AudioTrack returns zero.
    AudioTrack.blockWrites = true
    val stalledMixer = AudioMixer()
    val stalled = wrapper(stalledMixer, false)
    stalledMixer.start()
    stalled.write(pcm(240), 0, 960)
    stalled.pauseForIdle()
    waitFor("mixer consumed bank") { channel(stalledMixer).buffer.depthFrames() == 0 }
    check(!stalled.isQuiescent(SystemClock.elapsedRealtime() + 500))
    AudioTrack.blockWrites = false
    waitFor("write plus estimated drain") { stalled.isQuiescent(SystemClock.elapsedRealtime()) }
    finish(stalled); stalledMixer.stop()
    println("PASS bank empty cannot release focus while the final mixed block awaits write")

    val lease = focusLease()
    val lateMixer = AudioMixer(canRender = { lease.canRender(SystemClock.elapsedRealtime()) })
    var acquire: PlaybackFocusLease.Request? = null
    val late = AudioTrackWrapper(48000, 16, 2, false, 1f, 2, 0, lateMixer, 5, false, false,
        onPcmActivity = { acquire = lease.activity(5, SystemClock.elapsedRealtime()) })
    lateMixer.start()
    late.pauseForIdle()
    late.write(pcm(240), 0, 960)
    Thread.sleep(40)
    check(lateMixer.depthFramesFor(5) == 240)
    check(lease.complete(acquire!!))
    late.pauseForIdle()
    waitFor("late PCM rendered after focus result") { lateMixer.depthFramesFor(5) == 0 }
    finish(late); lateMixer.stop()
    println("PASS late PCM stays banked until the focus reacquisition result")

    AudioTrack.failMinimum = true
    val terminalLease = focusLease()
    terminalLease.complete(terminalLease.activity(5, SystemClock.elapsedRealtime())!!)
    val terminalMixer = AudioMixer(canRender = { terminalLease.canRender(SystemClock.elapsedRealtime()) })
    val terminal = wrapper(terminalMixer, false)
    terminalMixer.start()
    terminal.write(pcm(240), 0, 960)
    terminal.pauseForIdle()
    waitFor("exhausted output parks retained PCM without holding focus forever") {
        terminal.isQuiescent(SystemClock.elapsedRealtime())
    }
    terminalLease.release(5, terminalLease.snapshot().getValue(5))
    AudioTrack.failMinimum = false
    val terminalCreated = AudioTrack.created.size
    terminal.preparePlayback()
    val reacquire = terminalLease.activity(5, SystemClock.elapsedRealtime())!!
    Thread.sleep(40)
    check(AudioTrack.created.size == terminalCreated)
    terminalLease.complete(reacquire)
    waitFor("terminal output resumes its retained block after focus") {
        AudioTrack.created.size > terminalCreated && AudioTrack.created.last().samples.any { it.toInt() != 0 }
    }
    finish(terminal); terminalMixer.stop()
    println("PASS terminal parking releases focus and gates retained PCM on a fresh Start")

    val lockedBank = AudioMixer()
    val locked = wrapper(lockedBank)
    val lockedCodec = field(locked, "decoder") as MediaCodec
    val callback = Thread { lockedCodec.emit(pcm(1024)) }
    val retire = Thread {
        locked.javaClass.getDeclaredMethod("releaseDecoder").apply { isAccessible = true }.invoke(locked)
    }
    synchronized(channel(lockedBank)) {
        callback.start()
        waitFor("callback waiting for bank") { callback.state == Thread.State.BLOCKED }
        retire.start()
        waitFor("retirement waiting for handoff") { retire.state == Thread.State.BLOCKED }
        check(field(locked, "decoder") === lockedCodec)
    }
    callback.join(3000); retire.join(3000)
    check(field(locked, "decoder") == null && lockedBank.depthFramesFor(5) == 1024)
    finish(locked)
    println("PASS codec retirement waits for an already accepted bounded PCM handoff")
}

private fun priorityFallbackRegression() {
    val denied = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    val d = AudioDecoder()
    var track: AudioTrackWrapper? = null
    android.os.Process.hook = { name ->
        if (name.startsWith("AudioMixer-") || name.startsWith("AacDecode-") || name == "AacCodecThread") {
            denied.add(name)
            throw SecurityException("test scheduler denies requested priority")
        }
    }
    try {
        val createdBefore = AudioTrack.created.size
        d.start(5, 3, 48000, 16, 2, isAac = true, staticAudioFocus = false)
        track = (field(d, "audioTracks") as Map<*, *>)[5] as AudioTrackWrapper
        val codec = field(track, "decoder") as MediaCodec
        track.write(ByteArray(64), 0, 64)
        waitFor("priority denial must not stop AAC input") { codec.queued == 1 }
        codec.emit(pcm(240))
        track.pauseForIdle()
        waitFor("priority denial must not stop mixed PCM output") {
            AudioTrack.created.drop(createdBefore).any { output -> output.samples.any { it.toInt() != 0 } }
        }
        check(denied.containsAll(listOf("AudioMixer-3", "AacDecode-5", "AacCodecThread")))
    } finally {
        android.os.Process.hook = null
        d.stop()
        track?.let(::finish)
    }
    println("PASS denied audio thread priority still decodes and plays a short AAC prompt")
}

private fun setupReplacementFocusRegression() {
    val handler = android.os.Handler
    handler.reset()
    val decoder = AudioDecoder()
    val manager = AudioManager()
    val audio = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager,
        com.andrerinas.openheadunit.utils.Settings())
    val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
    android.os.Process.hook = { name -> if (name.startsWith("AudioMixer")) { entered.countDown(); unblock.await() } }
    var replacement: AudioTrackWrapper? = null
    try {
        audio.noteSinkCodec(5, com.andrerinas.openheadunit.aap.protocol.proto.Media.MediaCodecType.MEDIA_CODEC_AUDIO_PCM_VALUE)
        audio.precreateAudioTrack(5)
        check(entered.await(1, TimeUnit.SECONDS))
        audio.preparePlayback(5); handler.runAll()
        check(manager.focus.size == 1)
        val old = decoder.getTrack(5)!!
        val oldMixer = field(old, "mixer") as AudioMixer
        old.write(pcm(240), 0, 960); old.pauseForIdle()
        val blockedOutput = object : PcmOutput {
            override val name = "Blocked tail"
            override val capacityFrames = 19200
            override var bufferFrames = 960
            override val burstFrames = 480
            override val underruns = 0
            override fun start() {}
            override fun pause() {}
            override fun close() {}
            override fun setBufferFrames(frames: Int): Int { bufferFrames = frames; return frames }
            override fun write(data: ShortArray, offset: Int, count: Int) = 0
        }
        val output = RecoveringPcmOutput(blockedOutput, { blockedOutput }, SystemClock::elapsedRealtime, {})
        oldMixer.javaClass.getDeclaredField("output").apply { isAccessible = true }.set(oldMixer, output)
        android.os.Process.hook = null; unblock.countDown()
        waitFor("old tail is still waiting on output") {
            channel(oldMixer).buffer.depthFrames() == 0 && channel(oldMixer).writePending
        }
        audio.noteSinkCodec(5, com.andrerinas.openheadunit.aap.protocol.proto.Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC_VALUE)
        audio.precreateAudioTrack(5)
        replacement = decoder.getTrack(5)!!
        check(replacement !== old && replacement.builtCodec().isAac)
        SystemClock.offsetMs += 2000
        repeat(5) {
            val due = handler.delayed.toList()
            handler.delayed.clear()
            due.forEach { it.run() }
            handler.runAll()
        }
        check(manager.focus.isEmpty()) {
            "Setup-only replacement inherited the retired owner's focus without Start or DATA"
        }
    } finally {
        SystemClock.offsetMs = 0; android.os.Process.hook = null; unblock.countDown()
        audio.releaseAllFocus(); handler.runAll(); decoder.stop()
        replacement?.let { finish(it) }; handler.reset()
    }
    println("PASS Setup-only codec replacement releases the retired playback lease")
}

private fun sessionRecoveryRegression(reconnect: Boolean) {
    val handler = android.os.Handler
    handler.reset()
    val decoder = AudioDecoder()
    val oldManager = AudioManager()
    val oldAudio = com.andrerinas.openheadunit.aap.AapAudio(decoder, oldManager,
        com.andrerinas.openheadunit.utils.Settings())
    val oldCanRender = decoder.playbackCallbacks.canRender
    val preemptRecovery = java.util.concurrent.atomic.AtomicBoolean()
    val enteredRecovery = CountDownLatch(1); val resumeRecovery = CountDownLatch(1)
    val instrumentedCallbacks = decoder.playbackCallbacks.copy(canRender = {
        if (preemptRecovery.compareAndSet(true, false)) {
            enteredRecovery.countDown()
            // Model CPU preemption, which is not cancelled by requestStop's interrupt.
            while (true) {
                try { resumeRecovery.await(); break } catch (_: InterruptedException) {}
            }
        }
        oldCanRender()
    })
    // Install the scheduling hook before any owner exists, preserving the AapAudio session.
    decoder.captureSession().javaClass.getDeclaredField("callbacks").apply { isAccessible = true }
        .set(decoder.captureSession(), instrumentedCallbacks)
    val enteredOwner = CountDownLatch(1); val resumeOwner = CountDownLatch(1)
    android.os.Process.hook = { name -> if (name.startsWith("AudioMixer")) { enteredOwner.countDown(); resumeOwner.await() } }
    decoder.start(5, 3, 48000, 16, 2)
    val oldTrack = decoder.getTrack(5)!!
    val oldMixer = field(oldTrack, "mixer") as AudioMixer
    check(enteredOwner.await(1, TimeUnit.SECONDS))
    oldAudio.preparePlayback(5); handler.runAll()
    oldTrack.write(pcm(240), 0, 960); oldTrack.pauseForIdle()
    val first = ReplayDrainDevice(true).apply {
        beforeFailure = {
            oldAudio.pauseAllAudio()
            preemptRecovery.set(true)
        }
    }
    val output = RecoveringPcmOutput(first, { ReplayDrainDevice(false) }, SystemClock::elapsedRealtime, {})
    oldMixer.javaClass.getDeclaredField("output").apply { isAccessible = true }.set(oldMixer, output)
    android.os.Process.hook = null; resumeOwner.countDown()
    var newAudio: com.andrerinas.openheadunit.aap.AapAudio? = null
    try {
        check(enteredRecovery.await(2, TimeUnit.SECONDS))
        val manager = if (reconnect) AudioManager() else oldManager
        if (reconnect) {
            oldAudio.releaseAllFocus()
            decoder.stop()
            newAudio = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager,
                com.andrerinas.openheadunit.utils.Settings())
            decoder.start(5, 3, 48000, 16, 2)
        } else {
            newAudio = oldAudio
            oldAudio.noteSinkCodec(5, com.andrerinas.openheadunit.aap.protocol.proto.Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC_VALUE)
            oldAudio.precreateAudioTrack(5)
        } // Setup only: no Start or DATA in the replacement.
        resumeRecovery.countDown()
        (field(oldMixer, "mixThread") as Thread).join(3000)
        check(!(field(oldMixer, "mixThread") as Thread).isAlive)
        handler.runAll()
        repeat(10) {
            val due = handler.delayed.toList()
            handler.delayed.clear()
            due.forEach { it.run() }
            handler.runAll()
        }
        check(manager.focus.isEmpty()) { "Retired mixer acquired the new session's focus without Start or DATA" }
        check((field(newAudio, "playbackLease") as PlaybackFocusLease).snapshot().isEmpty())
        check(!decoder.playbackCallbacks.canRender())
    } finally {
        resumeRecovery.countDown(); decoder.stop(); newAudio?.releaseAllFocus()
        handler.runAll(); handler.reset()
    }
    println("PASS retired recovery owner cannot acquire focus after ${if (reconnect) "reconnection" else "same-session codec replacement"}")
}

private fun ownerIsolationRegression() {
    val handler = android.os.Handler
    handler.reset()
    val decoder = AudioDecoder()
    val manager = AudioManager()
    val audio = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager,
        com.andrerinas.openheadunit.utils.Settings())
    try {
        decoder.start(5, 3, 48000, 16, 2)
        decoder.start(6, 3, 48000, 16, 2)
        audio.preparePlayback(5); audio.preparePlayback(6); handler.runAll()
        val oldOwner = decoder.getTrack(5)!!.playbackOwner
        val callbacks = decoder.playbackCallbacks
        audio.noteSinkCodec(5, com.andrerinas.openheadunit.aap.protocol.proto.Media.MediaCodecType.MEDIA_CODEC_AUDIO_AAC_LC_VALUE)
        audio.precreateAudioTrack(5); handler.runAll()
        check(manager.focus.size == 1)
        val lease = field(audio, "playbackLease") as PlaybackFocusLease
        check(lease.snapshot().keys == setOf(6))
        println("PASS retiring one registration keeps another channel's focus demand")
        val newOwner = decoder.getTrack(5)!!.playbackOwner
        audio.preparePlayback(5); handler.runAll()
        callbacks.retired(5, oldOwner) // An old cleanup arrives after the new Start.
        handler.runAll()
        check(lease.snapshot().getValue(5).owner === newOwner && manager.focus.size == 1)
        check(decoder.isQuiescent(5, oldOwner, SystemClock.elapsedRealtime()))
        check(!decoder.isQuiescent(5, newOwner, SystemClock.elapsedRealtime()))
        decoder.stop(5); handler.runAll()
        check(lease.snapshot().keys == setOf(6) && manager.focus.size == 1)
        decoder.stop(6); handler.runAll()
        check(lease.snapshot().isEmpty() && manager.focus.isEmpty())
        println("PASS late retirement and old drain snapshots cannot cancel a replacement's Start")
    } finally { decoder.stop(); audio.releaseAllFocus(); handler.runAll(); handler.reset() }
}

private fun fatalOwnerRegression() {
    val handler = android.os.Handler
    handler.reset()
    val decoder = AudioDecoder()
    val manager = AudioManager()
    val audio = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager,
        com.andrerinas.openheadunit.utils.Settings())
    val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
    android.os.Process.hook = { name -> if (name.startsWith("AudioMixer")) { entered.countDown(); unblock.await() } }
    try {
        decoder.start(5, 3, 48000, 16, 2)
        check(entered.await(1, TimeUnit.SECONDS))
        audio.preparePlayback(5); handler.runAll()
        val track = decoder.getTrack(5)!!
        val mixer = field(track, "mixer") as AudioMixer
        val dead = FixedWriteDevice(-2)
        val output = RecoveringPcmOutput(dead, { dead }, SystemClock::elapsedRealtime, {})
        mixer.javaClass.getDeclaredField("output").apply { isAccessible = true }.set(mixer, output)
        track.write(pcm(240), 0, 960)
        android.os.Process.hook = null; unblock.countDown()
        waitFor("fatal owner closes its output") { dead.closed }
        handler.runAll()
        check(manager.focus.isEmpty() && (field(audio, "playbackLease") as PlaybackFocusLease).snapshot().isEmpty())
    } finally {
        android.os.Process.hook = null; unblock.countDown()
        decoder.stop(); audio.releaseAllFocus(); handler.runAll(); handler.reset()
    }
    println("PASS fatal output owner retires its demand even without protocol Stop")
}

private class FixedWriteDevice(private val result: Int) : PcmOutput {
    override val name = "Controlled write"
    override val capacityFrames = 19200
    override var bufferFrames = 960
    override val burstFrames = 480
    override val underruns = 0
    @Volatile var closed = false
    override fun start() {}
    override fun pause() {}
    override fun close() { closed = true }
    override fun setBufferFrames(frames: Int): Int { bufferFrames = frames; return frames }
    override fun write(data: ShortArray, offset: Int, count: Int) = if (result >= 0) minOf(count, result) else result
}

private fun rearmedDrainRegression(startDuringFailure: Boolean) {
    val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
    android.os.Process.hook = { name -> if (name.startsWith("AudioMixer")) { entered.countDown(); unblock.await() } }
    val mixer = AudioMixer()
    val track = wrapper(mixer, false)
    track.write(pcm(240), 0, 960); track.pauseForIdle()
    mixer.start(); check(entered.await(1, TimeUnit.SECONDS))
    val resumed = CountDownLatch(1)
    val replacement = object : PcmOutput {
        override val name = "Slow resumed output"
        override val capacityFrames = 19200
        override var bufferFrames = 960
        override val burstFrames = 480
        override val underruns = 0
        override fun start() {}
        override fun pause() {}
        override fun close() {}
        override fun setBufferFrames(frames: Int): Int { bufferFrames = frames; return frames }
        override fun write(data: ShortArray, offset: Int, count: Int): Int {
            resumed.countDown()
            Thread.sleep(10)
            return minOf(count, 2)
        }
    }
    var attempts = 0
    val output = RecoveringPcmOutput(ReplayDrainDevice(true).apply { heardReal = true }, {
        when (++attempts) {
            1 -> error("route not ready")
            2 -> {
                // Start arrives after the final attempt captured its previous version.
                if (startDuringFailure) { track.preparePlayback(); track.pauseForIdle() }
                error("last old attempt failed")
            }
            else -> replacement
        }
    }, SystemClock::elapsedRealtime, {})
    mixer.javaClass.getDeclaredField("output").apply { isAccessible = true }.set(mixer, output)
    android.os.Process.hook = null; unblock.countDown()
    try {
        if (!startDuringFailure) {
            waitFor("final failure is published") { output.isParked }
            track.preparePlayback(); track.pauseForIdle()
        }
        check(resumed.await(2, TimeUnit.SECONDS))
        Thread.sleep(1200)
        check(!track.isQuiescent(SystemClock.elapsedRealtime())) {
            "A superseded terminal failure released focus while resumed PCM was still pending"
        }
    } finally { finish(track); mixer.stop() }
    println("PASS Start ${if (startDuringFailure) "before" else "after"} final failure cannot park a resumed pending block")
}


private fun focusPostingRegression() {
    val handler = android.os.Handler
    handler.reset()
    for (sdk in listOf(16, 33)) {
        Build.VERSION.SDK_INT = sdk
        val manager = AudioManager()
        val decoder = AudioDecoder()
        val audio = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager,
            com.andrerinas.openheadunit.utils.Settings())
        decoder.start(5, 3, 48000, 16, 2)
        audio.preparePlayback(5)
        handler.runAll()
        check(manager.focus.size == 1)
        handler.beforePost = {
            handler.beforePost = null
            // Insert a new activity exactly between clear() and the old release's post().
            audio.preparePlayback(5)
        }
        audio.pauseAllAudio()
        handler.runAll()
        check(manager.focus.size == 1 && decoder.playbackCallbacks.canRender())
        audio.releaseAllFocus(); handler.runAll(); check(manager.focus.isEmpty())
        decoder.stop()
        handler.reset()
    }
    Build.VERSION.SDK_INT = 33
    println("PASS actual AapAudio sleep release cannot cancel a newer lease on API 16 or 33")

    val manager = AudioManager()
    val audio = com.andrerinas.openheadunit.aap.AapAudio(AudioDecoder(), manager,
        com.andrerinas.openheadunit.utils.Settings())
    val listener = AudioManager.OnAudioFocusChangeListener {}
    val transportFinished = CountDownLatch(1)
    val entered = CountDownLatch(1)
    val unblock = CountDownLatch(1)
    manager.onRequest = { entered.countDown(); unblock.await() }
    val transport = Thread({
        audio.postProtocolFocusChange(3, 1, listener)
        transportFinished.countDown() // next transport dispatch can execute before Binder returns
    }, "AapTransport:Handler::Poll")
    transport.start()
    check(transportFinished.await(1, TimeUnit.SECONDS))
    val main = Thread({ handler.runAll() }, "focus-main")
    main.start(); check(entered.await(1, TimeUnit.SECONDS))
    audio.releaseAllFocus()
    unblock.countDown(); main.join(2000)
    check(!main.isAlive && manager.focus.isEmpty())
    check(manager.requestThreads == listOf("focus-main"))
    handler.reset()

    val neverCalled = AudioManager()
    val closed = com.andrerinas.openheadunit.aap.AapAudio(AudioDecoder(), neverCalled,
        com.andrerinas.openheadunit.utils.Settings())
    closed.postProtocolFocusChange(3, 1, listener)
    closed.releaseAllFocus()
    handler.runAll()
    check(neverCalled.requestThreads.isEmpty())
    handler.reset()
    println("PASS protocol focus leaves transport free and invalidates queued or in-flight acquisition at teardown")
}

private fun closedTailRegression() {
    val mixer = AudioMixer()
    val track = wrapper(mixer)
    val codec = field(track, "decoder") as MediaCodec
    track.write(ByteArray(64), 0, 64)
    waitFor("first held AAC AU") { codec.queued == 1 && field(track, "submitInFlight") == false }
    track.pauseForIdle()
    SystemClock.offsetMs += 2000
    track.preparePlayback()
    Thread.sleep(80)
    check(field(track, "decoder") === codec)
    track.write(ByteArray(64), 0, 64)
    waitFor("second AU advances retained codec") { codec.queued == 2 }
    codec.emit(pcm(2048))
    check(mixer.depthFramesFor(5) == 2048)
    finish(track); SystemClock.offsetMs = 0
    println("PASS quiet closed AAC tail survives Start until new input can advance it")
}

private class ReplayDrainDevice(private val failInDrain: Boolean) : PcmOutput {
    override val name = "Replay test"
    override val capacityFrames = 19200
    override var bufferFrames = 960
    override val burstFrames = 480
    override val underruns = 0
    var heardReal = false
    var beforeFailure: (() -> Unit)? = null
    @Volatile var replayAt = -1L
    @Volatile var pauseAt = -1L
    override fun start() {}
    override fun pause() { pauseAt = SystemClock.elapsedRealtime() }
    override fun close() {}
    override fun setBufferFrames(frames: Int): Int { bufferFrames = frames; return frames }
    override fun closeForRecovery() = ShortArray(80) { 600 }
    override fun write(data: ShortArray, offset: Int, count: Int): Int {
        val real = (offset until offset + count).any { data[it].toInt() != 0 }
        if (failInDrain && heardReal && !real) {
            beforeFailure?.invoke(); beforeFailure = null
            return -6
        }
        heardReal = heardReal || real
        if (!failInDrain && real) replayAt = SystemClock.elapsedRealtime()
        Thread.sleep(5)
        return count
    }
}

private fun replayDrainRegression() {
    val entered = CountDownLatch(1)
    val unblock = CountDownLatch(1)
    android.os.Process.hook = { name -> if (name.startsWith("AudioMixer")) { entered.countDown(); unblock.await() } }
    val mixer = AudioMixer()
    val track = wrapper(mixer, false)
    track.write(pcm(240), 0, 960); track.pauseForIdle()
    mixer.start(); check(entered.await(1, TimeUnit.SECONDS))
    val replacement = ReplayDrainDevice(false)
    var attempts = 0
    val output = RecoveringPcmOutput(ReplayDrainDevice(true), {
        if (++attempts == 1) error("route not ready")
        replacement
    }, SystemClock::elapsedRealtime, {})
    mixer.javaClass.getDeclaredField("output").apply { isAccessible = true }.set(mixer, output)
    android.os.Process.hook = null; unblock.countDown()
    waitFor("recovery prefix reaches replacement during idle drain") { replacement.replayAt >= 0 }
    check(!track.isQuiescent(SystemClock.elapsedRealtime()))
    waitFor("replacement completes a fresh drain before pause") { replacement.pauseAt >= 0 }
    val drainMs = replacement.bufferFrames * 1000L / 48000 + 20
    check(replacement.pauseAt - replacement.replayAt >= drainMs) {
        "paused ${replacement.pauseAt - replacement.replayAt}ms after replay; needed $drainMs"
    }
    finish(track); mixer.stop()
    println("PASS recovery during silence drain restarts pause and focus drain deadlines")
}


private fun terminalReplayFocusRegression() {
    val lease = focusLease()
    lease.complete(lease.activity(5, SystemClock.elapsedRealtime())!!)
    val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
    android.os.Process.hook = { name -> if (name.startsWith("AudioMixer")) { entered.countDown(); unblock.await() } }
    val mixer = AudioMixer(canRender = { lease.canRender(SystemClock.elapsedRealtime()) })
    val track = wrapper(mixer, false)
    track.write(pcm(240), 0, 960); track.pauseForIdle()
    mixer.start(); check(entered.await(1, TimeUnit.SECONDS))
    val replacement = ReplayDrainDevice(false)
    val working = java.util.concurrent.atomic.AtomicBoolean()
    val output = RecoveringPcmOutput(ReplayDrainDevice(true), {
        check(working.get()) { "route unavailable" }
        replacement
    }, SystemClock::elapsedRealtime, {})
    mixer.javaClass.getDeclaredField("output").apply { isAccessible = true }.set(mixer, output)
    android.os.Process.hook = null; unblock.countDown()
    try {
        waitFor("terminal replay is parked") { track.isQuiescent(SystemClock.elapsedRealtime()) }
        lease.release(5, lease.snapshot().getValue(5))
        working.set(true)
        track.preparePlayback()
        // AapAudio can be preempted between rearming the owner and recording new focus activity.
        Thread.sleep(40)
        check(replacement.replayAt < 0) { "Recovered speech played before the new focus lease existed" }
        lease.complete(lease.activity(5, SystemClock.elapsedRealtime())!!)
        waitFor("recovered speech after the new focus result") { replacement.replayAt >= 0 }
    } finally { finish(track); mixer.stop() }
    println("PASS terminal recovery of an idle-cycle prefix waits for the new focus lease")
}


private fun replayActivityRegression() {
    val lease = focusLease()
    val request = java.util.concurrent.atomic.AtomicReference<PlaybackFocusLease.Request>()
    val decoder = AudioDecoder()
    decoder.playbackCallbacks = AudioDecoder.PlaybackCallbacks(
        canRender = { lease.canRender(SystemClock.elapsedRealtime()) },
        registered = { id, owner -> lease.register(id, owner); Unit },
        retired = { id, owner -> lease.retire(id, owner); Unit },
        activity = { id, owner -> lease.activity(id, owner, SystemClock.elapsedRealtime())?.let { request.set(it) }; Unit }
    )
    val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
    android.os.Process.hook = { name -> if (name.startsWith("AudioMixer")) { entered.countDown(); unblock.await() } }
    decoder.start(5, 3, 48000, 16, 2)
    val track = decoder.getTrack(5)!!
    val mixer = field(track, "mixer") as AudioMixer
    check(entered.await(1, TimeUnit.SECONDS))
    track.write(pcm(240), 0, 960); track.pauseForIdle()
    lease.complete(request.getAndSet(null))
    val first = ReplayDrainDevice(true).apply {
        beforeFailure = { lease.release(5, lease.snapshot().getValue(5)) }
    }
    val replacement = ReplayDrainDevice(false)
    val output = RecoveringPcmOutput(first, { replacement }, SystemClock::elapsedRealtime, {})
    mixer.javaClass.getDeclaredField("output").apply { isAccessible = true }.set(mixer, output)
    android.os.Process.hook = null; unblock.countDown()
    try {
        waitFor("software replay renews activity after focus was released") { request.get() != null }
        check(replacement.replayAt < 0)
        lease.complete(request.get())
        waitFor("replay follows reacquisition without another packet") { replacement.replayAt >= 0 }
    } finally { decoder.stop(); mixer.stop() }
    println("PASS recovered PCM renews focus activity without requiring another DATA or Start")
}

private fun retiredBeforeRegistrationRegression() {
    val handler = android.os.Handler
    handler.reset()
    val decoder = AudioDecoder()
    val manager = AudioManager()
    val audio = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager,
        com.andrerinas.openheadunit.utils.Settings())
    val entered = CountDownLatch(1); val unblock = CountDownLatch(1)
    val dead = FixedWriteDevice(-2)
    var candidateMixer: AudioMixer? = null
    android.os.Process.hook = { name -> if (name.startsWith("AudioMixer")) { entered.countDown(); unblock.await() } }
    MediaCodec.startHook = {
        check(entered.await(1, TimeUnit.SECONDS))
        val mixer = field(decoder, "mixer") as AudioMixer
        candidateMixer = mixer
        val output = RecoveringPcmOutput(dead, { dead }, SystemClock::elapsedRealtime, {})
        mixer.javaClass.getDeclaredField("output").apply { isAccessible = true }.set(mixer, output)
        mixer.prepareChannel(channel(mixer))
        android.os.Process.hook = null; unblock.countDown()
        waitFor("initial output failure before lease registration") { dead.closed }
        check(!mixer.isRunning())
    }
    try {
        // A codec setup pauses construction before registered(), after its bank already exists.
        decoder.start(5, 3, 48000, 16, 2, isAac = true, staticAudioFocus = true)
        MediaCodec.startHook = null
        audio.preparePlayback(5); handler.runAll()
        repeat(10) { handler.delayed.poll()?.run(); handler.runAll() }
        val lease = field(audio, "playbackLease") as PlaybackFocusLease
        check(manager.focus.isEmpty() && lease.snapshot().isEmpty()) { "retired initial owner acquired focus" }
        check((field(decoder, "audioTracks") as Map<*, *>).isEmpty()) { "dead initial candidate was published" }
        check(candidateMixer?.isRunning() == false)
        decoder.start(5, 3, 48000, 16, 2, staticAudioFocus = false)
        audio.preparePlayback(5); handler.runAll()
        val fresh = decoder.getTrack(5)!!
        fresh.write(pcm(240), 0, 960); fresh.pauseForIdle()
        check(manager.focus.size == 1)
        waitFor("normal owner can play after rejected initial candidate") {
            AudioTrack.created.any { output -> output.samples.any { it.toInt() != 0 } }
        }
    } finally {
        MediaCodec.startHook = null
        android.os.Process.hook = null; unblock.countDown()
        decoder.stop(); audio.releaseAllFocus(); handler.runAll(); handler.reset()
    }
    println("PASS retirement before registration rejects the dead candidate and permits a fresh owner")
}

private fun aacProgressOwnershipRegression(csdReplacement: Boolean) {
    val handler = android.os.Handler
    handler.reset()
    val decoder = AudioDecoder()
    val audio = com.andrerinas.openheadunit.aap.AapAudio(decoder, AudioManager(),
        com.andrerinas.openheadunit.utils.Settings().apply { staticAudioFocus = true })
    val entered = CountDownLatch(1); val resume = CountDownLatch(1)
    val mode = if (csdReplacement) "CSD" else "watchdog"
    val callbackName = "old-progress-$mode"
    var callback: Thread? = null
    try {
        audio.noteSinkCodec(5, 2); audio.precreateAudioTrack(5); audio.preparePlayback(5)
        val track = decoder.getTrack(5)!!
        val old = field(track, "decoder") as MediaCodec
        fun data() {
            check(audio.process(com.andrerinas.openheadunit.aap.AapMessage(5,
                com.andrerinas.openheadunit.aap.protocol.proto.Media.MsgType.MEDIA_MESSAGE_DATA_VALUE,
                ByteArray(72))))
        }
        data()
        waitFor("original codec accepted input") { old.queued == 1 && field(track, "submitInFlight") == false }
        SystemClock.hook = {
            // The output bytes are already copied; progress has not yet been published.
            if (Thread.currentThread().name == callbackName) {
                entered.countDown(); resume.await()
            }
        }
        old.currentOutput = java.nio.ByteBuffer.wrap(pcm(240))
        callback = Thread({ old.callback!!.onOutputBufferAvailable(old, 0,
            MediaCodec.BufferInfo().apply { size = 960; presentationTimeUs = 77_000L }) }, callbackName)
        callback.start(); check(entered.await(2, TimeUnit.SECONDS))
        if (csdReplacement) {
            check(audio.process(com.andrerinas.openheadunit.aap.AapMessage(5,
                com.andrerinas.openheadunit.aap.protocol.proto.Media.MsgType.MEDIA_MESSAGE_CODEC_CONFIG_VALUE,
                byteArrayOf(0x11, 0x90.toByte(), 0))))
            data()
        } else {
            SystemClock.offsetMs += 1500
        }
        waitFor("replacement codec started after $mode") {
            (field(track, "decoder") as? MediaCodec)?.let { it !== old && it.callback != null } == true
        }
        val fresh = field(track, "decoder") as MediaCodec
        if (!csdReplacement) data()
        waitFor("replacement codec accepted input") { fresh.queued == 1 && field(track, "submitInFlight") == false }
        val watchdog = field(track, "progressWatchdog")!!
        val waitingSince = field(watchdog, "outputWaitingSince")
        check((waitingSince as Long) >= 0)
        val copiedMs = field(track, "lastOutputCopiedMs")
        val ptsUs = field(track, "lastOutputPtsUs")
        resume.countDown(); callback.join(3000); check(!callback.isAlive)
        check(field(watchdog, "outputWaitingSince") == waitingSince) {
            "$mode: retired codec output erased the replacement's watchdog"
        }
        check(field(track, "lastOutputCopiedMs") == copiedMs && field(track, "lastOutputPtsUs") == ptsUs) {
            "$mode: retired codec output changed replacement progress metadata"
        }
        check((field(track, "mixerChannel") as AudioMixer.Channel).buffer.depthFrames() == 0) {
            "$mode: retired codec PCM entered the replacement's bank"
        }
        SystemClock.hook = null
        SystemClock.offsetMs += 1500
        waitFor("replacement still recovers without more DATA or Stop") { field(track, "decoder") !== fresh }
        println("PASS old AAC progress cannot erase new codec watchdog or metadata after $mode replacement")
    } finally {
        resume.countDown(); callback?.join(3000); SystemClock.hook = null
        audio.releaseAllFocus(); handler.runAll(); decoder.stop(); handler.reset(); SystemClock.offsetMs = 0
    }
}

private fun codecFramingRegression() {
    val handler = android.os.Handler
    handler.reset()
    val decoder = AudioDecoder()
    val audio = com.andrerinas.openheadunit.aap.AapAudio(decoder, AudioManager(),
        com.andrerinas.openheadunit.utils.Settings())
    try {
        audio.noteSinkCodec(5, 4); audio.precreateAudioTrack(5)
        val adts = decoder.getTrack(5)!!
        val originalCodec = field(adts, "decoder") as MediaCodec
        check(originalCodec.outputFormat.getInteger(MediaFormat.KEY_IS_ADTS) == 1)
        audio.noteSinkCodec(5, 4); audio.precreateAudioTrack(5)
        check(decoder.getTrack(5) === adts && field(adts, "decoder") === originalCodec)
        println("PASS negotiated ADTS reaches MediaCodec and repeated ADTS Setup keeps its owner")
        originalCodec.callback!!.onError(originalCodec, MediaCodec.CodecException())
        waitFor("ADTS recovery creates a new codec") {
            (field(adts, "decoder") as? MediaCodec)?.let {
                it !== originalCodec && it.outputFormat.containsKey(MediaFormat.KEY_IS_ADTS)
            } == true
        }
        val recovered = field(adts, "decoder") as MediaCodec
        check(recovered.outputFormat.getInteger(MediaFormat.KEY_IS_ADTS) == 1)
        val createdBefore = AudioTrack.created.size
        audio.preparePlayback(5); handler.runAll()
        adts.write(ByteArray(64), 0, 64)
        waitFor("ADTS input after codec recovery") { recovered.queued == 1 }
        recovered.emit(pcm(240)); adts.pauseForIdle()
        waitFor("decoded ADTS output reaches the bank and device double") {
            AudioTrack.created.drop(createdBefore).any { output -> output.samples.any { it.toInt() != 0 } }
        }
        println("PASS codec recovery preserves ADTS framing and decoded PCM reaches playback")
        audio.noteSinkCodec(5, 2); audio.precreateAudioTrack(5)
        val raw = decoder.getTrack(5)!!
        check(raw !== adts && raw.builtCodec() == AudioSinkCodec.AAC_LC)
        check(!(field(raw, "decoder") as MediaCodec).outputFormat.containsKey(MediaFormat.KEY_IS_ADTS))
        audio.noteSinkCodec(5, 4); audio.precreateAudioTrack(5)
        val framed = decoder.getTrack(5)!!
        check(framed !== raw && framed.builtCodec() == AudioSinkCodec.AAC_LC_ADTS)
        check((field(framed, "decoder") as MediaCodec).outputFormat.getInteger(MediaFormat.KEY_IS_ADTS) == 1)
        println("PASS AAC raw and ADTS Setup transitions rebuild in both directions")
    } finally {
        decoder.stop(); audio.releaseAllFocus(); handler.runAll(); handler.reset()
    }
}

private fun delayedSetupRegression(beforeNewSession: Boolean) {
    val handler = android.os.Handler
    handler.reset()
    val decoder = AudioDecoder()
    val old = com.andrerinas.openheadunit.aap.AapAudio(decoder, AudioManager(),
        com.andrerinas.openheadunit.utils.Settings())
    old.noteSinkCodec(5, 1)
    val entered = CountDownLatch(1); val resume = CountDownLatch(1)
    val name = "LateSetup-$beforeNewSession"
    com.andrerinas.openheadunit.utils.AppLog.hook = { line ->
        if (Thread.currentThread().name == name && line.startsWith("AudioDecoder.start:")) {
            entered.countDown(); resume.await()
        }
    }
    val delayed = Thread({ old.precreateAudioTrack(5) }, name)
    var freshAudio: com.andrerinas.openheadunit.aap.AapAudio? = null
    try {
        delayed.start(); check(entered.await(1, TimeUnit.SECONDS))
        old.releaseAllFocus(); decoder.stop(); handler.runAll()
        if (beforeNewSession) {
            resume.countDown(); delayed.join(3000); check(!delayed.isAlive)
            check((field(decoder, "audioTracks") as Map<*, *>).isEmpty()) { "closed session published a delayed owner" }
        }
        val manager = AudioManager()
        freshAudio = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager,
            com.andrerinas.openheadunit.utils.Settings())
        val createdBefore = AudioTrack.created.size
        freshAudio.noteSinkCodec(5, if (beforeNewSession) 1 else 2)
        freshAudio.precreateAudioTrack(5)
        val track = decoder.getTrack(5)!!
        freshAudio.preparePlayback(5); handler.runAll()
        if (!beforeNewSession) {
            resume.countDown(); delayed.join(3000); check(!delayed.isAlive)
            check(decoder.getTrack(5) === track && field(track, "isAac") == true) { "old Setup replaced the new AAC owner" }
        }
        if (beforeNewSession) track.write(pcm(240), 0, 960) else {
            val codec = field(track, "decoder") as MediaCodec
            track.write(ByteArray(64), 0, 64)
            waitFor("fresh AAC input after late Setup") { codec.queued == 1 }
            codec.emit(pcm(240))
        }
        track.pauseForIdle()
        waitFor("fresh session PCM renders after late Setup") {
            AudioTrack.created.drop(createdBefore).any { output -> output.samples.any { it.toInt() != 0 } }
        }
    } finally {
        resume.countDown(); delayed.join(3000)
        com.andrerinas.openheadunit.utils.AppLog.hook = null
        old.releaseAllFocus(); freshAudio?.releaseAllFocus(); decoder.stop(); handler.runAll(); handler.reset()
    }
    println("PASS delayed old Setup ${if(beforeNewSession) "before" else "after"} reconnection cannot publish or replace the fresh owner")
}

private fun retiredSessionCommandsRegression() {
    val handler = android.os.Handler
    handler.reset()
    val decoder = AudioDecoder()
    val old = com.andrerinas.openheadunit.aap.AapAudio(decoder, AudioManager(),
        com.andrerinas.openheadunit.utils.Settings().apply { guidanceVolumeOffset = 100 })
    val oldSession = decoder.captureSession()
    old.releaseAllFocus()
    val manager = AudioManager()
    val fresh = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager,
        com.andrerinas.openheadunit.utils.Settings())
    try {
        fresh.noteSinkCodec(5, 2); fresh.precreateAudioTrack(5)
        val track = decoder.getTrack(5)!!
        val config = field(track, "incomingAacConfig")
        fresh.preparePlayback(5); handler.runAll()
        old.preparePlayback(5); old.stopAudio(5); old.pauseAllAudio(); old.restartAudio(); old.updateGains()
        old.noteSinkCodec(5, 1); old.precreateAudioTrack(5)
        old.process(com.andrerinas.openheadunit.aap.AapMessage(5, 1, byteArrayOf(0x11, 0x90.toByte(), 0)))
        old.process(com.andrerinas.openheadunit.aap.AapMessage(5, 0, ByteArray(72)))
        decoder.stop(5, oldSession); decoder.stop(oldSession)
        handler.runAll()
        check(decoder.getTrack(5) === track && field(track, "inputClosed") == false)
        check(field(track, "incomingAacConfig") === config && manager.focus.size == 1)
        check((field(track, "mixerChannel") as AudioMixer.Channel).gain == 1f)
        Thread.sleep(30)
        check((field(track, "decoder") as MediaCodec).queued == 0)
        old.releaseAllFocus(); handler.runAll()
        check(decoder.getTrack(5) === track && manager.focus.size == 1)
    } finally { fresh.releaseAllFocus(); decoder.stop(); handler.runAll(); handler.reset() }
    println("PASS retired session Start Stop DATA CSD gain sleep restart and late teardown leave the fresh owner intact")
}

private fun handoffRetirementBeforePublicationRegression() {
    val handler = android.os.Handler
    for (api in listOf(16, 33)) {
        android.os.Build.VERSION.SDK_INT = api; handler.reset()
        val decoder = AudioDecoder(); val manager = AudioManager()
        val audio = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager,
            com.andrerinas.openheadunit.utils.Settings())
        audio.noteSinkCodec(5, 1); audio.precreateAudioTrack(5)
        audio.postProtocolFocusChange(3, 1, AudioManager.OnAudioFocusChangeListener {}); handler.runAll()
        // RELEASE runs before the queued initial playback acquire, while that Start has demand.
        audio.postProtocolFocusChange(3, 4, AudioManager.OnAudioFocusChangeListener {})
        audio.preparePlayback(5)
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        val calls = java.util.concurrent.atomic.AtomicInteger()
        SystemClock.hook = {
            if (Thread.currentThread().name == "handoff-before-publication" && calls.incrementAndGet() == 2) {
                entered.countDown(); resume.await()
            }
        }
        val main = Thread({ handler.runAll() }, "handoff-before-publication").also { it.start() }
        try {
            check(entered.await(2, TimeUnit.SECONDS))
            check(field(audio, "playbackFocusRequest") == null && field(audio, "legacyPlaybackFocusListener") == null)
            audio.noteSinkCodec(5, 2); audio.precreateAudioTrack(5)
            check((field(audio, "playbackLease") as PlaybackFocusLease).snapshot().isEmpty())
            resume.countDown(); main.join(3000); check(!main.isAlive)
            handler.runAll(); check(manager.focus.isEmpty()) { "retired handoff stranded newly published focus" }
            SystemClock.hook = null
            val track = decoder.getTrack(5)!!
            audio.preparePlayback(5); handler.runAll()
            check(manager.focus.size == 1 && decoder.getTrack(5) === track)
        } finally {
            resume.countDown(); main.join(3000); SystemClock.hook = null
            audio.releaseAllFocus(); handler.runAll(); decoder.stop(); handler.reset()
        }
        println("PASS retirement before handoff resource publication releases current focus and permits fresh Start on API $api")
    }
    android.os.Build.VERSION.SDK_INT = 33
}

private fun drainedProtocolReleaseRegression() {
    val handler = android.os.Handler
    for (api in listOf(16, 33)) {
        android.os.Build.VERSION.SDK_INT = api; handler.reset()
        val decoder = AudioDecoder(); val manager = AudioManager()
        val audio = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager,
            com.andrerinas.openheadunit.utils.Settings())
        try {
            audio.noteSinkCodec(5, 1); audio.precreateAudioTrack(5); audio.preparePlayback(5); handler.runAll()
            audio.postProtocolFocusChange(3, 1, AudioManager.OnAudioFocusChangeListener {}); handler.runAll()
            val stopped = decoder.getTrack(5)!!
            stopped.pauseForIdle()
            waitFor("empty Stop completes warmup/output drain") {
                decoder.isQuiescent(5, stopped.playbackOwner, SystemClock.elapsedRealtime())
            }
            val requests = manager.requestThreads.size
            audio.postProtocolFocusChange(3, 4, AudioManager.OnAudioFocusChangeListener {}); handler.runAll()
            check(manager.requestThreads.size == requests && manager.focus.isEmpty())
            check((field(audio, "playbackLease") as PlaybackFocusLease).snapshot().isEmpty())
            audio.preparePlayback(5); handler.runAll()
            check(manager.focus.size == 1) { "drained protocol release prevented fresh Start focus" }
        } finally { audio.releaseAllFocus(); handler.runAll(); decoder.stop(); handler.reset() }
        println("PASS drained Stop avoids protocol handoff acquisition and rearms fresh Start on API $api")
    }
    android.os.Build.VERSION.SDK_INT = 33
}

private fun protocolFocusHandoffClosureRegression() {
    val handler = android.os.Handler
    for (api in listOf(16, 33)) {
        android.os.Build.VERSION.SDK_INT = api; handler.reset()
        val decoder = AudioDecoder(); val manager = AudioManager()
        val old = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager,
            com.andrerinas.openheadunit.utils.Settings())
        old.noteSinkCodec(5, 1); old.precreateAudioTrack(5); old.preparePlayback(5); handler.runAll()
        old.postProtocolFocusChange(3, 1, AudioManager.OnAudioFocusChangeListener {}); handler.runAll()
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        manager.onRequest = { entered.countDown(); resume.await() }
        old.postProtocolFocusChange(3, 4, AudioManager.OnAudioFocusChangeListener {})
        val main = Thread({ handler.runAll() }, "handoff-main").also { it.start() }
        check(entered.await(1, TimeUnit.SECONDS))
        val fresh = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager,
            com.andrerinas.openheadunit.utils.Settings())
        try {
            fresh.noteSinkCodec(5, 1); fresh.precreateAudioTrack(5); fresh.preparePlayback(5)
            val track = decoder.getTrack(5)!!
            manager.onRequest = null; resume.countDown(); main.join(3000); check(!main.isAlive)
            handler.runAll()
            check(manager.focus.size == 1 && manager.focus.single() === field(fresh, "playbackFocusListener"))
            old.releaseAllFocus(); handler.runAll()
            check(manager.focus.size == 1 && decoder.getTrack(5) === track)
        } finally {
            manager.onRequest = null; resume.countDown(); main.join(3000)
            old.releaseAllFocus(); fresh.releaseAllFocus(); handler.runAll(); decoder.stop(); handler.reset()
        }
        println("PASS protocol handoff in flight retires with its session and preserves the fresh owner on API $api")
    }
    android.os.Build.VERSION.SDK_INT = 33
}

private fun protocolPlaybackFocusRegression() {
    val handler = android.os.Handler
    val originalClock = SystemClock.offsetMs
    for (api in listOf(16, 33)) {
        for ((type, mode) in listOf(1 to PlaybackFocusPolicy.Mode.ALWAYS,
                2 to PlaybackFocusPolicy.Mode.ALWAYS, 1 to PlaybackFocusPolicy.Mode.NEVER)) {
            android.os.Build.VERSION.SDK_INT = api; handler.reset(); AudioTrack.blockWrites = true
            val decoder = AudioDecoder(); val manager = AudioManager()
            val audio = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager,
                com.andrerinas.openheadunit.utils.Settings().apply { playbackFocusMode = mode })
            val createdBefore = AudioTrack.created.size
            try {
                audio.noteSinkCodec(5, 1); audio.precreateAudioTrack(5); audio.preparePlayback(5)
                handler.runAll()
                val track = decoder.getTrack(5)!!
                val playbackClient = field(audio, "playbackFocusListener")!!
                track.write(pcm(240), 0, 960); track.pauseForIdle()
                if (audio.shouldHonourProtocolFocusRequest(false)) {
                    audio.postProtocolFocusChange(3, type, AudioManager.OnAudioFocusChangeListener {})
                }
                handler.runAll()
                if (mode == PlaybackFocusPolicy.Mode.ALWAYS && type == 1) {
                    check(!manager.focus.contains(playbackClient)) { "permanent GAIN model did not evict playback" }
                }
                manager.onAbandon = {
                    if (mode == PlaybackFocusPolicy.Mode.ALWAYS) check(manager.focus.contains(playbackClient)) {
                        "protocol release left pending PCM without playback focus on API $api"
                    }
                }
                audio.postProtocolFocusChange(3, 4, AudioManager.OnAudioFocusChangeListener {})
                handler.runAll(); manager.onAbandon = null
                if (mode == PlaybackFocusPolicy.Mode.ALWAYS) {
                    check(manager.focus.size == 1 && manager.focus.contains(playbackClient))
                } else check(manager.focus.isEmpty()) { "NEVER acquired focus at protocol release" }
                AudioTrack.blockWrites = false
                waitFor("retained PCM after protocol release") {
                    AudioTrack.created.drop(createdBefore).any { it.samples.any { sample -> sample.toInt() != 0 } }
                }
                waitFor("final PCM write completion") {
                    (field(track, "mixerChannel") as AudioMixer.Channel).writePending == false
                }
                SystemClock.offsetMs = originalClock + 2000
                waitFor("ordinary drained focus release") {
                    handler.delayed.poll()?.run(); handler.runAll(); manager.focus.isEmpty()
                }
            } finally {
                manager.onAbandon = null; AudioTrack.blockWrites = false
                audio.releaseAllFocus(); handler.runAll(); decoder.stop(); handler.reset()
                SystemClock.offsetMs = originalClock
            }
            println("PASS protocol $type release preserves pending PCM and respects $mode focus on API $api")
        }
    }
    android.os.Build.VERSION.SDK_INT = 33
}

private fun staticSessionFocusRegression() {
    val handler = android.os.Handler
    for (api in listOf(16, 19, 25, 33)) {
        android.os.Build.VERSION.SDK_INT = api; handler.reset()
        val manager = AudioManager()
        val decoder = AudioDecoder()
        val settings = com.andrerinas.openheadunit.utils.Settings().apply { staticAudioFocus = true }
        val connecting = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager, settings)
        check(manager.focus.isEmpty()) // No service-owned focus before the handshake is ready.
        connecting.releaseAllFocus(); handler.runAll(); check(manager.focus.isEmpty())
        val old = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager, settings)
        old.postProtocolFocusChange(3, 1, AudioManager.OnAudioFocusChangeListener {})
        handler.runAll(); check(manager.focus.size == 1)
        old.releaseAllFocus(); handler.runAll(); check(manager.focus.isEmpty())
        settings.staticAudioFocus = false
        settings.playbackFocusMode = PlaybackFocusPolicy.Mode.NEVER
        val fresh = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager, settings)
        fresh.noteSinkCodec(5, 1); fresh.precreateAudioTrack(5)
        fresh.preparePlayback(5); handler.runAll()
        check(manager.focus.isEmpty() && decoder.playbackCallbacks.canRender())
        old.releaseAllFocus(); handler.runAll(); check(manager.focus.isEmpty())
        fresh.releaseAllFocus(); decoder.stop(); handler.runAll(); handler.reset()
        println("PASS static focus is session-owned before/after handshake and cannot survive a NEVER reconnect on API $api")
    }
    android.os.Build.VERSION.SDK_INT = 33
}

private fun protocolFocusIdentityRegression() {
    val handler = android.os.Handler
    for (api in listOf(16, 33)) {
        android.os.Build.VERSION.SDK_INT = api; handler.reset()
        val decoder = AudioDecoder(); val manager = AudioManager()
        val old = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager,
            com.andrerinas.openheadunit.utils.Settings())
        var notified = -1
        repeat(2) { number ->
            old.postProtocolFocusChange(3, 2, AudioManager.OnAudioFocusChangeListener { notified = number })
        }
        handler.runAll()
        check(manager.focus.size == 1) { "transient protocol requests accumulated clients on API $api" }
        (manager.focus.single() as AudioManager.OnAudioFocusChangeListener).onAudioFocusChange(1)
        check(notified == 1)
        old.postProtocolFocusChange(3, 4, AudioManager.OnAudioFocusChangeListener {})
        handler.runAll(); check(manager.focus.isEmpty())
        // Even the same caller callback must produce distinct session-owned system clients.
        val sharedCallback = AudioManager.OnAudioFocusChangeListener {}
        old.postProtocolFocusChange(3, 2, sharedCallback); handler.runAll()
        val oldClient = manager.focus.single() as AudioManager.OnAudioFocusChangeListener
        val oldRequest = field(old, "audioFocusRequest") as android.media.AudioFocusRequest?
        val fresh = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager,
            com.andrerinas.openheadunit.utils.Settings())
        try {
            fresh.postProtocolFocusChange(3, 2, sharedCallback); handler.runAll()
            check(manager.focus.size == 1 && manager.focus.single() !== oldClient)
            if (api >= 26) manager.abandonAudioFocusRequest(oldRequest!!) else manager.abandonAudioFocus(oldClient)
            check(manager.focus.size == 1) { "old system client release removed the fresh focus on API $api" }
            old.releaseAllFocus(); handler.runAll(); check(manager.focus.size == 1)
        } finally {
            old.releaseAllFocus(); fresh.releaseAllFocus(); handler.runAll(); decoder.stop(); handler.reset()
        }
        check(manager.focus.isEmpty())
        println("PASS protocol focus replaces its listener-owned client and isolates sessions on API $api")
    }
    android.os.Build.VERSION.SDK_INT = 33
}

private fun throwingSessionClosureRegression() {
    val decoder = AudioDecoder(); var notifications = 0
    val old = decoder.openSession(AudioDecoder.PlaybackCallbacks(onSessionClosed = {
        notifications++; error("deliberate session notification failure")
    }))
    decoder.start(5, 3, 48000, 16, 2, staticAudioFocus = true, session = old)
    val retired = decoder.getTrack(5, old)!!
    val fresh = decoder.openSession(AudioDecoder.PlaybackCallbacks())
    try {
        check(old.closed && notifications == 1 && field(retired, "isRunning") == false)
        check(decoder.getTrack(5, fresh) == null)
        decoder.closeSession(old); check(notifications == 1)
        decoder.start(5, 3, 48000, 16, 2, staticAudioFocus = true, session = fresh)
        check(decoder.getTrack(5, fresh) != null)
    } finally { decoder.closeSession(fresh); finish(retired) }
    println("PASS throwing session-close notification runs once and still retires tracks before fresh playback")
}

private fun protocolSessionClosureRegression() {
    val handler = android.os.Handler
    for (inFlight in listOf(false, true)) {
        handler.reset()
        val decoder = AudioDecoder()
        val manager = AudioManager()
        val old = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager,
            com.andrerinas.openheadunit.utils.Settings())
        val entered = CountDownLatch(1); val resume = CountDownLatch(1)
        if (inFlight) manager.onRequest = { entered.countDown(); resume.await() }
        old.postProtocolFocusChange(3, 1, AudioManager.OnAudioFocusChangeListener {})
        val main = if (inFlight) Thread({ handler.runAll() }, "protocol-main").also { it.start() } else null
        if (inFlight) check(entered.await(1, TimeUnit.SECONDS))
        val fresh = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager,
            com.andrerinas.openheadunit.utils.Settings())
        try {
            fresh.noteSinkCodec(5, 1); fresh.precreateAudioTrack(5)
            manager.onRequest = null; resume.countDown(); main?.join(3000)
            check(main?.isAlive != true)
            handler.runAll()
            check(manager.focus.isEmpty()) { "old protocol focus survived decoder session replacement" }
            fresh.preparePlayback(5); handler.runAll()
            val owner = decoder.getTrack(5)!!
            check(manager.focus.size == 1)
            old.releaseAllFocus(); handler.runAll()
            check(manager.focus.size == 1 && decoder.getTrack(5) === owner)
        } finally {
            manager.onRequest = null; resume.countDown(); main?.join(3000)
            old.releaseAllFocus(); fresh.releaseAllFocus(); handler.runAll(); decoder.stop(); handler.reset()
        }
        println("PASS ${if(inFlight) "in-flight" else "queued"} old protocol focus retires with its decoder session and preserves the fresh focus")
    }
}

/** Saving preferences must not combine new focus/codec choices with old output ownership. */
private fun audioSettingsRegression() {
    val handler = android.os.Handler
    val changes = listOf<Pair<String, (com.andrerinas.openheadunit.utils.Settings) -> Unit>>(
        "static focus" to { it.staticAudioFocus = true },
        "focus policy" to { it.playbackFocusMode = PlaybackFocusPolicy.Mode.NEVER },
        "separate streams" to { it.separateAudioStreams = true },
        "media route" to { it.mediaAudioStream = 4 },
        "guidance route" to { it.guidanceAudioStream = 3 },
        "system route" to { it.systemAudioStream = 3 },
        "media codec" to { it.useAacAudio = true },
        "guidance codec" to { it.usePcmGuidance = true },
        "sink disabled" to { it.enableAudioSink = false },
        "DSP route" to { it.attachHwDspEqualizer = true }
    )
    val cases = changes.map { (label, change) ->
        Triple(label, com.andrerinas.openheadunit.utils.Settings(), change)
    } + listOf(
        Triple("static to dynamic", com.andrerinas.openheadunit.utils.Settings().apply { staticAudioFocus = true },
            { settings: com.andrerinas.openheadunit.utils.Settings -> settings.staticAudioFocus = false }),
        Triple("AAC to PCM", com.andrerinas.openheadunit.utils.Settings().apply { useAacAudio = true },
            { settings: com.andrerinas.openheadunit.utils.Settings -> settings.useAacAudio = false })
    )
    for ((label, settings, change) in cases) {
        handler.reset()
        val decoder = AudioDecoder()
        val manager = AudioManager()
        val old = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager, settings)
        val original = old.sessionConfig
        val originalStream = old.streamFor(5)
        var fresh: com.andrerinas.openheadunit.aap.AapAudio? = null
        try {
            old.noteSinkCodec(5, if (settings.useAacAudio) 2 else 1); old.precreateAudioTrack(5)
            val originalTrack = decoder.getTrack(5)!!
            change(settings)
            check(old.sessionConfig == original && old.streamFor(5) == originalStream)
            check(old.needsSessionRestart()) { "$label was applied without renegotiation" }
            check(decoder.getTrack(5) === originalTrack) { "old tracks rebuilt before disconnect" }
            old.releaseAllFocus(); handler.runAll()
            check(manager.focus.isEmpty())
            // Same decoder, settings and process; only the projection session is replaced.
            val outputsBeforeReconnect = AudioTrack.created.size
            val current = com.andrerinas.openheadunit.aap.AapAudio(decoder, manager, settings).also { fresh = it }
            check(!current.needsSessionRestart())
            check(current.sessionConfig != original)
            current.noteSinkCodec(5, if (settings.useAacAudio) 2 else 1)
            current.precreateAudioTrack(5)
            check(decoder.getTrack(5) !== originalTrack && decoder.getTrack(5) != null)
            check(decoder.sinkCodecFor(5)?.isAac == settings.useAacAudio)
            current.preparePlayback(5); handler.runAll()
            old.releaseAllFocus(); handler.runAll()
            val track = decoder.getTrack(5)!!
            if (settings.useAacAudio) {
                val codec = field(track, "decoder") as MediaCodec
                track.write(ByteArray(64), 0, 64)
                waitFor("AAC input after settings reconnect") { codec.queued == 1 }
                codec.emit(pcm(240))
            } else {
                track.write(pcm(240), 0, 960)
            }
            current.stopAudio(5); handler.runAll()
            waitFor("non-silent output after changing $label") {
                AudioTrack.created.drop(outputsBeforeReconnect).any { output ->
                    output.samples.any { it.toInt() != 0 }
                }
            }
        } finally {
            old.releaseAllFocus(); fresh?.releaseAllFocus(); decoder.stop(); handler.runAll(); handler.reset()
        }
    }
    handler.reset()
    val settings = com.andrerinas.openheadunit.utils.Settings()
    val decoder = AudioDecoder()
    val audio = com.andrerinas.openheadunit.aap.AapAudio(decoder, AudioManager(), settings)
    try {
        audio.noteSinkCodec(5, 1); audio.precreateAudioTrack(5)
        val first = decoder.getTrack(5)!!
        settings.audioLatencyMultiplier = 8
        settings.audioQueueCapacity = 50
        settings.useAAudioOutput = true
        settings.systemVolumeOffset = -50
        check(!audio.needsSessionRestart())
        audio.restartAudio()
        check(decoder.getTrack(5) == null)
        audio.precreateAudioTrack(5)
        val resumed = decoder.getTrack(5)!!
        check(resumed !== first && resumed.builtCodec() == AudioSinkCodec.PCM)
        check((field(resumed, "mixerChannel") as AudioMixer.Channel).gain == 0.5f)
        audio.preparePlayback(5); handler.runAll()
        check(decoder.playbackCallbacks.canRender())
    } finally { audio.releaseAllFocus(); decoder.stop(); handler.runAll(); handler.reset() }
    println("PASS session snapshots preserve focus/routing/format until replacement; local output changes retain the negotiated codec")
}

/** Mixed sinks share playback ownership, but never a codec or its lifecycle. */
private fun mixedCodecRegression(staticFocus: Boolean) {
    val handler = android.os.Handler
    handler.reset()
    val configs = com.andrerinas.openheadunit.aap.protocol.AudioConfigs
    configs.formats = mapOf(4 to com.andrerinas.openheadunit.aap.protocol.AudioConfigs.Config(16000, 16, 1), 5 to com.andrerinas.openheadunit.aap.protocol.AudioConfigs.Config(16000, 16, 1))
    val decoder = AudioDecoder()
    val settings = com.andrerinas.openheadunit.utils.Settings().apply {
        useAacAudio = false; usePcmGuidance = true; staticAudioFocus = staticFocus
    }
    val audio = com.andrerinas.openheadunit.aap.AapAudio(decoder, AudioManager(), settings)
    try {
        // Model AAC selected by the wireless cap while the saved AAC preference is off.
        audio.noteAnnouncedAudioCodecs(true)
        // Exercise unknown Setup fallback as well as repeated, valid Setup on each sink.
        for ((id, type) in listOf(6 to 2, 4 to 1, 5 to 1)) {
            audio.noteSinkCodec(id, 3)
            audio.precreateAudioTrack(id)
            val track = decoder.getTrack(id)!!
            check(track.builtCodec().isAac == (id == 6))
            check((field(track, "decoder") != null) == (id == 6))
            audio.noteSinkCodec(id, type); audio.precreateAudioTrack(id)
            check(decoder.getTrack(id) === track)
        }
        fun outputPositions() = AudioTrack.created.associateWith { it.samples.size }
        fun hasNewSound(positions: Map<AudioTrack, Int>, requireNegative: Boolean = false): Boolean {
            handler.runAll()
            return AudioTrack.created.any { output ->
                output.samples.drop(positions[output] ?: 0).any { sample ->
                    if (requireNegative) sample.toInt() < 0 else sample.toInt() != 0
                }
            }
        }
        val music = decoder.getTrack(6)!!
        audio.preparePlayback(6); handler.runAll()
        val codec = field(music, "decoder") as MediaCodec
        music.write(ByteArray(64), 0, 64)
        waitFor("mixed music AAC input") { codec.queued == 1 }
        // Guidance can finish while music has not supplied decoded output yet. A short 16 kHz
        // PCM tail must reach playback without creating or waiting for a guidance MediaCodec.
        for (id in listOf(4, 5, 4)) {
            val before = outputPositions()
            val voice = decoder.getTrack(id)!!
            val bytes = ByteArray(320) { if (it % 2 == 0) 64 else 0 }
            audio.preparePlayback(id); handler.runAll()
            voice.write(bytes, 0, bytes.size); audio.stopAudio(id); handler.runAll()
            waitFor("PCM tail on mixed sink $id") {
                hasNewSound(before)
            }
            check(decoder.getTrack(6) === music && field(music, "decoder") === codec)
        }
        val beforeMusic = outputPositions()
        // Music has a negative waveform, unlike the positive PCM prompts. A prompt still
        // draining on the shared output cannot satisfy this music-tail assertion.
        val musicPcm = ByteArray(960) { if (it % 2 == 0) 0 else 0xc0.toByte() }
        codec.emit(musicPcm); audio.stopAudio(6); handler.runAll()
        waitFor("music tail after PCM guidance") {
            hasNewSound(beforeMusic, requireNegative = true)
        }
        audio.restartAudio()
        for ((id, expected) in listOf(6 to true, 4 to false, 5 to false)) {
            audio.precreateAudioTrack(id)
            check(decoder.getTrack(id)!!.builtCodec().isAac == expected)
        }
        // Even with the option enabled, a phone's actual Setup is authoritative. Changing one
        // sink's codec must not recreate the others or interpret compressed input as PCM.
        val rebuiltMusic = decoder.getTrack(6)
        audio.noteSinkCodec(4, 2); audio.precreateAudioTrack(4)
        check(decoder.getTrack(4)!!.builtCodec() == AudioSinkCodec.AAC_LC)
        check(decoder.getTrack(6) === rebuiltMusic)
    } finally {
        audio.releaseAllFocus(); decoder.stop(); handler.runAll(); handler.reset(); configs.formats = emptyMap()
    }
    println("PASS mixed AAC music and PCM voice/system (staticFocus=$staticFocus) preserve tails, repeated Setup and local restart; actual Setup wins")
}

private fun pcmRateChangeTailRegression() {
    // Do not start the render worker: both sides of Start remain buffered deterministically.
    val mixer = AudioMixer(3, false)
    var retired = 0
    val track = AudioTrackWrapper(16000, 16, 1, false, 1f, mixer = mixer, channelId = 4,
        onOwnerRetired = { retired++ })
    val state = field(track, "mixerChannel") as AudioMixer.Channel
    try {
        val old = ByteArray(320) { if (it % 2 == 0) 64 else 0 }
        track.write(old, 0, old.size)
        track.pauseForIdle()
        check(state.buffer.depthFrames() == 480)
        check(track.updatePcmFormat(48000, 1))
        check(state.buffer.depthFrames() == 480 && retired == 0)
        track.preparePlayback()
        val fresh = ByteArray(960) { if (it % 2 == 0) 64 else 0 }
        track.write(fresh, 0, fresh.size)
        check(state.buffer.depthFrames() == 960) { "48k input was resampled with the old rate, or the old tail was lost" }
        check(track.updatePcmFormat(16000, 1))
        track.write(old, 0, old.size)
        check(state.buffer.depthFrames() == 1440 && state.rate == 16000 && retired == 0)
    } finally { finish(track); mixer.stop() }
    println("PASS PCM 16/48/16k changes preserve every queued frame and the playback owner")
}
