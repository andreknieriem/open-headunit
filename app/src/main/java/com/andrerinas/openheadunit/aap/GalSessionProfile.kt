package com.andrerinas.openheadunit.aap

/** GAL is independent of Android's API level, the AA APK version and wireless bootstrap. */
internal data class GalVersion(val major: Int, val minor: Int) : Comparable<GalVersion> {
    override fun compareTo(other: GalVersion): Int =
        major.compareTo(other.major).takeIf { it != 0 } ?: minor.compareTo(other.minor)
    override fun toString(): String = "$major.$minor"

    /** Both fields are signed 16-bit on the sender; a major of 0 names no protocol. */
    val wellFormed: Boolean get() = major in 1..32767 && minor in 0..32767
}

/**
 * The implemented receiver profiles. The phone keeps the version requested last before TLS
 * for its feature gates, so the request, never a higher reply, names the active profile.
 * 6.1 shares the 6.0 media contract: no separate 6.1 feature branch has been found.
 * A/V correction still requires attributable playout samples.
 */
internal enum class GalProfile(val version: GalVersion) {
    COMPATIBILITY(GalVersion(1, 2)),
    GAL_1_6(GalVersion(1, 6)),
    GAL_6_1(GalVersion(6, 1))
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
