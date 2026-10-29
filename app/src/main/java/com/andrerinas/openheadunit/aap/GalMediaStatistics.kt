package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.utils.CheckedLong

/** Constant-space telemetry. Owned by the transport writer; it never queues a report per frame.
 * Each accepted Start has a local generation, even if the phone reuses the wire session ID.
 * Track replacement and options replacement start a fresh window and cumulative baseline.
 */
internal class GalMediaStatistics {
    private class Window(val generation: Long, val options: Media.MediaOptions?,
                         val sample: GalPlaybackSample, val sinceMs: Long) {
        val queue = Distribution()
        var latest = sample
    }
    private val windows = mutableMapOf<Int, Window>()

    fun retain(channels: Set<Int>) { windows.keys.retainAll(channels) }
    fun clear() = windows.clear()

    fun observe(channel: Int, session: GalMediaSessions.Session, sample: GalPlaybackSample?,
                nowMs: Long): Media.MediaStats? {
        val interval = session.runtime.statisticsIntervalMs
        if (!session.active || interval == null || sample == null) {
            windows.remove(channel)
            return null
        }
        var window = windows[channel]
        if (window == null || window.generation != session.generation || window.options != session.options ||
            window.sample.owner !== sample.owner || nowMs < window.sinceMs || regressed(window.latest, sample)) {
            window = Window(session.generation, session.options, sample, nowMs)
            windows[channel] = window
        }
        sample.audioQueueUs?.takeIf { it >= 0 }?.let(window.queue::add)
        window.latest = sample
        val elapsed = nowMs - window.sinceMs
        if (elapsed < interval) return null
        val report = Media.MediaStats.newBuilder().setObservationWindow(durationMs(elapsed))
        fun delta(before: Long?, after: Long?, apply: (Long) -> Unit) {
            if (before != null && after != null && after >= before) apply(after - before)
        }
        delta(window.sample.audioOverflows, sample.audioOverflows) { report.audioOverflowCount = it }
        delta(window.sample.audioUnderruns, sample.audioUnderruns) { report.audioUnderrunCount = it }
        delta(window.sample.videoFrames, sample.videoFrames) { report.frames = it }
        window.queue.build()?.let { report.estimatedAudioBufferUs = it }
        // No PTS clock mapping or calibrated device latency exists here. Fields6..9 remain
        // absent. Likewise an audio DATA packet is not one decoded or played sample frame.
        windows[channel] = Window(session.generation, session.options, sample, nowMs)
        return report.build()
    }

    private fun regressed(before: GalPlaybackSample, after: GalPlaybackSample): Boolean =
        regressed(before.audioOverflows, after.audioOverflows) ||
            regressed(before.audioUnderruns, after.audioUnderruns) ||
            regressed(before.videoFrames, after.videoFrames)

    private fun regressed(before: Long?, after: Long?): Boolean =
        before != null && after != null && after < before

    internal class Distribution {
        private var total = 0L
        private var count = 0L
        private var min = Long.MAX_VALUE
        private var max = Long.MIN_VALUE
        fun add(value: Long) {
            // Bounded queue durations cannot exhaust this during a <=60s window. Guard anyway
            // so a future metric cannot wrap into a plausible negative wire statistic.
            val sum = CheckedLong.add(total, value) ?: return
            if (count == Long.MAX_VALUE) return
            total = sum; count++; min = minOf(min, value); max = maxOf(max, value)
        }
        fun build(): Media.MediaDistribution? = if (count == 0L) null else
            Media.MediaDistribution.newBuilder().setAverage(total / count).setMinimum(min)
                .setMaximum(max).setCount(count).build()
    }
    companion object {
        fun durationMs(ms: Long): Media.MediaDuration = Media.MediaDuration.newBuilder()
            .setSeconds(ms / 1000).setNanos(((ms % 1000) * 1_000_000).toInt()).build()
    }
}
