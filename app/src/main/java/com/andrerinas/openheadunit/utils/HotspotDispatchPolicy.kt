package com.andrerinas.openheadunit.utils

/**
 * Determines the order and conditions under which hotspot invocation mechanisms
 * are attempted.
 *
 * Background:
 * On Android 11+ (API 30 / Build.VERSION_CODES.R), the official TetheringManager API is preferred.
 * On earlier Android versions, or if TetheringManager reflection fails, the app falls back to
 * ConnectivityManager's reflection path (startTethering).
 * Finally, if ConnectivityManager is unavailable or fails, it falls back to legacy
 * WifiManager.setWifiApEnabled.
 *
 * Crucially, these mechanisms must be attempted sequentially as FALLBACKS:
 * once a mechanism successfully posts a start/stop request without throwing, subsequent
 * mechanisms must NOT be invoked. Concurrently invoking multiple mechanisms (e.g. TetheringManager
 * and ConnectivityManager ~20-40ms apart) causes the OS tethering service to abort or tear down
 * the soft AP that was just starting up (#1230).
 */
object HotspotDispatchPolicy {

    enum class Method {
        TETHERING_MANAGER,
        CONNECTIVITY_MANAGER,
        LEGACY_WIFI_MANAGER
    }

    /**
     * Returns the list of invocation methods to attempt in order of priority.
     *
     * @param sdkInt Build.VERSION.SDK_INT
     * @param hasWriteSettings whether WRITE_SETTINGS permission is granted
     */
    fun candidateMethods(sdkInt: Int, hasWriteSettings: Boolean): List<Method> {
        val list = mutableListOf<Method>()
        if (sdkInt >= 30 /* Build.VERSION_CODES.R */) {
            list.add(Method.TETHERING_MANAGER)
        }
        if (hasWriteSettings) {
            list.add(Method.CONNECTIVITY_MANAGER)
            list.add(Method.LEGACY_WIFI_MANAGER)
        }
        return list
    }

    /**
     * Determines whether subsequent candidate methods should be skipped given the result
     * of the last attempted method.
     *
     * If an invocation method returned true (request was posted without throwing),
     * it must NOT be followed by another method call.
     */
    fun shouldStopAfterAttempt(attemptSucceeded: Boolean): Boolean = attemptSucceeded
}
