package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import android.content.Context
import android.os.PowerManager
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ScreenPower

/**
 * The screen held lit while a phone joins this unit's network. [JoinScreenWakePolicy] says why.
 *
 * One hold at a time, belonging to the handshake that took it: a handshake that is superseded
 * ends in its own finally, and must not let go of the hold its successor has just taken.
 */
internal class JoinScreenWake(private val context: Context) {

    private var lock: PowerManager.WakeLock? = null
    private var owner: Any? = null
    // Said once per run of refusals, not on every retry of it.
    private var saidRuledOut = false

    /** Lights or keeps lit the screen for [owner]'s join, as the policy says. */
    @Synchronized
    fun begin(owner: Any, refusalsInARow: Int): JoinScreenWakePolicy.Hold {
        val hold = JoinScreenWakePolicy.hold(ScreenPower.isInteractive(context), refusalsInARow)
        if (hold == JoinScreenWakePolicy.Hold.RULED_OUT) {
            if (!saidRuledOut) {
                saidRuledOut = true
                AppLog.i("NativeAA: the phone refused $refusalsInARow joins in a row with this unit's screen held for it, so the screen is not what stops it and is left alone from here.")
            }
            return hold
        }
        saidRuledOut = false
        if (hold == JoinScreenWakePolicy.Hold.NONE) return hold
        try {
            val held = lock ?: newLock().also { lock = it }
            // Not reference counted, so a second begin only restarts the timeout.
            held.acquire(JoinScreenWakePolicy.HOLD_MS)
            this.owner = owner
        } catch (e: RuntimeException) {
            AppLog.w("NativeAA: the screen could not be held for the phone's join (${e.message}); with it dark, this unit's WiFi can drop the phone's DHCP.")
            return JoinScreenWakePolicy.Hold.NONE
        }
        if (hold == JoinScreenWakePolicy.Hold.WAKE) {
            AppLog.i(
                "NativeAA: this unit's screen was off as the phone came to join, so it is lit for the join " +
                    "(${JoinScreenWakePolicy.HOLD_MS / 1000}s at most): with the screen off, this unit's WiFi can drop the phone's DHCP."
            )
        } else {
            AppLog.d("NativeAA: keeping the screen lit for the phone's join.")
        }
        return hold
    }

    /** Lets go of the hold if [owner] took it; a null [owner] lets go of any. */
    @Synchronized
    fun end(owner: Any?, why: String) {
        if (owner != null && owner !== this.owner) return
        this.owner = null
        val held = lock ?: return
        if (!held.isHeld) return
        held.release()
        AppLog.i("NativeAA: the screen held for the phone's join is let go ($why).")
    }

    // A service has no window to put FLAG_KEEP_SCREEN_ON on, which is all the deprecation offers.
    @Suppress("DEPRECATION")
    private fun newLock(): PowerManager.WakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).newWakeLock(
            PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
            "OpenHeadunit:NativeJoin"
        ).apply { setReferenceCounted(false) }
}
