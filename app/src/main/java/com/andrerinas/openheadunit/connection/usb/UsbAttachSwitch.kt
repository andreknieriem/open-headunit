package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.ConnectionArbiter
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An AOA switch worker: what its thread runs once the switch's authority was taken on Main, by the
 * attach screen or by a manual tap ([launchManual]). Unlocked, that authority is the admitted USB
 * [CommManager.ConnectionRequest]; before first unlock (attach screen only) it is a bare arbiter
 * claim, as it always was (no request can exist then).
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

    /**
     * A switch the user asked for by hand: the home screen's USB button or a row of the USB list.
     * The tap is the input, so its USER request is admitted here, on the tap's Main thread; the IO
     * hop that follows is not a new input and runs [run] with exactly that request, so an overlay
     * Cancel, Stop or a newer claim before the worker starts leaves the device alone. The request
     * ends by identity when the job completes, also when it is cancelled before its body runs.
     * [onOutcome] runs back on [scope]'s own dispatcher. Returns null when the tap was refused.
     */
    fun launchManual(
        scope: CoroutineScope,
        commManager: CommManager,
        what: String,
        io: CoroutineDispatcher = Dispatchers.IO,
        switch: () -> Boolean,
        onOutcome: (Outcome) -> Unit,
    ): Job? {
        val request = commManager.admitUsbRequest(ConnectionPriorityPolicy.Tier.USER, "USB switch of $what",
            save = null, recheck = commManager.usbRecheckNow()) ?: return null
        UsbSwitchClaim.stake()
        val job = scope.launch {
            onOutcome(withContext(io) { run(commManager, request, null, what, switch) })
        }
        // A job cancelled before its body ran never reached run()'s finally.
        job.invokeOnCompletion {
            UsbSwitchClaim.release()
            commManager.endRequest(request)
        }
        return job
    }
}
