package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.Intent
import android.util.Log

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File as IoFile
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.IllegalFormatException
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger

object AppLog {

    interface Logger {
        fun println(priority: Int, tag: String, msg: String)

        class Android : Logger {
            override fun println(priority: Int, tag: String, msg: String) {
                Log.println(priority, TAG, msg)
            }
        }

        /**
         * Writes to a file from one background thread fed by a bounded queue.
         *
         * Formatting and the write used to happen on the calling thread under a single lock shared
         * by the AAP poll and send threads, the audio and video threads and the main thread — so at
         * verbose, where several lines are emitted per frame, they serialised against each other and
         * against a disk flush. Here a caller only timestamps the line and offers it to the queue.
         *
         * When the queue is full the oldest line is dropped rather than blocking the caller: losing
         * log lines is always better than stalling the thread that produces them, and the count is
         * reported so a capture never silently under-reports.
         */
        class File(val file: IoFile) : Logger, Closeable {
            private val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
            private val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            private val queue = ArrayBlockingQueue<Any>(QUEUE_CAPACITY)
            private val dropped = AtomicInteger(0)
            @Volatile private var closed = false

            init {
                Thread({ drainLoop() }, "AppLog-file-writer").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY
                    start()
                }
            }

            override fun println(priority: Int, tag: String, msg: String) {
                if (closed) return
                val level = when (priority) {
                    Log.VERBOSE -> "V"
                    Log.DEBUG -> "D"
                    Log.INFO -> "I"
                    Log.WARN -> "W"
                    Log.ERROR -> "E"
                    Log.ASSERT -> "A"
                    else -> priority.toString()
                }
                // Timestamp here, not on the writer thread: the queue can lag, and a log whose
                // timestamps are when a line was *written* rather than when it happened is useless
                // for the millisecond-level causality these captures get read for.
                val line = synchronized(dateFormat) { dateFormat.format(java.util.Date()) } +
                        " [$tag:$level] $msg"
                while (!queue.offer(line)) {
                    if (queue.poll() == null) return
                    dropped.incrementAndGet()
                }
            }

            private fun drainLoop() {
                try {
                    while (true) {
                        val item = queue.take()
                        if (item !is String) break
                        val line: String = item
                        try {
                            writer.write(line)
                            writer.newLine()
                            val lost = dropped.getAndSet(0)
                            if (lost > 0) {
                                writer.write("--- AppLog: $lost lines dropped, writer could not keep up ---")
                                writer.newLine()
                            }
                            // Flush when the burst is over rather than per line, so a kill loses at
                            // most what is still queued instead of a whole 8 KB buffer.
                            if (queue.isEmpty()) writer.flush()
                        } catch (e: IOException) {
                            Log.e(TAG, "Failed to write AppLog file ${file.absolutePath}", e)
                        }
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                try { writer.flush() } catch (_: IOException) {}
                try { writer.close() } catch (_: IOException) {}
            }

            /**
             * Deliberately does not wait for the writer to finish. `close()` runs on the main thread
             * from [AppLog.init] every time the log settings change, and joining a thread that is
             * mid-flush to slow eMMC there is an ANR waiting to happen. The drain thread owns the
             * writer and flushes and closes it once it sees the sentinel, so the file still ends up
             * complete — just a moment later.
             */
            override fun close() {
                if (closed) return
                closed = true
                // Make room for the sentinel even if producers had filled the queue.
                while (!queue.offer(POISON)) {
                    if (queue.poll() == null) break
                }
            }

            private companion object {
                const val QUEUE_CAPACITY = 4096

                /** End marker; a distinct type so it can never collide with a real line. */
                val POISON = Any()
            }
        }
    }

    @Volatile private var appLogFileLogger: Logger.File? = null
    @Volatile private var lastAppLogFile: IoFile? = null
    @Volatile private var currentLogSource: Settings.LogSource = Settings.LogSource.LOGCAT

    fun init(settings: Settings?, context: Context? = null) {
        // Cache the level rather than resolving it per call: every call site below, and every
        // LOG_VERBOSE/LOG_DEBUG guard, used to reach SharedPreferences.getInt() through
        // Settings.exporterLogLevel — a lock plus a map lookup for lines that are mostly discarded.
        // Every caller of init() is a place the level can change, so the cache cannot go stale.
        cachedLogLevel = settings?.logLevel ?: Log.INFO

        val desiredSource = settings?.logSource ?: Settings.LogSource.LOGCAT
        val captureEnabled = settings?.exporterCaptureEnabled == true
        val captureAllowed = settings?.logLevel != LogExporter.LogLevel.SILENT.logLevel
        val shouldUseAppLogFile = desiredSource == Settings.LogSource.APPLOG_FILE && captureEnabled && captureAllowed && context != null

        if (!shouldUseAppLogFile) {
            closeAppLogFileLogger()
            LOGGER = Logger.Android()
            currentLogSource = desiredSource
            return
        }

        if (desiredSource == currentLogSource && appLogFileLogger != null) {
            return
        }

        val appContext = context?.applicationContext
        closeAppLogFileLogger()

        if (appContext == null) {
            LOGGER = Logger.Android()
            currentLogSource = desiredSource
            return
        }

        val logDir = LogFilesHelper.resolveLogDirectory(appContext, settings) ?: appContext.filesDir
        LogFilesHelper.rotateLogs(logDir)

        val logFile = LogFilesHelper.createTimestampedLogFile(logDir)
        try {
            appLogFileLogger = Logger.File(logFile)
            LOGGER = appLogFileLogger!!
            lastAppLogFile = logFile
        } catch (e: IOException) {
            appLogFileLogger = null
            LOGGER = Logger.Android()
            Log.e(TAG, "Failed to open AppLog file ${logFile.absolutePath}", e)
        }
        currentLogSource = desiredSource
    }

    @Volatile
    var LOGGER: Logger = Logger.Android()

    @Volatile
    private var cachedLogLevel: Int = Log.INFO
    private val LOG_LEVEL get() = cachedLogLevel

    val logSource: Settings.LogSource get() = currentLogSource
    val currentLogFile: IoFile? get() = appLogFileLogger?.file
    val lastLogFile: IoFile? get() = lastAppLogFile
    val isCapturing: Boolean get() = appLogFileLogger != null

    const val TAG = "OPENHU"
    // LOG_LEVEL constants should not longer be needed because we check the setting directly.
    val LOG_VERBOSE get() = LOG_LEVEL <= Log.VERBOSE
    val LOG_DEBUG get() = LOG_LEVEL <= Log.DEBUG

    fun i(msg: String) {
        if (isLoggable(Log.INFO)) log(Log.INFO, format(msg))
    }

    fun i(msg: String, vararg params: Any) {
        if (isLoggable(Log.INFO)) log(Log.INFO, format(msg, *params))
    }

    fun e(msg: String?) {
        if (isLoggable(Log.ERROR)) loge(format(msg ?: "Unknown error"), null)
    }

    fun e(msg: String, tr: Throwable) {
        if (isLoggable(Log.ERROR)) loge(format(msg), tr)
    }

    fun e(tr: Throwable) {
        if (isLoggable(Log.ERROR)) loge(tr.message ?: "Unknown error", tr)
    }


    fun e(msg: String?, vararg params: Any) {
        if (isLoggable(Log.ERROR)) loge(format(msg ?: "Unknown error", *params), null)
    }

    fun v(msg: String, vararg params: Any) {
        if (isLoggable(Log.VERBOSE)) log(Log.VERBOSE, format(msg, *params))
    }

    fun d(msg: String, vararg params: Any) {
        if (isLoggable(Log.DEBUG)) log(Log.DEBUG, format(msg, *params))
    }

    fun d(msg: String) {
        if (isLoggable(Log.DEBUG)) log(Log.DEBUG, format(msg))
    }

    fun w(msg: String) {
        if (isLoggable(Log.WARN)) log(Log.WARN, format(msg))
    }

    fun w(msg: String, vararg params: Any) {
        if (isLoggable(Log.WARN)) log(Log.WARN, format(msg, *params))
    }

    private fun log(priority: Int, msg: String) {
        LOGGER.println(priority, TAG, msg)
    }

    private fun isLoggable(priority: Int): Boolean = priority >= LOG_LEVEL

    /**
     * The trace is not conditional on the destination.
     *
     * It used to be attached only when the logger was [Logger.Android], so a capture written to a
     * file — the one that outlives the fault and actually gets read afterwards — recorded the
     * message and dropped the exception. "Wireless server error" with no cause is not a diagnosis,
     * and the throwable is the whole reason these call sites pass one.
     */
    private fun loge(message: String, tr: Throwable?) {
        val trace = if (tr != null) Log.getStackTraceString(tr) else ""
        LOGGER.println(Log.ERROR, TAG, if (trace.isEmpty()) message else "$message\n$trace")
    }

    private fun closeAppLogFileLogger() {
        appLogFileLogger?.close()
        appLogFileLogger = null
    }


    private fun format(msg: String, vararg array: Any): String {
        var formatted: String
        if (array.isEmpty()) {
            formatted = msg
        } else try {
            formatted = String.format(Locale.US, msg, *array)
        } catch (_: IllegalFormatException) {
            e("IllegalFormatException: formatString='%s' numArgs=%d", msg, array.size)
            formatted = "$msg (An error occurred while formatting the message.)"
        }
        // Throwable's constructor already fills in the stack trace; calling fillInStackTrace()
        // again captured the whole thing a second time, per emitted line.
        val stackTrace = Throwable().stackTrace
        var string = "<unknown>"
        for (i in 2 until stackTrace.size) {
            val className = stackTrace[i].className
            if (className != AppLog::class.java.name) {
                val substring = className.substring(1 + className.indexOfLast { a -> a == 46.toChar() })
                string = substring.substring(1 + substring.indexOfLast { a -> a == 36.toChar() }) + "." + stackTrace[i].methodName
                break
            }
        }
        return String.format(Locale.US, "[%d] %s | %s", Thread.currentThread().id, string, formatted)
    }

    fun i(intent: Intent) {
        i(intent.toString())
        val ex = intent.extras
        if (ex != null) {
            i(ex.toString())
        }
    }
}
