package com.andrerinas.openheadunit.automation

import android.content.Context
import android.content.Intent
import android.os.Environment
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.LogExporter
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SettingsBackupManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Carries out what [AutomationCommandPolicy] decided. Shared by [AutomationReceiver] and the
 * deep-link activity so the two entry points cannot answer the same command differently.
 */
object AutomationEffectRunner {

    /** Runs [effects], filling [reply] with anything worth reporting. Returns false if any refused. */
    fun run(context: Context, effects: List<AutomationCommandPolicy.Effect>, reply: JSONObject): Boolean {
        val component = App.provide(context)
        var ok = true

        for (effect in effects) {
            when (effect) {
                is AutomationCommandPolicy.Effect.Refuse -> {
                    ok = false
                    reply.put("error", effect.reason)
                    AppLog.w("Automation: refused - ${effect.reason}")
                }
                is AutomationCommandPolicy.Effect.StartService -> startService(context, effect)
                is AutomationCommandPolicy.Effect.ConnectSocket ->
                    CoroutineScope(Dispatchers.IO).launch {
                        component.commManager.connect(effect.ip, effect.port, ConnectionPriorityPolicy.Tier.USER)
                    }
                is AutomationCommandPolicy.Effect.SetNightMode ->
                    setNightMode(context, component.settings, effect.mode)
                is AutomationCommandPolicy.Effect.ImportSettings -> importSettings(context, effect, reply)
                is AutomationCommandPolicy.Effect.ExportSettings ->
                    if (!exportSettings(context, effect, reply)) ok = false
                AutomationCommandPolicy.Effect.ResetSettings ->
                    reply.put("reset", SettingsBackupManager.resetFromContext(context).resetKeys)
                is AutomationCommandPolicy.Effect.SetLogLevel -> {
                    component.settings.exporterLogLevel = logLevelOf(effect.level)
                    reply.put("level", effect.level)
                }
                AutomationCommandPolicy.Effect.StartLogCapture ->
                    LogExporter.startCapture(context, component.settings.exporterLogLevel)
                AutomationCommandPolicy.Effect.StopLogCapture -> LogExporter.stopCapture()
                is AutomationCommandPolicy.Effect.ExportLog ->
                    if (!exportLog(context, component.settings, effect, reply)) ok = false
                is AutomationCommandPolicy.Effect.LogMarker ->
                    // One fixed shape, so two arms of a test round can share a capture and still be
                    // told apart. WARN so it survives an INFO-filtered export.
                    AppLog.w("AutomationMarker: ${effect.text}")
                AutomationCommandPolicy.Effect.QueryState -> putState(component, reply)
            }
        }
        return ok
    }

    private fun startService(context: Context, effect: AutomationCommandPolicy.Effect.StartService) {
        ContextCompat.startForegroundService(
            context,
            Intent(context, AapService::class.java).apply {
                action = effect.action
                effect.stringExtras.forEach { (k, v) -> putExtra(k, v) }
                effect.flagExtras.forEach { (k, v) -> putExtra(k, v) }
            }
        )
    }

    private fun setNightMode(context: Context, settings: Settings, mode: String) {
        settings.nightMode = when (mode) {
            "day" -> Settings.NightMode.DAY
            "night" -> Settings.NightMode.NIGHT
            else -> Settings.NightMode.AUTO
        }
        ContextCompat.startForegroundService(
            context,
            Intent(context, AapService::class.java).apply {
                action = AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE
            }
        )
    }

    private fun importSettings(
        context: Context,
        effect: AutomationCommandPolicy.Effect.ImportSettings,
        reply: JSONObject
    ) {
        val result = if (!effect.json.isNullOrBlank()) {
            SettingsBackupManager.importFromJson(context, effect.json)
        } else {
            SettingsBackupManager.importFromFile(context, File(effect.path!!))
        }
        reply.put("imported", result.importedKeys)
            .put("skipped", result.skippedKeys)
            .put("restartNeeded", SettingsBackupManager.requiresProjectionRestart(result.changedKeys))
    }

    private fun exportSettings(
        context: Context,
        effect: AutomationCommandPolicy.Effect.ExportSettings,
        reply: JSONObject
    ): Boolean {
        val exported = JSONObject(SettingsBackupManager.exportFromContext(context))
        val withheld = redact(exported)

        if (effect.path.isNullOrBlank()) {
            reply.put("settings", exported)
        } else {
            if (!AutomationOutputPolicy.mayWriteTo(effect.path, writableRoots(context), externalStorageRoot())) {
                reply.put("error", AutomationOutputPolicy.writeRefusedReason(effect.path))
                return false
            }
            reply.put(
                "file",
                SettingsBackupManager.writeBackupFile(File(effect.path), exported.toString()).absolutePath
            )
        }
        if (withheld > 0) reply.put("withheld", withheld)
        return true
    }

    /**
     * Strips the credential-bearing settings out of an export, in place, returning how many went.
     * The count is reported so a partial export is not mistaken for unset keys.
     */
    private fun redact(exported: JSONObject): Int {
        val settings = exported.optJSONObject("settings") ?: return 0
        var withheld = 0
        for (key in AutomationOutputPolicy.WITHHELD_KEYS) {
            if (settings.has(key)) {
                settings.remove(key)
                withheld++
            }
        }
        return withheld
    }

    /** Where an export may land: collected off the device, never back into the app's own storage. */
    private fun writableRoots(context: Context): List<String> = listOfNotNull(
        context.getExternalFilesDir(null)?.absolutePath,
        @Suppress("DEPRECATION")
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)?.absolutePath
    )

    /** What `/sdcard` and friends actually point at on this device, or null if it is unavailable. */
    @Suppress("DEPRECATION")
    private fun externalStorageRoot(): String? =
        runCatching { Environment.getExternalStorageDirectory()?.absolutePath }.getOrNull()

    private fun exportLog(
        context: Context,
        settings: Settings,
        effect: AutomationCommandPolicy.Effect.ExportLog,
        reply: JSONObject
    ): Boolean {
        val target = effect.path
        if (!target.isNullOrBlank() &&
            !AutomationOutputPolicy.mayWriteTo(target, writableRoots(context), externalStorageRoot())
        ) {
            reply.put("error", AutomationOutputPolicy.writeRefusedReason(target))
            return false
        }
        // The save suspends and a receiver must not block, so the reply says where to look rather
        // than waiting for the write.
        CoroutineScope(Dispatchers.IO).launch {
            val file = LogExporter.saveLogToPublicFile(context, settings.exporterLogLevel)
            if (file != null && !target.isNullOrBlank()) {
                runCatching { file.copyTo(File(target), overwrite = true) }
                    .onFailure { AppLog.w("Automation: could not copy the log to $target: ${it.message}") }
            }
            AppLog.i("Automation: log export finished (${file?.absolutePath ?: "no file"})")
        }
        reply.put("exporting", true)
        return true
    }

    /**
     * The answer to "which build is this, and what is it doing" in one call. The commit is what
     * makes it useful: version name and code do not move between two candidates of the same fix.
     */
    private fun putState(component: AppComponent, reply: JSONObject) {
        val comm = component.commManager
        reply.put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("commit", BuildConfig.GIT_SHA)
            .put("flavor", BuildConfig.FLAVOR)
            .put("connected", comm.isConnected)
            .put("wireless", comm.isWirelessSession)
            .put("wifiMode", component.settings.wifiConnectionMode.toString())
            .put("state", comm.connectionState.value.let { it::class.java.simpleName })
    }

    private fun logLevelOf(level: String) = when (level) {
        "verbose" -> LogExporter.LogLevel.VERBOSE
        "debug" -> LogExporter.LogLevel.DEBUG
        "info" -> LogExporter.LogLevel.INFO
        "warn" -> LogExporter.LogLevel.WARNING
        "error" -> LogExporter.LogLevel.ERROR
        else -> LogExporter.LogLevel.SILENT
    }
}
