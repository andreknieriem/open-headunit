package lifecycle

import com.andrerinas.openheadunit.connection.AppExitPolicy
import kotlinx.coroutines.runBlocking

/** Real end-effect APIs: old operations and cleanup owners must leave successors untouched. */
fun effectEntryAuthority() = runBlocking {
    for (open in listOf(false, true)) {
        val manager = CommManager()
        try {
            val old = ConnectionOperation(manager.appConnectionTicket(), null)
            checkNotNull(manager.requestExplicitAppExit(manager.appConnectionTicket(), false))
            if (open) manager.onAppRelaunched()
            val before = manager.state
            val connection = manager.network()
            val revision = manager.appOwnershipRevision()
            manager.disconnect(old, honorKillOnDisconnect = false)
            check(manager.state === before && manager.network() === connection && !manager.requested())
            check(manager.requestExplicitAppExit(old.ticket, false) == null)
            check(manager.appOwnershipRevision() == revision)
        } finally { manager.close() }
    }

    val manager = CommManager()
    try {
        val old = checkNotNull(manager.currentConnectionAttempt())
        manager.disconnect(ConnectionOperation(manager.appConnectionTicket(), null), honorKillOnDisconnect = false)
        manager.cleanups(); manager.awaitCleanup(); manager.connectNext()
        val replacement = manager.network()
        manager.disconnectOwned(old, honorKillOnDisconnect = false)
        manager.disconnectPreempted(checkNotNull(old.claim))
        manager.disconnectForLinkLoss(old, 0)
        check(manager.network() === replacement && manager.state is ConnectionState.Connected && !manager.requested())
        manager.disconnectOwned(checkNotNull(manager.currentConnectionAttempt()), honorKillOnDisconnect = false)
        check(manager.state is ConnectionState.Disconnected)
    } finally { manager.close() }

    val saveManager = CommManager()
    try {
        saveManager.disconnect(ConnectionOperation(saveManager.appConnectionTicket(), null),
            isUserExit = false, honorKillOnDisconnect = false, reason = DisconnectReason.SETTINGS_RESTART)
        val save = saveManager.state as ConnectionState.Disconnected
        val operation = ConnectionOperation(saveManager.appConnectionTicket(), save)
        check(saveManager.isCurrent(operation))
        saveManager.cancelPendingSettingsRestart()
        val recheck = saveManager.usbRecheckNow()
        saveManager.disconnect(operation, honorKillOnDisconnect = false)
        check(saveManager.isCurrent(recheck)) { "a withdrawn Save executed an end effect in the same epoch" }
    } finally { saveManager.close() }

    val stopping = CommManager()
    try {
        val first = checkNotNull(stopping.requestExplicitAppExit(stopping.appConnectionTicket(), false))
        stopping.disconnectForAppExit(first)
        check(stopping.state is ConnectionState.Connected) { "uncommitted Stop owned physical cleanup" }
        check(stopping.commitAppExit(first) === first)
        val second = checkNotNull(stopping.requestExplicitAppExit(stopping.appConnectionTicket(), false))
        check(stopping.commitAppExit(second) === first)
        stopping.onAppRelaunched()
        stopping.disconnectForAppExit(first)
        check(stopping.state is ConnectionState.Disconnected)
        check(stopping.completeAppExit(first).effect == AppExitPolicy.Effect.RESTART_SERVICE)
    } finally { stopping.close() }

    val yielding = CommManager()
    try {
        val old = ConnectionOperation(yielding.appConnectionTicket(), null)
        checkNotNull(yielding.requestExplicitAppExit(old.ticket, false))
        yielding.onAppRelaunched()
        val successor = checkNotNull(ConnectionArbiter.claim(ConnectionPriorityPolicy.Tier.USER,
            ConnectionPriorityPolicy.Owner.MANUAL, "successor manual connection"))
        fun admission(operation: ConnectionOperation) = ConnectionAdmission { action ->
            yielding.withAppConnectionLifecycleLock {
                if (!yielding.isCurrent(operation)) false else { action(); true }
            }
        }
        check(!ConnectionArbiter.yieldToUser("stale wireless continuation", admission(old)))
        check(ConnectionArbiter.holds(successor) && !successor.ended.isCompleted)
        val current = ConnectionOperation(yielding.appConnectionTicket(), null)
        check(ConnectionArbiter.yieldToUser("fresh wireless input", admission(current)))
        check(!ConnectionArbiter.holds(successor) && successor.ended.isCompleted)
    } finally { yielding.close() }

    println("PASS effect entry authority: revoked Stop epochs, same-epoch Save, exact session/claim cleanup and committed physical Stop ownership")
}
