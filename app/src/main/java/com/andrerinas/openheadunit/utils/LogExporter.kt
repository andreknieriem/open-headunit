package com.andrerinas.openheadunit.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.decoder.video.VideoFaultInjector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

object LogExporter {

    enum class LogLevel(val filter: String, val logLevel: Int) {
        VERBOSE("*:V", Log.VERBOSE),
        DEBUG("*:D", Log.DEBUG),
        INFO("*:I", Log.INFO),
        WARNING("*:W", Log.WARN),
        ERROR("*:E", Log.ERROR),
        /** Do not perform any background capture. */
        SILENT("", Int.MAX_VALUE)
    }

    private var captureProcess: Process? = null
    private var captureThread: Thread? = null
    private var captureFile: File? = null

    /**
     * The segment written before [captureFile], once a capture has rolled. Kept so an export is the
     * whole retained tail and not just whatever landed after the most recent roll.
     */
    private var capturePreviousFile: File? = null
    private var captureVerbosity: LogLevel = LogLevel.DEBUG
    private var captureRestarts = 0
    private const val MAX_RESTARTS = 5

    /** Long enough for any ROM that answers at all; `logcat -d` normally returns in well under a second. */
    private const val RING_BUFFER_TIMEOUT_MS = 10_000L

    /**
     * Ceiling for one capture, counting every segment it keeps. Sits under [LogFilesHelper]'s 50 MB
     * budget for the whole directory, so a single runaway capture cannot consume it.
     */
    private const val MAX_CAPTURE_BYTES = 16L * 1024 * 1024

    /**
     * Bytes per segment. A capture rolls at this bound and keeps the two newest segments, so what
     * survives is the last 8-16 MB rather than the first 16.
     *
     * The capture used to stop dead at [MAX_CAPTURE_BYTES], which at VERBOSE is reached in minutes -
     * and the fault a reporter is driving to capture is at the end of the drive, not the start. One
     * reporter hit exactly that ("Verbose not record after 10 minutes"), which is why every log on
     * two open issues is INFO and why sub-second frame pacing has never been measured on that
     * hardware.
     */
    private const val SEGMENT_BYTES = MAX_CAPTURE_BYTES / 2

    val isCapturing: Boolean get() = captureProcess != null

    /** Current capture verbosity while capturing, or null when no capture is active. */
    val currentLevel: LogLevel?
        get() = if (isCapturing) captureVerbosity else null

    /**
     * Starts a continuous logcat process writing to a timestamped file.
     * Unlike [saveLogToPublicFile], this captures everything from the moment it is called,
     * bypassing the small shared ring buffer.
     */
    fun startCapture(context: Context, verbosity: LogLevel) {
        val settings = Settings(context)

        if (AppLog.logSource == Settings.LogSource.APPLOG_FILE) {
            AppLog.w("LogExporter: log source is APPLOG_FILE; logcat capture is disabled")
            stopCapture()
            captureFile = null
            captureVerbosity = verbosity
            return
        }

        // If SILENT requested, ensure capture is stopped and don't start a new one.
        if (verbosity == LogLevel.SILENT) {
            stopCapture()
            captureFile = null
            captureVerbosity = verbosity
            return
        }

        stopCapture()
        val logDir = LogFilesHelper.resolveLogDirectory(context, settings, allowInternalFallback = false) ?: return
        LogFilesHelper.rotateLogs(logDir)

        val file = LogFilesHelper.createTimestampedLogFile(logDir)
        captureFile = file
        capturePreviousFile = null
        captureVerbosity = verbosity
        captureRestarts = 0

        launchLogcatPipe(file, verbosity, context)
        // After the pipe, not before: logcat is spawned without -T, so it drains the ring buffer and
        // then follows, and a line emitted here lands in the file either way.
        AppLog.w(sessionBanner(context))
    }

    /**
     * One line saying what produced this log and what it was configured to do.
     *
     * Reporter logs arrive as a file and nothing else, and none of them used to say which build or
     * which settings wrote them: identifying a build meant fingerprinting which log lines existed,
     * and the settings that decide the video path could at best be pieced together from scattered
     * lines. A test-APK install can also rewrite settings through onboarding without the reporter
     * noticing, so the log has to carry them itself. `exporterLogLevel` is included so missing
     * lines can be told apart from a phone that sent nothing.
     *
     * `videoCodec` is the user's stored *choice*, not the codec that gets announced; the two differ
     * wherever ServiceDiscoveryResponse overrides the choice, and having both in one log is the
     * point. Nothing identifying goes in: no MAC, SSID or filesystem path.
     *
     * Emitted at WARN because both gates on the way out - [AppLog.isLoggable] and the `logcat`
     * filter this object spawns - follow the same `exporterLogLevel` the banner reports, so an INFO
     * banner would be dropped exactly on the sparse captures that need it most.
     *
     * Deliberately untested: it makes no decision, only a string, and the values come from `Build`,
     * `BuildConfig` and SharedPreferences, none of which read without Robolectric.
     */
    private fun sessionBanner(context: Context): String {
        val settings = Settings(context)
        return "LogExporter: session | " +
            "build=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
            "${BuildConfig.FLAVOR}/${BuildConfig.BUILD_TYPE} | " +
            "device=${Build.MANUFACTURER} ${Build.MODEL} board=${Build.BOARD} api=${Build.VERSION.SDK_INT} | " +
            "video=codec:${settings.videoCodec} fps:${settings.fpsLimit} resId:${settings.resolutionId} " +
            "view:${settings.viewMode.name} forceSw:${settings.forceSoftwareDecoding} " +
            "swDecoder:${settings.softwareVideoDecoder.name} | " +
            "wifi=mode:${settings.wifiConnectionMode} strategy:${wifiTransport(settings)} | " +
            "logLevel=${settings.exporterLogLevel.name} | " +
            "debug=${debugLevers(settings)}"
    }

    /**
     * The transport selector that the reported mode actually reads.
     *
     * Native AA has its own, `nativeApStrategy`, and reads `helperConnectionStrategy` nowhere. This
     * line printed the helper's for every mode, so a Native capture opened with a value nothing in
     * that mode consults - which meant a header could not tell a Native/WiFi-Direct failure from a
     * Native/hotspot one, and reading it literally sent triage after a Nearby path that never ran.
     */
    private fun wifiTransport(settings: Settings): String =
        if (settings.wifiConnectionMode == WifiLauncherMode.NATIVE) {
            settings.nativeApStrategy.name
        } else {
            settings.helperConnectionStrategy.name
        }

    /**
     * The settings that make the app behave unlike itself, in the one line every capture opens with.
     *
     * Video fault injection deliberately corrupts the stream, and a reporter exploring the debug
     * screen left it on for a whole drive: the artifacts in that capture were ours, injected, and
     * the only thing that said so was a disclaimer buried mid-log. The same applies to the feed hold
     * and the forced memory profile, which imitate hardware the unit does not have. Reads "none"
     * when nothing is on, so a clean capture says so rather than saying nothing.
     */
    private fun debugLevers(settings: Settings): String {
        val levers = mutableListOf<String>()
        val injection = settings.debugVideoFaultInjection
        if (injection != VideoFaultInjector.Mode.OFF) {
            val budget = settings.debugVideoFaultBudget
            val cap = if (budget == VideoFaultInjector.UNLIMITED_BUDGET) "no budget" else "budget:$budget"
            levers += "inject:${injection.name}(1-in-${settings.debugVideoFaultRate}, $cap)"
        }
        if (settings.debugVideoFeedHoldMs > 0) levers += "feedHold:${settings.debugVideoFeedHoldMs}ms"
        settings.debugForceMemoryProfile?.let { levers += "forceMemory:${it.name}" }
        if (settings.debugVideoLowLatency) levers += "lowLatency:on"
        return if (levers.isEmpty()) "none" else levers.joinToString(" ")
    }

    /**
     * Spawns a logcat process piping stdout into [file] (append mode).
     * When the process exits unexpectedly, restarts automatically up to [MAX_RESTARTS] times
     * so a system-killed logcat doesn't silently stop the capture.
     */
    private fun launchLogcatPipe(file: File, verbosity: LogLevel, context: Context) {
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-v", "threadtime", verbosity.filter)
            )
            captureProcess = process
            captureThread = Thread {
                try {
                    // Not copyTo(): at VERBOSE the filter is the whole system, and rotateLogs only
                    // runs when a capture starts, so an unattended capture would grow until the disk
                    // did. Count what we write and roll to a new segment at SEGMENT_BYTES, keeping
                    // the two newest - so the bound is on disk, not on how long a capture lasts.
                    var segment = file
                    var out = FileOutputStream(segment, true)
                    var written = segment.length()
                    val buffer = ByteArray(8 * 1024)
                    try {
                        while (true) {
                            val read = process.inputStream.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            written += read
                            if (written < SEGMENT_BYTES) continue

                            val logDir = segment.parentFile ?: break
                            out.write("\n--- continues in the next log file ---\n".toByteArray())
                            out.flush()
                            out.close()

                            val next = LogFilesHelper.createTimestampedLogFile(logDir)
                            // Two segments at a time. The one before the previous is older than the
                            // retained tail and nothing reads it again.
                            capturePreviousFile?.delete()
                            capturePreviousFile = segment
                            captureFile = next
                            segment = next
                            out = FileOutputStream(segment, true)
                            written = 0L
                            out.write("--- continued from the previous log file ---\n".toByteArray())
                        }
                    } finally {
                        try { out.flush(); out.close() } catch (_: IOException) { }
                    }
                } catch (_: IOException) { }

                // the read loop ended — logcat process died or was intentionally stopped
                if (captureProcess === process && captureRestarts < MAX_RESTARTS) {
                    captureRestarts++
                    val err = try { process.errorStream.bufferedReader().readText().trim() } catch (_: Exception) { "" }
                    if (err.isNotEmpty()) {
                        AppLog.w("Log capture process exited with error: $err (attempt $captureRestarts/$MAX_RESTARTS)")
                    } else {
                        AppLog.w("Log capture process exited, restarting (attempt $captureRestarts/$MAX_RESTARTS)")
                    }
                    try { Thread.sleep(2000) } catch (_: InterruptedException) { return@Thread }
                    // The current segment, not [file]: after a roll that one is the older half of
                    // the tail, and appending to it would lose the roll.
                    launchLogcatPipe(captureFile ?: file, verbosity, context)
                } else if (captureProcess === process && capturePreviousFile == null && file.length() == 0L) {
                    // The only reliable test for a ROM that refuses logcat: the capture the user
                    // asked for ran and produced nothing. Asking beforehand meant spawning logcat
                    // speculatively, which on Android 13+ raises the system consent dialog.
                    // exporterCaptureEnabled is deliberately not touched - this branch is only
                    // reachable from a capture that is already enabled, and the source is the only
                    // thing being corrected.
                    val err = try { process.errorStream.bufferedReader().readText().trim() } catch (_: Exception) { "" }
                    AppLog.w("LogExporter: Logcat capture produced 0 bytes ($err). Automatically switching to Direct to file (APPLOG_FILE).")
                    file.delete()
                    captureFile = null
                    val settings = Settings(context)
                    settings.logSource = Settings.LogSource.APPLOG_FILE
                    AppLog.init(settings, context.applicationContext)
                }
            }.also { it.isDaemon = true; it.start() }
        } catch (e: IOException) {
            AppLog.e("Failed to start log capture", e)
            if (file.exists() && file.length() == 0L) {
                file.delete()
            }
            captureFile = null
        }
    }

    /** Stops the continuous capture process. */
    fun stopCapture() {
        captureProcess?.destroy()
        captureProcess = null
        captureThread?.join(2000)
        captureThread = null
        val file = captureFile
        if (file != null && file.exists() && file.length() == 0L) {
            file.delete()
            // A stop landing just after a roll empties the newest segment, and dropping the
            // reference there would strand the retained tail in capturePreviousFile with nothing
            // reading it. Promote it instead.
            captureFile = capturePreviousFile
            capturePreviousFile = null
        }
    }

    /**
     * Writes logs to a timestamped file and returns it.
     * - If a capture file is available (capture was started, active or already stopped):
     *   copies its content into a fresh export file so the original capture file is preserved.
     * - Otherwise: dumps the current logcat ring buffer.
     */
    suspend fun saveLogToPublicFile(context: Context, verbosity: LogLevel): File? = withContext(Dispatchers.IO) {
        if (verbosity == LogLevel.SILENT) {
            AppLog.w("LogExporter: export requested while SILENT; skipping export")
            return@withContext null
        }

        // Before every path below, because this is the only one that reaches AppLog's own file.
        // The two logcat paths do not trust it to arrive and append it themselves - see
        // appendBanner. A second copy in a capture file costs a line and is worth it.
        AppLog.w(sessionBanner(context))

        val settings = Settings(context)

        if (AppLog.logSource == Settings.LogSource.APPLOG_FILE) {
            return@withContext (AppLog.currentLogFile ?: AppLog.lastLogFile)
                ?.takeIf { it.exists() && it.length() > 0 }
        }

        val logDir = LogFilesHelper.resolveLogDirectory(context, settings, allowInternalFallback = false)
            ?: return@withContext null
        LogFilesHelper.ensureDirectory(logDir)

        val source = captureFile
        if (source != null && source.exists() && source.length() > 0) {
            val earlier = capturePreviousFile?.takeIf { it.exists() && it.length() > 0 }
            if (earlier == null) {
                appendBanner(source, context)
                return@withContext source
            }
            // The capture rolled, so neither segment is the whole retained log and a reporter
            // attaches one file. Join them into a fresh export and leave both segments in place,
            // so an export cannot cost the capture that is still running.
            return@withContext try {
                val joined = LogFilesHelper.createTimestampedLogFile(logDir)
                FileOutputStream(joined).use { out ->
                    earlier.inputStream().use { it.copyTo(out) }
                    source.inputStream().use { it.copyTo(out) }
                }
                appendBanner(joined, context)
                joined
            } catch (e: Exception) {
                AppLog.e("LogExporter: could not join the capture segments; exporting the newest one", e)
                appendBanner(source, context)
                source
            }
        }

        dumpRingBuffer(logDir, verbosity)?.also { appendBanner(it, context) }
    }

    /**
     * Writes the session banner into an export file directly, rather than trusting it to arrive.
     *
     * The [AppLog] call above reaches a capture file only after travelling logd, the logcat process
     * and the pipe's writer thread, and the export returns first: two reporter captures arrived
     * with no banner at all, which is what made identifying their build and flavor guesswork. One
     * small append is atomic against the pipe because both streams are opened `O_APPEND`, so the
     * line lands at the end of the file whatever the capture is doing.
     */
    private fun appendBanner(file: File, context: Context) {
        try {
            FileOutputStream(file, true).use {
                it.write("\n${sessionBanner(context)}\n".toByteArray())
            }
        } catch (e: Exception) {
            AppLog.w("LogExporter: could not write the session banner into ${file.name}: ${e.message}")
        }
    }

    /**
     * Dumps the logcat ring buffer for an export that has no capture file to hand.
     *
     * Bounded because `logcat` does not always answer: on a ROM that gates it behind the system
     * log-access dialog the process sits there until somebody taps, which used to hang the caller
     * for as long as that took. Only [Process.destroy] ends the read - cancelling the wait cannot,
     * because `copyTo` blocks somewhere coroutine cancellation does not reach.
     */
    private suspend fun dumpRingBuffer(logDir: File, verbosity: LogLevel): File? = coroutineScope {
        LogFilesHelper.rotateLogs(logDir)
        val logFile = LogFilesHelper.createTimestampedLogFile(logDir)

        val process = try {
            // Use stdout piping instead of -f flag; -f is unreliable on Android 4.4.
            Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-v", "threadtime", verbosity.filter)
            )
        } catch (e: Exception) {
            AppLog.e("Failed to save logs", e)
            return@coroutineScope null
        }

        val dump = async(Dispatchers.IO) {
            try {
                FileOutputStream(logFile).use { out -> process.inputStream.copyTo(out) }
                process.waitFor()
                true
            } catch (e: Exception) {
                AppLog.e("Failed to save logs", e)
                false
            }
        }

        val finished = withTimeoutOrNull(RING_BUFFER_TIMEOUT_MS) { dump.await() }
        if (finished == null) {
            AppLog.w(
                "LogExporter: the logcat ring-buffer dump produced nothing in " +
                    "${RING_BUFFER_TIMEOUT_MS}ms, so the export is being abandoned. A ROM that asks " +
                    "for log-access consent looks exactly like this while the dialog goes untapped."
            )
            process.destroy()
            dump.join()
        }

        if (finished != true || logFile.length() == 0L) {
            logFile.delete()
            return@coroutineScope null
        }
        logFile
    }

    fun shareLogFile(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, "Share Log File")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private const val CLIPBOARD_SAFE_LIMIT_BYTES = 800 * 1024

    suspend fun copyLogToClipboard(context: Context, verbosity: LogLevel): Boolean = withContext(Dispatchers.IO) {
        if (verbosity == LogLevel.SILENT) {
            AppLog.w("LogExporter: clipboard copy requested while SILENT; skipping")
            return@withContext false
        }
        val logFile = saveLogToPublicFile(context, verbosity) ?: return@withContext false
        copyFileToClipboard(context, logFile)
    }

    fun copyFileToClipboard(context: Context, logFile: File): Boolean {
        return try {
            val content = readLogFileForClipboard(logFile)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("OpenHeadunit Log", content)
            clipboard.setPrimaryClip(clip)
            AppLog.i("LogExporter: copied ${content.length} chars (${content.toByteArray(Charsets.UTF_8).size} bytes) to clipboard")
            true
        } catch (e: Exception) {
            AppLog.e("LogExporter: failed to copy log to clipboard", e)
            false
        }
    }

    private fun readLogFileForClipboard(file: File): String {
        val fileLen = file.length()
        if (fileLen <= CLIPBOARD_SAFE_LIMIT_BYTES) {
            return file.readText(Charsets.UTF_8)
        }
        val headBytes = CLIPBOARD_SAFE_LIMIT_BYTES / 4
        val tailBytes = 3 * CLIPBOARD_SAFE_LIMIT_BYTES / 4
        val head = FileInputStream(file).use { fis ->
            val buf = ByteArray(headBytes.toInt())
            var read = 0
            while (read < buf.size) {
                val n = fis.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read, Charsets.UTF_8)
        }
        val skipAmount = (fileLen - tailBytes).coerceAtLeast(0L)
        val tail = FileInputStream(file).use { fis ->
            fis.skip(skipAmount)
            fis.reader(Charsets.UTF_8).readText()
        }
        val kbTotal = fileLen / 1024
        val kbCopied = CLIPBOARD_SAFE_LIMIT_BYTES / 1024
        val omittedBytes = (fileLen - headBytes - tailBytes).coerceAtLeast(0L)
        return buildString {
            append("[TRUNCATED for clipboard: full log is ${kbTotal} KB; only first ~${kbCopied} KB shown below.]\n")
            append("=== BEGINNING OF LOG ===\n")
            append(head)
            append("\n\n=== [${String.format("%,d", omittedBytes)} bytes omitted in the middle] ===\n\n")
            append("=== END OF LOG (most recent lines, most likely to contain errors) ===\n")
            append(tail)
        }
    }
}
