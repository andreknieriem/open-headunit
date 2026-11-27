package com.andrerinas.openheadunit.connection.carplaynative.airplay

/**
 * Which handler an RTSP request on the AirPlay control connection reaches. Before pair-verify
 * ends, only OPTIONS and the four pairing and info paths pass; every other request is refused with 470.
 * Follows LIVI (https://github.com/f-io/LIVI), crate livi-cp, `stack/control.rs`.
 */
object RtspRoutes {
    const val STATUS_NEEDS_PAIR_VERIFY = 470
    const val CONTENT_PLIST = "application/x-apple-binary-plist"
    const val CONTENT_PAIRING = "application/pairing+tlv8"
    const val CONTENT_OCTET = "application/octet-stream"

    enum class Route { Setup, Record, Teardown, PairSetup, PairVerify, AuthSetup, Info, Command, Feedback, Options, Other, NeedsPairVerify }

    private val OPEN_PATHS = listOf("/info", "/pair-setup", "/pair-verify", "/auth-setup")

    fun route(method: String, path: String, pairVerified: Boolean): Route {
        val lower = path.lowercase()
        if (!pairVerified && !isOpen(method, lower)) return Route.NeedsPairVerify
        return when (method) {
            "SETUP" -> Route.Setup
            "RECORD" -> Route.Record
            "TEARDOWN" -> Route.Teardown
            else -> when {
                lower.endsWith("/pair-setup") -> Route.PairSetup
                lower.endsWith("/pair-verify") -> Route.PairVerify
                lower.endsWith("/auth-setup") -> Route.AuthSetup
                lower.endsWith("/info") -> Route.Info
                method == "POST" && lower.endsWith("/command") -> Route.Command
                method == "POST" && lower.endsWith("/feedback") -> Route.Feedback
                method == "OPTIONS" -> Route.Options
                else -> Route.Other
            }
        }
    }

    private val STREAM_METHODS = setOf("SETUP", "RECORD", "TEARDOWN")

    /** A stream method never passes, whatever its path ends with. */
    private fun isOpen(method: String, lowerPath: String): Boolean =
        method == "OPTIONS" || (method !in STREAM_METHODS && OPEN_PATHS.any { lowerPath.endsWith(it) })
}
