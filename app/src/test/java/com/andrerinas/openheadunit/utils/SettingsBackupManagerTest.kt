package com.andrerinas.openheadunit.utils

import android.app.Application
import android.content.Context
import com.andrerinas.openheadunit.input.InputAction
import com.andrerinas.openheadunit.input.LocalAction
import com.andrerinas.openheadunit.main.SettingsPendingCopy
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.mockito.Mockito.*

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SettingsBackupManagerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
    private val scanPrefs get() = context.getSharedPreferences("wifi-scan-option", Context.MODE_PRIVATE)

    @Test fun `every preference read by the settings screen has a backup registration`() {
        val trackedPrefs = spy(prefs)
        val trackedContext = mock(Context::class.java)
        `when`(trackedContext.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)).thenReturn(trackedPrefs)
        SettingsPendingCopy().loadFrom(Settings(trackedContext))
        val readKeys = mockingDetails(trackedPrefs).invocations
            .filter { it.method.name.startsWith("get") && it.arguments.firstOrNull() is String }
            .map { it.arguments[0] as String }.toSet()
        val migrationKeys = setOf(
            "wifi-launcher-mode", "native-aa-wireless", "debug-force-p2p-band-24",
            "p2p-legacy-5ghz", "p2p-legacy-5ghz-upper", "stand-down-station-for-wifi-direct",
            "start-in-fullscreen-mode", "auto-start-bt-mac"
        )
        assertEquals(emptySet<String>(), readKeys - SettingsBackupManager.backupKeys.keys - migrationKeys)
    }

    @Test fun `every registered type survives JSON export and parsing`() {
        val values = SettingsBackupManager.backupKeys.mapValues { (_, type) ->
            when (type) {
                SettingsBackupManager.ValueType.BOOLEAN -> true
                SettingsBackupManager.ValueType.INT -> 37
                SettingsBackupManager.ValueType.LONG -> (-122.123456789).toRawBits()
                SettingsBackupManager.ValueType.STRING -> "saved value"
                SettingsBackupManager.ValueType.STRING_SET -> setOf("second", "first")
            }
        }
        val parsed = SettingsBackupManager.parseImportJson(SettingsBackupManager.exportToJson(values, "test", 1))
        assertEquals(values, parsed.values)
        assertEquals(0, parsed.skippedKeys)
    }

    @Test fun `new user settings restore to their real preference stores`() {
        val values = linkedMapOf<String, Any>(
            "connection-modes" to setOf("usb", "wifi"),
            "left-two-finger-gesture" to 4,
            "gesture-edge-zone" to 2,
            "dim-opacity-percent" to 70,
            "night-overlay-show-track" to false,
            "ui-scale-exit-dialog-percent" to 125,
            "home-button-scale-percent" to 80,
            "custom-usb-button-color" to -1234567,
            "home-favorite-apps" to "[]",
            "show-home-widgets" to false,
            "home-surface-opacity-percent" to 75,
            "show-home-clock" to true,
            "show-home-speed" to true,
            "auto-resume-playback-on-reconnect" to true,
            "raise-projection-during-call" to false,
            "car-signal-invert" to true,
            "dpi-floor-override" to true,
            "hide-battery-level" to true,
            "use-aaudio-output" to true,
            "native-driver-selection-timeout" to 20,
            "external-bt-zbt-transport" to true,
            "device-specific-head-unit-model" to "adayo_haval",
            "device-specific-pause-projection-on-reverse" to true,
            "use-fixed-sun-location" to true,
            "fixed-sun-latitude" to (51.123456789).toRawBits(),
            "fixed-sun-longitude" to (-122.123456789).toRawBits(),
            "geofence-locations" to "[]",
            "location-outside-night" to true,
            "wifi-scan-enabled" to true,
            "wifi-scan-fyt-auto-start" to true
        )
        val json = SettingsBackupManager.exportToJson(values, "test", 1)
        val result = SettingsBackupManager.importFromJson(context, json, applyTheme = false)
        assertEquals(values.size, result.importedKeys)
        assertEquals(values.keys, result.changedKeys)
        values.filterKeys { !it.startsWith("wifi-scan-") }.forEach { (key, value) ->
            assertEquals(key, value, prefs.all[key])
        }
        assertTrue(scanPrefs.getBoolean("enabled", false))
        assertTrue(scanPrefs.getBoolean("fyt-auto-start", false))
        assertFalse(prefs.contains("wifi-scan-enabled"))
        val exported = SettingsBackupManager.parseImportJson(SettingsBackupManager.exportFromContext(context))
        assertEquals(values, exported.values.filterKeys { it in values })
        // Reading the legacy Bluetooth auto-start preference materializes its empty default.
        assertEquals(setOf("auto-start-bt-macs"), exported.values.keys - values.keys)
        assertTrue(Settings(context).showHomeClock)
        assertEquals(51.123456789, Settings(context).fixedSunriseLatitude, 0.0)
        assertEquals(-122.123456789, Settings(context).fixedSunriseLongitude, 0.0)
    }

    @Test fun `an older backup's key mappings and gestures replace the newer ones`() {
        prefs.edit().clear()
            .putStringSet(Settings.KEY_BINDINGS, setOf("140=local:dim"))
            .putString("gesture-action-left", "local:overlay")
            .putString("gesture-action-right", "local:dim")
            .commit()
        val json = SettingsBackupManager.exportToJson(
            mapOf("key-codes" to setOf("87-131"), "left-two-finger-gesture" to 0), "test", 1
        )
        SettingsBackupManager.importFromJson(context, json, applyTheme = false)
        val settings = Settings(context)
        assertEquals(mapOf(131 to InputAction.AaKey(87)), settings.keyBindings)
        assertEquals(InputAction.None, settings.gestureAction(Settings.GestureSlot.LEFT))
        // Not in the backup: kept.
        assertEquals(InputAction.Local(LocalAction.DIM_SCREEN), settings.gestureAction(Settings.GestureSlot.RIGHT))
    }

    @Test fun `a newer backup's bindings win over its legacy key mappings`() {
        assertEquals(
            emptySet<String>(),
            SettingsBackupManager.supersededKeys(setOf("key-codes", Settings.KEY_BINDINGS, "left-two-finger-gesture", "gesture-action-left"))
        )
    }

    @Test fun `legacy backup merges without resetting missing user preferences`() {
        prefs.edit().putInt("dim-opacity-percent", 80).putBoolean("show-home-clock", true).commit()
        val json = """{"format":"headunit-revived-settings","version":1,"settings":{"fps-limit":30}}"""
        val result = SettingsBackupManager.importFromJson(context, json, applyTheme = false)
        assertEquals(setOf("fps-limit"), result.changedKeys)
        assertEquals(30, prefs.getInt("fps-limit", 0))
        assertEquals(80, prefs.getInt("dim-opacity-percent", 0))
        assertTrue(prefs.getBoolean("show-home-clock", false))
        assertFalse(prefs.contains("night-overlay-background"))
    }

    @Test fun `runtime state media widget bindings permissions and ESP32 are excluded`() {
        val excluded = mapOf(
            "cached-surface-width" to 1920,
            "has-accepted-disclaimer" to true,
            "onboarding-version" to 1,
            "wifi-restart-root-granted" to true,
            "wifi-restart-adb-verified" to true,
            "connection-issue-bt-silent" to true,
            "home-background-image-path" to "/private/image",
            "loading-screen-media-path" to "/private/video",
            "home-widgets" to "[123]",
            "home-widget-pages" to "[[123]]",
            "home-widget-host-id" to 123
        )
        assertTrue(SettingsBackupManager.parseImportJson(
            SettingsBackupManager.exportToJson(excluded, "test", 1)
        ).values.isEmpty())
        context.getSharedPreferences("Esp32Relay", Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", true).putString("name", "relay").commit()
        assertTrue(SettingsBackupManager.parseImportJson(
            SettingsBackupManager.exportFromContext(context)
        ).values.isEmpty())
    }

    @Test fun `long coordinate bits remain exact and reject lossy or overflowing input`() {
        for (value in listOf(Long.MIN_VALUE, Long.MAX_VALUE, (-0.0).toRawBits(), (89.123456789).toRawBits())) {
            val json = SettingsBackupManager.exportToJson(mapOf("fixed-sun-latitude" to value), "test", 1)
            assertEquals(value, SettingsBackupManager.parseImportJson(json).values["fixed-sun-latitude"])
        }
        for (invalid in listOf("9223372036854775808", "-9223372036854775809", "1.25", "\"123\"", "true")) {
            val parsed = SettingsBackupManager.parseImportJson(
                """{"format":"open-headunit-settings","version":1,"settings":{"fixed-sun-latitude":$invalid}}"""
            )
            assertTrue(parsed.values.isEmpty())
            assertEquals(1, parsed.skippedKeys)
        }
    }

    @Test fun `unknown or mistyped keys are counted and malformed backup writes nothing`() {
        val parsed = SettingsBackupManager.parseImportJson(
            """{"format":"open-headunit-settings","version":1,"settings":{"show-home-clock":true,"unknown":7,"dim-opacity-percent":"70"}}"""
        )
        assertEquals(mapOf("show-home-clock" to true), parsed.values)
        assertEquals(2, parsed.skippedKeys)
        prefs.edit().putInt("dim-opacity-percent", 80).commit()
        val before = prefs.all
        assertThrows(IllegalArgumentException::class.java) {
            SettingsBackupManager.importFromJson(context,
                """{"format":"unrelated","settings":{"dim-opacity-percent":50}}""", applyTheme = false)
        }
        assertEquals(before, prefs.all)
    }

    @Test fun `reset includes new keys and scan preferences but preserves unrelated state`() {
        prefs.edit().putInt("dim-opacity-percent", 80)
            .putLong("fixed-sun-latitude", (51.0).toRawBits())
            .putString("device-specific-head-unit-model", "adayo_haval")
            .putBoolean("has-accepted-disclaimer", true).commit()
        scanPrefs.edit().putBoolean("enabled", true).putBoolean("fyt-auto-start", true)
            .putString("unrelated", "keep").commit()
        val esp = context.getSharedPreferences("Esp32Relay", Context.MODE_PRIVATE)
        esp.edit().putBoolean("enabled", true).commit()
        val result = SettingsBackupManager.resetFromContext(context)
        assertEquals(5, result.resetKeys)
        assertEquals(setOf("dim-opacity-percent", "fixed-sun-latitude", "device-specific-head-unit-model",
            "wifi-scan-enabled", "wifi-scan-fyt-auto-start"), result.changedKeys)
        assertTrue(prefs.getBoolean("has-accepted-disclaimer", false))
        assertFalse(scanPrefs.contains("enabled"))
        assertEquals("keep", scanPrefs.getString("unrelated", null))
        assertTrue(esp.getBoolean("enabled", false))
    }

    @Test fun `unchanged import does not report changes and projection options request restart`() {
        val json = SettingsBackupManager.exportToJson(mapOf("hide-clock" to true), "test", 1)
        assertTrue(SettingsBackupManager.requiresProjectionRestart(
            SettingsBackupManager.importFromJson(context, json, applyTheme = false).changedKeys))
        assertTrue(SettingsBackupManager.importFromJson(context, json, applyTheme = false).changedKeys.isEmpty())
        assertFalse(SettingsBackupManager.requiresProjectionRestart(setOf("show-home-clock")))
        assertEquals(1, JSONObject(json).getJSONObject("settings").length())
    }
}
