package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.connection.projection.LoopbackBindPolicy
import com.andrerinas.openheadunit.connection.projection.SocketProjectionConnection
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.WirelessRecoveryPolicy

/**
 * Pure-Kotlin policy defining Connection-Mode Fallback priority ordering, precondition validation,
 * and per-mode attempt budgets.
 *
 * ## Fallback Priority Order
 * 1. **Last successful mode** (persisted/tracked from the most recent healthy session, if eligible).
 * 2. [CandidateMode.NATIVE_WIFI_DIRECT] (Bluetooth-initiated transition to Wi-Fi Direct / head unit hotspot).
 * 3. [CandidateMode.SHARED_WIFI_HEADUNIT_SERVER] (Shared Wi-Fi network for AA 17.4+ Headunit Server or AA <= 17.3 with Wireless Helper).
 * 4. [CandidateMode.MANUAL_IP] (User-configured target IP/subnet via [SocketProjectionConnection]).
 * 5. [CandidateMode.USB] (Only when a USB projection transport/device is attached).
 */
object ConnectionFallbackPolicy {

    const val DEFAULT_MAX_ATTEMPTS_PER_MODE = 2
    const val DEFAULT_MANUAL_PORT = 5277

    enum class CandidateMode(val key: String, val displayName: String) {
        NATIVE_WIFI_DIRECT("native_wifi_direct", "Native Wi-Fi Direct"),
        SHARED_WIFI_HEADUNIT_SERVER("shared_wifi_headunit_server", "Shared Wi-Fi Headunit Server"),
        MANUAL_IP("manual_ip", "Manual IP"),
        USB("usb", "USB");

        fun toWifiLauncherMode(
            configuredWifiMode: WifiLauncherMode = WifiLauncherMode.DEFAULT,
            helperUsesSharedNetwork: Boolean = false,
        ): WifiLauncherMode? = when (this) {
            NATIVE_WIFI_DIRECT -> WifiLauncherMode.NATIVE
            SHARED_WIFI_HEADUNIT_SERVER ->
                if (configuredWifiMode == WifiLauncherMode.HELPER && helperUsesSharedNetwork) {
                    WifiLauncherMode.HELPER
                } else {
                    WifiLauncherMode.AUTO
                }
            MANUAL_IP -> WifiLauncherMode.MANUAL
            USB -> null
        }

        companion object {
            val STATIC_PRIORITY_ORDER: List<CandidateMode> = listOf(
                NATIVE_WIFI_DIRECT,
                SHARED_WIFI_HEADUNIT_SERVER,
                MANUAL_IP,
                USB,
            )

            fun fromKey(key: String?): CandidateMode? {
                val normalized = key?.trim()?.takeIf { it.isNotEmpty() } ?: return null
                return entries.firstOrNull { it.key.equals(normalized, ignoreCase = true) }
            }
        }
    }

    enum class SkipReason(val description: String) {
        FALLBACK_DISABLED("automatic connection-mode fallback is disabled"),
        WIRELESS_NOT_SELECTED("wireless connection mode is not enabled in user settings"),
        WIRELESS_PAUSED_BY_BOOT_LOOP("wireless bring-up is paused by the boot-loop guard"),
        NATIVE_BLOCKED_BY_EXTERNAL_BT("native AA handshake is blocked by external Bluetooth transport"),
        SHARED_WIFI_UNAVAILABLE("no shared Wi-Fi network or local discovery route is available"),
        MANUAL_IP_UNCONFIGURED("no valid manual target IPv4 address is configured"),
        USB_NOT_CONNECTED("no USB projection device or accessory is connected"),
        USB_NOT_SELECTED("USB connection mode is not enabled in user settings"),
    }

    data class Preconditions(
        val fallbackEnabled: Boolean = true,
        val lastSuccessfulMode: CandidateMode? = null,
        val wirelessSelected: Boolean = true,
        val wirelessPausedByBootLoop: Boolean = false,
        val nativeBlockedByExternalBt: Boolean = false,
        val sharedWifiAvailable: Boolean = true,
        val manualIp: String? = null,
        val manualPort: Int = DEFAULT_MANUAL_PORT,
        val usbSelected: Boolean = true,
        val usbConnected: Boolean = false,
        val maxAttemptsPerMode: Int = DEFAULT_MAX_ATTEMPTS_PER_MODE,
    )

    data class ChainEvaluation(
        val orderedCandidates: List<CandidateMode>,
        val eligibleModes: List<CandidateMode>,
        val skippedModes: Map<CandidateMode, SkipReason>,
        val lastSuccessfulPromoted: Boolean,
    )

    fun isValidManualEndpoint(ip: String?, port: Int = DEFAULT_MANUAL_PORT): Boolean {
        val host = ip?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        if (port !in 1..65535) return false
        if (SocketProjectionConnection.ipv4ToInt(host) == null) return false
        return LoopbackBindPolicy.needsNetworkBinding(host)
    }

    /**
     * Returns the deduplicated priority order: [lastSuccessfulMode] first (when non-null),
     * followed by the static priority order ([CandidateMode.NATIVE_WIFI_DIRECT],
     * [CandidateMode.SHARED_WIFI_HEADUNIT_SERVER], [CandidateMode.MANUAL_IP], [CandidateMode.USB]).
     */
    fun orderedModes(lastSuccessfulMode: CandidateMode?): List<CandidateMode> {
        if (lastSuccessfulMode == null) return CandidateMode.STATIC_PRIORITY_ORDER
        val result = ArrayList<CandidateMode>(CandidateMode.STATIC_PRIORITY_ORDER.size)
        result.add(lastSuccessfulMode)
        for (mode in CandidateMode.STATIC_PRIORITY_ORDER) {
            if (mode != lastSuccessfulMode) {
                result.add(mode)
            }
        }
        return result
    }

    /**
     * Checks whether [mode] satisfies [preconditions]. Returns `null` when eligible, or the
     * disqualifying [SkipReason] when the mode must be skipped.
     */
    fun skipReasonFor(mode: CandidateMode, preconditions: Preconditions): SkipReason? {
        return when (mode) {
            CandidateMode.NATIVE_WIFI_DIRECT -> when {
                !preconditions.wirelessSelected -> SkipReason.WIRELESS_NOT_SELECTED
                preconditions.wirelessPausedByBootLoop -> SkipReason.WIRELESS_PAUSED_BY_BOOT_LOOP
                preconditions.nativeBlockedByExternalBt -> SkipReason.NATIVE_BLOCKED_BY_EXTERNAL_BT
                else -> null
            }
            CandidateMode.SHARED_WIFI_HEADUNIT_SERVER -> when {
                !preconditions.wirelessSelected -> SkipReason.WIRELESS_NOT_SELECTED
                preconditions.wirelessPausedByBootLoop -> SkipReason.WIRELESS_PAUSED_BY_BOOT_LOOP
                !preconditions.sharedWifiAvailable -> SkipReason.SHARED_WIFI_UNAVAILABLE
                else -> null
            }
            CandidateMode.MANUAL_IP -> when {
                !preconditions.wirelessSelected -> SkipReason.WIRELESS_NOT_SELECTED
                preconditions.wirelessPausedByBootLoop -> SkipReason.WIRELESS_PAUSED_BY_BOOT_LOOP
                !isValidManualEndpoint(preconditions.manualIp, preconditions.manualPort) ->
                    SkipReason.MANUAL_IP_UNCONFIGURED
                else -> null
            }
            CandidateMode.USB -> when {
                !preconditions.usbSelected -> SkipReason.USB_NOT_SELECTED
                !preconditions.usbConnected -> SkipReason.USB_NOT_CONNECTED
                else -> null
            }
        }
    }

    /**
     * Evaluates all candidate modes in priority order against [preconditions].
     */
    fun evaluateChain(preconditions: Preconditions): ChainEvaluation {
        val ordered = orderedModes(preconditions.lastSuccessfulMode)
        val eligible = ArrayList<CandidateMode>(ordered.size)
        val skipped = LinkedHashMap<CandidateMode, SkipReason>()
        for (mode in ordered) {
            val reason = skipReasonFor(mode, preconditions)
            if (reason == null) {
                eligible.add(mode)
            } else {
                skipped[mode] = reason
            }
        }
        val promoted = preconditions.lastSuccessfulMode != null &&
            eligible.firstOrNull() == preconditions.lastSuccessfulMode
        return ChainEvaluation(
            orderedCandidates = ordered,
            eligibleModes = eligible,
            skippedModes = skipped,
            lastSuccessfulPromoted = promoted,
        )
    }

    /**
     * True when the current mode should immediately yield to the next eligible mode in the chain
     * even before reaching [Preconditions.maxAttemptsPerMode].
     */
    fun shouldEscalateEarly(
        mode: CandidateMode,
        failureCause: String?,
        silentPeerFailures: Int,
    ): Boolean {
        if (failureCause == WirelessRecoveryPolicy.FAILURE_PEER_SILENT &&
            silentPeerFailures >= UnresponsivePeerPolicy.SILENT_FAILURES_BEFORE_BACKOFF &&
            (mode == CandidateMode.SHARED_WIFI_HEADUNIT_SERVER || mode == CandidateMode.MANUAL_IP)
        ) {
            return true
        }
        return false
    }
}
