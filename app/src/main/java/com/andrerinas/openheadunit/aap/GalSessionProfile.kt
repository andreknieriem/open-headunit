package com.andrerinas.openheadunit.aap

/** GAL is independent of Android's API level, the AA APK version and wireless bootstrap. */
internal data class GalVersion(val major: Int, val minor: Int) : Comparable<GalVersion> {
    override fun compareTo(other: GalVersion): Int =
        major.compareTo(other.major).takeIf { it != 0 } ?: minor.compareTo(other.minor)
    override fun toString(): String = "$major.$minor"
}

/**
 * A selected profile is requested directly. A higher phone reply describes its ceiling, not
 * permission to promote the request. A/V correction still requires attributable playout samples.
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
        fun fromSetting(value: String): GalProfile = entries.firstOrNull { it.version.toString() == value }
            ?: COMPATIBILITY
    }
}

/** Immutable after the one response is accepted; all service handlers share this snapshot. */
internal data class GalSessionProfile(
    val profile: GalProfile,
    val finalResponse: AapVersionNegotiation.Result
) {
    val requestedVersion get() = profile.version
}

internal val AapVersionNegotiation.Result.version: GalVersion
    get() = GalVersion(major, minor)
