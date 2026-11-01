package com.andrerinas.openheadunit.utils

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.andrerinas.openheadunit.app.AutoStartFixPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs the phase two fixes of #1122 through root or the unit's own ADB. None of them writes to
 * /system: the exemptions are the system's own per-app switches, and DuraSpeed is a setting.
 */
object AutoStartFixes {

    // The boot receiver hears several actions per start (locked boot, boot, unlock, ACC on).
    private const val DURASPEED_DEBOUNCE_MS = 60_000L
    @Volatile
    private var lastDuraSpeedOff = 0L

    suspend fun setExemptions(context: Context, grant: Boolean): Boolean = withContext(Dispatchers.IO) {
        val commands = AutoStartFixPolicy.exemptionCommands(Build.VERSION.SDK_INT, context.packageName, grant)
        if (commands.isEmpty() || !hasRoute()) return@withContext false
        val failed = commands.filter { OemAppManager.executeCommand(context, it).first != 0 }
        AppLog.i("AutoStartFixes: exemptions ${if (grant) "granted" else "returned"}, failed=$failed")
        failed.isEmpty()
    }

    suspend fun turnDuraSpeedOff(context: Context, trigger: String): Boolean = withContext(Dispatchers.IO) {
        val now = SystemClock.elapsedRealtime()
        if (lastDuraSpeedOff != 0L && now - lastDuraSpeedOff < DURASPEED_DEBOUNCE_MS) {
            AppLog.i("AutoStartFixes: DuraSpeed already turned off ${(now - lastDuraSpeedOff) / 1000}s ago ($trigger)")
            return@withContext true
        }
        if (!hasRoute()) {
            AppLog.w("AutoStartFixes: no root or ADB, DuraSpeed left as it is ($trigger)")
            return@withContext false
        }
        val (code, out) = OemAppManager.executeCommand(context, AutoStartFixPolicy.duraSpeedOffCommand())
        if (code == 0) lastDuraSpeedOff = now
        AppLog.i("AutoStartFixes: DuraSpeed off ($trigger) exit=$code ${out.take(200)}")
        code == 0
    }

    // Asking here can raise the superuser prompt, so callers only get here from a switch the user
    // turned on: the grant was saved at that moment.
    private suspend fun hasRoute(): Boolean {
        OemAppManager.requestRoot()
        return OemAppManager.checkAvailableBackend() != OemAppManager.ExecutionBackend.NONE
    }
}
