package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.GalLaboratoryCapabilities
import com.andrerinas.openheadunit.aap.GalLaboratoryOptions
import com.andrerinas.openheadunit.aap.GalLaboratoryOptions.Mode
import com.andrerinas.openheadunit.aap.GalProfile
import com.andrerinas.openheadunit.utils.Settings
import java.io.Serializable

/** The child owns GAL writes; changing profiles never clears its receiver overrides. */
internal data class LaboratoryPendingCopy(
    var version: String,
    var options: GalLaboratoryOptions
) : Serializable {
    val profile: GalProfile get() = GalProfile.fromSetting(version)
    val capabilities: GalLaboratoryCapabilities get() = GalLaboratoryCapabilities.forProfile(profile)

    fun selectProfile(index: Int) {
        version = GalProfile.entries[index].version.toString()
    }

    fun differsFrom(settings: Settings): Boolean =
        version != settings.galProtocolVersion || options != settings.galLaboratoryOptions

    fun saveTo(settings: Settings) {
        settings.galProtocolVersion = version
        settings.galLaboratoryOptions = options
        settings.commit()
    }

    fun resetOptions() { options = GalLaboratoryOptions() }

    fun numericEnabled(field: LaboratoryNumber): Boolean =
        capabilities.mediaOptions && field.mode(options) != Mode.OFF

    /** Blank means Auto. Reject invalid input instead of silently clamping a dialog value. */
    fun setNumber(field: LaboratoryNumber, input: String): Boolean {
        if (!numericEnabled(field)) return false
        val text = input.trim()
        val value = if (text.isEmpty()) null else text.toIntOrNull() ?: return false
        val candidate = field.withValue(options, value)
        if (candidate.normalized() != candidate) return false
        options = candidate
        return true
    }

    companion object {
        fun from(settings: Settings) = LaboratoryPendingCopy(settings.galProtocolVersion, settings.galLaboratoryOptions)
    }
}

internal enum class LaboratoryNumber(val title: Int, val range: IntRange, val milliseconds: Boolean = true) {
    MAX_LAG(R.string.lab_max_lag, 0..500),
    SYNC_INTERVAL(R.string.lab_sync_interval, 1..5000),
    AUDIO_DELAY(R.string.lab_audio_delay, 0..500),
    VIDEO_DELAY(R.string.lab_video_delay, 0..500),
    LAG_WEIGHT(R.string.lab_lag_weight, 0..1000, false),
    SYNC_OFFSET(R.string.lab_sync_offset, -500..500),
    VIDEO_MIN(R.string.lab_video_min, 0..250),
    VIDEO_MAX(R.string.lab_video_max, 0..250),
    BUFFER_LIMIT(R.string.lab_buffer_limit, 1..1500),
    STATS_INTERVAL(R.string.lab_stats_interval, 250..60000);

    fun mode(options: GalLaboratoryOptions): Mode = when (this) {
        VIDEO_MIN, VIDEO_MAX -> options.videoTiming
        BUFFER_LIMIT -> options.audioBuffer
        STATS_INTERVAL -> options.mediaStats
        else -> options.avSync
    }

    fun value(options: GalLaboratoryOptions): Int? = when (this) {
        MAX_LAG -> options.maxLagToleranceMs
        SYNC_INTERVAL -> options.syncIntervalMs
        AUDIO_DELAY -> options.maxAudioDelayMs
        VIDEO_DELAY -> options.maxVideoDelayMs
        LAG_WEIGHT -> options.averageLagWeight
        SYNC_OFFSET -> options.syncOffsetMs
        VIDEO_MIN -> options.videoMinDelayMs
        VIDEO_MAX -> options.videoMaxDelayMs
        BUFFER_LIMIT -> options.audioBufferLimitMs
        STATS_INTERVAL -> options.statisticsIntervalMs
    }

    fun withValue(options: GalLaboratoryOptions, value: Int?): GalLaboratoryOptions = when (this) {
        MAX_LAG -> options.copy(maxLagToleranceMs = value)
        SYNC_INTERVAL -> options.copy(syncIntervalMs = value)
        AUDIO_DELAY -> options.copy(maxAudioDelayMs = value)
        VIDEO_DELAY -> options.copy(maxVideoDelayMs = value)
        LAG_WEIGHT -> options.copy(averageLagWeight = value)
        SYNC_OFFSET -> options.copy(syncOffsetMs = value)
        VIDEO_MIN -> options.copy(videoMinDelayMs = value)
        VIDEO_MAX -> options.copy(videoMaxDelayMs = value)
        BUFFER_LIMIT -> options.copy(audioBufferLimitMs = value)
        STATS_INTERVAL -> options.copy(statisticsIntervalMs = value)
    }
}
