package com.andrerinas.openheadunit.utils

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.WirelessRearmPolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.esp32.Esp32RelaySession

/** Applies restored preferences independently of the screen that imported them. */
object SettingsRestoreHandler {
    data class Snapshot(
        val wireless: WirelessRearmPolicy.Config,
        val appearance: Appearance
    )

    data class Appearance(
        val appLanguage: String,
        val uiScaleSettingsPercent: Int,
        val appTheme: Settings.AppTheme,
        val useExtremeDarkMode: Boolean,
        val useGradientBackground: Boolean,
        val screenOrientation: Settings.ScreenOrientation,
        val hudMirroring: Boolean
    )

    fun capture(context: Context, settings: Settings): Snapshot = Snapshot(
        WirelessRearmPolicy.capture(settings, Esp32RelaySession.hasSavedEsp32Relay(context)),
        appearance(settings)
    )

    private fun appearance(settings: Settings) = Appearance(
        settings.appLanguage,
        settings.uiScaleSettingsPercent,
        settings.appTheme,
        settings.useExtremeDarkMode,
        settings.useGradientBackground,
        settings.screenOrientation,
        settings.hudMirroring
    )

    fun requiresRecreation(before: Snapshot, settings: Settings): Boolean =
        before.appearance != appearance(settings)

    /** Returns whether the settings screen needs recreating; navigation belongs to the caller. */
    @MainThread
    fun apply(context: Context, settings: Settings, before: Snapshot, changedKeys: Set<String>): Boolean {
        val recreate = requiresRecreation(before, settings)
        val after = WirelessRearmPolicy.capture(settings, Esp32RelaySession.hasSavedEsp32Relay(context))
        if (WirelessRearmPolicy.requiresRearm(before.wireless, after)) {
            AapService.applyWirelessSettings(context,
                startWireless = settings.wifiConnectionMode != WifiLauncherMode.MANUAL && settings.showsWifi())
        }
        context.sendBroadcast(Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE).setPackage(context.packageName))
        if (SettingsBackupManager.requiresProjectionRestart(changedKeys) && App.provide(context).commManager.isConnected) {
            ToastUtils.showToast(context, context.getString(R.string.stopping_service), Toast.LENGTH_SHORT, force = true)
            ContextCompat.startForegroundService(context,
                Intent(context, AapService::class.java).setAction(AapService.ACTION_STOP_SERVICE))
        }
        AppThemeManager.reapply(context, settings)
        return recreate
    }
}
