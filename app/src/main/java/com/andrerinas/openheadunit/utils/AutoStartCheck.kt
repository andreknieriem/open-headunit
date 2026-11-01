package com.andrerinas.openheadunit.utils

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.app.AutoStartCheckPolicy
import com.andrerinas.openheadunit.app.AutoStartFixPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Collects why the head unit may be closing the app (#1122). Read-only: every command here only
 * reads properties, files and logs. Uses root or the unit's own ADB through [OemAppManager] when one
 * is there; without them it still reports what an app can see by itself.
 */
object AutoStartCheck {

    private const val DURASPEED_PACKAGE = "com.mediatek.duraspeed"
    private const val MAX_EXITS = 5

    suspend fun run(context: Context): AutoStartCheckPolicy.Findings = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        val backend = OemAppManager.checkAvailableBackend()
        val privileged = backend != OemAppManager.ExecutionBackend.NONE

        // With a route everything goes through it; without one, getprop and the system
        // directories are still readable by any app.
        suspend fun read(command: String): String = AutoStartCheckPolicy.unbracket(
            if (privileged) OemAppManager.executeCommand(context, AutoStartCheckPolicy.bracket(command)).second
            else runPlain(command)
        )

        // Filtering stays in Kotlin: toolbox on Android 4.x has no sed, and grep only from 4.4 on some.
        val listing = AutoStartCheckPolicy.LIST_DIRS.map { dir ->
            read(AutoStartCheckPolicy.listCommand(dir)).lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString("\n") { "$dir/$it" }
        }.joinToString("\n")
        val listFiles = AutoStartCheckPolicy.listCandidates(listing).map { path ->
            AutoStartCheckPolicy.ListFile(path, read("cat '$path' 2>/dev/null").contains(pkg))
        }

        // Other apps' log lines and /proc entries need root or ADB.
        val forceStops = if (privileged) AutoStartCheckPolicy.forceStopLines(
            read("logcat -d -b main -b system -b events 2>/dev/null"), pkg
        ) else emptyList()
        val exits = recentExits(context)
        val stoppedBy = AutoStartCheckPolicy.callerPids(forceStops + exits).map { pid ->
            val name = if (privileged) AutoStartCheckPolicy.processName(read("cat /proc/$pid/cmdline 2>/dev/null")) else null
            if (name != null) "$name (pid $pid)" else "pid $pid"
        }

        val findings = AutoStartCheckPolicy.Findings(
            appVersion = BuildConfig.VERSION_NAME,
            route = backend.name.lowercase(),
            device = "${Build.MANUFACTURER} ${Build.MODEL}",
            android = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            platform = read("getprop ro.board.platform").trim().ifEmpty { Build.HARDWARE },
            firmware = read("getprop ro.build.display.id").trim().ifEmpty { Build.DISPLAY },
            isHomeApp = CarLauncherManager.isDefaultLauncher(context),
            batteryExempt = batteryExempt(context),
            backgroundRestricted = backgroundRestricted(context),
            duraSpeed = duraSpeedInstalled(context, privileged) { read(it) },
            recentExits = exits,
            forceStops = forceStops,
            stoppedBy = stoppedBy,
            listFiles = listFiles,
        )
        AppLog.i("AutoStartCheck: access=${findings.route} home=${findings.isHomeApp} " +
            "forceStops=${forceStops.size} stoppedBy=$stoppedBy lists=${listFiles.size} " +
            "verdict=${AutoStartCheckPolicy.verdict(findings)}")
        findings
    }

    private fun runPlain(command: String): String = try {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        out
    } catch (e: Exception) {
        AppLog.w("AutoStartCheck: '$command' failed: ${e.message}")
        ""
    }

    private fun batteryExempt(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return try { pm.isIgnoringBatteryOptimizations(context.packageName) } catch (_: Exception) { null }
    }

    private fun backgroundRestricted(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        return try { am.isBackgroundRestricted } catch (_: Exception) { null }
    }

    private suspend fun duraSpeedInstalled(context: Context, privileged: Boolean, read: suspend (String) -> String): Boolean? {
        // The build properties are readable by any app and name it even when the package is hidden.
        val props = AutoStartFixPolicy.DURASPEED_PROPS.map { read("getprop $it") }
        if (AutoStartFixPolicy.duraSpeedSupported(props)) return true
        try {
            context.packageManager.getPackageInfo(DURASPEED_PACKAGE, 0)
            return true
        } catch (_: PackageManager.NameNotFoundException) {
        } catch (_: Exception) {
        }
        // From API 30 another package is hidden unless declared, so a miss only counts with a route.
        if (privileged) return read("pm list packages 2>/dev/null").contains(DURASPEED_PACKAGE)
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) false else null
    }

    /** From API 30 Android itself keeps why the process ended, force stops included, without root. */
    private fun recentExits(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        return try { exitsSinceR(context) } catch (e: Exception) {
            AppLog.w("AutoStartCheck: exit reasons unavailable: ${e.message}")
            emptyList()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun exitsSinceR(context: Context): List<String> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return emptyList()
        val format = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        return am.getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS).map { info ->
            val description = info.description?.let { " $it" } ?: ""
            "${format.format(Date(info.timestamp))} ${reasonName(info.reason)}$description"
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_EXIT_SELF -> "exit-self"
        ApplicationExitInfo.REASON_SIGNALED -> "signaled"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low-memory"
        ApplicationExitInfo.REASON_CRASH -> "crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash-native"
        ApplicationExitInfo.REASON_ANR -> "anr"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive-resource-usage"
        ApplicationExitInfo.REASON_USER_REQUESTED -> AutoStartCheckPolicy.FORCE_STOP_EXIT
        ApplicationExitInfo.REASON_USER_STOPPED -> "user-stopped"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency-died"
        ApplicationExitInfo.REASON_OTHER -> "other"
        else -> "reason-$reason"
    }
}
