package com.andrerinas.openheadunit.connection.carkey.dongfeng

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.connection.carkey.CarKeyReceiver
import com.andrerinas.openheadunit.input.DongfengWheelKey
import com.andrerinas.openheadunit.utils.AppLog
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Dongfeng wheel media keys, read from the input service's log during a session. Needs READ_LOGS,
 * granted once with `adb shell pm grant <package> android.permission.READ_LOGS`; the app declares it
 * up to API 32 only. Registered only where `com.dftc.carinput` is installed.
 */
class CarDongfengReceiver : CarKeyReceiver {

    override val isSupported = true
    override val isSUNeeded = false
    override val isSessionScoped = true

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    /** Bumped on every register and unregister, so a reader from an earlier session stops by itself. */
    @Volatile private var generation = 0
    @Volatile private var process: Process? = null

    override fun isSupportedOn(context: Context): Boolean {
        if (Build.VERSION.SDK_INT > MAX_READ_LOGS_SDK) return false
        return runCatching { context.packageManager.getPackageInfo(DongfengWheelKey.INPUT_PACKAGE, 0) }.isSuccess
    }

    override fun register(context: Context) {
        if (running) return
        val app = context.applicationContext
        running = true
        val mine = ++generation
        Thread({ readLoop(app, mine) }, "dongfeng-wheel-keys").apply {
            isDaemon = true
            start()
        }
        AppLog.i("CarDongfengReceiver: Wheel key reader started (session $mine)")
    }

    override fun unregister() {
        running = false
        generation++
        process?.destroy()
        process = null
    }

    private fun live(mine: Int) = running && generation == mine

    private fun readLoop(context: Context, mine: Int) {
        val pressed = HashSet<Int>()
        var warnedPermission = false
        var quickFailures = 0
        while (live(mine)) {
            // Checked every round, so a grant given during the session is picked up.
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_LOGS) != PackageManager.PERMISSION_GRANTED) {
                if (!warnedPermission) {
                    warnedPermission = true
                    AppLog.w("CarDongfengReceiver: READ_LOGS is not granted. Grant it once with: " +
                        "adb shell pm grant ${context.packageName} android.permission.READ_LOGS")
                }
                sleep(5_000L)
                continue
            }
            val startedAt = SystemClock.elapsedRealtime()
            var lines = 0
            var firstLine: String? = null
            var started: Process? = null
            try {
                // -T 1 starts at now, so keys pressed before the session are not replayed.
                val reading = ProcessBuilder(
                    "logcat", "-v", "brief", "-T", "1", "${DongfengWheelKey.LOG_TAG}:I", "*:S",
                ).redirectErrorStream(true).start()
                started = reading
                process = reading
                BufferedReader(InputStreamReader(reading.inputStream)).use { reader ->
                    while (live(mine)) {
                        val line = reader.readLine() ?: break
                        lines++
                        if (firstLine == null) firstLine = line
                        val event = DongfengWheelKey.parse(line) ?: continue
                        // A repeated press with no release would hold the key on the phone.
                        if (event.isDown && !pressed.add(event.keyCode)) continue
                        if (!event.isDown && !pressed.remove(event.keyCode)) continue
                        post(context, mine, event.keyCode, event.isDown)
                    }
                }
            } catch (e: Exception) {
                if (live(mine)) AppLog.e("CarDongfengReceiver: Key reader failed", e)
            } finally {
                started?.destroy()
                for (key in pressed) post(context, null, key, false)
                pressed.clear()
            }
            if (!live(mine)) break
            // A reader that ends at once was refused; say what it said, then back off.
            val lived = SystemClock.elapsedRealtime() - startedAt
            if (lived < 2_000L) {
                quickFailures++
                if (quickFailures <= 3 || quickFailures % 30 == 0) {
                    AppLog.w("CarDongfengReceiver: Key reader ended after $lived ms ($lines lines, first: ${firstLine ?: "none"})")
                }
            } else {
                quickFailures = 0
            }
            sleep(if (quickFailures > 3) 10_000L else 2_000L)
        }
    }

    /**
     * Keys go on from the main thread, like every other route. A release always goes. No press
     * identity: the dispatched key may also reach the media session, and the copy must merge.
     */
    private fun post(context: Context, mine: Int?, keyCode: Int, isDown: Boolean) {
        main.post {
            if (mine != null && !live(mine)) return@post
            handleKey(context, keyCode, isDown, source = ROUTE)
        }
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val ROUTE = "carkey-dongfeng"
        // The manifest declares READ_LOGS up to this level only.
        const val MAX_READ_LOGS_SDK = 32
    }
}
