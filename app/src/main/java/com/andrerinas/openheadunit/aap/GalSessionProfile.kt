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

/**
 * Which version to request first and which profile a reply selects.
 *
 * Inspected Android Auto handlers (9.7 to 18.2) never refuse a VersionRequest. They answer 1.7
 * to a request of 1.7 or lower and their maximum constant to anything higher, with success, and
 * keep the last request before TLS. One 6.1 request therefore reveals that maximum, and a phone
 * answering less receives one more, final request on the same connection.
 *
 * Google Play services 12.8.76 (2018) hosted a car service that accepted major version 1 only:
 * it answers a 6.x request with 1.5 and STATUS_NO_COMPATIBLE_VERSION, then closes the
 * connection. No phone in use is known to run it. After such a refusal this process requests
 * 1.2 on every later attempt; 1.6 would exceed the 1.5 that implementation answers.
 */
internal object GalVersionPolicy {
    @Volatile private var openingRefused = false

    fun opening(compatibilityMode: Boolean): GalProfile =
        if (compatibilityMode || openingRefused) GalProfile.COMPATIBILITY else GalProfile.GAL_6_1

    /** The highest implemented profile the reply covers, never above the request already sent. */
    fun select(opening: GalProfile, reply: GalVersion): GalProfile =
        GalProfile.entries.lastOrNull { it <= opening && it.version <= reply } ?: GalProfile.COMPATIBILITY

    /** A refused opening request ends its connection; the next attempt asks for 1.2 instead. */
    fun noteRefused(opening: GalProfile) {
        if (opening != GalProfile.COMPATIBILITY) openingRefused = true
    }

    internal fun resetForTest() {
        openingRefused = false
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
