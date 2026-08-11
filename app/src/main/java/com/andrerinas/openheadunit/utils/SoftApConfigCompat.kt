package com.andrerinas.openheadunit.utils

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import com.andrerinas.openheadunit.connection.wifi.modes.native.ApBand
import com.andrerinas.openheadunit.connection.wifi.modes.native.SoftApBandPolicy

/**
 * Compatibility helper for Android 11+ (API 30) hotspot configuration using
 * the SoftApConfiguration API via reflection.
 */
object SoftApConfigCompat {
    private const val TAG = "SoftApConfigCompat"

    /**
     * Enables a Wi‑Fi hotspot with a configurable SSID and a default WPA2‑PSK password.
     * Returns true if the hotspot was successfully configured, false otherwise.
     *
     * The caller must ensure that Wi‑Fi is disabled beforehand (handled by HotspotManager).
     */
    fun enableHotspot(context: Context, enabled: Boolean, band: ApBand = ApBand.BAND_5GHZ): Boolean {
        if (!enabled) return false // disabling handled by legacy path
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return configureLegacyBand(context, band)
        }

        // Ensure location permission (required for hotspot config on API 30+)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val hasLocation = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!hasLocation) {
                AppLog.e("SoftApConfigCompat: Missing ACCESS_FINE_LOCATION permission – cannot configure hotspot")
                return false
            }
        }

        // Log entry for debugging
        AppLog.i("SoftApConfigCompat: enableHotspot called (API=${Build.VERSION.SDK_INT})")
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val settings = Settings(context)

            // Build SoftApConfiguration via reflection, from the one already on the device where
            // there is one. This access point is usually the user's, and it is the one whose name
            // and passphrase have already gone out to the phone — rewriting either here renames
            // somebody's hotspot behind their back and invalidates credentials that were correct
            // when they were sent. The same reasoning is already spelled out on the pre-R path
            // below; only this branch was inventing values.
            val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
            val existing = readSoftApConfiguration(wifiManager)
            if (existing == null && settings.hotspotSsid.isEmpty()) {
                // Nothing to copy and nothing the user named: an empty builder would hand the
                // framework a configuration with no SSID at all, which is how a working access
                // point loses its name. The caller starts the hotspot regardless of this; it only
                // goes up on whatever band the device chooses.
                AppLog.w("SoftApConfigCompat: cannot read this device's access point configuration and no name is set in the app, so the band cannot be requested without risking the existing one. Starting the hotspot unconfigured.")
                return false
            }
            val builder = if (existing != null) {
                builderClass
                    .getDeclaredConstructor(Class.forName("android.net.wifi.SoftApConfiguration"))
                    .newInstance(existing)
            } else {
                builderClass.getDeclaredConstructor().newInstance()
            }

            // The user's explicit overrides only, and nothing invented in their absence. The name
            // and passphrase this route hands the phone are whatever the device's own access point
            // has — SoftApCredentialsProvider reads them back rather than assuming — so a made-up
            // pair here would rename a working hotspot to something nobody was told about.
            // hotspotSsid, not autoStartWifiSsid: that one names the network this unit joins as a
            // client, which has never had anything to do with the one it hosts.
            val ssid = settings.hotspotSsid
            if (ssid.isNotEmpty()) {
                builderClass.getMethod("setSsid", String::class.java).invoke(builder, ssid)
            }
            val password = settings.hotspotPassword
            if (password.isNotEmpty()) {
                // 1 = SECURITY_TYPE_WPA2_PSK
                builderClass.getMethod("setPassphrase", String::class.java, Int::class.javaPrimitiveType)
                    .invoke(builder, password, 1)
            }

            // Ask for the band the link needs. Not fatal if the platform refuses the call: the
            // caller confirms the access point afterwards and retries on the other band, so a
            // failure here just means this attempt runs on whatever the framework picks.
            try {
                builderClass.getMethod("setBand", Int::class.javaPrimitiveType)
                    .invoke(builder, SoftApBandPolicy.softApConfigurationBand(band))
                AppLog.i("SoftApConfigCompat: requesting ${SoftApBandPolicy.describe(band)} for the access point.")
            } catch (e: Exception) {
                AppLog.w("SoftApConfigCompat: could not request ${SoftApBandPolicy.describe(band)} (${e.message}); the framework will choose the band.")
            }

            // Build the configuration object
            val buildMethod = builderClass.getMethod("build")
            val softApConfig = buildMethod.invoke(builder)

            // Apply the configuration via WifiManager#setSoftApConfiguration
            val setConfigMethod = wifiManager.javaClass.getMethod(
                "setSoftApConfiguration",
                Class.forName("android.net.wifi.SoftApConfiguration")
            )
            val result = setConfigMethod.invoke(wifiManager, softApConfig) as Boolean
            AppLog.i("SoftApConfiguration applied (SSID=${ssid.ifEmpty { "unchanged" }}). Success=$result")
            result
        } catch (e: Exception) {
            // Expected on plenty of head units, which refuse setSoftApConfiguration() outright with
            // "App not allowed to read or update stored WiFi Ap config". Not fatal and not worth an
            // error: the caller has other start paths and confirms the access point afterwards.
            AppLog.w("SoftApConfigCompat: could not configure the access point (${e.message}); leaving it as the device has it and starting it anyway.")
            false
        }
    }

    /** The access point this device already has configured, or null if it will not say. */
    private fun readSoftApConfiguration(wifiManager: WifiManager): Any? = try {
        wifiManager.javaClass.getMethod("getSoftApConfiguration").invoke(wifiManager)
    } catch (e: Exception) {
        AppLog.d("SoftApConfigCompat: could not read the current access point configuration: ${e.message}")
        null
    }

    /**
     * Sets the band on pre-Android-11 devices, where the access point is described by a
     * `WifiConfiguration` and its band lives in the hidden `apBand` field.
     *
     * Reads the current configuration and writes it back with only that field changed, so the
     * SSID and passphrase the user (or the vendor) set stay as they are — this path never owned
     * them, and inventing values here would rename someone's hotspot behind their back.
     */
    private fun configureLegacyBand(context: Context, band: ApBand): Boolean = try {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val config = wifiManager.javaClass.getMethod("getWifiApConfiguration").invoke(wifiManager)
        if (config == null) {
            AppLog.w("SoftApConfigCompat: no existing AP configuration to set the band on.")
            false
        } else {
            val field = config.javaClass.getField("apBand")
            field.setInt(config, SoftApBandPolicy.legacyApBand(band))
            val applied = wifiManager.javaClass.getMethod(
                "setWifiApConfiguration", android.net.wifi.WifiConfiguration::class.java
            ).invoke(wifiManager, config) as? Boolean ?: false
            AppLog.i("SoftApConfigCompat: requested ${SoftApBandPolicy.describe(band)} via WifiConfiguration.apBand. Success=$applied")
            applied
        }
    } catch (e: Exception) {
        // Expected on plenty of ROMs — the field is hidden and some vendors drop it entirely.
        AppLog.w("SoftApConfigCompat: could not set ${SoftApBandPolicy.describe(band)} on this API level: ${e.message}")
        false
    }
}
