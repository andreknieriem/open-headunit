package com.andrerinas.openheadunit.main

/**
 * Pure policy for calculating the floating button's target opacity and touchability.
 */
object FloatingButtonOpacityPolicy {

    /**
     * Calculates the target opacity alpha (0.0f to 1.0f).
     *
     * @param isConnectionStatusMode whether connection status indicator mode is enabled.
     * @param isConnected whether a phone is currently connected.
     * @param connectedOpacityPercent opacity percentage (10 to 100) when connected or status mode is off.
     * @param disconnectedOpacityPercent opacity percentage (0 to 100) when disconnected in status mode.
     */
    fun targetAlpha(
        isConnectionStatusMode: Boolean,
        isConnected: Boolean,
        connectedOpacityPercent: Int,
        disconnectedOpacityPercent: Int,
    ): Float {
        val configuredAlpha = (connectedOpacityPercent / 100f).coerceIn(0.1f, 1.0f)
        val disconnectedAlpha = (disconnectedOpacityPercent / 100f).coerceIn(0.0f, 1.0f)
        return if (isConnectionStatusMode && !isConnected) {
            disconnectedAlpha
        } else {
            configuredAlpha
        }
    }

    /**
     * An overlay window at 0% opacity (alpha <= 0.0f) must carry FLAG_NOT_TOUCHABLE
     * so it does not intercept touch events meant for the app underneath.
     */
    fun isTouchable(targetAlpha: Float): Boolean = targetAlpha > 0.0f

    /** Android 12 blocks taps through an overlay window above 0.8 and only 13+ caps it, so a hidden button's window is 0. */
    fun windowAlpha(targetAlpha: Float): Float = if (isTouchable(targetAlpha)) 1.0f else 0.0f
}
