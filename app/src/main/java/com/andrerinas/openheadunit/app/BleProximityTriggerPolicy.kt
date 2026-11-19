package com.andrerinas.openheadunit.app

/**
 * Pure decision policy for the opt-in internal BLE proximity auto-start trigger.
 *
 * BLE proximity is strictly a wake/proximity signal (not a transport or classic BT HFP pairing
 * replacement). This policy prevents false triggers and infinite loops by enforcing:
 * - Opt-in gate (off by default)
 * - Active session / busy / settings-visible / user-exit / user-cancel guards
 * - Target device name or normalized MAC match + RSSI floor
 * - Monotonic cooldown window after each trigger
 */
object BleProximityTriggerPolicy {

    const val DEFAULT_COOLDOWN_SECONDS = 45
    const val MIN_COOLDOWN_SECONDS = 15
    const val MAX_COOLDOWN_SECONDS = 300
    const val DEFAULT_MIN_RSSI = -78

    private val MAC_REGEX = Regex("^[0-9A-Fa-f]{2}([:-][0-9A-Fa-f]{2}){5}$")

    data class Config(
        val enabled: Boolean,
        val target: String,
        val cooldownMs: Long = DEFAULT_COOLDOWN_SECONDS * 1000L,
        val minRssi: Int = DEFAULT_MIN_RSSI,
    )

    data class Environment(
        val sessionBusy: Boolean,
        val settingsVisible: Boolean,
        val userExitActive: Boolean,
        val wirelessCancelledByUser: Boolean,
    )

    data class Sighting(
        val deviceName: String?,
        val deviceAddress: String?,
        val rssi: Int,
        val nowMs: Long,
    )

    sealed class Decision {
        object Trigger : Decision()
        data class Ignore(val reason: String) : Decision()
    }

    fun isMacAddress(value: String): Boolean = MAC_REGEX.matches(value.trim())

    fun normalizeMac(value: String): String = value.trim().replace('-', ':').uppercase()

    fun matchesTarget(rawTarget: String, deviceName: String?, deviceAddress: String?): Boolean {
        val target = rawTarget.trim()
        if (target.isEmpty()) return false
        if (isMacAddress(target)) {
            val addr = deviceAddress?.trim() ?: return false
            return isMacAddress(addr) && normalizeMac(addr) == normalizeMac(target)
        }
        val name = deviceName?.trim() ?: return false
        if (name.isEmpty()) return false
        return name.equals(target, ignoreCase = true) || name.startsWith(target, ignoreCase = true)
    }

    fun evaluate(
        config: Config,
        env: Environment,
        sighting: Sighting,
        lastTriggerAtMs: Long?,
    ): Decision {
        if (!config.enabled) return Decision.Ignore("disabled")
        if (config.target.isBlank()) return Decision.Ignore("no_target")
        if (env.sessionBusy) return Decision.Ignore("session_busy")
        if (env.settingsVisible) return Decision.Ignore("settings_visible")
        if (env.userExitActive) return Decision.Ignore("user_exit_cooldown")
        if (env.wirelessCancelledByUser) return Decision.Ignore("wireless_cancelled")
        if (!matchesTarget(config.target, sighting.deviceName, sighting.deviceAddress)) {
            return Decision.Ignore("target_mismatch")
        }
        if (sighting.rssi < config.minRssi) return Decision.Ignore("rssi_too_weak")
        if (lastTriggerAtMs != null && (sighting.nowMs - lastTriggerAtMs) < config.cooldownMs) {
            return Decision.Ignore("cooldown_active")
        }
        return Decision.Trigger
    }

    /** Whether the BLE scanner itself should be running right now. */
    fun shouldScan(
        enabled: Boolean,
        target: String,
        sessionBusy: Boolean,
    ): Boolean = enabled && target.isNotBlank() && !sessionBusy
}
