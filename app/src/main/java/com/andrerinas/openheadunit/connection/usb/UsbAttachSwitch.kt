package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.ConnectionArbiter
import com.andrerinas.openheadunit.utils.AppLog

/**
 * The attach screen's AOA switch worker: what its thread runs once the switch's authority was
 * taken on Main. Unlocked, that authority is the admitted USB [CommManager.ConnectionRequest];
 * before first unlock it is a bare arbiter claim, as it always was (no request can exist then).
 * At the worker's first entry, before any USB call, exactly that authority must still be valid: the
 * request (a user exit, Stop or a newer USB admission may have revoked it meanwhile) or the claim
 * (the user may have picked wireless, or a newer claim replaced it). A revoked switch touches
 * nothing. Either way the authority ends here, by identity, whatever the switch did.
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
        val current = if (request != null) commManager?.isValid(request) == true
            else lockedClaim != null && ConnectionArbiter.holds(lockedClaim)
        if (!current) {
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
