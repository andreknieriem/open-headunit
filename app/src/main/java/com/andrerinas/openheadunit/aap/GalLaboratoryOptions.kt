package com.andrerinas.openheadunit.aap

/** Receiver experiments, not a claim that GAL versions prescribe these local policies.
 * Immutable and captured once per connection. Null numeric values follow the phone request.
 */
internal data class GalLaboratoryOptions(
    val guidance48Khz: Boolean = true,
    val modernDisplayConfig: Boolean = true,
    val audioUpdates: Boolean = true,
    val videoUpdates: Boolean = true,
    val avSync: Mode = Mode.AUTO,
    val videoTiming: Mode = Mode.AUTO,
    val audioBuffer: Mode = Mode.AUTO,
    val mediaStats: Mode = Mode.AUTO,
    val maxLagToleranceMs: Int? = null,
    val syncIntervalMs: Int? = null,
    val maxAudioDelayMs: Int? = null,
    val maxVideoDelayMs: Int? = null,
    val averageLagWeight: Int? = null,
    val syncOffsetMs: Int? = null,
    val videoMinDelayMs: Int? = null,
    val videoMaxDelayMs: Int? = null,
    val audioBufferLimitMs: Int? = null,
    val statisticsIntervalMs: Int? = null
) : java.io.Serializable {
    enum class Mode { AUTO, OFF, ON }

    /** Defensive receiver bounds, shared by imported preferences and the settings editor.
     * If both video bounds conflict, raise the maximum to the minimum (never negative delay).
     */
    fun normalized(): GalLaboratoryOptions {
        val min = videoMinDelayMs?.coerceIn(0, 250)
        val max = videoMaxDelayMs?.coerceIn(0, 250)?.let { maxOf(it, min ?: 0) }
        return copy(
            maxLagToleranceMs = maxLagToleranceMs?.coerceIn(0, 500),
            syncIntervalMs = syncIntervalMs?.coerceIn(1, 5000),
            maxAudioDelayMs = maxAudioDelayMs?.coerceIn(0, 500),
            maxVideoDelayMs = maxVideoDelayMs?.coerceIn(0, 500),
            averageLagWeight = averageLagWeight?.coerceIn(0, 1000),
            syncOffsetMs = syncOffsetMs?.coerceIn(-500, 500),
            videoMinDelayMs = min, videoMaxDelayMs = max,
            audioBufferLimitMs = audioBufferLimitMs?.coerceIn(1, 1500),
            statisticsIntervalMs = statisticsIntervalMs?.coerceIn(250, 60000)
        )
    }

    /** Only stable primitive preference values; no Java deserialization of imported data. */
    fun storedValues(): Map<String, Any?> = with(normalized()) {
        mapOf(
            "guidance48Khz" to guidance48Khz, "modernDisplayConfig" to modernDisplayConfig,
            "audioUpdates" to audioUpdates, "videoUpdates" to videoUpdates,
            "avSync" to avSync.name, "videoTiming" to videoTiming.name,
            "audioBuffer" to audioBuffer.name, "mediaStats" to mediaStats.name,
            "maxLagToleranceMs" to maxLagToleranceMs, "syncIntervalMs" to syncIntervalMs,
            "maxAudioDelayMs" to maxAudioDelayMs, "maxVideoDelayMs" to maxVideoDelayMs,
            "averageLagWeight" to averageLagWeight, "syncOffsetMs" to syncOffsetMs,
            "videoMinDelayMs" to videoMinDelayMs, "videoMaxDelayMs" to videoMaxDelayMs,
            "audioBufferLimitMs" to audioBufferLimitMs, "statisticsIntervalMs" to statisticsIntervalMs
        ).mapKeys { (key, _) -> PREFIX + key }
    }

    companion object {
        private const val PREFIX = "gal-laboratory-"

        fun fromStored(values: Map<String, *>): GalLaboratoryOptions {
            fun boolean(key: String) = values[PREFIX + key] as? Boolean ?: true
            fun number(key: String) = values[PREFIX + key] as? Int
            fun mode(key: String) = (values[PREFIX + key] as? String)?.let { stored ->
                Mode.entries.firstOrNull { it.name == stored }
            } ?: Mode.AUTO
            return GalLaboratoryOptions(
                boolean("guidance48Khz"), boolean("modernDisplayConfig"),
                boolean("audioUpdates"), boolean("videoUpdates"),
                mode("avSync"), mode("videoTiming"), mode("audioBuffer"), mode("mediaStats"),
                number("maxLagToleranceMs"), number("syncIntervalMs"), number("maxAudioDelayMs"),
                number("maxVideoDelayMs"), number("averageLagWeight"), number("syncOffsetMs"),
                number("videoMinDelayMs"), number("videoMaxDelayMs"), number("audioBufferLimitMs"),
                number("statisticsIntervalMs")
            ).normalized()
        }
    }
}

/** The single profile gate table used by both the settings page and connection contracts. */
internal data class GalLaboratoryCapabilities(
    val guidance48Khz: Boolean,
    val mediaOptions: Boolean,
    val modernDisplayConfig: Boolean,
    val audioUpdates: Boolean,
    val videoUpdates: Boolean
) {
    companion object {
        fun forProfile(profile: GalProfile): GalLaboratoryCapabilities = when (profile) {
            GalProfile.COMPATIBILITY -> GalLaboratoryCapabilities(false, false, false, false, false)
            GalProfile.GAL_1_6 -> GalLaboratoryCapabilities(true, false, false, false, false)
            GalProfile.GAL_5_0 -> GalLaboratoryCapabilities(true, true, true, false, false)
            GalProfile.GAL_5_1 -> GalLaboratoryCapabilities(true, true, true, true, false)
            // There is no independently evidenced 6.1 feature branch.
            GalProfile.GAL_6_0, GalProfile.GAL_6_1 -> GalLaboratoryCapabilities(true, true, true, true, true)
        }
    }
}
