package lifecycle

import android.os.Handler
import com.andrerinas.openheadunit.connection.AppExitPolicy
import kotlinx.coroutines.*
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

/** Actual production transfer/receiver methods; Android effects and dispatcher queues are doubles. */
private class ExitMain : CoroutineDispatcher(), AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "exit-main-fixture") }
    @Volatile var dispatched: (() -> Unit)? = null
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        executor.execute(block)
        dispatched?.invoke()
    }
    fun effect(action: () -> Unit) = executor.submit(action)
    override fun close() {
        executor.shutdownNow()
        check(executor.awaitTermination(5, TimeUnit.SECONDS))
    }
}

private fun CommManager.exitDispatcher(dispatcher: CoroutineDispatcher) {
    CommManager::class.java.getDeclaredField("appExitDispatcher")
        .apply { isAccessible = true }.set(this, dispatcher)
}

private fun waitFor(latch: CountDownLatch) = check(latch.await(5, TimeUnit.SECONDS))
private fun QueuedDispatcher.step() = checkNotNull(tasks.poll()).run()

/** Pause exactly after the old retirement join, before production snapshots the terminal. */
private class SnapshotRetirement(
    private val completed: Job,
    private var afterJoin: (() -> Unit)?,
) : Job by completed {
    override suspend fun join() {
        completed.join()
        val callback = afterJoin
        afterJoin = null
        callback?.invoke()
    }
}

fun appExitSerializedEffects() = runBlocking {
    // The real task-list lookup sits after ownsAppExit and before finishAndRemoveTask.
    ExitMain().use { main ->
        val c = CommManager()
        c.exitDispatcher(main)
        c.settings.killOnDisconnect = true
        c.disconnect(honorKillOnDisconnect = false)
        c.cleanups()
        val request = checkNotNull(c.requestExplicitAppExit(killProcess = true))
        check(c.commitAppExit(request) === request)
        val insideEffect = CountDownLatch(1)
        val resumeEffect = CountDownLatch(1)
        val queuedClaim = CountDownLatch(1)
        val tasks = c.context.activityManager.appTasks
        c.context.activityManager.beforeTasks = { insideEffect.countDown(); waitFor(resumeEffect) }
        val effect = main.effect { c.finishAppExitTasks(request) }
        try {
            waitFor(insideEffect)
            main.dispatched = { queuedClaim.countDown() }
            val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
                c.connectClaimedNext(fixtureClaim())
            }
            waitFor(queuedClaim)
            check(c.ownsAppExit(request.id)) // Queued is not yet accepted.
            resumeEffect.countDown()
            effect.get(5, TimeUnit.SECONDS)
            c.context.activityManager.beforeTasks = null
            withContext(main) { Unit }
            check(!c.ownsAppExit(request.id))
            check(tasks.single().finishes == 1)
            c.destroy()
            check(c.completeAppExit(request).effect == AppExitPolicy.Effect.RESTART_SERVICE)
            replacement.join()
            check(c.state is ConnectionState.Connected && c.network()?.closed == false)
            withContext(main) { c.finishAppExitTasks(request) }
            check(tasks.single().finishes == 1)
        } finally {
            resumeEffect.countDown()
            c.context.activityManager.beforeTasks = null
            c.close()
            Handler.delayed.clear()
        }
    }

    // Both activity receivers must complete their Main-side effects before a prepared
    // restricted candidate can supersede a completed STOPPED exit.
    for (projection in listOf(false, true)) ExitMain().use { main ->
        val c = CommManager()
        c.exitDispatcher(main)
        c.disconnect(honorKillOnDisconnect = false)
        c.cleanups()
        val request = checkNotNull(c.requestExplicitAppExit(killProcess = false))
        check(c.commitAppExit(request) === request)
        c.broadcastAppExit(request)
        c.destroy()
        check(c.completeAppExit(request).effect == AppExitPolicy.Effect.STOPPED)
        val intent = c.context.broadcastIntents.single()
        val mainReceiver = MainActivity(c, c.context)
        val projectionReceiver = AapProjectionActivity(c, c.context)
        val insideEffect = CountDownLatch(1)
        val resumeEffect = CountDownLatch(1)
        val queuedPublication = CountDownLatch(1)
        AppLog.hook = { text ->
            if (text.contains("Received finish request")) {
                insideEffect.countDown()
                waitFor(resumeEffect)
            }
        }
        val effect = main.effect {
            if (projection) projectionReceiver.deliver(intent) else mainReceiver.deliver(intent)
        }
        val socket = Socket()
        try {
            waitFor(insideEffect)
            main.dispatched = { queuedPublication.countDown() }
            val replacement = launch(start = CoroutineStart.UNDISPATCHED) { c.connectPrepared(socket) }
            waitFor(queuedPublication)
            check(c.ownsAppExit(request.id))
            resumeEffect.countDown()
            effect.get(5, TimeUnit.SECONDS)
            AppLog.hook = null
            replacement.join()
            check(!c.ownsAppExit(request.id))
            check(c.state is ConnectionState.Connected && c.network()?.closed == false && !socket.isClosed)
            withContext(main) {
                if (projection) projectionReceiver.deliver(intent) else mainReceiver.deliver(intent)
            }
            check(if (projection) projectionReceiver.finishes == 1 else mainReceiver.finishes == 1)
        } finally {
            resumeEffect.countDown()
            AppLog.hook = null
            c.close()
            socket.close()
            Handler.delayed.clear()
        }
    }

    // Cancel before Connecting's Main turn: no transfer, no teardown of the current owner.
    for (raw in listOf(false, true)) {
        val c = CommManager()
        val old = checkNotNull(c.network())
        val main = QueuedDispatcher()
        val caller = QueuedDispatcher()
        c.exitDispatcher(main)
        val socket = Socket()
        val scope = CoroutineScope(SupervisorJob() + caller)
        try {
            val attempt = scope.launch {
                if (raw) c.connectAccepted(socket) else c.connectNext()
            }
            caller.step()
            attempt.cancel()
            main.step()
            caller.drain()
            check(attempt.isCancelled && c.state is ConnectionState.Connected)
            check(c.network() === old && !old.closed && c.queue.tasks.isEmpty())
            if (raw) check(socket.isClosed)
        } finally {
            scope.cancel()
            socket.close()
            c.close()
        }
    }

    // Main published Connecting, but cancellation wins the return to the caller.
    // Close the displaced owner; never disconnect an already-published newer attempt.
    for (newer in listOf(false, true)) {
        val c = CommManager()
        val old = checkNotNull(c.network())
        val main = QueuedDispatcher()
        val caller = QueuedDispatcher()
        c.exitDispatcher(main)
        val scope = CoroutineScope(SupervisorJob() + caller)
        val first = scope.launch { c.connectNext() }
        caller.step()
        main.step()
        check(c.state is ConnectionState.Connecting)
        if (newer) {
            c.disconnect(honorKillOnDisconnect = false)
            c.cleanups()
            val nextCaller = QueuedDispatcher()
            val nextScope = CoroutineScope(SupervisorJob() + nextCaller)
            val next = nextScope.launch { c.connectNext() }
            nextCaller.step()
            main.step()
            nextCaller.drain()
            check(next.isCompleted && c.state is ConnectionState.Connected)
            nextScope.cancel()
        }
        val replacement = c.network()
        first.cancel()
        caller.drain()
        check(first.isCancelled && old.closed)
        if (newer) {
            check(c.state is ConnectionState.Connected && c.network() === replacement && replacement?.closed == false)
        } else {
            check(c.state is ConnectionState.Disconnected)
            c.cleanups()
            check(c.network() == null)
        }
        c.close()
        scope.cancel()
    }

    // Established cancellation-capture repro: A and B pass their initial teardown joins,
    // A publishes, then its cancelled handback queues cleanup before B's Main turn.
    // B must recheck that latest retirement, even though its publication was queued first.
    // An old transport control also proves the fix does not skip native/decoder cleanup.
    for (withTransport in listOf(false, true)) {
        val c = CommManager()
        val old = checkNotNull(c.network())
        if (withTransport) { c.startHandshake(); c.startReading() }
        val oldTransport = c.nullableOwner()
        val main = QueuedDispatcher()
        val callerA = QueuedDispatcher()
        val callerB = QueuedDispatcher()
        c.exitDispatcher(main)
        val scopeA = CoroutineScope(SupervisorJob() + callerA)
        val scopeB = CoroutineScope(SupervisorJob() + callerB)
        try {
            val attemptA = scopeA.launch { c.connectNext() }
            val attemptB = scopeB.launch { c.connectNext() }
            callerA.step()
            callerB.step()
            main.step()
            check(c.state is ConnectionState.Connecting)
            attemptA.cancel()
            callerA.drain()
            check(attemptA.isCancelled && old.closed && c.state is ConnectionState.Disconnected)
            main.step()
            callerB.drain()
            check(!attemptB.isCompleted && c.network() == null)
            check(c.state is ConnectionState.Disconnected)
            c.cleanups()
            check(c.nullableOwner() == null && old.closed)
            if (withTransport) {
                check(checkNotNull(oldTransport).liveWorkers() == 0 && oldTransport.aapAudio.closed)
                check(c.audioDecoder.cleanups == 1 && c.videoDecoder.stops == 1)
            }
            callerB.drain()
            main.step()
            callerB.drain()
            val replacement = checkNotNull(c.network())
            check(attemptB.isCompleted && c.state is ConnectionState.Connected && !replacement.closed)
            c.cleanups()
            check(c.network() === replacement && c.state is ConnectionState.Connected && !replacement.closed)
        } finally {
            scopeA.cancel()
            scopeB.cancel()
            c.close()
        }
    }

    // Restricted B reads an old, completed retirement. A's cancellation queues a NEW J
    // before B snapshots the terminal, so terminal identity alone cannot expose the race.
    // B must reject/dispose locally rather than transfer into J's later resource capture.
    run {
        val c = CommManager()
        val old = checkNotNull(c.network())
        val main = QueuedDispatcher()
        val callerA = QueuedDispatcher()
        val callerB = QueuedDispatcher()
        c.exitDispatcher(main)
        val scopeA = CoroutineScope(SupervisorJob() + callerA)
        val scopeB = CoroutineScope(SupervisorJob() + callerB)
        val socket = Socket()
        try {
            val attemptA = scopeA.launch { c.connectNext() }
            callerA.step()
            main.step()
            check(c.state is ConnectionState.Connecting)
            val oldRetirement = SnapshotRetirement(Job().apply { complete() }) {
                attemptA.cancel()
                callerA.drain()
                check(attemptA.isCancelled && old.closed && c.state is ConnectionState.Disconnected)
            }
            CommManager::class.java.getDeclaredField("_disconnectJob")
                .apply { isAccessible = true }.set(c, oldRetirement)
            var rejected = false
            val attemptB = scopeB.launch {
                try {
                    c.connectPrepared(socket)
                } catch (_: ConnectionAdmissionRejectedException) {
                    rejected = true
                }
            }
            callerB.step() // Old join, then cancellation hook, then the actual terminal snapshot.
            val terminal = c.state
            val candidate = SocketProjectionConnection.created.last()
            check(terminal is ConnectionState.Disconnected && !socket.isClosed && !candidate.closed)
            main.step()
            callerB.drain()
            val publishedBeforeCleanup = c.network() === candidate && c.state is ConnectionState.Connected
            val parentUntouched = c.network() == null && c.nullableOwner() == null && c.state === terminal
            c.cleanups() // Exercise real J capture even on an unfixed source run.
            println("restricted retirement snapshot: publishedBeforeCleanup=$publishedBeforeCleanup, rejected=$rejected, candidateClosedAfterCleanup=${candidate.closed}")
            check(attemptB.isCompleted && rejected && !publishedBeforeCleanup && parentUntouched)
            check(candidate.closed && socket.isClosed && old.closed)
            check(c.network() == null && c.nullableOwner() == null && c.state === terminal)
        } finally {
            scopeA.cancel()
            scopeB.cancel()
            socket.close()
            c.close()
        }
    }

    // A raw socket is local until a wrapper exists, including a cancelled Connecting handback.
    run {
        val c = CommManager()
        val old = checkNotNull(c.network())
        val main = QueuedDispatcher()
        val caller = QueuedDispatcher()
        c.exitDispatcher(main)
        val socket = Socket()
        val scope = CoroutineScope(SupervisorJob() + caller)
        val attempt = scope.launch { c.connectAccepted(socket) }
        caller.step()
        main.step()
        attempt.cancel()
        caller.drain()
        check(socket.isClosed && old.closed && c.state is ConnectionState.Disconnected)
        c.close()
        scope.cancel()
    }

    // Restricted publication transfers the resource itself. A cancelled return must not
    // close the now-published candidate or raw socket; pre-publication cancellation must.
    for (cancelBeforePublication in listOf(false, true)) {
        val c = CommManager()
        c.disconnect(honorKillOnDisconnect = false)
        c.cleanups()
        val main = QueuedDispatcher()
        val caller = QueuedDispatcher()
        c.exitDispatcher(main)
        val socket = Socket()
        val scope = CoroutineScope(SupervisorJob() + caller)
        val attempt = scope.launch { c.connectPrepared(socket) }
        caller.step()
        if (cancelBeforePublication) attempt.cancel()
        main.step()
        if (!cancelBeforePublication) attempt.cancel()
        caller.drain()
        if (cancelBeforePublication) {
            check(socket.isClosed && c.state is ConnectionState.Disconnected && c.network() == null)
        } else {
            check(!socket.isClosed && c.state is ConnectionState.Connected && c.network()?.closed == false)
        }
        c.close()
        scope.cancel()
    }

    // The real service tail can wait for the XYAuto worker after its final kill decision.
    // No queued replacement may revoke ownership halfway through that Main-side effect.
    ExitMain().use { main ->
        val c = CommManager()
        c.exitDispatcher(main)
        c.settings.killOnDisconnect = true
        c.disconnect(honorKillOnDisconnect = false)
        c.cleanups()
        val request = checkNotNull(c.requestExplicitAppExit(killProcess = true))
        check(c.commitAppExit(request) === request)
        c.destroy()
        val tail = ExitServiceTail(c, request)
        val beforeExit = CountDownLatch(1)
        val resumeExit = CountDownLatch(1)
        val queuedClaim = CountDownLatch(1)
        tail.beforeProcessExit = { beforeExit.countDown(); waitFor(resumeExit) }
        val effect = main.effect { tail.finish() }
        try {
            waitFor(beforeExit)
            main.dispatched = { queuedClaim.countDown() }
            val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
                c.connectClaimedNext(fixtureClaim())
            }
            waitFor(queuedClaim)
            check(c.ownsAppExit(request.id) && tail.processExits == 0)
            resumeExit.countDown()
            effect.get(5, TimeUnit.SECONDS)
            check(tail.processExits == 1 && tail.ownedAtProcessExit)
            // System.exit ends the real process. Cancel this double's impossible continuation.
            replacement.cancelAndJoin()
        } finally {
            resumeExit.countDown()
            c.close()
            Handler.delayed.clear()
        }
    }
    // Restricted preparation grants no OPEN. A later Stop cancels its final Main transfer,
    // and an intervening UI Open cannot revive the same prepared candidate.
    for (openAfterStop in listOf(false, true)) {
        val c = CommManager()
        c.disconnect(honorKillOnDisconnect = false)
        c.cleanups()
        val main = QueuedDispatcher()
        val caller = QueuedDispatcher()
        c.exitDispatcher(main)
        val socket = Socket()
        val scope = CoroutineScope(SupervisorJob() + caller)
        var rejected = false
        try {
            val u = scope.launch {
                try { c.connectPrepared(socket) }
                catch (_: ConnectionAdmissionRejectedException) { rejected = true }
            }
            caller.step()
            val candidate = SocketProjectionConnection.created.last()
            val b = c.requestExplicitAppExit(killProcess = false)
            if (openAfterStop) c.onAppRelaunched()
            main.step()
            caller.drain()
            check(u.isCompleted && rejected && candidate.closed && socket.isClosed)
            check(c.network() == null && c.state is ConnectionState.Disconnected)
            check(c.ownsAppExit(b.id) == !openAfterStop)
        } finally {
            scope.cancel()
            socket.close()
            c.close()
        }
    }
    println("PASS app-exit effect serialization: task enumeration, both post-STOPPED receivers, owned process-exit tail, cancellation before/after Connecting, latest retirement before queued publication and restricted terminal snapshot, old transport cleanup, newer owner preservation, raw socket ownership, and restricted transfer handback")
}
