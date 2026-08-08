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

object AppLog {

    interface Logger {
        fun println(priority: Int, tag: String, msg: String)

        class Android : Logger {
            override fun println(priority: Int, tag: String, msg: String) {
                Log.println(priority, TAG, msg)
            }
        }

        class File(val file: IoFile) : Logger, Closeable {
            private val lock = Any()
            private val writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
            private val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
            @Volatile private var closed = false



            override fun println(priority: Int, tag: String, msg: String) {
                synchronized(lock) {
                    if (closed) return
                    try {
                        val ts = dateFormat.format(java.util.Date())
                        val level = when (priority) {
                            Log.VERBOSE -> "V"
                            Log.DEBUG -> "D"
                            Log.INFO -> "I"
                            Log.WARN -> "W"
                            Log.ERROR -> "E"
                            Log.ASSERT -> "A"
                            else -> priority.toString()
                        }
                        writer.write("$ts [$tag:$level] $msg")
                        writer.newLine()
                    } catch (e: IOException) {
                        Log.e(TAG, "Failed to write AppLog file ${file.absolutePath}", e)
                    }
                }
            }

            override fun close() {
                synchronized(lock) {
                    if (closed) return
                    closed = true
                    try {
                        writer.flush()
                    } catch (_: IOException) {
                    }
                    try {
                        writer.close()
                    } catch (_: IOException) {
                    }
                }
            }
        }
    }

    private var settings: Settings? = null
    @Volatile private var appLogFileLogger: Logger.File? = null
    @Volatile private var lastAppLogFile: IoFile? = null
    @Volatile private var currentLogSource: Settings.LogSource = Settings.LogSource.LOGCAT

    fun init(settings: Settings?, context: Context? = null) {
        this.settings = settings

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
    private val LOG_LEVEL get() = settings?.logLevel ?: Log.INFO

    val logSource: Settings.LogSource get() = currentLogSource
    val currentLogFile: IoFile? get() = appLogFileLogger?.file
    val lastLogFile: IoFile? get() = lastAppLogFile
    val isCapturing: Boolean get() = appLogFileLogger != null

    const val TAG = "OPENHU"
    // LOG_LEVEL constants should not longer be needed because we check the setting directly.
    val LOG_VERBOSE get() = LOG_LEVEL <= Log.VERBOSE
    val LOG_DEBUG get() = LOG_LEVEL <= Log.DEBUG

    @JvmStatic
    fun i(msg: String) {
        if (isLoggable(Log.INFO)) log(Log.INFO, format(msg))
    }

    @JvmStatic
    fun i(msg: String, vararg params: Any) {
        if (isLoggable(Log.INFO)) log(Log.INFO, format(msg, *params))
    }

    @JvmStatic
    fun e(msg: String?) {
        if (isLoggable(Log.ERROR)) loge(format(msg ?: "Unknown error"), null)
    }

    @JvmStatic
    fun e(msg: String, tr: Throwable) {
        if (isLoggable(Log.ERROR)) loge(format(msg), tr)
    }

    @JvmStatic
    fun e(tr: Throwable) {
        if (isLoggable(Log.ERROR)) loge(tr.message ?: "Unknown error", tr)
    }

    @JvmStatic
    fun e(msg: String?, vararg params: Any) {
        if (isLoggable(Log.ERROR)) loge(format(msg ?: "Unknown error", *params), null)
    }

    @JvmStatic
    fun v(msg: String, vararg params: Any) {
        if (isLoggable(Log.VERBOSE)) log(Log.VERBOSE, format(msg, *params))
    }

    @JvmStatic
    fun d(msg: String, vararg params: Any) {
        if (isLoggable(Log.DEBUG)) log(Log.DEBUG, format(msg, *params))
    }

    @JvmStatic
    fun d(msg: String) {
        if (isLoggable(Log.DEBUG)) log(Log.DEBUG, format(msg))
    }

    @JvmStatic
    fun w(msg: String) {
        if (isLoggable(Log.WARN)) log(Log.WARN, format(msg))
    }

    @JvmStatic
    fun w(msg: String, vararg params: Any) {
        if (isLoggable(Log.WARN)) log(Log.WARN, format(msg, *params))
    }

    private fun log(priority: Int, msg: String) {
        LOGGER.println(priority, TAG, msg)
    }

    private fun isLoggable(priority: Int): Boolean = priority >= LOG_LEVEL

    private fun loge(message: String, tr: Throwable?) {
        val trace = if (LOGGER is Logger.Android) Log.getStackTraceString(tr) else ""
        LOGGER.println(Log.ERROR, TAG, message + '\n' + trace)
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
        val stackTrace = Throwable().fillInStackTrace().stackTrace
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
