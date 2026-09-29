package com.andrerinas.openheadunit.decoder.audio

import java.util.ArrayDeque
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import android.os.Process
import com.andrerinas.openheadunit.utils.AppLog

/** Keep rare audio events available even after noisy vendor logs overwrite the logcat ring.
 * Called by mixer/transport threads, never by the native output callback. No disk I/O on record. */
internal object AudioDiagnostics {
    private data class Event(val elapsedMs: Long, val message: String)
    private val events = ArrayDeque<Event>()
    private const val LIMIT = 64
    private data class LogEntry(val elapsedMs: Long, val message: String, val warning: Boolean)
    private val pending = ArrayBlockingQueue<LogEntry>(LIMIT)
    private val skippedLogs = AtomicLong()
    private val logger by lazy {
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val entry = pending.take()
                    val line = "${entry.message} eventElapsedMs=${entry.elapsedMs}"
                    if (entry.warning) AppLog.w(line) else AppLog.i(line)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (_: Exception) {
                    skippedLogs.incrementAndGet()
                }
            }
        }, "AudioDiagnostics").apply { isDaemon = true; start() }
    }

    /** Never wait for logcat or file-export I/O on a mixer/codec thread. Bounded on overload. */
    fun report(elapsedMs: Long, message: String, warning: Boolean = false, remember: Boolean = true) {
        if (remember) record(elapsedMs, message)
        logger
        if (!pending.offer(LogEntry(elapsedMs, message, warning))) skippedLogs.incrementAndGet()
    }

    @Synchronized fun record(elapsedMs: Long, message: String) {
        if (events.size == LIMIT) events.removeFirst()
        events.addLast(Event(elapsedMs, message))
    }

    fun snapshot(nowMs: Long): String {
        // Do string formatting after releasing the audio thread's very short record lock.
        val copy = synchronized(this) { events.toList() }
        return "Audio diagnostics: ${copy.size} recent events, oldest first (age at export), " +
            "skippedLogWrites=${skippedLogs.get()}\n" +
            copy.joinToString("\n") { "ageMs=${(nowMs - it.elapsedMs).coerceAtLeast(0)} ${it.message}" }
    }
}
