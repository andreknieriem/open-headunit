package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.andrerinas.openheadunit.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SettingsBackupManager {
    const val FORMAT = "open-headunit-settings"
    // Backups exported before the rename used this format id and file name. Keep accepting
    // them so users do not lose access to existing backups after updating.
    const val LEGACY_FORMAT = "headunit-revived-settings"
    private val SUPPORTED_FORMATS = setOf(FORMAT, LEGACY_FORMAT)
    const val VERSION = 1
    const val MIME_TYPE = "application/json"
    val DOCUMENT_PICKER_MIME_TYPES = arrayOf(MIME_TYPE)
    val IMPORT_MIME_TYPES = arrayOf(MIME_TYPE, "text/json", "text/plain")

    private val backupFileNamePattern = Regex("""(?:open-headunit|headunit-revived)-settings-\d{8}-\d{6}\.json""")

    data class ImportData(
        val values: Map<String, Any>,
        val skippedKeys: Int
    )

    data class ImportResult(
        val importedKeys: Int,
        val skippedKeys: Int,
        val changedKeys: Set<String>
    )

    data class ResetResult(
        val resetKeys: Int,
        val changedKeys: Set<String>
    )

    private enum class ValueType {
        BOOLEAN,
        INT,
        STRING,
        STRING_SET
    }

    private val backupKeys = linkedMapOf(
        "allow-devices" to ValueType.STRING_SET,
        "network-addresses" to ValueType.STRING_SET,
        "bt-address" to ValueType.STRING,
        "resolutionId" to ValueType.INT,
        "stretch_to_fill" to ValueType.BOOLEAN,
        "forced_scale" to ValueType.BOOLEAN,
        "hud_mirroring" to ValueType.BOOLEAN,
        "ui-scale-home-percent" to ValueType.INT,
        "ui-scale-settings-percent" to ValueType.INT,
        "mic-sample-rate" to ValueType.INT,
        "gps-navigation" to ValueType.BOOLEAN,
        "show-navigation-notifications" to ValueType.BOOLEAN,
        Settings.KEY_SYNC_MEDIA_SESSION_AA_METADATA to ValueType.BOOLEAN,
        "night-mode" to ValueType.INT,
        "night-mode-threshold-lux" to ValueType.INT,
        "night-mode-threshold-brightness" to ValueType.INT,
        "key-codes" to ValueType.STRING_SET,
        Settings.KEY_LOG_LEVEL to ValueType.INT,
        "view-mode" to ValueType.INT,
        Settings.KEY_SCREEN_ORIENTATION to ValueType.INT,
        "dpi-pixel-density" to ValueType.INT,
        "pixel-aspect-ratio-e4" to ValueType.INT,
        "fake_speed" to ValueType.BOOLEAN,
        "inset-left" to ValueType.INT,
        "inset-top" to ValueType.INT,
        "inset-right" to ValueType.INT,
        "inset-bottom" to ValueType.INT,
        "margin-left" to ValueType.INT,
        "margin-top" to ValueType.INT,
        "margin-right" to ValueType.INT,
        "margin-bottom" to ValueType.INT,
        "fullscreen-mode" to ValueType.INT,
        "force-software-decoding" to ValueType.BOOLEAN,
        "software-video-decoder" to ValueType.INT,
        "right-hand-drive" to ValueType.BOOLEAN,
        "vehicle-display-name" to ValueType.STRING,
        "vehicle-make" to ValueType.STRING,
        "vehicle-model" to ValueType.STRING,
        "vehicle-year" to ValueType.STRING,
        "vehicle-id" to ValueType.STRING,
        "head-unit-make" to ValueType.STRING,
        "head-unit-model" to ValueType.STRING,
        "wifi-connection-mode" to ValueType.INT,
        "video-codec" to ValueType.STRING,
        "fps-limit" to ValueType.INT,
        "auto-connect-last-session" to ValueType.BOOLEAN,
        "auto-connect-single-usb" to ValueType.BOOLEAN,
        "enable-audio-sink" to ValueType.BOOLEAN,
        "static-audio-focus" to ValueType.BOOLEAN,
        "separate-audio-streams" to ValueType.BOOLEAN,
        "mic-input-source" to ValueType.INT,
        "audio-latency-multiplier" to ValueType.INT,
        "audio-queue-capacity" to ValueType.INT,
        "use-aac-audio" to ValueType.BOOLEAN,
        "mic-echo-canceler" to ValueType.BOOLEAN,
        "mic-noise-suppressor" to ValueType.BOOLEAN,
        "mic-auto-gain-control" to ValueType.BOOLEAN,
        "attach_hw_dsp_equalizer" to ValueType.BOOLEAN,
        "use-native-ssl" to ValueType.BOOLEAN,
        "auto-start-self-mode" to ValueType.BOOLEAN,
        "auto-start-on-usb" to ValueType.BOOLEAN,
        "auto-start-on-boot" to ValueType.BOOLEAN,
        "auto-start-on-screen-on" to ValueType.BOOLEAN,
        "auto-start-on-wifi" to ValueType.BOOLEAN,
        "auto-start-wifi-ssid" to ValueType.STRING,
        "listen-for-usb-devices" to ValueType.BOOLEAN,
        "reopen-on-reconnection" to ValueType.BOOLEAN,
        "auto-connect-priority-order" to ValueType.STRING,
        "auto-start-bt-macs" to ValueType.STRING_SET,
        "auto-start-bt-name" to ValueType.STRING,
        "app-language" to ValueType.STRING,
        "media-volume-offset" to ValueType.INT,
        "assistant-volume-offset" to ValueType.INT,
        "navigation-volume-offset" to ValueType.INT,
        "night-mode-manual-start" to ValueType.INT,
        "night-mode-manual-end" to ValueType.INT,
        "app-theme-threshold-lux" to ValueType.INT,
        "app-theme-threshold-brightness" to ValueType.INT,
        "app-theme-manual-start" to ValueType.INT,
        "app-theme-manual-end" to ValueType.INT,
        "show-fps-counter" to ValueType.BOOLEAN,
        "monochrome-icons" to ValueType.BOOLEAN,
        "use-extreme-dark-mode" to ValueType.BOOLEAN,
        "use-gradient-background" to ValueType.BOOLEAN,
        "aa-monochrome-enabled" to ValueType.BOOLEAN,
        "aa-desaturation-level" to ValueType.INT,
        "app-theme" to ValueType.INT,
        "enable-rotary" to ValueType.BOOLEAN,
        "kill-on-disconnect" to ValueType.BOOLEAN,
        "auto-enable-hotspot" to ValueType.BOOLEAN,
        "wait-for-wifi-before-wifi-direct" to ValueType.BOOLEAN,
        "wait-for-wifi-timeout" to ValueType.INT,
        "helper-connection-strategy" to ValueType.INT,
        "bluetooth-manager-service-name" to ValueType.STRING,
        "use-libusb" to ValueType.BOOLEAN,
        // Custom loading screen display options. The picked image/video is copied into the app's
        // private storage, so the media path/type cannot be restored on another install and are not
        // backed up (only these display preferences are, and apply once the media is added again).
        "loading-screen-show-text" to ValueType.BOOLEAN,
        "loading-screen-keep-aspect-ratio" to ValueType.BOOLEAN,
        "loading-screen-loop-video" to ValueType.BOOLEAN,
        "loading-screen-scale-percent" to ValueType.INT,
        // Wireless hotspot host credentials and manual BSSID override.
        "hotspot-ssid" to ValueType.STRING,
        "hotspot-password" to ValueType.STRING,
        "static-bssid" to ValueType.STRING,
        // Touch calibration fix and toast visibility.
        "use_measured_touch_surface" to ValueType.BOOLEAN,
        "show-toast-messages" to ValueType.BOOLEAN,
        "usb-blacklist" to ValueType.STRING_SET
    )

    private val projectionRestartKeys = setOf(
        "resolutionId",
        "video-codec",
        "fps-limit",
        "dpi-pixel-density",
        "pixel-aspect-ratio-e4",
        "force-software-decoding",
        "software-video-decoder",
        "enable-rotary",
        "enable-audio-sink",
        "static-audio-focus",
        "separate-audio-streams",
        "use-aac-audio",
        "attach_hw_dsp_equalizer",
        "audio-latency-multiplier",
        "audio-queue-capacity",
        "use-native-ssl",
        "inset-left",
        "inset-top",
        "inset-right",
        "inset-bottom",
        "wifi-connection-mode",
        "use-libusb"
    )

    fun defaultFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "$FORMAT-$timestamp.json"
    }

    fun exportFromContext(context: Context): String {
        val prefs = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        return exportToJson(prefs.all, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
    }

    fun exportToJson(
        preferences: Map<String, *>,
        appVersionName: String,
        appVersionCode: Int
    ): String {
        val settingsJson = JSONObject()
        backupKeys.forEach { (key, type) ->
            val value = preferences[key] ?: return@forEach
            putExportValue(settingsJson, key, type, value)
        }

        return JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put(
                "app",
                JSONObject()
                    .put("versionName", appVersionName)
                    .put("versionCode", appVersionCode)
            )
            .put("settings", settingsJson)
            .toString(2)
    }

    fun parseImportJson(json: String): ImportData {
        val root = JSONObject(json)
        if (root.optString("format") !in SUPPORTED_FORMATS) {
            throw IllegalArgumentException("Unsupported settings backup format")
        }
        if (root.optInt("version", VERSION) > VERSION) {
            throw IllegalArgumentException("Unsupported settings backup version")
        }

        val settings = root.optJSONObject("settings")
            ?: throw IllegalArgumentException("Settings backup is missing settings")
        val parsedValues = linkedMapOf<String, Any>()
        var skippedKeys = 0
        val keys = settings.keys()

        while (keys.hasNext()) {
            val key = keys.next()
            val type = backupKeys[key]
            if (type == null) {
                skippedKeys++
                continue
            }

            val value = parseImportValue(settings.get(key), type)
            if (value == null) {
                skippedKeys++
            } else {
                parsedValues[key] = value
            }
        }

        return ImportData(parsedValues, skippedKeys)
    }

    fun importFromUri(context: Context, uri: Uri): ImportResult {
        val json = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8).use { reader ->
            reader?.readText()
        } ?: throw IllegalArgumentException("Unable to open settings backup")

        return importFromJson(context, json)
    }

    fun importFromFile(context: Context, file: File): ImportResult {
        return importFromJson(context, file.readText(Charsets.UTF_8))
    }

    fun importFromJson(context: Context, json: String): ImportResult {
        val prefs = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val importData = parseImportJson(json)
        val changedKeys = importData.values.filter { (key, value) -> prefs.all[key] != value }.keys

        applyValues(prefs, importData.values)
        syncImportedSettings(context)

        return ImportResult(
            importedKeys = importData.values.size,
            skippedKeys = importData.skippedKeys,
            changedKeys = changedKeys
        )
    }

    fun resetFromContext(context: Context): ResetResult {
        val prefs = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val result = resetPreferencesToDefaults(prefs)
        syncImportedSettings(context)
        return result
    }

    fun resetPreferencesToDefaults(prefs: SharedPreferences): ResetResult {
        val existingPreferences = prefs.all
        val changedKeys = backupKeys.keys
            .filter { key -> existingPreferences.containsKey(key) }
            .toCollection(linkedSetOf())

        if (changedKeys.isEmpty()) {
            return ResetResult(resetKeys = 0, changedKeys = emptySet())
        }

        val editor = prefs.edit()
        changedKeys.forEach { key -> editor.remove(key) }
        if (!editor.commit()) {
            throw IllegalStateException("Unable to reset settings")
        }

        return ResetResult(
            resetKeys = changedKeys.size,
            changedKeys = changedKeys
        )
    }

    fun exportToUri(context: Context, uri: Uri) {
        val json = exportFromContext(context)
        val output = context.contentResolver.openOutputStream(uri)
            ?: throw IllegalArgumentException("Unable to open export destination")

        output.bufferedWriter(Charsets.UTF_8).use { writer ->
            writer.write(json)
        }
    }

    fun exportToLegacyFile(context: Context): File {
        val directory = context.getExternalFilesDir(null) ?: context.cacheDir
        val file = File(directory, defaultFileName())
        return writeBackupFile(file, exportFromContext(context))
    }

    @Suppress("DEPRECATION")
    fun exportToDownloadsFile(context: Context): File {
        val directory = downloadsDirectory()
        val file = File(directory, defaultFileName())
        return writeBackupFile(file, exportFromContext(context))
    }

    @Suppress("DEPRECATION")
    fun downloadsDirectory(): File {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    }

    fun canAccessDownloadsDirectory(sdkInt: Int = Build.VERSION.SDK_INT): Boolean {
        return sdkInt < Build.VERSION_CODES.Q
    }

    fun writeBackupFile(file: File, json: String): File {
        file.parentFile?.mkdirs()
        file.writeText(json, Charsets.UTF_8)
        return file
    }

    fun findBackupFiles(directories: List<File?>): List<File> {
        return directories
            .filterNotNull()
            .distinctBy { it.absolutePath }
            .flatMap { directory ->
                runCatching { directory.listFiles() }.getOrNull()?.filter { file ->
                    file.isFile && backupFileNamePattern.matches(file.name)
                } ?: emptyList()
            }
            .sortedWith(compareByDescending<File> { it.name }.thenByDescending { it.lastModified() })
    }

    fun backupSearchDirectories(appBackupDirectory: File?, cacheDirectory: File?, downloadsDirectory: File?): List<File?> {
        return listOf(appBackupDirectory, downloadsDirectory, cacheDirectory)
    }

    fun requiresProjectionRestart(changedKeys: Set<String>): Boolean {
        return changedKeys.any { it in projectionRestartKeys }
    }

    private fun putExportValue(settingsJson: JSONObject, key: String, type: ValueType, value: Any) {
        when (type) {
            ValueType.BOOLEAN -> if (value is Boolean) settingsJson.put(key, value)
            ValueType.INT -> if (value is Int) settingsJson.put(key, value)
            ValueType.STRING -> if (value is String) settingsJson.put(key, value)
            ValueType.STRING_SET -> {
                val set = value as? Set<*> ?: return
                val strings = set.mapNotNull { it as? String }
                if (strings.size == set.size) {
                    settingsJson.put(key, JSONArray(strings.sorted()))
                }
            }
        }
    }

    private fun parseImportValue(value: Any, type: ValueType): Any? {
        return when (type) {
            ValueType.BOOLEAN -> value as? Boolean
            ValueType.INT -> parseInt(value)
            ValueType.STRING -> value as? String
            ValueType.STRING_SET -> parseStringSet(value)
        }
    }

    private fun parseInt(value: Any): Int? {
        val longValue = when (value) {
            is Int -> return value
            is Long -> value
            is Short -> value.toLong()
            is Byte -> value.toLong()
            is BigInteger -> if (value.bitLength() <= Int.SIZE_BITS - 1) value.toLong() else return null
            is BigDecimal -> runCatching { value.longValueExact() }.getOrNull() ?: return null
            is Double -> {
                if (value.isNaN() || value.isInfinite() || value % 1.0 != 0.0) return null
                value.toLong()
            }
            is Float -> {
                if (value.isNaN() || value.isInfinite() || value % 1.0f != 0.0f) return null
                value.toLong()
            }
            else -> return null
        }
        return if (longValue in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) longValue.toInt() else null
    }

    private fun parseStringSet(value: Any): Set<String>? {
        val array = value as? JSONArray ?: return null
        val result = sortedSetOf<String>()
        for (index in 0 until array.length()) {
            val item = array.opt(index)
            if (item !is String) return null
            result.add(item)
        }
        return result
    }

    private fun applyValues(prefs: SharedPreferences, values: Map<String, Any>) {
        val editor = prefs.edit()
        values.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Int -> editor.putInt(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.mapNotNull { it as? String }.toSet())
            }
        }
        if (!editor.commit()) {
            throw IllegalStateException("Unable to save imported settings")
        }
    }

    private fun syncImportedSettings(context: Context) {
        val settings = Settings(context)
        Settings.syncAutoStartOnBootToDeviceStorage(context, settings.autoStartOnBoot)
        Settings.syncAutoStartOnScreenOnToDeviceStorage(context, settings.autoStartOnScreenOn)
        Settings.syncAutoStartOnUsbToDeviceStorage(context, settings.autoStartOnUsb)
        Settings.syncAutoStartOnWifiToDeviceStorage(context, settings.autoStartOnWifi)
        Settings.syncAutoStartWifiSsidToDeviceStorage(context, settings.autoStartWifiSsid)
        Settings.syncListenForUsbDevicesToDeviceStorage(context, settings.listenForUsbDevices)
        Settings.syncAutoStartBtMacsToDeviceStorage(context, settings.autoStartBluetoothDeviceMacs)
        Settings.setUsbAttachedActivityEnabled(context, settings.listenForUsbDevices)
    }
}
