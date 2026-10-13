package com.andrerinas.openheadunit.app

/** What a power event does to a pending external-power disconnect. */
enum class ExternalPowerArm { IGNORE, NOTHING_PROJECTING, ARM, ARM_SCREEN_ONLY, CANCEL }

/** What a screen-only timer does once its delay has run out. */
enum class ScreenOnlyOutcome { SKIP_POWER_BACK, SKIP_DISABLED, SKIP_SESSION, NO_GRANT, SCREEN_OFF }

/** One step of the end of a session, or of the return of power. */
enum class ExternalPowerStep { DISCONNECT, SCREEN_OFF, SCREEN_ON, REARM }

/** When the wireless re-arm a wired session owes runs, once a stand-down has ended that session. */
enum class WiredRearm { NOW, ON_POWER_BACK, DROP }

/**
 * Ends a session when external power is lost, the way the Exit button does, and undoes it when
 * power returns. With no session only the screen follows the charger: Native AA's poke loop is not one.
 */
object ExternalPowerDisconnectPolicy {

    /** Not below the ACC inference window, or our own screen-off could read as a car switch-off. */
    const val MIN_DELAY_SECONDS = 5
    const val DEFAULT_DELAY_SECONDS = 60

    /** Power back cancels whatever the switch says, so a timer armed before a switch-off cannot fire. */
    fun onPowerEvent(enabled: Boolean, connected: Boolean, sessionUp: Boolean, screenRow: Boolean): ExternalPowerArm = when {
        connected -> ExternalPowerArm.CANCEL
        !enabled -> ExternalPowerArm.IGNORE
        sessionUp -> ExternalPowerArm.ARM
        screenRow -> ExternalPowerArm.ARM_SCREEN_ONLY
        else -> ExternalPowerArm.NOTHING_PROJECTING
    }

    fun delayMs(seconds: Int): Long =
        seconds.coerceIn(MIN_DELAY_SECONDS, BtAutoDisconnectPolicy.MAX_DELAY_SECONDS) * 1000L

    /** Asked once the delay has run out; the switch and the plug are read again, not trusted from the arm. */
    fun shouldEndSession(enabled: Boolean, sessionUp: Boolean, chargerBack: Boolean): Boolean =
        enabled && sessionUp && !chargerBack

    /** Asked once a screen-only delay has run out; the switch, the row and the plug are read again. */
    fun screenOnlyOutcome(
        enabled: Boolean, screenRow: Boolean, grantHeld: Boolean, sessionUpOrForming: Boolean, chargerBack: Boolean
    ): ScreenOnlyOutcome = when {
        chargerBack -> ScreenOnlyOutcome.SKIP_POWER_BACK
        !enabled || !screenRow -> ScreenOnlyOutcome.SKIP_DISABLED
        sessionUpOrForming -> ScreenOnlyOutcome.SKIP_SESSION
        !grantHeld -> ScreenOnlyOutcome.NO_GRANT
        else -> ScreenOnlyOutcome.SCREEN_OFF
    }

    /** The screen goes dark only when its row is on and the lock grant is held. */
    fun planAfterEnd(screenRow: Boolean, grantHeld: Boolean): List<ExternalPowerStep> =
        if (screenRow && grantHeld) listOf(ExternalPowerStep.DISCONNECT, ExternalPowerStep.SCREEN_OFF)
        else listOf(ExternalPowerStep.DISCONNECT)

    /** Power back while our session end is still running waits for it, or the end undoes the return. */
    fun returnWaitsForEnd(endedByUs: Boolean, stillEnding: Boolean): Boolean = endedByUs && stillEnding

    /** The stand-down after our own end stops the stack and darkens the screen only while power is out. */
    fun standDownHolds(powerBackSeen: Boolean, pluggedNow: Boolean): Boolean = !(powerBackSeen && pluggedNow)

    /** The wired re-arm waits for the stand-down, or it brings the stack back up after the stop. */
    fun wiredRearm(standDownHolds: Boolean, endedByPower: Boolean): WiredRearm = when {
        !standDownHolds -> WiredRearm.NOW
        endedByPower -> WiredRearm.ON_POWER_BACK
        else -> WiredRearm.DROP
    }

    /** Past the hotspot restart and the ByeBye that run before the stand-down; a return waits this long at worst. */
    const val END_REACH_BOUND_MS = 60_000L

    /** Our end is dropped only when it is still the current one and its stand-down never started. */
    fun dropsUnreachedEnd(stillEnding: Boolean, reachedStandDown: Boolean, sameEnd: Boolean): Boolean =
        stillEnding && !reachedStandDown && sameEnd

    /** Power back undoes only what this feature did. */
    fun planOnReturn(endedByUs: Boolean, screenWasOff: Boolean): List<ExternalPowerStep> = when {
        !endedByUs && screenWasOff -> listOf(ExternalPowerStep.SCREEN_ON)
        !endedByUs -> emptyList()
        screenWasOff -> listOf(ExternalPowerStep.SCREEN_ON, ExternalPowerStep.REARM)
        else -> listOf(ExternalPowerStep.REARM)
    }
}
