package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.utils.CheckedLong

/** Validated requests, never Android buffer sizes or measured output latency.
 * Start begins a fresh request set; standalone update policy is owned by GalMediaSessions.
 * Invalid fields are ignored independently; an invalid interval must not create a tight loop.
 */
internal data class GalRuntimeOptions(
    val audioBufferLimitMs: Long? = null,
    val statisticsIntervalMs: Long? = null,
    val videoTiming: VideoTiming? = null,
    val avSyncRequested: Boolean = false,
    val invalidFields: List<String> = emptyList(),
    val avSync: AvSync? = null
) {
    /** Receiver-chosen arrival-to-presentation window, not a recovered sender clock rule. */
    data class VideoTiming(val minDelayNs: Long, val maxDelayNs: Long)

    /** Local bounded receiver policy. Weight is the number of historical observations in
     * filtered = filtered + (observed - filtered) / (weight + 1). Positive offset delays video. */
    data class AvSync(
        val maxLagToleranceNs: Long = 100_000_000L,
        val syncIntervalNs: Long = 100_000_000L,
        val maxAudioDelayNs: Long = 100_000_000L,
        val maxVideoDelayNs: Long = 100_000_000L,
        val averageLagWeight: Long = 3L,
        val syncOffsetNs: Long = 0L
    )

    /** Resolve local experiments once at Start/update, never on a media read/output hot path.
     * Raw protobuf requests remain untouched so sparse updates and subsequent Starts retain
     * their existing semantics. AUTO numeric values refine only phone-enabled features.
     */
    fun withLaboratory(
        peer: Media.MediaOptions?,
        options: GalLaboratoryOptions,
        format: GalMediaFormat
    ): GalRuntimeOptions {
        fun enabled(mode: GalLaboratoryOptions.Mode, requested: Boolean): Boolean = when (mode) {
            GalLaboratoryOptions.Mode.AUTO -> requested
            GalLaboratoryOptions.Mode.OFF -> false
            GalLaboratoryOptions.Mode.ON -> true
        }
        fun Int?.ns(fallback: Long) = this?.toLong()?.times(1_000_000L) ?: fallback
        val syncEnabled = enabled(options.avSync, peer?.enableAvSync == true)
        val sync = if (!syncEnabled) null else {
            val syncBase = avSync ?: AvSync()
            AvSync(
                options.maxLagToleranceMs.ns(syncBase.maxLagToleranceNs),
                options.syncIntervalMs.ns(syncBase.syncIntervalNs),
                options.maxAudioDelayMs.ns(syncBase.maxAudioDelayNs),
                options.maxVideoDelayMs.ns(syncBase.maxVideoDelayNs),
                options.averageLagWeight?.toLong() ?: syncBase.averageLagWeight,
                options.syncOffsetMs.ns(syncBase.syncOffsetNs)
            )
        }
        val timingEnabled = format is GalMediaFormat.Video &&
            enabled(options.videoTiming, peer?.enableVideoTimingController == true)
        var timing: VideoTiming? = null
        if (timingEnabled) {
            // ON supplies a bounded receiver policy when the phone omitted/invalidated values.
            // AUTO never fills missing peer bounds unless the user supplied that numeric bound.
            val force = options.videoTiming == GalLaboratoryOptions.Mode.ON
            val peerMin = peer?.takeIf { it.hasVideoTimingControllerMinDelay() }
                ?.videoTimingControllerMinDelay?.let(::nanos)?.takeIf { it in 0..250_000_000L }
            val peerMax = peer?.takeIf { it.hasVideoTimingControllerMaxDelay() }
                ?.videoTimingControllerMaxDelay?.let(::nanos)?.takeIf { it in 0..250_000_000L }
            val min = options.videoMinDelayMs?.toLong()?.times(1_000_000L) ?: peerMin
                ?: if (force) 0L else null
            val max = options.videoMaxDelayMs?.toLong()?.times(1_000_000L) ?: peerMax
                ?: if (force) 33_000_000L else null
            if (min != null && max != null) {
                val localRange = force || options.videoMinDelayMs != null || options.videoMaxDelayMs != null
                if (max >= min || localRange) timing = VideoTiming(min, maxOf(min, max))
            }
        }
        val bufferEnabled = format is GalMediaFormat.Audio &&
            enabled(options.audioBuffer, audioBufferLimitMs != null)
        val statsEnabled = enabled(options.mediaStats, peer?.enableMediaStats == true)
        return copy(
            avSyncRequested = syncEnabled, avSync = sync,
            videoTiming = timing,
            audioBufferLimitMs = if (bufferEnabled)
                options.audioBufferLimitMs?.toLong() ?: audioBufferLimitMs ?: 100L else null,
            statisticsIntervalMs = if (statsEnabled)
                options.statisticsIntervalMs?.toLong() ?: statisticsIntervalMs
                    ?: if (options.mediaStats == GalLaboratoryOptions.Mode.ON) 5000L else null
                else null
        )
    }

    companion object {
        // Defensive local bounds, not limits recovered from a GAL specification. Telemetry
        // cannot occupy the writer at the expense of ACK/Ping. Sender defaults are 500/5000ms.
        const val MIN_REPORT_MS = 250L
        const val MAX_REPORT_MS = 60_000L

        fun from(options: Media.MediaOptions?, forceAvSync: Boolean = false): GalRuntimeOptions {
            if (options == null) return GalRuntimeOptions()
            val invalid = mutableListOf<String>()
            fun duration(name: String, present: Boolean, value: Media.MediaDuration): Long? {
                if (!present) return null
                return nanos(value).also { if (it == null) invalid.add(name) }
            }
            val buffer = duration("audio_buffer_limit", options.hasAudioBufferLimit(), options.audioBufferLimit)
                ?.takeIf { it > 0 && it <= 1_500_000_000L }
                .also { if (options.hasAudioBufferLimit() && it == null && "audio_buffer_limit" !in invalid) invalid.add("audio_buffer_limit") }
            val cadence = duration("media_stats_report_interval", options.hasMediaStatsReportInterval(), options.mediaStatsReportInterval)
            val interval = if (!options.enableMediaStats) null else when {
                !options.hasMediaStatsReportInterval() -> 5000L
                cadence == null || cadence <= 0 -> { if ("media_stats_report_interval" !in invalid) invalid.add("media_stats_report_interval"); null }
                else -> (cadence / 1_000_000).coerceIn(MIN_REPORT_MS, MAX_REPORT_MS)
            }
            val min = duration("video_timing_controller_min_delay", options.hasVideoTimingControllerMinDelay(), options.videoTimingControllerMinDelay)
            val max = duration("video_timing_controller_max_delay", options.hasVideoTimingControllerMaxDelay(), options.videoTimingControllerMaxDelay)
            val timing = if (!options.enableVideoTimingController) null else if (
                min != null && max != null && min >= 0 && max >= min && max <= 250_000_000L
            ) VideoTiming(min, max) else { invalid.add("video_timing_range"); null }
            fun bounded(name: String, present: Boolean, value: Media.MediaDuration,
                        fallback: Long, low: Long, high: Long): Long {
                if (!present) return fallback
                val ns = duration(name, true, value)
                if (ns == null || ns !in low..high) {
                    if (name !in invalid) invalid.add(name)
                    return fallback
                }
                return ns
            }
            val lag = bounded("max_lag_tolerance", options.hasMaxLagTolerance(), options.maxLagTolerance,
                100_000_000L, 0, 500_000_000L)
            val sync = bounded("sync_interval", options.hasSyncInterval(), options.syncInterval,
                100_000_000L, 1_000_000L, 5_000_000_000L)
            val audio = bounded("max_audio_delay_allowed", options.hasMaxAudioDelayAllowed(), options.maxAudioDelayAllowed,
                100_000_000L, 0, 500_000_000L)
            val video = bounded("max_video_delay_allowed", options.hasMaxVideoDelayAllowed(), options.maxVideoDelayAllowed,
                100_000_000L, 0, 500_000_000L)
            val offset = bounded("sync_offset_override", options.hasSyncOffsetOverride(), options.syncOffsetOverride,
                0L, -500_000_000L, 500_000_000L)
            // Protobuf uint32 occupies a signed JVM int: widen before validating.
            val unsignedWeight = options.averageLagWeight.toLong() and 0xffff_ffffL
            val weight = if (!options.hasAverageLagWeight()) 3L else if (unsignedWeight <= 1000L) unsignedWeight
                else { invalid.add("average_lag_weight"); 3L }
            // Decode dormant numeric requests for a local ON override without cloning protobufs.
            val av = if (options.enableAvSync || forceAvSync) AvSync(lag, sync, audio, video, weight, offset) else null
            return GalRuntimeOptions(buffer?.let { maxOf(1L, it / 1_000_000) }, interval,
                timing, options.enableAvSync, invalid.distinct(), av)
        }

        /** google.protobuf.Duration sign/range checks followed by checked platform conversion. */
        fun nanos(value: Media.MediaDuration): Long? {
            val seconds = value.seconds
            val nanos = value.nanos
            if (seconds !in -315_576_000_000L..315_576_000_000L || nanos !in -999_999_999..999_999_999 ||
                (seconds > 0 && nanos < 0) || (seconds < 0 && nanos > 0)) return null
            val whole = CheckedLong.scale(seconds, 1_000_000_000L) ?: return null
            return CheckedLong.add(whole, nanos.toLong())
        }
    }
}
