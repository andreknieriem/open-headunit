package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zjsocket

import android.net.LocalSocket
import android.net.LocalSocketAddress
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct.HctLocalSocket
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Connects a [LocalSocket] with a deadline, because `LocalSocket.connect()` has none. */
internal object ZjSocketConnector {

    /** [socket] is set only when [outcome] is CONNECTS; [detail] carries the failure text. */
    class Attempt(val outcome: ZjSocketPolicy.Outcome, val socket: LocalSocket?, val detail: String?)

    fun connect(path: String, deadlineMs: Long = ZjSocketPolicy.CONNECT_DEADLINE_MS): Attempt {
        val socket = LocalSocket()
        val failure = AtomicReference<Exception?>(null)
        val done = CountDownLatch(1)
        val worker = Thread({
            try {
                socket.connect(LocalSocketAddress(path, LocalSocketAddress.Namespace.FILESYSTEM))
            } catch (e: Exception) {
                failure.set(e)
            } finally {
                done.countDown()
            }
        }, "ZjSocket-connect")
        worker.isDaemon = true
        worker.start()
        val finished = try {
            done.await(deadlineMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!finished) {
            // Shutdown first: it is what wakes a connect or read blocked in the other thread.
            HctLocalSocket.close(socket)
            return Attempt(ZjSocketPolicy.Outcome.TIMED_OUT, null, "no answer in ${deadlineMs}ms")
        }
        val error = failure.get()
        if (error != null) {
            HctLocalSocket.close(socket)
            val outcome = if (error is IOException) ZjSocketPolicy.failureOutcome(error.message)
            else ZjSocketPolicy.Outcome.REFUSED
            return Attempt(outcome, null, "${error.javaClass.simpleName}: ${error.message}")
        }
        return Attempt(ZjSocketPolicy.Outcome.CONNECTS, socket, null)
    }
}
