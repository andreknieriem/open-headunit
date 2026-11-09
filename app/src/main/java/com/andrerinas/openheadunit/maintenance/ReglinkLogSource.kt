package com.andrerinas.openheadunit.maintenance

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * The CAN box on Reglink units. The unit's own service decodes the box and writes every packet
 * to the system log, so this reads the log rather than the serial port, which belongs to the
 * unit (reading it would steal bytes from it). Reading other apps' log lines needs READ_LOGS,
 * which the user grants once over ADB; see [DistanceSourcePolicy.access].
 *
 * One `logcat` process filtered to that tag, read on its own thread: the thread blocks on the
 * pipe, so nothing runs while the box is quiet. Only a changed counter (or the heartbeat) reaches
 * the main thread.
 */
class ReglinkLogSource : CanBoxSource {

    override val name: String = "Reglink"

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var running = false

    @Volatile
    private var process: Process? = null

    private var thread: Thread? = null

    override fun start(listener: (TripReading) -> Unit) {
        if (running) return
        running = true
        thread = Thread({ readLoop(listener) }, "ReglinkLogSource").also {
            it.isDaemon = true
            it.start()
        }
    }

    override fun stop() {
        running = false
        try { process?.destroy() } catch (_: Exception) {}
        process = null
        thread?.interrupt()
        thread = null
    }

    private fun readLoop(listener: (TripReading) -> Unit) {
        val decoder = ReglinkTripDecoder()
        var restarts = 0
        while (running) {
            var lines = 0
            var started: Process? = null
            try {
                started = Runtime.getRuntime().exec(command())
                process = started
                if (!running) break
                AppLog.i("$TAG: reading the CAN box from the log")
                BufferedReader(InputStreamReader(started!!.inputStream), BUFFER_CHARS).use { reader ->
                    while (running) {
                        val line = reader.readLine() ?: break
                        lines++
                        val reading = decoder.onLine(line, SystemClock.elapsedRealtime()) ?: continue
                        main.post { if (running) listener(reading) }
                    }
                }
            } catch (e: Exception) {
                if (running) AppLog.w("$TAG: the log reader failed: ${e.message}")
            } finally {
                try { started?.destroy() } catch (_: Exception) {}
            }
            if (!running) break
            // logcat ended on its own (killed, or the log restarted). A reader that got lines was
            // working, so it gets its retries back.
            if (lines > 0) restarts = 0
            if (++restarts > MAX_RESTARTS) {
                AppLog.w("$TAG: the log reader keeps ending, giving up until the next start; the GPS counts")
                break
            }
            AppLog.i("$TAG: the log reader ended after $lines lines, again in ${RESTART_DELAY_MS / 1000} s")
            try {
                Thread.sleep(RESTART_DELAY_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    // -T 1 starts at the newest line instead of replaying the buffer, whose older readings would
    // look like the counters went back. logd's logcat (Android 5 and up) knows it.
    private fun command(): Array<String> = arrayOf(
        "logcat", "-v", "raw", "-T", "1", "-s", "${ReglinkTripDecoder.LOG_TAG}:D"
    )

    companion object {
        private const val TAG = "ReglinkLogSource"
        private const val RESTART_DELAY_MS = 30_000L
        private const val MAX_RESTARTS = 5
        private const val BUFFER_CHARS = 4 * 1024

        /** The unit's service that decodes the box. */
        private const val SERVICE_PACKAGE = "com.reglink.services"

        fun isReglinkUnit(context: Context): Boolean = try {
            context.packageManager.getPackageInfo(SERVICE_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            AppLog.w("$TAG: could not look for $SERVICE_PACKAGE: ${e.message}")
            false
        }

        /** Whether this process may read other apps' log lines right now. */
        fun inLogGroup(): Boolean = try {
            DistanceSourcePolicy.inLogGroup(File("/proc/self/status").readText())
        } catch (e: Exception) {
            AppLog.w("$TAG: could not read the process groups: ${e.message}")
            false
        }
    }
}
