package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel

/** Receiver policy, NOT an inferred Sony/Google algorithm. All local times use nanoTime.
 * Source clocks are related only by PTS differences against measured audio playout; tracks
 * are never independently first-arrival aligned. No I/O, sleeping, or per-frame containers.
 */
internal class GalPresentationController {
    private class Track {
        @Volatile var active = false
        @Volatile var generation = 0L
        var options = GalRuntimeOptions()
        var initialDeadline = NONE
        var initialReleased = false
        var observed = false
        var sourceUs = 0L
        var presentationNs = 0L
        var correctedNs = 0L
        var correctedSourceUs = 0L
        var correctionAt = 0L
    }
    private val tracks = Array(7) { Track() }
    @Volatile private var closed = false
    @Volatile private var enabledAudioMask = 0
    @Volatile private var videoTimingEnabled = false
    // Stable primitive diagnostics: MIN means no observation, never a fabricated timestamp.
    @Volatile var audioObservationCount = 0L
        private set
    @Volatile var videoObservationCount = 0L
        private set
    @Volatile var audioCorrectionCount = 0L
        private set
    @Volatile var avScheduleCount = 0L
        private set
    @Volatile var lastAudioSourceUs = NONE
        private set
    @Volatile var lastAudioPresentationNs = NONE
        private set
    private var videoReady = false
    private var pacedSourceUs = NONE
    private var pacedReleaseNs = 0L
    private val scheduledSources = LongArray(64) { NONE }
    private val scheduledArrivals = LongArray(64)
    private val scheduledReleases = LongArray(64)
    private var nextScheduled = 0
    private var renderLagNs = 0L
    private var renderLagAt = NONE
    private var nextVideoOutputEpoch = 0L
    private var videoOutputEpoch = 0L

    @Synchronized fun start(channel: Int, generation: Long, options: GalRuntimeOptions) {
        if (closed || channel !in tracks.indices) return
        val t = tracks[channel]
        t.active = true
        t.generation = generation
        t.options = options
        t.observed = false
        t.initialDeadline = NONE
        t.initialReleased = false
        if (channel == Channel.ID_VID) resetVideo()
        refreshEnabled()
    }

    @Synchronized fun update(channel: Int, generation: Long, options: GalRuntimeOptions) {
        val t = matching(channel, generation) ?: return
        t.options = options
        // Enabling midstream cannot rearm initial audio waiting.
        tracks.forEach { it.initialReleased = true }
        t.correctionAt = NONE
        if (channel == Channel.ID_VID) {
            pacedSourceUs = NONE
            renderLagNs = 0
            renderLagAt = NONE
        }
        refreshEnabled()
    }

    @Synchronized fun stop(channel: Int, generation: Long) {
        val t = matching(channel, generation) ?: return
        t.active = false
        t.observed = false
        t.initialReleased = true
        if (channel == Channel.ID_VID) resetVideo()
        refreshEnabled()
    }

    @Synchronized fun close() {
        closed = true
        tracks.forEach { it.active = false; it.observed = false; it.initialReleased = true }
        resetVideo()
        refreshEnabled()
    }

    fun isAudioSyncEnabled(channel: Int, generation: Long): Boolean =
        !closed && channel in tracks.indices && (enabledAudioMask and (1 shl channel)) != 0 &&
            tracks[channel].active && tracks[channel].generation == generation

    fun isVideoSessionActive(generation: Long): Boolean =
        !closed && tracks[Channel.ID_VID].active && tracks[Channel.ID_VID].generation == generation

    fun isVideoTimingEnabled(generation: Long): Boolean =
        videoTimingEnabled && isVideoSessionActive(generation)

    private fun refreshEnabled() {
        val video = tracks[Channel.ID_VID]
        val videoAv = video.active && video.options.avSync != null
        var mask = 0
        var anyAudioAv = false
        for (id in Channel.ID_AU1..Channel.ID_AUD) {
            val t = tracks[id]
            if (t.active && t.options.avSync != null) anyAudioAv = true
            if (t.active && (videoAv || t.options.avSync != null)) mask = mask or (1 shl id)
            else t.observed = false
        }
        enabledAudioMask = if (closed) 0 else mask
        videoTimingEnabled = !closed && video.active && (videoAv || anyAudioAv || video.options.videoTiming != null)
    }

    /** Revoke an unsupported/lost output mapping without stopping playback or rearming wait. */
    @Synchronized fun invalidateAudio(channel: Int, generation: Long) {
        matching(channel, generation)?.observed = false
    }

    /** Called only by the audio worker with queued real media PCM. Other sinks never wait. */
    @Synchronized fun audioStartDelayNs(channel: Int, generation: Long, sourceTimeUs: Long, nowNs: Long): Long {
        val t = matching(channel, generation) ?: return 0
        if (channel != Channel.ID_AUD || t.initialReleased) return 0
        val av = avOptions(t)
        if (av == null || videoReady || sourceTimeUs == NONE) {
            t.initialReleased = true
            return 0
        }
        if (t.initialDeadline == NONE) t.initialDeadline = add(nowNs, av.maxAudioDelayNs)
        val left = subtract(t.initialDeadline, nowNs)
        if (left == NONE || left <= 0) { t.initialReleased = true; return 0 }
        return minOf(left, av.maxAudioDelayNs)
    }

    @Synchronized fun observeAudio(channel: Int, generation: Long, sourceTimeUs: Long, presentationNs: Long) {
        val t = matching(channel, generation) ?: return
        if (!isAudioSyncEnabled(channel, generation)) return
        if (!Channel.isAudio(channel) || !validSource(sourceTimeUs)) return
        val av = avOptions(t)
        if (t.observed) {
            val sourceDelta = sourceDeltaNs(sourceTimeUs, t.sourceUs)
            val localDelta = subtract(presentationNs, t.presentationNs)
            val error = subtract(sourceDelta, localDelta)
            if (sourceDelta == NONE || localDelta == NONE || sourceDelta < 0 || localDelta < 0 ||
                error == NONE || error !in -DISCONTINUITY_NS..DISCONTINUITY_NS) {
                t.observed = false
                pacedSourceUs = NONE
                return
            }
        }
        if (!t.observed) {
            t.correctedSourceUs = sourceTimeUs
            t.correctedNs = presentationNs
            t.correctionAt = presentationNs
        } else if (av == null || t.correctionAt == NONE ||
            subtract(presentationNs, t.correctionAt) >= av.syncIntervalNs) {
            val predicted = add(t.correctedNs, sourceDeltaNs(sourceTimeUs, t.correctedSourceUs))
            val error = subtract(presentationNs, predicted)
            if (predicted == NONE || error == NONE) { t.observed = false; return }
            t.correctedNs = add(predicted, error / ((av?.averageLagWeight ?: 0) + 1))
            t.correctedSourceUs = sourceTimeUs
            t.correctionAt = presentationNs
            audioCorrectionCount++
        }
        t.sourceUs = sourceTimeUs
        t.presentationNs = presentationNs
        t.observed = true
        t.initialReleased = true
        audioObservationCount++
        lastAudioSourceUs = sourceTimeUs
        lastAudioPresentationNs = presentationNs
    }

    /** Surface/backend ownership is narrower than a media Start generation. */
    @Synchronized fun beginVideoOutput(generation: Long): Long {
        matching(Channel.ID_VID, generation) ?: return 0
        clearVideoOutputTiming()
        videoOutputEpoch = ++nextVideoOutputEpoch
        return videoOutputEpoch
    }

    @Synchronized fun retireVideoOutput(generation: Long, outputEpoch: Long) {
        matching(Channel.ID_VID, generation) ?: return
        if (outputEpoch == 0L || outputEpoch != videoOutputEpoch) return
        videoOutputEpoch = 0
        clearVideoOutputTiming()
    }

    /** Actual rendered feedback only, from the current output instance. */
    @Synchronized fun observeVideoOutput(generation: Long, outputEpoch: Long,
                                         sourceTimeUs: Long, presentationNs: Long) {
        val t = matching(Channel.ID_VID, generation) ?: return
        if (outputEpoch == 0L || outputEpoch != videoOutputEpoch || !isVideoTimingEnabled(generation)) return
        if (!validSource(sourceTimeUs)) return
        videoObservationCount++
        videoReady = true
        val av = t.options.avSync ?: audioMaster(presentationNs)?.options?.avSync ?: return
        var match = -1
        for (i in scheduledSources.indices) if (scheduledSources[i] == sourceTimeUs) {
            if (match >= 0) return // Repeated wire PTS has no unique rendered-frame identity.
            match = i
        }
        if (match < 0) return
        scheduledSources[match] = NONE
        if (renderLagAt != NONE && subtract(presentationNs, renderLagAt) < av.syncIntervalNs) return
        val lag = subtract(presentationNs, scheduledReleases[match])
        if (lag in 0..250_000_000L) {
            renderLagNs += (lag - renderLagNs) / (av.averageLagWeight + 1)
            renderLagAt = presentationNs
        } else { renderLagNs = 0; renderLagAt = NONE }
    }

    /**
     * Final submission reservation, serialized with Start/Stop/options. Already admitted
     * native work may complete after those boundaries; no monitor is held during I/O.
     * Missing frame metadata permits immediate output, never stale-generation output.
     * The caller alone owns the bounded, matching startup-repair proof.
     */
    @Synchronized fun admitVideo(generation: Long, outputEpoch: Long, sourceTimeUs: Long?,
                                  arrivalNs: Long, nowNs: Long, allowLateRepair: Boolean = false): Long {
        matching(Channel.ID_VID, generation) ?: return DROP
        if (outputEpoch == 0L || outputEpoch != videoOutputEpoch) return DROP
        if (arrivalNs == NONE) return 0
        val deadline = videoReleaseTimeNs(generation, sourceTimeUs, arrivalNs, nowNs)
        return if (deadline == DROP && allowLateRepair) 0 else deadline
    }

    /** Absolute deadline, 0 immediate, or DROP. Never holds the transport reader/ACK. */
    @Synchronized fun videoReleaseTimeNs(generation: Long, sourceTimeUs: Long?, arrivalNs: Long, nowNs: Long): Long {
        val video = matching(Channel.ID_VID, generation) ?: return DROP
        videoReady = true
        val source = sourceTimeUs?.takeIf { validSource(it) } ?: NONE
        val master = audioMaster(nowNs)
        val av = video.options.avSync ?: master?.options?.avSync
        if (av != null && master != null && source != NONE) {
            val sourceDelta = sourceDeltaNs(source, master.correctedSourceUs)
            val target = add(add(master.correctedNs, sourceDelta), av.syncOffsetNs)
            val lag = subtract(nowNs, target)
            if (target != NONE && lag != NONE &&
                sourceDeltaNs(source, master.sourceUs) in -DISCONTINUITY_NS..DISCONTINUITY_NS) {
                if (lag > av.maxLagToleranceNs) return DROP
                val cap = add(arrivalNs, av.maxVideoDelayNs)
                if (cap != NONE) {
                    val correction = if (subtract(nowNs, renderLagAt) in 0..STALE_NS) renderLagNs else 0
                    val release = minOf(subtract(target, correction), cap)
                    recordVideoRelease(source, arrivalNs, if (release <= nowNs) nowNs else release)
                    avScheduleCount++
                    return if (release <= nowNs) 0 else release
                }
            }
            // Missing/discontinuous clocks: bounded arrival policy, not claimed A/V sync.
            pacedSourceUs = NONE
        }
        val timing = video.options.videoTiming ?: return 0
        val earliest = add(arrivalNs, timing.minDelayNs)
        val latest = add(arrivalNs, timing.maxDelayNs)
        if (earliest == NONE || latest == NONE) return 0
        if (nowNs > latest) return DROP
        var target = earliest
        if (source != NONE && pacedSourceUs != NONE) {
            val delta = sourceDeltaNs(source, pacedSourceUs)
            if (delta in 0..DISCONTINUITY_NS) {
                val paced = add(pacedReleaseNs, delta)
                if (paced != NONE) target = paced.coerceIn(earliest, latest)
            }
        }
        pacedSourceUs = source
        pacedReleaseNs = target
        return if (target <= nowNs) 0 else target
    }

    private fun recordVideoRelease(source: Long, arrival: Long, release: Long) {
        for (i in scheduledSources.indices) {
            if (scheduledSources[i] == source && scheduledArrivals[i] == arrival) {
                scheduledReleases[i] = release
                return
            }
        }
        scheduledSources[nextScheduled] = source
        scheduledArrivals[nextScheduled] = arrival
        scheduledReleases[nextScheduled] = release
        nextScheduled = (nextScheduled + 1) % scheduledSources.size
    }

    private fun matching(channel: Int, generation: Long): Track? =
        if (!closed && channel in tracks.indices) tracks[channel].takeIf { it.active && it.generation == generation } else null

    private fun avOptions(track: Track): GalRuntimeOptions.AvSync? =
        tracks[Channel.ID_VID].takeIf { it.active }?.options?.avSync ?: track.options.avSync

    private fun audioMaster(nowNs: Long): Track? {
        // Media is preferred, but an absent/stopped media sink never starves active speech.
        val media = tracks[Channel.ID_AUD]
        if (fresh(media, nowNs)) return media
        for (id in Channel.ID_AU1..Channel.ID_AU2) if (fresh(tracks[id], nowNs)) return tracks[id]
        return null
    }
    private fun fresh(t: Track, nowNs: Long): Boolean = t.active && t.observed &&
        subtract(nowNs, t.presentationNs) in 0..STALE_NS
    private fun resetVideo() {
        videoReady = false
        videoOutputEpoch = 0
        clearVideoOutputTiming()
    }

    private fun clearVideoOutputTiming() {
        pacedSourceUs = NONE
        scheduledSources.fill(NONE)
        nextScheduled = 0
        renderLagNs = 0
        renderLagAt = NONE
    }

    companion object {
        const val DROP = Long.MIN_VALUE
        private const val NONE = Long.MIN_VALUE
        private const val STALE_NS = 500_000_000L
        private const val DISCONTINUITY_NS = 1_000_000_000L
        private fun validSource(us: Long) = us >= 0 && us <= Long.MAX_VALUE / 1000
        private fun sourceDeltaNs(a: Long, b: Long): Long {
            val delta = subtract(a, b)
            return if (delta == NONE || delta !in (Long.MIN_VALUE / 1000 + 1)..(Long.MAX_VALUE / 1000)) NONE else delta * 1000
        }
        private fun add(a: Long, b: Long): Long {
            if (a == NONE || b == NONE) return NONE
            val result = a + b
            return if (((a xor result) and (b xor result)) < 0) NONE else result
        }
        private fun subtract(a: Long, b: Long): Long {
            if (a == NONE || b == NONE) return NONE
            val result = a - b
            return if (((a xor b) and (a xor result)) < 0) NONE else result
        }
    }
}
