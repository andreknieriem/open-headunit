package com.andrerinas.openheadunit.aap

/** GAL is independent of Android's API level, the AA APK version and wireless bootstrap. */
internal data class GalVersion(val major: Int, val minor: Int) : Comparable<GalVersion> {
    override fun compareTo(other: GalVersion): Int =
        major.compareTo(other.major).takeIf { it != 0 } ?: minor.compareTo(other.minor)
    override fun toString(): String = "$major.$minor"
}

/**
 * A manual profile is requested directly; Auto resolves a concrete profile before TLS.
 * A higher reply describes a ceiling, not permission to promote the request already sent.
 * A/V correction still requires attributable playout samples.
 * 6.1 retains its identity while sharing the currently supported 6.0 media contract.
 */
internal enum class GalProfile(val version: GalVersion) {
    COMPATIBILITY(GalVersion(1, 2)),
    GAL_1_6(GalVersion(1, 6)),
    GAL_5_0(GalVersion(5, 0)),
    GAL_5_1(GalVersion(5, 1)),
    GAL_6_0(GalVersion(6, 0)),
    GAL_6_1(GalVersion(6, 1));

    companion object {
        const val AUTO_SETTING = "auto"

        /** Auto exposes this ceiling in settings, not an already negotiated runtime profile. */
        fun fromSetting(value: String): GalProfile = if (value == AUTO_SETTING) entries.last()
            else entries.firstOrNull { it.version.toString() == value } ?: COMPATIBILITY

        fun normalizeSetting(value: String): String =
            if (value == AUTO_SETTING) AUTO_SETTING else fromSetting(value).version.toString()
    }
}

/** Immutable after the final response is accepted; all service handlers share this snapshot. */
internal data class GalSessionProfile(
    val profile: GalProfile,
    val finalResponse: AapVersionNegotiation.Result
) {
    val requestedVersion get() = profile.version
}

internal val AapVersionNegotiation.Result.version: GalVersion
    get() = GalVersion(major, minor)
