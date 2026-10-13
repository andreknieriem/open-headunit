package com.andrerinas.openheadunit.app

/** What a power event does to a pending external-power disconnect. */
enum class ExternalPowerArm { IGNORE, NOTHING_PROJECTING, ARM, CANCEL }

/** One step of the end of a session, or of the return of power. */
enum class ExternalPowerStep { DISCONNECT, SCREEN_OFF, SCREEN_ON, REARM }

/**
 * Ends a session when external power is lost, the way the Exit button does, and undoes it when
 * power returns. Nothing fires without a session: Native AA's poke loop is not one.
 */
object ExternalPowerDisconnectPolicy {

    /** Not below the ACC inference window, or our own screen-off could read as a car switch-off. */
    const val MIN_DELAY_SECONDS = 5
    const val DEFAULT_DELAY_SECONDS = 60

    fun onPowerEvent(enabled: Boolean, connected: Boolean, sessionUp: Boolean): ExternalPowerArm = when {
        !enabled -> ExternalPowerArm.IGNORE
        connected -> ExternalPowerArm.CANCEL
        sessionUp -> ExternalPowerArm.ARM
        else -> ExternalPowerArm.NOTHING_PROJECTING
    }

    fun delayMs(seconds: Int): Long =
        seconds.coerceIn(MIN_DELAY_SECONDS, BtAutoDisconnectPolicy.MAX_DELAY_SECONDS) * 1000L

    /** Asked once the delay has run out. */
    fun shouldEndSession(sessionUp: Boolean, chargerBack: Boolean): Boolean = sessionUp && !chargerBack

    /** The screen goes dark only when its row is on and the lock grant is held. */
    fun planAfterEnd(screenRow: Boolean, grantHeld: Boolean): List<ExternalPowerStep> =
        if (screenRow && grantHeld) listOf(ExternalPowerStep.DISCONNECT, ExternalPowerStep.SCREEN_OFF)
        else listOf(ExternalPowerStep.DISCONNECT)

    /** Power back while our session end is still running waits for it, or the end undoes the return. */
    fun returnWaitsForEnd(endedByUs: Boolean, stillEnding: Boolean): Boolean = endedByUs && stillEnding

    /** The stand-down after our own end stops the stack and darkens the screen only while power is out. */
    fun standDownHolds(powerBack: Boolean): Boolean = !powerBack

    /** Past the hotspot restart and the ByeBye that run before the stand-down; a return waits this long at worst. */
    const val END_REACH_BOUND_MS = 60_000L

    /** Our end is dropped only when it is still the current one and its stand-down never started. */
    fun dropsUnreachedEnd(stillEnding: Boolean, reachedStandDown: Boolean, sameEnd: Boolean): Boolean =
        stillEnding && !reachedStandDown && sameEnd

    /** Power back undoes only what this feature did. */
    fun planOnReturn(endedByUs: Boolean, screenWasOff: Boolean): List<ExternalPowerStep> = when {
        !endedByUs -> emptyList()
        screenWasOff -> listOf(ExternalPowerStep.SCREEN_ON, ExternalPowerStep.REARM)
        else -> listOf(ExternalPowerStep.REARM)
    }
}
