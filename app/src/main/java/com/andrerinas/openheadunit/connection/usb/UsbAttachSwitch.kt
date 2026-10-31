package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.ConnectionArbiter
import com.andrerinas.openheadunit.utils.AppLog

/**
 * The attach screen's AOA switch worker: what its thread runs once the switch's authority was
 * taken on Main. Unlocked, that authority is the admitted USB [CommManager.ConnectionRequest]; at
 * the worker's first entry, before any USB call, the same request must still be valid (a user
 * exit, Stop or a newer USB admission may have revoked it meanwhile), and a revoked switch touches
 * nothing. Before first unlock it is a bare arbiter claim, as it always was (no request can exist
 * then). Either way the authority ends here, by identity, whatever the switch did.
 */
internal object UsbAttachSwitch {
    enum class Outcome { SWITCHED, FAILED, REVOKED }

    /**
     * Exactly one of [request] (with its [commManager]) or [lockedClaim] is the switch's authority.
     * [switch] performs the blocking USB work; no lock is held across it.
     */
    fun run(
        commManager: CommManager?,
        request: CommManager.ConnectionRequest?,
        lockedClaim: ConnectionArbiter.Claim?,
        what: String,
        switch: () -> Boolean,
    ): Outcome = try {
        if (request != null && commManager?.isValid(request) != true) {
            AppLog.i("UsbAttachedActivity: the switch of $what was revoked before it started; leaving the device alone")
            Outcome.REVOKED
        } else if (switch()) {
            UsbSwitchClaim.noteSwitched()
            Outcome.SWITCHED
        } else Outcome.FAILED
    } catch (e: Exception) {
        AppLog.e("AOA switch threw for $what", e)
        Outcome.FAILED
    } finally {
        UsbSwitchClaim.release()
        if (request != null) commManager?.endRequest(request)
        else ConnectionArbiter.release(lockedClaim, sessionFormed = false)
    }
}
