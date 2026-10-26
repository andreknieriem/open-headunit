package com.andrerinas.openheadunit.decoder.audio

import java.util.concurrent.CopyOnWriteArrayList

/** A deliberately delayed engine exposes output ownership/Stop ordering without Android JNI. */
internal fun timeStretchWorkerRegression() {
    // Compare direct transfers with array transfers through both ring boundaries.
    // Extreme values and asymmetric stereo expose byte order/offset mistakes; Stop
    // and backpressure must preserve the same samples and arrival metadata.
    val arrayQueue = NativePcmQueue(1000)
    val directQueue = NativePcmQueue(1000)
    val arrayRead = NativePcmQueue.Read()
    val directRead = NativePcmQueue.Read()
    val directBlock = TimeStretchBlock()
    val arrayBlock = ShortArray(960)
    val arrayBank = AdaptivePcmBuffer(nativeTimeCorrection = true)
    val directBank = AdaptivePcmBuffer(nativeTimeCorrection = true)
    val expected = ShortArray(960)
    val actual = ShortArray(960)
    repeat(200) { round ->
        val packet = ShortArray(1478) { (round * 1701 + it * 8191).toShort() }
        check(arrayQueue.offer(packet, packet.size, round.toLong()))
        check(directQueue.offer(packet, packet.size, round.toLong()))
        arrayQueue.stop(); directQueue.stop()
        do {
            arrayQueue.poll(arrayBlock, arrayRead)
            directQueue.poll(directBlock, directRead)
            check(arrayRead.frames == directRead.frames && arrayRead.packetFrames == directRead.packetFrames &&
                arrayRead.arrivalMs == directRead.arrivalMs)
            if (arrayRead.frames > 0) {
                repeat(arrayRead.frames * 2) { check(arrayBlock[it] == directBlock[it]) }
                val now = round * 30L
                check(arrayBank.writeIfFits(arrayBlock, arrayRead.frames * 2, now))
                check(directBank.writeIfFits(directBlock, directRead.frames * 2, now))
            }
        } while (arrayRead.frames > 0)
        check(arrayRead.frames == -1 && arrayQueue.isEmpty() && directQueue.isEmpty())
        arrayBank.finish(); directBank.finish()
        repeat(3) { cycle ->
            val now = round * 30L + cycle * 10L
            check(arrayBank.render(expected, now) == directBank.render(actual, now))
            check(expected.contentEquals(actual))
        }
    }
    val blockedBank = AdaptivePcmBuffer(nativeTimeCorrection = true)
    blockedBank.write(ShortArray(144_000) { 7 }, 144_000, 0)
    repeat(960) { directBlock[it] = (it * 31).toShort() }
    check(!blockedBank.writeIfFits(directBlock, 960, 1))
    repeat(960) { check(directBlock[it] == (it * 31).toShort()) }
    check(blockedBank.droppedFrames == 0L)
    println("PASS shared native blocks: wrap, stereo extremes, Stop, bank parity and backpressure")

    fun waitUntil(message: String, predicate: () -> Boolean) {
        val limit = System.nanoTime() + 3_000_000_000L
        while (!predicate()) { check(System.nanoTime() < limit) { message }; Thread.sleep(1) }
    }
    val events = CopyOnWriteArrayList<String>()
    var sequence = 0
    fun delayedEngine(): TimeStretchEngine {
        val id = ++sequence
        events += "open$id"
        return object : TimeStretchEngine {
            val pcm = ArrayList<Short>()
            var final = false
            var index = 0
            override fun process(pcm: TimeStretchBlock, frames: Int, tempo: Double) {
                check(!final); check(Thread.currentThread().name.startsWith("AudioTime-"))
                repeat(frames * 2) { this.pcm.add(pcm[it]) }; events += "input$id"
            }
            override fun receive(pcm: TimeStretchBlock): Int {
                if (!final) return 0
                val n = minOf(13, (this.pcm.size - index) / 2)
                repeat(n * 2) { pcm[it] = this.pcm[index++] }
                return n
            }
            override fun finish() { check(!final); final = true; events += "final$id" }
            override fun finished() = final && index == pcm.size
            override fun close() { events += "close$id" }
        }
    }
    val bank = AdaptivePcmBuffer(nativeTimeCorrection = true)
    val worker = TimeStretchWorker(AudioTimeCorrectionMode.WSOLA, bank, { 0L }, {}, { error(it) }, ::delayedEngine)
    try {
        val data = ShortArray(480) { 1000 }
        worker.offer(data, data.size, 0); data.fill(0)
        worker.finish()
        worker.offer(ShortArray(480) { 2000 }, 480, 10)
        worker.finish()
        waitUntil("both segment tails reach the bank") { worker.deliveredFrames == 480L && worker.isIdle() }
        check(worker.acceptedFrames == 480L && worker.submittedFrames == 480L)
        check(events == listOf("open1", "input1", "final1", "close1", "open2", "input2", "final2", "close2")) { events }
        val output = ShortArray(960)
        check(bank.render(output, 1000))
        check(output.take(480).any { it > 0 } && output.takeLast(100).all { it == 2000.toShort() })
        check(bank.droppedFrames == 0L)
    } finally { worker.close(); worker.awaitClosed() }

    // A fresh surplus permit may park with one source block already removed from
    // the queue. Stop/new input must not overtake it, and explicit close must wake it.
    for (abort in listOf(false, true)) {
        events.clear()
        sequence = 0
        val clock = java.util.concurrent.atomic.AtomicLong(500)
        val ready = AdaptivePcmBuffer(latencyMultiplier = 16, isMediaSink = true, nativeTimeCorrection = true)
        ready.write(ShortArray(60000) { 7 }, 60000, clock.get())
        val rendered = ShortArray(960)
        check(ready.render(rendered, clock.get()) && ready.canTimeCorrect())
        val paced = TimeStretchWorker(AudioTimeCorrectionMode.WSOLA, ready, clock::get, {}, {}, ::delayedEngine)
        val parked = TimeStretchWorker::class.java.getDeclaredField("waitingForRender").apply { isAccessible = true }
        try {
            paced.offer(ShortArray(960) { 1000 }, 960, clock.get())
            waitUntil("paced engine initialized") { paced.submittedFrames == 480L }
            for (now in 0L..500L step 10) ready.observeTimeCorrection(paced.policy, now, 480)
            val burst = ShortArray(12480) { 1000 }
            paced.offer(burst, burst.size, clock.get())
            burst.fill(-3000)
            waitUntil("source quota retains the next block") {
                paced.submittedFrames == 6240L && parked.getBoolean(paced)
            }
            check(!paced.isIdle())
            if (abort) {
                paced.close()
                paced.awaitClosed()
                check(paced.submittedFrames == 6240L && events.count { it == "close1" } == 1)
                check(paced.failure == null)
            } else {
                paced.finish()
                paced.offer(ShortArray(960) { 2000 }, 960, 600)
                paced.finish()
                clock.set(600)
                ready.observeTimeCorrection(paced.policy, clock.get(), 480)
                paced.spaceAvailable()
                waitUntil("Stop and next segment follow the retained block") { paced.isIdle() }
                check(paced.acceptedFrames == 7200L && paced.submittedFrames == 7200L)
                check(paced.rejectedFrames == 0L && paced.deliveredFrames == 7200L)
                val samples = ArrayList<Short>()
                for (cycle in 0 until 100) {
                    if (ready.isIdle()) break
                    clock.addAndGet(10)
                    ready.render(rendered, clock.get())
                    repeat(ready.lastRenderRealFrames * 2) { samples.add(rendered[it]) }
                }
                check(ready.isIdle() && samples.size == 73440)
                val tail = samples.takeLast(14400)
                check(tail.take(13440).all { it == 1000.toShort() })
                check(tail.takeLast(960).all { it == 2000.toShort() })
                check(ready.droppedFrames == 0L)
            }
        } finally { paced.close(); paced.awaitClosed() }
    }

    // Finalization may return more PCM than a ready bank can accept in one burst.
    val full = AdaptivePcmBuffer(nativeTimeCorrection = true)
    val initial = ShortArray(144_000) { 100 }
    full.write(initial, initial.size, 0)
    val waiting = TimeStretchWorker(AudioTimeCorrectionMode.PHASE_VOCODER, full, { 0L }, {}, {}, ::delayedEngine)
    try {
        waiting.offer(ShortArray(960) { 1000 }, 960, 0); waiting.finish()
        waitUntil("worker holds produced tail outside a full bank") { waiting.producedFrames > 0 }
        check(waiting.deliveredFrames == 0L && !waiting.isIdle())
        check(full.droppedFrames == 0L)
        full.render(ShortArray(960), 0)
        waiting.wake()
        waitUntil("tail resumes after space becomes available") { waiting.deliveredFrames == 480L && waiting.isIdle() }
        check(full.droppedFrames == 0L)
    } finally { waiting.close(); waiting.awaitClosed() }
    // An owner failure is independent of protocol Stop. Initial failure retires before
    // the mixer ever renders; a later failure waits for the device tail and drain time.
    for (withTail in listOf(false, true)) {
        var retired = 0
        val mixer = AudioMixer(3)
        val state = AudioMixer.Channel(48000, 2, 2, false, { retired++ },
            AudioTimeCorrectionMode.WSOLA, createEngine = { throw IllegalStateException("fixture") })
        val failed = state.timeStretch!!
        try {
            failed.offer(ShortArray(480), 480, 0)
            waitUntil("first-use failure is published") { failed.failure != null }
            waitUntil("failure finishes the empty bank") { state.isIdle() }
            if (withTail) {
                state.outputConsumed = false
                state.lastWriteCompletedMs = 100
                mixer.retireFailedTimeStretch(state, 1000)
                check(retired == 0) { "retired before the playback head reached the tail" }
                state.outputConsumed = true
                mixer.retireFailedTimeStretch(state, 110)
                check(retired == 0) { "retired before the final device drain interval" }
                state.writePending = true
                mixer.retireFailedTimeStretch(state, 1000)
                check(retired == 0) { "retired with a pending device write" }
                state.writePending = false
            }
            mixer.retireFailedTimeStretch(state, 1000)
            mixer.retireFailedTimeStretch(state, 1001)
            check(retired == 1) { "failure retirement must occur once, including before first render" }
            failed.offer(ShortArray(480), 480, 10)
            check(failed.rejectedFrames == 240L)
        } finally { failed.close(); failed.awaitClosed() }
    }

    // JNI NewGlobalRef can leave a pending OOME during bind. Use a preallocated
    // error, not heap exhaustion, and observe the same boundary as the consumer.
    for (withReadyTail in listOf(false, true)) {
        val allocationFailure = OutOfMemoryError("fixture bind allocation")
        val allocationEvents = CopyOnWriteArrayList<String>()
        var retired = 0
        val mixer = AudioMixer(3)
        val state = AudioMixer.Channel(48000, 2, 2, false, { retired++ },
            AudioTimeCorrectionMode.WSOLA, onReady = { allocationEvents += "ready" }, createEngine = {
                object : TimeStretchEngine {
                    override fun bind(input: TimeStretchBlock, output: TimeStretchBlock) {
                        allocationEvents += "bind"
                        throw allocationFailure
                    }
                    override fun process(pcm: TimeStretchBlock, frames: Int, tempo: Double) = error("process after failed bind")
                    override fun receive(pcm: TimeStretchBlock): Int = error("receive after failed bind")
                    override fun finish() = error("finish after failed bind")
                    override fun finished() = false
                    override fun close() { allocationEvents += "close" }
                }
            })
        val failed = state.timeStretch!!
        val pcm = ShortArray(480) { 1000 }
        val output = ShortArray(960)
        try {
            if (withReadyTail) {
                state.buffer.write(pcm, pcm.size, 0)
                check(!state.buffer.render(output, 0)) { "short ready tail must initially wait for preroll" }
            }
            failed.offer(pcm, pcm.size, 0)
            waitUntil("bind allocation failure is published") { failed.failure != null }
            failed.awaitClosed()
            check(failed.failure == allocationFailure.message)
            check(allocationEvents.count { it == "close" } == 1) { "failed binding must release its engine" }
            check(failed.acceptedFrames == 240L && failed.submittedFrames == 0L && failed.producedFrames == 0L)
            check(failed.isIdle()) { "allocation failure must not leave a dead worker busy" }
            failed.offer(pcm, pcm.size, 10)
            check(failed.acceptedFrames == 240L && failed.rejectedFrames == 240L) {
                "a failed worker must reject new PCM instead of accepting it into a dead queue"
            }
            if (withReadyTail) {
                mixer.retireFailedTimeStretch(state, 1000)
                check(retired == 0) { "allocation failure must preserve ready output until drained" }
                check(state.buffer.render(output, 0)) { "failure must finalize the bank and bypass preroll" }
                check(state.buffer.lastRenderRealFrames == 240 && output.any { it != 0.toShort() })
            }
            check(state.isIdle()) { "the finalized bank must become idle after its ready tail drains" }
            mixer.retireFailedTimeStretch(state, 1000)
            mixer.retireFailedTimeStretch(state, 1001)
            check(retired == 1) { "allocation failure must retire the consumer-visible owner exactly once" }
        } finally { failed.close(); failed.awaitClosed() }
        check(allocationEvents.count { it == "close" } == 1) { "teardown must not close the failed engine twice" }
    }

    // A parked output may temporarily release focus, but cannot remove the owner
    // registration while that failed native stream still has ready PCM to replay.
    for (failAfterTail in listOf(false, true)) {
        val clock = java.util.concurrent.atomic.AtomicLong()
        val routeReady = java.util.concurrent.atomic.AtomicBoolean()
        val parked = RecoveringPcmOutput(null, {
            if (!routeReady.get()) throw IllegalStateException("route unavailable")
            AudioTrackPcmOutput(3, false)
        }, { clock.get() }, {})
        val block = ShortArray(960)
        repeat(2) {
            if (it > 0) clock.addAndGet(1000)
            var attempts = 0
            while (!parked.isParked) {
                check(attempts++ < 100); clock.addAndGet(100); parked.write(block, 0, block.size)
            }
        }
        val unavailableSince = clock.get()
        clock.addAndGet(1100)
        check(parked.isParked)
        val lease = PlaybackFocusLease()
        val identity = Any()
        lease.register(5, identity); lease.complete(lease.activity(5, identity, clock.get())!!)
        val permanentRetirements = java.util.concurrent.atomic.AtomicInteger()
        val bankRecoveryRequests = java.util.concurrent.atomic.AtomicInteger()
        val focusChecks = java.util.concurrent.atomic.AtomicInteger()
        val pendingFocus = java.util.concurrent.atomic.AtomicReference<PlaybackFocusLease.Request>()
        val parkedMixer = AudioMixer(3, canRender = { focusChecks.incrementAndGet(); lease.canRender(clock.get()) }, onRecoveryActivity = {
            bankRecoveryRequests.incrementAndGet()
            lease.activity(5, identity, clock.get())?.let { pendingFocus.set(it) }
        })
        val delayedFinal = java.util.concurrent.CountDownLatch(1)
        val releaseFinal = java.util.concurrent.CountDownLatch(1)
        val retained = AudioMixer.Channel(48000, 2, 2, false, {
            permanentRetirements.incrementAndGet(); lease.retire(5, identity)
        }, AudioTimeCorrectionMode.WSOLA, createEngine = {
            val delegate = delayedEngine()
            object : TimeStretchEngine by delegate {
                var finalized = false
                override fun finish() {
                    delayedFinal.countDown(); check(releaseFinal.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    delegate.finish(); finalized = true
                }
                override fun receive(pcm: TimeStretchBlock): Int {
                    val result = delegate.receive(pcm)
                    if (finalized && result == 0 && failAfterTail) throw IllegalStateException("failure after final PCM")
                    return result
                }
            }
        })
        try {
            AudioMixer::class.java.getDeclaredField("output").apply { isAccessible = true }.set(parkedMixer, parked)
            AudioMixer::class.java.getDeclaredField("outputUnavailableSinceMs").apply { isAccessible = true }
                .setLong(parkedMixer, unavailableSince)
            retained.timeStretch!!.offer(ShortArray(480) { 1000 }, 480, 0)
            retained.timeStretch.finish()
            check(delayedFinal.await(2, java.util.concurrent.TimeUnit.SECONDS))
            check(parkedMixer.isQuiescent(retained, clock.get())) { "park still allows temporary focus release" }
            lease.release(5, lease.snapshot().getValue(5))
            releaseFinal.countDown()
            waitUntil("final tail arrives after focus release") { retained.timeStretch.isIdle() }
            check(retained.buffer.depthFrames() == 240)
            parkedMixer.retireFailedTimeStretch(retained, clock.get())
            check(permanentRetirements.get() == 0) { "parked shortcut permanently retired a ready tail" }
            @Suppress("UNCHECKED_CAST")
            val registrations = AudioMixer::class.java.getDeclaredField("channels").apply { isAccessible = true }
                .get(parkedMixer) as java.util.concurrent.ConcurrentHashMap<Int, AudioMixer.Channel>
            registrations[5] = retained
            AudioMixer::class.java.getDeclaredField("channelSnapshot").apply { isAccessible = true }
                .set(parkedMixer, arrayOf(retained))
            val running = AudioMixer::class.java.getDeclaredField("running").apply { isAccessible = true }
                .get(parkedMixer) as java.util.concurrent.atomic.AtomicBoolean
            running.set(true)
            val loop = AudioMixer::class.java.getDeclaredMethod("mixLoop").apply { isAccessible = true }
            val loopErrors = CopyOnWriteArrayList<Throwable>()
            val loopThread = Thread({
                try { loop.invoke(parkedMixer) }
                catch (e: java.lang.reflect.InvocationTargetException) {
                    if (e.cause !is InterruptedException) loopErrors.add(e.cause ?: e)
                }
            }, "NativeTailRecoveryRegression")
            AudioMixer::class.java.getDeclaredField("mixThread").apply { isAccessible = true }.set(parkedMixer, loopThread)
            val outputsBefore = android.media.AudioTrack.created.size
            loopThread.start()
            waitUntil("outer focus gate reached during cooldown") { focusChecks.get() >= 2 }
            check(bankRecoveryRequests.get() == 0) { "focus requested during output cooldown" }
            routeReady.set(true); clock.addAndGet(1000)
            waitUntil("outer loop requests focus") { pendingFocus.get() != null }
            val checksBeforeGrant = focusChecks.get()
            waitUntil("pending focus does not flood callbacks") { focusChecks.get() >= checksBeforeGrant + 3 }
            check(bankRecoveryRequests.get() == 1)
            lease.complete(pendingFocus.get())
            waitUntil("late ready tail reopens output without new Start or input") {
                android.media.AudioTrack.created.drop(outputsBefore).any { track -> track.samples.any { it.toInt() != 0 } }
            }
            waitUntil("tail drains before failed-owner retirement") {
                if (failAfterTail) permanentRetirements.get() == 1
                else parkedMixer.isQuiescent(retained, android.os.SystemClock.elapsedRealtime())
            }
            check(retained.buffer.depthFrames() == 0)
            check(bankRecoveryRequests.get() == 1) { "recovery request repeated while focus was being acquired" }
            check(loopErrors.isEmpty()) { loopErrors }
        } finally { releaseFinal.countDown(); parkedMixer.stop(); retained.timeStretch!!.close(); retained.timeStretch.awaitClosed(); parked.close() }
    }

    // A completed worker must not make a banked final prompt look quiescent. Hold
    // finish across an idle poll, then release it without another packet or Start.
    val finalEntered = java.util.concurrent.CountDownLatch(1)
    val finalRelease = java.util.concurrent.CountDownLatch(1)
    val pending = AudioMixer.Channel(48000, 2, 2, false, {}, AudioTimeCorrectionMode.WSOLA,
        createEngine = {
            val delegate = delayedEngine()
            object : TimeStretchEngine by delegate {
                override fun finish() {
                    finalEntered.countDown(); check(finalRelease.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    delegate.finish()
                }
            }
        })
    try {
        pending.timeStretch!!.offer(ShortArray(480) { 2000 }, 480, 0)
        pending.timeStretch.finish()
        check(finalEntered.await(2, java.util.concurrent.TimeUnit.SECONDS))
        check(!pending.isIdle()) { "pending native tail is not idle" }
        finalRelease.countDown()
        waitUntil("native tail delivered") { pending.timeStretch.isIdle() }
        check(!pending.isIdle()) { "completed worker must not hide ready tail" }
        check(pending.buffer.render(ShortArray(960), 1000))
    } finally { finalRelease.countDown(); pending.timeStretch!!.close(); pending.timeStretch.awaitClosed() }
    println("PASS native worker owns PCM, orders Stop/start tails and retains final output under bank backpressure")
}
