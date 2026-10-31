package lifecycle

import android.content.Intent
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.AppExitPolicy

/** Real service validation prefix and completion tail; platform lifetime/start effects are recorded. */
fun bootstrapRejectionPreservesLatestRun() {
    val previousService = AapService.instance
    val previousStarts = ContextCompat.serviceStarts.toList()
    try {
        AapService.instance = null
        for (staleStop in listOf(false, true)) {
            val manager = CommManager()
            manager.disconnect(honorKillOnDisconnect = false)
            manager.cleanups()
            ContextCompat.serviceStarts.clear()
            try {
                val stale = if (staleStop) {
                    val old = manager.requestExplicitAppExit(killProcess = false)
                    Intent(ServiceBootstrap.ACTION_STOP_SERVICE)
                        .putExtra(ServiceBootstrap.EXTRA_STOP_OWNER, old.id)
                } else {
                    val old = manager.onAppRelaunched()
                    Intent("entry").putExtra(ServiceBootstrap.EXTRA_RUN_OWNER, old)
                }
                val current = manager.onAppRelaunched()
                val bootstrap = ServiceBootstrap(manager)
                bootstrap.onStartCommand(stale, 0, 1)
                check(bootstrap.stops == 1 && bootstrap.isDestroying && !bootstrap.initializedForStart)
                // Android has queued destruction but can still deliver an already queued C start.
                val valid = Intent("entry").putExtra(ServiceBootstrap.EXTRA_RUN_OWNER, current)
                bootstrap.onStartCommand(valid, 0, 2)
                check(!bootstrap.initializedForStart) { "a doomed bootstrap initialized the latest Run" }
                bootstrap.finish()
                check(manager.isAppRunOwner(current))
                check(ContextCompat.serviceStarts.single()
                    .getLongExtra(ServiceBootstrap.EXTRA_RUN_OWNER, -1L) == current)
            } finally {
                manager.close()
            }
        }
        val stopped = CommManager()
        stopped.disconnect(honorKillOnDisconnect = false)
        stopped.cleanups()
        ContextCompat.serviceStarts.clear()
        try {
            val exit = stopped.requestExplicitAppExit(killProcess = false)
            check(stopped.commitAppExit(exit) === exit)
            check(stopped.completeAppExit(exit).effect == AppExitPolicy.Effect.STOPPED)
            val duplicate = Intent(ServiceBootstrap.ACTION_STOP_SERVICE)
                .putExtra(ServiceBootstrap.EXTRA_STOP_OWNER, exit.id)
            val bootstrap = ServiceBootstrap(stopped)
            bootstrap.onStartCommand(duplicate, 0, 1)
            check(bootstrap.isDestroying && !bootstrap.initializedForStart)
            bootstrap.finish()
            check(ContextCompat.serviceStarts.isEmpty()) { "a completed Stop must not restore a service" }
        } finally {
            stopped.close()
        }
        // Internal relays carry the Stop ticket of their input; only external entries mint one at
        // receipt. A relay sent before Stop and received after it, whether it carries that ticket
        // or was sent untagged, initializes nothing. Control: the carried ticket with no Stop.
        for (relay in listOf("untagged", "carried", "control")) {
            val manager = CommManager()
            manager.disconnect(honorKillOnDisconnect = false)
            manager.cleanups()
            ContextCompat.serviceStarts.clear()
            try {
                val input = manager.appConnectionTicket()
                val intent = Intent("check-usb")
                if (relay != "untagged") intent.putExtra(ServiceBootstrap.EXTRA_OPERATION_TICKET, input)
                if (relay != "control") manager.requestExplicitAppExit(killProcess = false)
                val bootstrap = ServiceBootstrap(manager)
                bootstrap.onStartCommand(intent, 0, 1)
                check(bootstrap.initializedForStart == (relay == "control")) {
                    "a service relay sent before Stop initialized after it (relay=$relay)"
                }
            } finally {
                manager.close()
            }
        }
    } finally {
        AapService.instance = previousService
        ContextCompat.serviceStarts.clear()
        ContextCompat.serviceStarts.addAll(previousStarts)
    }
    println("PASS actual service bootstrap: stale Run/Stop cannot initialize a doomed instance; latest Run restores and completed Stop stays stopped")
}
