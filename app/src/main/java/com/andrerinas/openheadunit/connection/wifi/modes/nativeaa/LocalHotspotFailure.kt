package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * Why a local-only hotspot start failed, with one line of advice. Every advice names the
 * "Use the system hotspot" setting: there is no automatic fall-back to it.
 */
enum class LocalHotspotFailure {
    NO_CHANNEL,
    GENERIC,
    INCOMPATIBLE_MODE,
    TETHERING_DISALLOWED,
    TIMEOUT,
    WIFI_OFF,
    SECURITY_EXCEPTION,
    REQUEST_PENDING,
    MISSING_GRANT,
    NOT_JOINABLE,
    UNSUPPORTED_SECURITY,
    NO_ADDRESS,
    NO_INTERFACE,
    P2P_TEARDOWN_TIMEOUT,
    RESTART_BUDGET,
    SYSTEM_HOTSPOT_STARTED,
    PLATFORM_STOPPED;

    /** One line of what to check. [sdkInt] decides the lines that hold from one API level on. */
    fun describe(sdkInt: Int): String = when (this) {
        NO_CHANNEL -> "this unit found no channel for a hotspot. $FALLBACK"
        GENERIC -> "a Wi-Fi Direct group or a station that holds the radio can block it" +
            (if (sdkInt >= 28) ", and so can a denied Wi-Fi change right" else "") + ". $FALLBACK"
        INCOMPATIBLE_MODE -> "the system hotspot is on, or the app is not in the foreground. $FALLBACK"
        TETHERING_DISALLOWED -> "this unit forbids tethering. $FALLBACK"
        TIMEOUT -> "the hotspot did not start in time. $FALLBACK"
        WIFI_OFF -> "Wi-Fi is off. Turn it on, or $SETTING"
        SECURITY_EXCEPTION -> "the platform refused the call. $FALLBACK"
        REQUEST_PENDING -> "an earlier hotspot request of this app has no answer yet. $FALLBACK"
        MISSING_GRANT -> "a permission the hotspot needs is missing. $FALLBACK"
        NOT_JOINABLE -> "the hotspot gave no name or password. $FALLBACK"
        UNSUPPORTED_SECURITY -> "the hotspot uses a security type the phone cannot join. $FALLBACK"
        NO_ADDRESS -> "the unit gives no hotspot address. $FALLBACK"
        NO_INTERFACE -> "no new network interface appeared for the hotspot. $FALLBACK"
        P2P_TEARDOWN_TIMEOUT -> "Wi-Fi Direct did not finish stopping. $FALLBACK"
        RESTART_BUDGET -> "the hotspot restarted too often in one minute. $FALLBACK"
        SYSTEM_HOTSPOT_STARTED -> "the system hotspot started and took the radio. $FALLBACK"
        PLATFORM_STOPPED -> "the platform stopped the hotspot. $FALLBACK"
    }

    companion object {
        private const val SETTING = "turn on \"Use the system hotspot\" in the settings."
        private const val FALLBACK = "If it keeps failing, $SETTING"

        /** [wifiOn] false wins over any callback reason: the radio is the cause. */
        fun fromReason(reason: Int, wifiOn: Boolean): LocalHotspotFailure = when {
            !wifiOn -> WIFI_OFF
            reason == 1 -> NO_CHANNEL
            reason == 3 -> INCOMPATIBLE_MODE
            reason == 4 -> TETHERING_DISALLOWED
            else -> GENERIC
        }

        /** The call threw. The platform throws IllegalStateException while this app has a request open. */
        fun fromThrown(error: RuntimeException): LocalHotspotFailure = when (error) {
            is SecurityException -> SECURITY_EXCEPTION
            is IllegalStateException -> REQUEST_PENDING
            else -> GENERIC
        }
    }
}
