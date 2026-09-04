package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * Pure decision policy governing multi-driver / device selection in Native AA Wireless mode.
 *
 * All decisions regarding whether to show the selection dialog, which device to prioritize,
 * and how to resolve auto-connect timeouts are encapsulated here for testability.
 */
object NativeDriverSelectionPolicy {

    enum class Mode(val id: Int) {
        DISABLED(0),
        AUTO(1),
        ALWAYS(2);

        companion object {
            fun fromId(id: Int): Mode = entries.firstOrNull { it.id == id } ?: AUTO
        }
    }

    const val DEFAULT_TIMEOUT_SEC = 10
    const val MIN_TIMEOUT_SEC = 3
    const val MAX_TIMEOUT_SEC = 30

    /**
     * Determines whether the driver/device selection dialog should be displayed.
     *
     * @param mode User preference mode (DISABLED, AUTO, ALWAYS)
     * @param pairedCount Total number of paired phone candidates
     * @param connectedCount Number of devices currently connected via Bluetooth
     * @param hasHistory Whether a preferred or previously connected device is recorded
     */
    fun shouldShowSelector(
        mode: Mode,
        pairedCount: Int,
        connectedCount: Int,
        hasHistory: Boolean = true
    ): Boolean {
        if (mode == Mode.DISABLED) return false
        if (pairedCount <= 1) return false

        return when (mode) {
            Mode.ALWAYS -> true
            Mode.AUTO -> {
                // If exactly one phone is already connected to the car's Bluetooth, we know
                // unambiguously which driver is in the vehicle, so skip the prompt!
                if (connectedCount == 1) {
                    false
                } else if (!hasHistory) {
                    // On first start with multiple paired phones and no history, prompt user to choose
                    true
                } else {
                    // Multiple paired phones and either 0 or multiple connected
                    true
                }
            }
            Mode.DISABLED -> false
        }
    }

    /**
     * Resolves the target device MAC to connect to when the selection timer expires without user interaction.
     *
     * Priority:
     * 1. If exactly 1 device is currently connected via Bluetooth in the car, choose it.
     * 2. If preferred device is specified and available, choose it (connected takes precedence over paired).
     * 3. If last-used device is specified and available, choose it (connected takes precedence over paired).
     * 4. If exactly 1 device is in the candidate paired list, choose it.
     * 5. Otherwise, return null (never blindly pick among multiple unknown devices).
     */
    fun resolveAutoConnectTarget(
        preferredMac: String,
        lastUsedMac: String,
        connectedMacs: List<String>,
        pairedMacs: List<String>
    ): String? {
        if (pairedMacs.isEmpty()) return null

        val validConnected = connectedMacs.filter { it in pairedMacs }

        // 1. If exactly one phone is confirmed connected in the vehicle, it takes priority
        if (validConnected.size == 1) {
            return validConnected[0]
        }

        // 2. Preferred MAC (if multiple connected and preferred is among them, or if none connected and preferred is paired)
        if (preferredMac.isNotEmpty()) {
            if (preferredMac in validConnected) {
                return preferredMac
            }
            if (validConnected.isEmpty() && preferredMac in pairedMacs) {
                return preferredMac
            }
        }

        // 3. Last used MAC (if multiple connected and last-used is among them, or if none connected and last-used is paired)
        if (lastUsedMac.isNotEmpty()) {
            if (lastUsedMac in validConnected) {
                return lastUsedMac
            }
            if (validConnected.isEmpty() && lastUsedMac in pairedMacs) {
                return lastUsedMac
            }
        }

        // 4. Exactly 1 candidate phone available
        if (pairedMacs.size == 1) {
            return pairedMacs[0]
        }

        // Multiple candidates, none or multiple connected without history/preference
        return null
    }

    /**
     * Constrains timeout to [MIN_TIMEOUT_SEC]..[MAX_TIMEOUT_SEC].
     */
    fun sanitizeTimeout(timeoutSec: Int): Int {
        return timeoutSec.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)
    }
}
