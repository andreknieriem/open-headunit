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
                check(bootstrap.stops == 1 && bootstrap.isDestroying && !bootstrap.initializedForStart && !bootstrap.foreground)
                // Android has queued destruction but can still deliver an already queued C start.
                val valid = Intent("entry").putExtra(ServiceBootstrap.EXTRA_RUN_OWNER, current)
                bootstrap.onStartCommand(valid, 0, 2)
                check(bootstrap.foreground && bootstrap.isDestroying && !bootstrap.initializedForStart) {
                    "a doomed bootstrap must promote without initializing the latest Run"
                }
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

        val running = CommManager()
        try {
            val run = running.onAppRelaunched()
            val bootstrap = ServiceBootstrap(running).apply { failForeground = true }
            val start = Intent("entry").putExtra(ServiceBootstrap.EXTRA_RUN_OWNER, run)
            bootstrap.onStartCommand(start, 0, 1)
            check(bootstrap.foregroundPromotionFailed && bootstrap.stops == 1 && !bootstrap.initializedForStart)
            bootstrap.finish()
            check(running.isAppRunOwner(run) && ContextCompat.serviceStarts.isEmpty()) {
                "failed foreground promotion must not loop through another service start"
            }
        } finally {
            running.close()
        }

        // A tagged relay preserves its originating Stop ticket across service delivery.
        for (stop in listOf(false, true)) {
            val manager = CommManager()
            try {
                manager.onAppRelaunched()
                val ticket = manager.appConnectionTicket()
                val relay = Intent("check-usb").putExtra(ServiceBootstrap.EXTRA_OPERATION_TICKET, ticket)
                if (stop) manager.requestExplicitAppExit(killProcess = false)
                val reopened = manager.onAppRelaunched()
                val bootstrap = ServiceBootstrap(manager)
                bootstrap.onStartCommand(relay, 0, 1)
                bootstrap.onStartCommand(relay, 0, 2)
                check(bootstrap.initializedForStart == !stop && manager.isAppRunOwner(reopened))
                check(bootstrap.usbStartupTickets == if (stop) emptyList() else listOf(ticket)) {
                    "a stale relay must not arm USB; a valid relay initializes once with its carried ticket"
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
    println("PASS actual service bootstrap: stale Run/Stop and relays cannot initialize; latest Run restores and valid startup arms USB once")
}
