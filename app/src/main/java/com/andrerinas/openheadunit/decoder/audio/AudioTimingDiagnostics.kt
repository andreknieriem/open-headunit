package com.andrerinas.openheadunit.decoder.audio

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import java.io.File

/** Temporary #1159 instrumentation. Construct only for Debug/Verbose logging at connection
 * creation; reconnect after changing the log level to compare instrumented/uninstrumented runs.
 * Each instance belongs to one mixer or serialized producer. No file reads or formatting occur
 * in those owners. The logger reads schedstat later: its cumulative run-delay is a separate
 * observation, never attributed to a particular call or subtracted from that call's wall time. */
internal class AudioTimingDiagnostics(private val label: String) {
    private val window = AudioTimingWindow()
    private var ownerTid = -1
    private var windowStartNs = -1L
    private var cpuStartNs = -1L
    // Accessed only by the serial diagnostic logger, never by the mixer.
    private var schedstatUnavailable = false

    fun begin(): Long = System.nanoTime()
    fun end(phase: AudioTimingWindow.Phase, startedNs: Long) {
        window.record(phase, System.nanoTime() - startedNs)
    }
    fun output(track: Int, written: Long, played: Long, capacity: Int) =
        window.output(track, written, played, capacity, System.nanoTime())
    fun writeResult(result: Int, admissionRejected: Boolean = false) =
        window.writeResult(result, admissionRejected)

    fun poll() {
        val nowNs = System.nanoTime()
        if (windowStartNs >= 0 && nowNs - windowStartNs < 1_000_000_000L) return
        val tid = Process.myTid()
        val cpuNs = Debug.threadCpuTimeNanos()
        if (windowStartNs >= 0) {
            val elapsed = nowNs - windowStartNs
            val cpu = if (tid == ownerTid && cpuNs >= cpuStartNs && cpuStartNs >= 0)
                cpuNs - cpuStartNs else -1L
            val data = window.snapshot()
            val observedAt = SystemClock.elapsedRealtime()
            AudioDiagnostics.reportDeferred(observedAt) {
                val phases = AudioTimingWindow.Phase.values().mapIndexedNotNull { index, phase ->
                    if (data.counts[index] == 0L) null else
                        "${phase.name.lowercase()}=${data.counts[index]}/${data.totalNs[index] / 1000}/${data.maxNs[index] / 1000}"
                }.joinToString(" ")
                "AudioTiming: $label tid=$tid wallUs=${elapsed / 1000} cpuUs=${if (cpu < 0) -1 else cpu / 1000} " +
                    "track=${data.track} continuous=${data.continuous} outputSpanUs=${if (data.outputSpanNs < 0) -1 else data.outputSpanNs / 1000} " +
                    "writtenDelta=${data.writtenDelta} headDelta=${data.headDelta} " +
                    "queueObservedMin=${data.queueMin} queueObservedMax=${data.queueMax} " +
                    "writes=${data.writes} zeroWrites=${data.zeroWrites} admissionRejected=${data.admissionRejected} " +
                    "writeErrors=${data.errors} acceptedShorts=${data.acceptedShorts} " +
                    "phase=count/totalUs/maxUs $phases ${readScheduler(tid)}"
            }
        } else window.snapshot() // Do not include work before the CPU/window baseline.
        ownerTid = tid
        windowStartNs = nowNs
        cpuStartNs = cpuNs
    }

    private fun readScheduler(tid: Int): String {
        if (schedstatUnavailable) return "schedstat=N/A"
        return try {
            val fields = File("/proc/self/task/$tid/schedstat").readText().trim().split(Regex("\\s+"))
            val runtime = fields[0].toLong()
            val runDelay = fields[1].toLong()
            val slices = fields[2].toLong()
            // Some kernels expose disabled counters as all zero. Do not report that as
            // proof that a busy audio thread never waited for CPU.
            if (runtime <= 0 || runDelay < 0 || slices <= 0) {
                schedstatUnavailable = true
                "schedstat=N/A"
            } else "schedObservedElapsedMs=${SystemClock.elapsedRealtime()} schedRuntimeNs=$runtime schedRunDelayNs=$runDelay schedSlices=$slices"
        } catch (_: Exception) { schedstatUnavailable = true; "schedstat=N/A" }
    }
}
