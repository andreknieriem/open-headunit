package com.andrerinas.openheadunit.decoder.audio

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Optional in-process flight recorder. Ordinary log capture may stop without clearing it.
 * Neither audio bytes nor network/device identifiers are recorded. No device API or audio
 * monitor is touched by the collector. History survives export, but not process death. */
internal object AudioProbeLog {
    @Volatile var enabled = false
    private val ids = AtomicLong()
    private val owners = CopyOnWriteArrayList<AudioProbe>()
    private val history = ArrayDeque<String>()
    private var historyBytes = 0
    private var overwritten = 0L
    private val eventIds = AtomicLong()
    private val skippedOwners = AtomicLong()
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_RECORDS = 2048
    private val collector by lazy {
        Thread({
            requestAudioThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            while (!Thread.currentThread().isInterrupted) {
                try {
                    drain()
                    Thread.sleep(500)
                } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                catch (_: Exception) { }
            }
        }, "AudioProbeCollector").apply { isDaemon = true; start() }
    }
    fun open(label: String): AudioProbe? {
        if (!enabled) return null
        collector
        // An abnormal number of live owners must not allow unbounded diagnostic storage.
        synchronized(owners) {
            if (owners.size >= 64) { skippedOwners.incrementAndGet(); return null }
            return AudioProbe(ids.incrementAndGet(), label).also { owners.add(it) }
        }
    }
    fun poll(probe: AudioProbe?) {
        if (probe == null || probe.closed) return
        val nowNs = System.nanoTime()
        if (probe.due(nowNs)) probe.publish(nowNs, cpu(), Process.myTid())
    }
    fun cpu(): Long = try { Debug.threadCpuTimeNanos() } catch (_: Exception) { -1 }
    /** Low-rate UI/lifecycle events only; not an audio-block logging API. */
    fun event(message: String) {
        if (!enabled) return
        collector
        val line = "AudioProbeEvent id=${eventIds.incrementAndGet()} atNs=${System.nanoTime()} $message"
        retain(line)
        AudioDiagnostics.report(SystemClock.elapsedRealtime(), line, remember = false)
    }
    @Synchronized private fun drain() {
        for (owner in owners) {
            // Observe close before draining: all final publications then precede this read.
            val wasClosed = owner.closed
            owner.drain { slot ->
                val text = buildString {
                    append("AudioProbe owner=${owner.id} ${owner.label} seq=${slot.sequence} atNs=${slot.atNs} tid=${slot.tid} cpuNs=${slot.cpuNs} boundary=${slot.boundary} lost=${slot.dropped}")
                    for (key in AudioProbe.Counter.entries) {
                        val value = slot.values[key.ordinal]
                        if (value != 0L) append(" ${key.name}=$value")
                    }
                    for (key in AudioProbe.Phase.entries) {
                        val i = key.ordinal
                        if (slot.counts[i] > 0) append(" ${key.name}=${slot.counts[i]}/${slot.totalNs[i]}/${slot.maxNs[i]}")
                    }
                }
                retain(text)
                AppLog.i(text)
            }
            if (wasClosed) {
                retain("AudioProbeEnd owner=${owner.id} lost=${owner.dropped}")
                owners.remove(owner)
            }
        }
    }
    private fun retain(line: String) = synchronized(history) {
        val bytes = line.length * 2
        while (history.isNotEmpty() && (history.size >= MAX_RECORDS || historyBytes + bytes > MAX_BYTES)) {
            historyBytes -= history.removeFirst().length * 2
            overwritten++
        }
        if (bytes <= MAX_BYTES) { history.addLast(line); historyBytes += bytes }
    }
    fun snapshot(): String {
        drain() // Export worker, never audio owner; serialize with the sole collector.
        return synchronized(history) {
            "Detailed audio diagnostics: enabled=$enabled records=${history.size} overwritten=$overwritten " +
                "liveOwners=${owners.size} skippedOwners=${skippedOwners.get()} atNs=${System.nanoTime()} counters=cumulative phases=count/totalNs/maxNs " +
                "zeroCountersOmitted=true head=framework-not-DAC memoryOnly=true\n" + history.joinToString("\n")
        }
    }
}
