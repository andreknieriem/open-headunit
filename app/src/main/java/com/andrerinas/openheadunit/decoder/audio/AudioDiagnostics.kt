package com.andrerinas.openheadunit.decoder.audio

import java.util.ArrayDeque

/** Keep rare audio events available even after noisy vendor logs overwrite the logcat ring.
 * Called by mixer/transport threads, never by the native output callback. No disk I/O on record. */
internal object AudioDiagnostics {
    private data class Event(val elapsedMs: Long, val message: String)
    private val events = ArrayDeque<Event>()
    private const val LIMIT = 64

    @Synchronized fun record(elapsedMs: Long, message: String) {
        if (events.size == LIMIT) events.removeFirst()
        events.addLast(Event(elapsedMs, message))
    }

    fun snapshot(nowMs: Long): String {
        // Do string formatting after releasing the audio thread's very short record lock.
        val copy = synchronized(this) { events.toList() }
        return "Audio diagnostics: ${copy.size} recent events, oldest first (age at export)\n" +
            copy.joinToString("\n") { "ageMs=${(nowMs - it.elapsedMs).coerceAtLeast(0)} ${it.message}" }
    }
}
