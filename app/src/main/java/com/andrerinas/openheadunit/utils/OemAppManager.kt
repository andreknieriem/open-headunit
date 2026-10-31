package com.andrerinas.openheadunit.utils

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.andrerinas.openheadunit.utils.adb.AdbManager
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages detection, status inspection, and disabling/restoring of OEM CarPlay/Android Auto
 * companion apps (such as Zlink, AutoKit, SpeedPlay) that conflict with Open Headunit by stealing
 * USB and Bluetooth endpoints.
 *
 * Execution is performed either via root (libsu) if granted, or via Self-ADB (127.0.0.1:5555).
 */
object OemAppManager {

    enum class ExecutionBackend {
        ROOT,
        ADB,
        NONE
    }

    data class OemAppTarget(
        val id: String,
        val displayName: String,
        val packageCandidates: List<String>,
        val daemonNames: List<String> = emptyList(),
        val daemonScripts: List<String> = emptyList()
    )

    data class OemAppStatus(
        val target: OemAppTarget,
        val installedPackage: String?,
        val isInstalled: Boolean,
        val isEnabled: Boolean,
        val isRunning: Boolean,
        val detectedDaemons: List<String> = emptyList()
    )

    val KNOWN_TARGETS = listOf(
        OemAppTarget(
            id = "zlink",
            displayName = "Zlink / Zlink5",
            packageCandidates = listOf(
                "com.zjinnova.zlink5",
                "com.zjinnova.zlink"
            ),
            daemonNames = listOf("zlink5.sh", "z-link", "zlink"),
            daemonScripts = listOf("/system/bin/zlink5.sh")
        ),
        OemAppTarget(
            id = "autokit",
            displayName = "AutoKit (Carlinkit)",
            packageCandidates = listOf(
                "cn.manstep.phonemirrorBox",
                "cn.manstep.phonemirrorBox.nomap",
                "com.autokit"
            ),
            daemonNames = listOf("phonemirrorBox", "AutoKit"),
            daemonScripts = emptyList()
        ),
        OemAppTarget(
            id = "speedplay",
            displayName = "SpeedPlay / TLink",
            packageCandidates = listOf(
                "com.suding.speedplay",
                "com.tlink.carplay"
            ),
            daemonNames = listOf("speedplay", "tlink"),
            daemonScripts = emptyList()
        ),
        OemAppTarget(
            id = "carlink",
            displayName = "CarLink (FYT)",
            packageCandidates = listOf(
                "com.syu.carlink",
                "com.syu.carlink2"
            ),
            daemonNames = listOf("carlink"),
            daemonScripts = emptyList()
        )
    )

    fun isPackageInstalled(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    fun isPackageEnabled(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        return try {
            val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }

            val enabledSetting = pm.getApplicationEnabledSetting(packageName)
            when (enabledSetting) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> appInfo.enabled
                else -> appInfo.enabled
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isProcessRunningInActivityManager(context: Context, packageNames: List<String>): Boolean {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            val runningProcesses = am.runningAppProcesses ?: return false
            runningProcesses.any { processInfo ->
                packageNames.any { pkg -> processInfo.processName.contains(pkg) }
            }
        } catch (_: Exception) {
            false
        }
    }

    // libsu needs API 21. Older units answer a plain `su`, so their grant is remembered here.
    @Volatile
    private var legacyRootGranted = false

    /**
     * Ask for root, raising the superuser prompt if no grant is saved yet. Until a shell has been
     * opened in this process libsu cannot tell whether root is granted, so [checkAvailableBackend]
     * alone reads "no root" on a rooted unit. Call this when the user opens something that needs it.
     */
    suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                legacyRootGranted = runLegacySu("id").first == 0
                legacyRootGranted
            } else {
                Shell.getShell()
                Shell.isAppGrantedRoot() == true
            }
        } catch (e: Exception) {
            AppLog.w("OemAppManager: root request failed: ${e.message}")
            false
        }
    }

    private fun isRootGranted(): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) legacyRootGranted
        else Shell.isAppGrantedRoot() == true

    private fun runLegacySu(command: String): Pair<Int, String> {
        val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
        return Pair(process.waitFor(), out)
    }

    suspend fun checkAvailableBackend(): ExecutionBackend = withContext(Dispatchers.IO) {
        if (isRootGranted()) {
            return@withContext ExecutionBackend.ROOT
        }
        if (AdbManager.isAdbPortOpen()) {
            return@withContext ExecutionBackend.ADB
        }
        ExecutionBackend.NONE
    }

    suspend fun executeCommand(context: Context, command: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        if (isRootGranted()) {
            try {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return@withContext runLegacySu(command)
                val result = Shell.cmd(command).exec()
                val out = (result.out + result.err).joinToString("\n").trim()
                return@withContext Pair(result.code, out)
            } catch (e: Exception) {
                AppLog.e("OemAppManager: Root execution failed: ${e.message}", e)
            }
        }

        // Fall back to Self-ADB (localhost:5555)
        AdbManager.exec(context, command)
    }

    suspend fun detectApps(context: Context, checkDaemons: Boolean = false): List<OemAppStatus> = withContext(Dispatchers.IO) {
        val statuses = mutableListOf<OemAppStatus>()

        for (target in KNOWN_TARGETS) {
            var installedPkg: String? = null
            for (candidate in target.packageCandidates) {
                if (isPackageInstalled(context, candidate)) {
                    installedPkg = candidate
                    break
                }
            }

            val isInstalled = installedPkg != null
            val isEnabled = installedPkg?.let { isPackageEnabled(context, it) } ?: false
            val isProcRunning = isProcessRunningInActivityManager(context, target.packageCandidates)

            val daemons = mutableListOf<String>()
            if (checkDaemons && (isInstalled || target.daemonNames.isNotEmpty())) {
                for (daemon in target.daemonNames) {
                    val (code, out) = executeCommand(context, "pgrep -f $daemon")
                    if (code == 0 && out.isNotBlank()) {
                        daemons.add(daemon)
                    }
                }
            }

            statuses.add(
                OemAppStatus(
                    target = target,
                    installedPackage = installedPkg,
                    isInstalled = isInstalled,
                    isEnabled = isEnabled,
                    isRunning = isProcRunning || daemons.isNotEmpty(),
                    detectedDaemons = daemons
                )
            )
        }

        statuses
    }

    fun getDisableCommands(status: OemAppStatus): List<String> {
        val cmds = mutableListOf<String>()
        val pkg = status.installedPackage ?: status.target.packageCandidates.first()
        cmds.add("pm disable-user --user 0 $pkg")
        cmds.add("am force-stop $pkg")
        for (daemon in status.target.daemonNames) {
            cmds.add("pkill -f $daemon")
        }
        return cmds
    }

    fun getRestoreCommands(status: OemAppStatus): List<String> {
        val cmds = mutableListOf<String>()
        val pkg = status.installedPackage ?: status.target.packageCandidates.first()
        cmds.add("pm enable --user 0 $pkg")
        for (script in status.target.daemonScripts) {
            cmds.add("[ -f $script ] && setsid $script >/dev/null 2>&1 </dev/null &")
        }
        return cmds
    }

    suspend fun disableTarget(context: Context, status: OemAppStatus): Pair<Boolean, String> {
        val cmds = getDisableCommands(status)
        val combined = cmds.joinToString(" ; ")
        AppLog.i("OemAppManager: Disabling ${status.target.displayName} with: $combined")
        val (code, out) = executeCommand(context, combined)
        val success = code == 0
        return Pair(success, out)
    }

    suspend fun restoreTarget(context: Context, status: OemAppStatus): Pair<Boolean, String> {
        val cmds = getRestoreCommands(status)
        val combined = cmds.joinToString(" ; ")
        AppLog.i("OemAppManager: Restoring ${status.target.displayName} with: $combined")
        val (code, out) = executeCommand(context, combined)
        val success = code == 0
        return Pair(success, out)
    }

    suspend fun runAutoKillIfEnabled(context: Context) = withContext(Dispatchers.IO) {
        val settings = Settings(context)
        if (!settings.autoKillOemApps) return@withContext

        AppLog.i("OemAppManager: autoKillOemApps is active. Scanning for conflicting apps...")
        val statuses = detectApps(context, checkDaemons = false)
        for (status in statuses) {
            if (status.isInstalled && status.isEnabled) {
                AppLog.i("OemAppManager: Auto-disabling conflicting ${status.target.displayName} (${status.installedPackage})")
                val (success, out) = disableTarget(context, status)
                AppLog.i("OemAppManager: Auto-disable result for ${status.target.displayName}: success=$success, output=$out")
            }
        }
    }
}
