package com.andrerinas.openheadunit.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bounded, thread-safe in-memory ring buffer for recent [AppLog] entries.
 *
 * On noisy head units (e.g. MediaTek / Allwinner), system services flood Android's shared logcat
 * ring buffer in seconds, pushing Open Headunit's own lines out before the user taps Export Log.
 * Keeping the last [capacity] lines in memory guarantees every exported log contains recent
 * Open Headunit history even without prior file capture.
 *
 * Pure JVM (no Android dependencies) so it can be unit tested directly and runs on API 16+.
 */
internal class AppLogRingBuffer(val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val entries = arrayOfNulls<String>(capacity)
    private var head = 0
    private var count = 0
    private var totalRecorded = 0L
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun record(level: String, tag: String, message: String, timestampMs: Long = System.currentTimeMillis()) {
        synchronized(this) {
            val stamp = dateFormat.format(Date(timestampMs))
            entries[head] = "$stamp $level/$tag: $message"
            head = (head + 1) % capacity
            if (count < capacity) count++
            totalRecorded++
        }
    }

    fun size(): Int = synchronized(this) { count }

    fun droppedCount(): Long = synchronized(this) { (totalRecorded - count).coerceAtLeast(0L) }

    fun snapshot(): String = synchronized(this) {
        if (count == 0) {
            return "=== In-app OPENHU ring buffer (0 lines) ==="
        }
        val dropped = (totalRecorded - count).coerceAtLeast(0L)
        val header = if (dropped > 0L) {
            "=== In-app OPENHU ring buffer ($count lines, $dropped older dropped) ==="
        } else {
            "=== In-app OPENHU ring buffer ($count lines) ==="
        }
        val sb = StringBuilder(header.length + count * 96)
        sb.append(header).append('\n')
        val start = if (count < capacity) 0 else head
        for (i in 0 until count) {
            val line = entries[(start + i) % capacity] ?: continue
            sb.append(line).append('\n')
        }
        sb.append("=== End in-app OPENHU ring buffer ===")
        sb.toString()
    }

    fun clear() = synchronized(this) {
        entries.fill(null)
        head = 0
        count = 0
        totalRecorded = 0L
    }

    companion object {
        const val DEFAULT_CAPACITY = 2000
    }
}
