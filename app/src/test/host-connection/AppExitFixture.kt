package lifecycle

import android.app.ActivityManager
import android.hardware.usb.UsbDevice
import android.os.Handler
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.AppExitPolicy
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Production CommManager entrypoints and exit policy; Android queues/effects are controlled doubles. */
fun appExitOwnershipRaces() {
    fun fixture(): Pair<CommManager, AapService> {
        check(Handler.delayed.isEmpty())
        val service = AapService()
        AapService.instance = service
        return CommManager().apply { settings.killOnDisconnect = true } to service
    }
    fun noExit(c: CommManager, service: AapService) {
        check(c.context.broadcasts.isEmpty())
        check(c.context.activityManager.appTasks.single().finishes == 0)
        check(service.stops == 0 && service.exitRequest == null)
    }
    fun close(c: CommManager) {
        c.close()
        Handler.delayed.clear()
        AapService.instance = null
    }

    val (idle, idleService) = fixture()
    try {
        idle.disconnect(isUserExit = false)
        val old = checkNotNull(Handler.delayed.peek())
        idle.onAppRelaunched()
        check(Handler.delayed.isEmpty())
        repeat(3) { old.run() }
        check(idle.state is ConnectionState.Disconnected)
        noExit(idle, idleService)
    } finally { close(idle) }

    for (held in listOf(false, true)) {
        val (waiting, service) = fixture()
        try {
            waiting.disconnect(isUserExit = false)
            val old = checkNotNull(Handler.delayed.peek())
            runBlocking {
                val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
                    waiting.connectClaimedNext(ConnectionArbiter.Claim(held))
                }
                if (held) {
                    // Teardown has not run, so Connecting has not been published yet.
                    check(!replacement.isCompleted && waiting.state is ConnectionState.Disconnected)
                    check(Handler.delayed.isEmpty())
                    repeat(3) { old.run() }
                    noExit(waiting, service)
                    waiting.cleanups()
                    replacement.join()
                    check(waiting.state is ConnectionState.Connected)
                } else {
                    check(replacement.isCompleted && Handler.delayed.size == 1)
                    Handler.delayed.poll().run()
                    check(service.stops == 1)
                    waiting.destroy()
                    check(waiting.completeAppExit(checkNotNull(service.exitRequest)) == AppExitPolicy.Completion.KILL_PROCESS)
                }
            }
        } finally { close(waiting) }
    }

    val (noPermission, noPermissionService) = fixture()
    try {
        noPermission.disconnect(isUserExit = false)
        noPermission.context.usb.permitted = false
        runBlocking { noPermission.connectClaimedUsb(UsbDevice(), ConnectionArbiter.Claim()) }
        check(Handler.delayed.size == 1)
        Handler.delayed.poll().run()
        check(noPermissionService.stops == 1)
        noPermission.destroy()
        check(noPermission.completeAppExit(checkNotNull(noPermissionService.exitRequest)) == AppExitPolicy.Completion.KILL_PROCESS)
    } finally { close(noPermission) }

    val (connecting, connectingService) = fixture()
    val opened = CountDownLatch(1)
    val release = CountDownLatch(1)
    val errors = ConcurrentLinkedQueue<Throwable>()
    val opener = Thread {
        try { runBlocking { connecting.connectNext() } } catch (t: Throwable) { errors.add(t) }
    }
    try {
        connecting.disconnect(isUserExit = false)
        val old = checkNotNull(Handler.delayed.peek())
        connecting.cleanups()
        SocketProjectionConnection.connectionHook = {
            opened.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            true
        }
        opener.start()
        check(opened.await(5, TimeUnit.SECONDS))
        check(connecting.state is ConnectionState.Connecting)
        check(Handler.delayed.isEmpty())
        repeat(3) { old.run() }
        noExit(connecting, connectingService)
        release.countDown()
        opener.join(5000)
        check(!opener.isAlive && errors.isEmpty()) { errors.toString() }
        check(connecting.state is ConnectionState.Connected && connecting.network()?.closed == false)
    } finally {
        release.countDown()
        opener.join(5000)
        SocketProjectionConnection.connectionHook = null
        close(connecting)
    }

    val (rejected, rejectedService) = fixture()
    try {
        rejected.disconnect(isUserExit = false)
        rejected.cleanups()
        val device = UsbDevice()
        rejected.context.usb.deviceList[device.deviceName] = device
        var admissions = 0
        runBlocking {
            rejected.connectRecovery(device, ConnectionAdmission { action ->
                admissions++
                if (admissions == 1) { action(); true } else false
            })
        }
        check(admissions == 2 && rejected.state is ConnectionState.Disconnected)
        check(Handler.delayed.size == 1)
        Handler.delayed.poll().run()
        check(rejectedService.stops == 1)
        check(rejected.context.activityManager.appTasks.single().finishes == 1)
        rejected.destroy()
        check(rejected.completeAppExit(checkNotNull(rejectedService.exitRequest)) == AppExitPolicy.Completion.KILL_PROCESS)
    } finally { close(rejected) }

    for (userStop in listOf(false, true)) {
        val (eligible, service) = fixture()
        try {
            eligible.disconnect(isUserExit = userStop)
            val old = checkNotNull(Handler.delayed.poll())
            repeat(3) { old.run() }
            check(eligible.context.broadcasts.size == 1 && service.stops == 1)
            check(eligible.context.activityManager.appTasks.single().finishes == 1)
            val request = checkNotNull(service.exitRequest)
            check(request.userStop == userStop)
            eligible.destroy()
            check(eligible.completeAppExit(request) == AppExitPolicy.Completion.KILL_PROCESS)
            check(eligible.completeAppExit(request) == AppExitPolicy.Completion.NONE)
        } finally { close(eligible) }
    }

    for (disableBeforeDisconnect in listOf(false, true)) {
        val (optedOut, service) = fixture()
        try {
            if (disableBeforeDisconnect) optedOut.settings.killOnDisconnect = false
            optedOut.disconnect(isUserExit = false)
            optedOut.settings.killOnDisconnect = false
            while (Handler.delayed.isNotEmpty()) Handler.delayed.poll().run()
            noExit(optedOut, service)
        } finally { close(optedOut) }
    }

    val (reopened, reopenedService) = fixture()
    try {
        reopened.disconnect(isUserExit = false)
        reopened.cleanups()
        val old = checkNotNull(Handler.delayed.poll())
        old.run()
        val request = checkNotNull(reopenedService.exitRequest)
        val finishIntent = reopened.context.broadcastIntents.single()
        reopened.onAppRelaunched()
        reopened.onAppRelaunched()
        repeat(3) {
            check(reopened.requestExplicitAppExit(killProcess = false) == null)
            check(!reopened.commitAppExit(request))
        }
        // A new home task exists while Android still owes the old service onDestroy.
        reopened.context.activityManager.appTasks[0] = ActivityManager.AppTask()
        check(!reopened.ownsAppExit(finishIntent.getLongExtra(AppExitPolicy.EXTRA_EXIT_OWNER, 0L)))
        runBlocking {
            val replacement = launch(start = CoroutineStart.UNDISPATCHED) { reopened.connectNext() }
            check(!replacement.isCompleted && reopened.state is ConnectionState.Disconnected)
            repeat(3) { old.run() }
            check(reopenedService.stops == 1)
            check(reopened.context.activityManager.appTasks.single().finishes == 0)
            reopened.destroy()
            var restarts = 0
            repeat(3) {
                if (reopened.completeAppExit(request) == AppExitPolicy.Completion.RESTART_SERVICE) restarts++
            }
            check(restarts == 1)
            replacement.join()
            check(reopened.state is ConnectionState.Connected && reopened.network()?.closed == false)
            repeat(3) { old.run() }
            check(reopenedService.stops == 1 && reopened.context.activityManager.appTasks.single().finishes == 0)
        }
    } finally { close(reopened) }

    val (claimedAfterStop, claimedService) = fixture()
    try {
        claimedAfterStop.disconnect(isUserExit = false)
        claimedAfterStop.cleanups()
        Handler.delayed.poll().run()
        val request = checkNotNull(claimedService.exitRequest)
        runBlocking {
            val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
                claimedAfterStop.connectClaimedNext(ConnectionArbiter.Claim())
            }
            check(!replacement.isCompleted)
            check(!claimedAfterStop.ownsAppExit(request.id))
            claimedAfterStop.destroy()
            check(claimedAfterStop.completeAppExit(request) == AppExitPolicy.Completion.RESTART_SERVICE)
            replacement.join()
            check(claimedAfterStop.state is ConnectionState.Connected)
            check(claimedAfterStop.network()?.closed == false && claimedService.stops == 1)
        }
    } finally { close(claimedAfterStop) }

    val (idleStop, idleStopService) = fixture()
    try {
        idleStop.settings.killOnDisconnect = false
        val request = checkNotNull(idleStop.requestExplicitAppExit(killProcess = false))
        check(idleStop.commitAppExit(request))
        idleStop.broadcastAppExit(request)
        idleStopService.stopForAppExit(request)
        idleStop.destroy()
        check(idleStop.completeAppExit(request) == AppExitPolicy.Completion.STOPPED)
        check(!idleStop.commitAppExit(request))
        val queuedFinish = idleStop.context.broadcastIntents.single()
        val oldMain = MainActivity(idleStop, idleStop.context)
        val oldProjection = AapProjectionActivity(idleStop, idleStop.context)
        oldMain.deliver(queuedFinish)
        oldProjection.deliver(queuedFinish)
        check(oldMain.finishes == 1 && oldProjection.finishes == 1)
        idleStop.onAppRelaunched()
        val newMain = MainActivity(idleStop, idleStop.context)
        val newProjection = AapProjectionActivity(idleStop, idleStop.context)
        newMain.deliver(queuedFinish)
        newProjection.deliver(queuedFinish)
        check(newMain.finishes == 0 && newProjection.finishes == 0)
    } finally { close(idleStop) }

    println("PASS owned app exit: idle reopen, Connecting replacement, rejected candidate, repeated callbacks, eligible exit, opt-out, and queued destruction/restart")
}
