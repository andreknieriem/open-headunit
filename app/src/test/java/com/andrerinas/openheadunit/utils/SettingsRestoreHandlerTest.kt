package com.andrerinas.openheadunit.utils

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.andrerinas.openheadunit.aap.AapService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class SettingsRestoreHandlerTest {
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        val broadcasts = mutableListOf<Intent>()
        val services = mutableListOf<Intent>()

        override fun sendBroadcast(intent: Intent) {
            broadcasts.add(intent)
        }

        override fun startService(service: Intent): ComponentName {
            services.add(service)
            return ComponentName(this, AapService::class.java)
        }
    }
    private val settings = Settings(context)
    private val prefs = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)

    @Test fun `unchanged import updates theme and night mode without rearming wireless`() {
        val before = SettingsRestoreHandler.capture(context, settings)
        val themeVersion = AppThemeManager.themeVersion.value
        assertFalse(SettingsRestoreHandler.apply(context, settings, before, emptySet()))
        assertTrue(context.services.isEmpty())
        assertEquals(listOf(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE), context.broadcasts.map { it.action })
        assertEquals(context.packageName, context.broadcasts.single().`package`)
        assertNotEquals(themeVersion, AppThemeManager.themeVersion.value)
    }

    @Test fun `wireless changes use the same service handoff as settings imports`() {
        settings.connectionModes = setOf(Settings.ConnectionMode.WIFI)
        val before = SettingsRestoreHandler.capture(context, settings)
        settings.externalBtZbtTransport = !settings.externalBtZbtTransport
        assertFalse(SettingsRestoreHandler.apply(context, settings, before, setOf("external-bt-zbt-transport")))
        assertEquals(listOf(AapService.ACTION_START_WIRELESS), context.services.map { it.action })
        assertTrue(context.services.single().getBooleanExtra(AapService.EXTRA_SETTINGS_REARM, false))
    }

    @Test fun `restoring automatic Bluetooth startup rearms the wireless launcher`() {
        settings.connectionModes = setOf(Settings.ConnectionMode.WIFI)
        settings.nativeAaEnableBluetooth = false
        val before = SettingsRestoreHandler.capture(context, settings)
        assertFalse(before.wireless.nativeAaEnableBluetooth)
        settings.nativeAaEnableBluetooth = true
        assertTrue(SettingsRestoreHandler.capture(context, settings).wireless.nativeAaEnableBluetooth)
        assertFalse(SettingsRestoreHandler.apply(context, settings, before, setOf("native-aa-enable-bluetooth")))
        assertEquals(listOf(AapService.ACTION_START_WIRELESS), context.services.map { it.action })
        assertTrue(context.services.single().getBooleanExtra(AapService.EXTRA_SETTINGS_REARM, false))
    }

    @Test fun `removing wireless connection mode stops instead of starting the launcher`() {
        settings.connectionModes = setOf(Settings.ConnectionMode.WIFI)
        val before = SettingsRestoreHandler.capture(context, settings)
        settings.connectionModes = setOf(Settings.ConnectionMode.USB)
        SettingsRestoreHandler.apply(context, settings, before, setOf("connection-modes"))
        assertEquals(listOf(AapService.ACTION_STOP_WIRELESS), context.services.map { it.action })
    }

    @Test fun `all existing appearance triggers still require settings screen recreation`() {
        val changes = mapOf<String, Any>(
            "app-language" to "ru",
            "ui-scale-settings-percent" to 125,
            "app-theme" to Settings.AppTheme.DARK.value,
            "use-extreme-dark-mode" to true,
            "use-gradient-background" to !settings.useGradientBackground,
            Settings.KEY_SCREEN_ORIENTATION to 1,
            "hud_mirroring" to true
        )
        for ((key, value) in changes) {
            prefs.edit().clear().commit()
            val before = SettingsRestoreHandler.capture(context, settings)
            val editor = prefs.edit()
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
            }
            editor.commit()
            assertTrue(key, SettingsRestoreHandler.requiresRecreation(before, settings))
        }
    }
}
