package com.andrerinas.openheadunit.connection

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Holds a producer's ownership check through the non-suspending publication it authorizes. */
fun interface ConnectionAdmission {
    fun run(action: () -> Unit): Boolean

    companion object {
        val UNRESTRICTED = ConnectionAdmission { action -> action(); true }
    }
}

/** Nearby retirement and higher-priority claim replacement must both precede publication. */
internal fun ConnectionAdmission.withClaim(claim: ConnectionArbiter.Claim): ConnectionAdmission =
    ConnectionAdmission { action ->
        var accepted = false
        run { accepted = ConnectionArbiter.runIfHeld(claim, action) }
        accepted
    }

/**
 * Prepare an incoming resource locally before transferring it to shared connection state.
 * Cancellation and rejection dispose only this candidate, never whichever connection is current.
 * [publish] must finish the ownership transfer without suspending and return true on transfer.
 */
internal suspend fun <T> ConnectionAdmission.prepareAndPublish(
    awaitRetirement: suspend () -> Unit,
    create: () -> T,
    open: suspend (T) -> Unit,
    publish: (T) -> Boolean,
    dispose: (T) -> Unit,
): Boolean {
    awaitRetirement()
    currentCoroutineContext().ensureActive()
    val candidate = create()
    var transferred = false
    try {
        open(candidate)
        currentCoroutineContext().ensureActive()
        run { transferred = publish(candidate) }
        return transferred
    } finally {
        if (!transferred) dispose(candidate)
    }
}

/** A live producer must retire its own attempt when CommManager declines ownership transfer. */
internal class ConnectionAdmissionRejectedException : java.io.IOException("Connection admission superseded")
