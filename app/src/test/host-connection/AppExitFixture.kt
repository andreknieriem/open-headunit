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
        idle.disconnect(ConnectionOperation(idle.appConnectionTicket(),null), isUserExit = false)
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
            waiting.disconnect(ConnectionOperation(waiting.appConnectionTicket(),null), isUserExit = false)
            val old = checkNotNull(Handler.delayed.peek())
            runBlocking {
                val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
                    waiting.connectClaimedNext(fixtureClaim(held))
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
                    check(waiting.completeAppExit(checkNotNull(service.exitRequest)).effect == AppExitPolicy.Effect.KILL_PROCESS)
                }
            }
        } finally { close(waiting) }
    }

    val (noPermission, noPermissionService) = fixture()
    try {
        noPermission.disconnect(ConnectionOperation(noPermission.appConnectionTicket(),null), isUserExit = false)
        noPermission.context.usb.permitted = false
        runBlocking { noPermission.connectClaimedUsb(UsbDevice(), fixtureClaim()) }
        check(Handler.delayed.size == 1)
        Handler.delayed.poll().run()
        check(noPermissionService.stops == 1)
        noPermission.destroy()
        check(noPermission.completeAppExit(checkNotNull(noPermissionService.exitRequest)).effect == AppExitPolicy.Effect.KILL_PROCESS)
    } finally { close(noPermission) }

    val (connecting, connectingService) = fixture()
    val opened = CountDownLatch(1)
    val release = CountDownLatch(1)
    val errors = ConcurrentLinkedQueue<Throwable>()
    val opener = Thread {
        try { runBlocking { connecting.connectNext() } } catch (t: Throwable) { errors.add(t) }
    }
    try {
        connecting.disconnect(ConnectionOperation(connecting.appConnectionTicket(),null), isUserExit = false)
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
        rejected.disconnect(ConnectionOperation(rejected.appConnectionTicket(),null), isUserExit = false)
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
        check(rejected.completeAppExit(checkNotNull(rejectedService.exitRequest)).effect == AppExitPolicy.Effect.KILL_PROCESS)
    } finally { close(rejected) }

    for (userStop in listOf(false, true)) {
        val (eligible, service) = fixture()
        try {
            eligible.disconnect(ConnectionOperation(eligible.appConnectionTicket(),null), isUserExit = userStop)
            val old = checkNotNull(Handler.delayed.poll())
            repeat(3) { old.run() }
            check(eligible.context.broadcasts.size == 1 && service.stops == 1)
            check(eligible.context.activityManager.appTasks.single().finishes == 1)
            val request = checkNotNull(service.exitRequest)
            check(request.userStop == userStop)
            eligible.destroy()
            check(eligible.completeAppExit(request).effect == AppExitPolicy.Effect.KILL_PROCESS)
            check(eligible.completeAppExit(request).effect == AppExitPolicy.Effect.NONE)
        } finally { close(eligible) }
    }

    for (disableBeforeDisconnect in listOf(false, true)) {
        val (optedOut, service) = fixture()
        try {
            if (disableBeforeDisconnect) optedOut.settings.killOnDisconnect = false
            optedOut.disconnect(ConnectionOperation(optedOut.appConnectionTicket(),null), isUserExit = false)
            optedOut.settings.killOnDisconnect = false
            while (Handler.delayed.isNotEmpty()) Handler.delayed.poll().run()
            noExit(optedOut, service)
        } finally { close(optedOut) }
    }

    val (reopened, reopenedService) = fixture()
    try {
        reopened.disconnect(ConnectionOperation(reopened.appConnectionTicket(),null), isUserExit = false)
        reopened.cleanups()
        val old = checkNotNull(Handler.delayed.poll())
        old.run()
        val request = checkNotNull(reopenedService.exitRequest)
        val finishIntent = reopened.context.broadcastIntents.single()
        reopened.onAppRelaunched()
        reopened.onAppRelaunched()
        repeat(3) {
            check(reopened.acceptedAppExit(request.id) == null)
            check(reopened.commitAppExit(request) == null)
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
                if (reopened.completeAppExit(request).effect == AppExitPolicy.Effect.RESTART_SERVICE) restarts++
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
        claimedAfterStop.disconnect(ConnectionOperation(claimedAfterStop.appConnectionTicket(),null), isUserExit = false)
        claimedAfterStop.cleanups()
        Handler.delayed.poll().run()
        val request = checkNotNull(claimedService.exitRequest)
        runBlocking {
            val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
                claimedAfterStop.connectClaimedNext(fixtureClaim())
            }
            check(!replacement.isCompleted)
            check(!claimedAfterStop.ownsAppExit(request.id))
            claimedAfterStop.destroy()
            check(claimedAfterStop.completeAppExit(request).effect == AppExitPolicy.Effect.RESTART_SERVICE)
            replacement.join()
            check(claimedAfterStop.state is ConnectionState.Connected)
            check(claimedAfterStop.network()?.closed == false && claimedService.stops == 1)
        }
    } finally { close(claimedAfterStop) }

    val (idleStop, idleStopService) = fixture()
    try {
        idleStop.settings.killOnDisconnect = false
        val request = checkNotNull(idleStop.requestExplicitAppExit(ticket = idleStop.appConnectionTicket(), killProcess = false))
        check(idleStop.commitAppExit(request) === request)
        idleStop.broadcastAppExit(request)
        idleStopService.stopForAppExit(request)
        idleStop.destroy()
        check(idleStop.completeAppExit(request).effect == AppExitPolicy.Effect.STOPPED)
        check(idleStop.commitAppExit(request) == null)
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

    // A's gate remains physical; fresh B cancels U even if a later C opens the UI.
    for (openAfterStop in listOf(false, true)) {
        val (c, service) = fixture()
        try {
            c.disconnect(ConnectionOperation(c.appConnectionTicket(), null), isUserExit = false)
            c.cleanups()
            Handler.delayed.poll().run()
            val a = checkNotNull(service.exitRequest)
            c.onAppRelaunched()
            runBlocking {
                val u = launch(start = CoroutineStart.UNDISPATCHED) {
                    c.connectClaimedNext(fixtureClaim())
                }
                check(!u.isCompleted)
                val b = checkNotNull(c.requestExplicitAppExit(ticket = c.appConnectionTicket(), killProcess = false))
                check(c.commitAppExit(b) === a)
                check(c.appExitIsUserStop(a))
                if (openAfterStop) c.onAppRelaunched()
                check(c.commitAppExit(a) == null)
                c.destroy()
                val completion = c.completeAppExit(a)
                check(completion.effect == if (openAfterStop) AppExitPolicy.Effect.RESTART_SERVICE else AppExitPolicy.Effect.STOPPED)
                if (!openAfterStop) check(completion.owner == b.id && completion.request === b)
                u.join()
                check(c.state is ConnectionState.Disconnected && c.network() == null)
                check(service.stops == 1)
                check(c.completeAppExit(a).effect == AppExitPolicy.Effect.NONE)
            }
        } finally { close(c) }
    }
    // Ordinary U already published Connecting and is inside its real socket open. Stop
    // acceptance cancels final Connected publication even if its relay loses to Open C.
    for (openAfterStop in listOf(false, true)) {
        val (c, service) = fixture()
        val opened = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val caller = Thread {
            try { runBlocking { c.connectNext() } } catch (t: Throwable) { errors.add(t) }
        }
        try {
            c.disconnect(ConnectionOperation(c.appConnectionTicket(), null), honorKillOnDisconnect = false)
            c.cleanups()
            SocketProjectionConnection.connectionHook = {
                opened.countDown()
                check(releaseOpen.await(5, TimeUnit.SECONDS))
                true
            }
            caller.start()
            check(opened.await(5, TimeUnit.SECONDS))
            val candidate = checkNotNull(c.network())
            check(c.state is ConnectionState.Connecting)
            val b = checkNotNull(c.requestExplicitAppExit(ticket = c.appConnectionTicket(), killProcess = false))
            if (openAfterStop) c.onAppRelaunched()
            releaseOpen.countDown()
            caller.join(5000)
            check(!caller.isAlive && errors.isEmpty()) { errors.toString() }
            c.cleanups()
            check(candidate.closed && c.network() == null && c.state is ConnectionState.Disconnected)
            check(service.stops == 0) // No service relay or duplicate teardown was needed.
            check(c.ownsAppExit(b.id) == !openAfterStop)
        } finally {
            releaseOpen.countDown()
            caller.join(5000)
            SocketProjectionConnection.connectionHook = null
            close(c)
        }
    }

    // Restoration carries the approved Run through A's gate; B cancels it permanently.
    runBlocking {
        val (c, _) = fixture()
        try {
            c.disconnect(ConnectionOperation(c.appConnectionTicket(), null), honorKillOnDisconnect = false)
            c.cleanups()
            val a = checkNotNull(c.requestExplicitAppExit(ticket = c.appConnectionTicket(), killProcess = false))
            check(c.commitAppExit(a) === a)
            AapService.instance = null
            val starts = androidx.core.content.ContextCompat.serviceStarts.size
            val r = c.onAppRelaunched()
            c.restoreAppForEntry(r)
            check(androidx.core.content.ContextCompat.serviceStarts.size == starts)
            val b = checkNotNull(c.requestExplicitAppExit(ticket = c.appConnectionTicket(), killProcess = false))
            c.destroy()
            check(c.completeAppExit(a).owner == b.id)
            check(androidx.core.content.ContextCompat.serviceStarts.size == starts)
            c.restoreAppForEntry(r) // A stale completion/entry effect cannot start anything.
            check(androidx.core.content.ContextCompat.serviceStarts.size == starts)
            val latest = c.onAppRelaunched()
            c.restoreAppForEntry(latest)
            check(androidx.core.content.ContextCompat.serviceStarts.size == starts + 1)
            check(androidx.core.content.ContextCompat.serviceStarts.last().getLongExtra("accepted_run_owner", -1) == latest)
        } finally { close(c) }
    }

    // Real settings operation replacement/cancellation, not a global app revision check.
    runBlocking {
        val (c, _) = fixture()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined)
        val operations = SettingsMutation(scope)
        val oldResult = kotlinx.coroutines.CompletableDeferred<Unit>()
        val newResult = kotlinx.coroutines.CompletableDeferred<Unit>()
        var stops = 0
        try {
            operations.start(prepare = { oldResult.await() }, apply = { checkNotNull(c.requestExplicitAppExit(ticket = c.appConnectionTicket(), false)); stops++ }, onFailure = { throw it })
            operations.start(prepare = { newResult.await() }, apply = { checkNotNull(c.requestExplicitAppExit(ticket = c.appConnectionTicket(), false)); stops++ }, onFailure = { throw it })
            oldResult.complete(Unit)
            check(stops == 0)
            c.onAppRelaunched() // A compatible UI return does not cancel a legitimate result.
            newResult.complete(Unit)
            check(stops == 1)
            val cancelledResult = kotlinx.coroutines.CompletableDeferred<Unit>()
            operations.start(prepare = { cancelledResult.await() }, apply = { checkNotNull(c.requestExplicitAppExit(ticket = c.appConnectionTicket(), false)); stops++ }, onFailure = { throw it })
            operations.cancel()
            cancelledResult.complete(Unit)
            check(stops == 1)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
            close(c)
        }
    }
    println("PASS owned app exit: idle reopen, Connecting replacement, rejected candidate, repeated callbacks, eligible exit, opt-out, and queued destruction/restart")
}
