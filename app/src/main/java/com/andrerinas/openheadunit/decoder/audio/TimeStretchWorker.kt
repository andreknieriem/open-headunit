package com.andrerinas.openheadunit.decoder.audio

import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** PCM-only ordered producer. Neither the transport nor mixer thread executes native DSP.
 * Stop closes only the PCM already preceding it in this queue. New PCM waits behind all
 * returned tail output, then gets a fresh engine and bank segment. AAC Stop is not EOS and
 * must not enter this contract until a separate decoder-drain fence is implemented.
 */
internal class TimeStretchWorker(
    private val mode: AudioTimeCorrectionMode,
    private val bank: AdaptivePcmBuffer,
    private val nowMs: () -> Long,
    private val onReady: () -> Unit,
    private val report: (String) -> Unit,
    private val createEngine: () -> TimeStretchEngine = { NativeTimeStretchEngine(mode) }
) {
    private val input = NativePcmQueue()
    val policy = NativeTempoPolicy()
    private val signal = Semaphore(0)
    @Volatile private var running = true
    @Volatile private var activeSegment = false
    @Volatile private var processing = false
    @Volatile private var waitingForRender = false
    @Volatile var failure: String? = null
        private set
    @Volatile var acceptedFrames = 0L
        private set
    @Volatile var submittedFrames = 0L
        private set
    @Volatile var producedFrames = 0L
        private set
    @Volatile var deliveredFrames = 0L
        private set
    val rejectedFrames get() = input.rejectedFrames
    private val thread = Thread(::run, "AudioTime-${mode.preferenceValue}").also { it.start() }

    fun offer(pcm: ShortArray, count: Int, arrivalMs: Long) {
        if (!running) return
        if (failure != null) { input.reject(count); return }
        if (input.offer(pcm, count, arrivalMs)) acceptedFrames += count / 2
        else if (input.rejectedFrames == count / 2L) report("time-stretch input queue full; rejectedFrames=${input.rejectedFrames}")
        wake()
    }
    fun finish() { if (running) { input.stop(); wake() } }
    fun wake() { if (signal.availablePermits() == 0) signal.release() }
    fun spaceAvailable() { if (waitingForRender) wake() }
    // A failed owner has stopped playback, not successfully consumed its retained source.
    // Ready PCM still drains from bank; source/native pending ownership lasts until close.
    fun isIdle(): Boolean = synchronized(input) {
        failure != null || (!processing && !activeSegment && input.isEmpty())
    }
    fun close() { running = false; thread.interrupt() }
    internal fun awaitClosed() { thread.join(3000); check(!thread.isAlive) }

    private fun run() {
        var engine: TimeStretchEngine? = null
        try {
            val source = TimeStretchBlock()
            val output = TimeStretchBlock()
            val read = NativePcmQueue.Read()
            // DSP is not the device callback. Let its consumer keep the higher audio priority.
            requestAudioThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            while (running) {
                // Queue removal and the busy publication share the idle reader's lock.
                // Otherwise it could see old busy=false followed by an already emptied queue.
                synchronized(input) {
                    processing = true
                    input.poll(source, read)
                }
                if (read.frames == 0) {
                    processing = false
                    signal.acquire()
                    continue
                }
                if (read.frames < 0) {
                    engine?.let { it.finish(); drain(it, output); if (running) check(it.finished()) { "Native final output not drained" } }
                    if (!running) break
                    engine?.close()
                    engine = null
                    bank.finish()
                    policy.beginSegment()
                    activeSegment = false
                    processing = false
                    onReady()
                    continue
                }
                if (engine == null) {
                    policy.beginSegment()
                    engine = createEngine()
                    engine.bind(source, output)
                    activeSegment = true
                }
                if (read.packetFrames > 0) bank.noteArrival(read.arrivalMs, read.packetFrames)
                var tempo = bank.timeCorrectionTempo(policy, nowMs(), read.frames)
                while (running && tempo == NativeTempoPolicy.WAIT_FOR_RENDER) {
                    // Retain the polled source block and queue order across permit renewal.
                    // No bank lock or DSP work is held while the renderer makes progress.
                    waitingForRender = true
                    try { signal.tryAcquire(10, TimeUnit.MILLISECONDS) }
                    finally { waitingForRender = false }
                    if (running) tempo = bank.timeCorrectionTempo(policy, nowMs(), read.frames)
                }
                if (!running) break
                engine.process(source, read.frames, tempo)
                submittedFrames += read.frames
                drain(engine, output)
                processing = false
            }
        } catch (_: InterruptedException) {
            // Explicit teardown aborts pending PCM; a protocol Stop never interrupts this owner.
        } catch (e: Exception) {
            fail(e)
        } catch (e: LinkageError) {
            // Loading succeeds before registration, but a missing JNI entry point can
            // still fail on first use. Retire through the same output-drain boundary.
            fail(e)
        } catch (e: OutOfMemoryError) {
            // Direct startup buffers and JNI global references can fail allocation.
            // Retire this owner without treating unrelated VM errors as recoverable.
            fail(e)
        } finally {
            try { engine?.close() }
            catch (e: Exception) { report("native close failed: ${e.message}") }
            catch (e: LinkageError) { report("native close unavailable: ${e.message}") }
            processing = false
            activeSegment = false
        }
    }

    private fun fail(error: Throwable) {
        failure = error.message ?: error.javaClass.simpleName
        bank.finish()
        onReady()
        report("${mode.preferenceValue} stopped: $failure; draining ready tail, new input rejected; " +
            "local audio restart or reconnect required (no replay of partially processed input)")
        // Replaying submitted PCM would duplicate an unknown prefix. Keep the failed
        // registration/queue visible until teardown, but let the owner's finally close
        // the native engine now. Ready PCM belongs to the bank and drains independently;
        // retaining an idle thread and native workspace cannot recover uncertain input.
    }

    private fun drain(engine: TimeStretchEngine, output: TimeStretchBlock) {
        while (running) {
            val frames = engine.receive(output)
            check(frames in 0..480) { "Invalid native output size: $frames" }
            if (frames == 0) return
            producedFrames += frames
            // The native engine may produce a burst at finalization. Wait for space while
            // retaining this exact block; ordinary bank.write would discard older PCM.
            waitingForRender = true
            try {
                while (running && !bank.writeIfFits(output, frames * 2, nowMs()))
                    signal.tryAcquire(10, TimeUnit.MILLISECONDS)
            } finally { waitingForRender = false }
            if (!running) return
            deliveredFrames += frames
            onReady()
        }
    }
}
