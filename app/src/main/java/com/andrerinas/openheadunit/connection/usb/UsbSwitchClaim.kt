package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.connection.ConnectionArbiter

/**
 * The AOA switch guard shared by the attach screen and the service's attach fallback.
 * It needs no AppComponent or credential-protected settings, including before first unlock.
 * Every mutation names the exact admitted claim: an old worker's finally/completion cannot
 * release a replacement guard or count its switch as the replacement's work.
 */
object UsbSwitchClaim {
    private class Stake(val owner: ConnectionArbiter.Claim, val untilMs: Long) {
        var switched = false
    }

    private var current: Stake? = null
    private var switches = 0L

    /** The arbiter admission and guard publication are one decision (arbiter -> guard). */
    fun stake(owner: ConnectionArbiter.Claim): Boolean = ConnectionArbiter.runIfHeld(owner) {
        synchronized(this) {
            if (current?.owner !== owner) {
                current = Stake(owner, UsbAccessoryHandoffPolicy.claimExpiryFrom(System.currentTimeMillis()))
            }
        }
    }

    @Synchronized
    fun release(owner: ConnectionArbiter.Claim) {
        if (current?.owner === owner) current = null
    }

    /** A physical switch is counted once, only by the still-owned stake that performed it. */
    fun noteSwitched(owner: ConnectionArbiter.Claim) {
        ConnectionArbiter.runIfHeld(owner) {
            synchronized(this) {
                current?.takeIf { it.owner === owner && !it.switched }?.let {
                    it.switched = true
                    switches++
                }
            }
        }
    }

    @Synchronized
    fun switches(): Long = switches

    @Synchronized
    fun isLive(): Boolean = current?.let {
        UsbAccessoryHandoffPolicy.switchClaimIsLive(System.currentTimeMillis(), it.untilMs)
    } == true
}
