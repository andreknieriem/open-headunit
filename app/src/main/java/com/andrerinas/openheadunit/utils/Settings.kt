package com.andrerinas.openheadunit.utils

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import com.andrerinas.openheadunit.aap.MediaKeyRoutingPolicy
import com.andrerinas.openheadunit.aap.VideoFaultInjector
import com.andrerinas.openheadunit.decoder.DeviceMemoryProfile
import com.andrerinas.openheadunit.aap.PlaybackFocusPolicy
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.app.UsbAttachedActivity
import com.andrerinas.openheadunit.connection.UsbDeviceCompat
import com.andrerinas.openheadunit.connection.wifi.modes.helper.HelperStrategy
import com.andrerinas.openheadunit.connection.wifi.modes.native.NativeStrategy
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode

class Settings(private val context: Context) {

    private val _prefs: SharedPreferences? by lazy {
        try {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        } catch (e: Exception) {
            null
        }
    }

    private val prefs: SharedPreferences
        get() = _prefs ?: throw IllegalStateException("SharedPreferences in credential encrypted storage are not available until after user is unlocked")

    fun isConnectingDevice(deviceCompat: UsbDeviceCompat): Boolean {
        val allowDevices = prefs.getStringSet("allow-devices", null) ?: return false
        return allowDevices.contains(deviceCompat.uniqueName)
    }

    var isAdvancedSettingsActive: Boolean
        get() = prefs.getBoolean("advanced-settings-active", false)
        set(value) { prefs.edit().putBoolean("advanced-settings-active", value).apply() }

    var allowedDevices: Set<String>
        get() = prefs.getStringSet("allow-devices", null)?.toSet() ?: emptySet()
        set(devices) {
            prefs.edit().putStringSet("allow-devices", devices).apply()
        }

    var networkAddresses: Set<String>
        get() = prefs.getStringSet("network-addresses", null)?.toSet() ?: emptySet()
        set(addrs) {
            prefs.edit().putStringSet("network-addresses", addrs).apply()
        }

    var bluetoothAddress: String
        get() = prefs.getString("bt-address", "")!!
        set(value) = prefs.edit().putString("bt-address", value).apply()

    var lastKnownLocation: Location
        get() {
            val latitudeBits = prefs.getLong("last-loc-latitude", (32.0864169).toRawBits())
            val longitudeBits = prefs.getLong("last-loc-longitude", (34.7557871).toRawBits())

            val location = Location("")
            location.latitude = java.lang.Double.longBitsToDouble(latitudeBits)
            location.longitude = java.lang.Double.longBitsToDouble(longitudeBits)
            return location
        }
        set(location) {
            prefs.edit()
                .putLong("last-loc-latitude", location.latitude.toRawBits())
                .putLong("last-loc-longitude", location.longitude.toRawBits())
                .apply()
        }

    var resolutionId: Int
        get() = prefs.getInt("resolutionId", 0)
        set(value) = prefs.edit().putInt("resolutionId", value).apply()

    // Flag to determine if the projection should stretch and ignore aspect ratio to fill the screen
    var stretchToFill: Boolean
        get() = prefs.getBoolean("stretch_to_fill", true)
        set(value) { prefs.edit().putBoolean("stretch_to_fill", value).apply() }

    // Forced scale for older devices (SurfaceView fix)
    var forcedScale: Boolean
        get() = prefs.getBoolean("forced_scale", false)
        set(value) { prefs.edit().putBoolean("forced_scale", value).apply() }

    // HUD Mode (Horizontal flip for windshield reflection)
    var hudMirroring: Boolean
        get() = prefs.getBoolean("hud_mirroring", false)
        set(value) { prefs.edit().putBoolean("hud_mirroring", value).apply() }

    var useMeasuredTouchSurface: Boolean
        get() = prefs.getBoolean("use_measured_touch_surface", false)
        set(value) { prefs.edit().putBoolean("use_measured_touch_surface", value).apply() }

    // UI Scale percentage for Home
    var uiScaleHomePercent: Int
        get() = prefs.getInt("ui-scale-home-percent", 100)
        set(value) { prefs.edit().putInt("ui-scale-home-percent", value).apply() }

    // UI Scale percentage for Settings
    var uiScaleSettingsPercent: Int
        get() = prefs.getInt("ui-scale-settings-percent", 100)
        set(value) { prefs.edit().putInt("ui-scale-settings-percent", value).apply() }

    var micSampleRate: Int
        get() = prefs.getInt("mic-sample-rate", 16000)
        set(sampleRate) {
            prefs.edit().putInt("mic-sample-rate", sampleRate).apply()
        }

    var useGpsForNavigation: Boolean
        get() = prefs.getBoolean("gps-navigation", true)
        set(value) {
            prefs.edit().putBoolean("gps-navigation", value).apply()
        }

    var showNavigationNotifications: Boolean
        get() = prefs.getBoolean("show-navigation-notifications", false)
        set(value) {
            prefs.edit().putBoolean("show-navigation-notifications", value).apply()
        }

    /** Mirror phone now-playing (title, artist, duration, art) in the system media session. */
    var syncMediaSessionWithAaMetadata: Boolean
        get() = prefs.getBoolean(KEY_SYNC_MEDIA_SESSION_AA_METADATA, false)
        set(value) {
            prefs.edit().putBoolean(KEY_SYNC_MEDIA_SESSION_AA_METADATA, value).apply()
        }

    var nightMode: NightMode
        get() {
            val value = prefs.getInt("night-mode", 0)
            val mode = NightMode.fromInt(value)
            return mode!!
        }
        set(nightMode) {
            prefs.edit().putInt("night-mode", nightMode.value).apply()
        }

    var nightModeThresholdLux: Int
        get() = prefs.getInt("night-mode-threshold-lux", 100)
        set(value) {
            prefs.edit().putInt("night-mode-threshold-lux", value).apply()
        }

    var nightModeThresholdBrightness: Int
        get() = prefs.getInt("night-mode-threshold-brightness", 100)
        set(value) {
            prefs.edit().putInt("night-mode-threshold-brightness", value).apply()
        }

    var keyCodes: MutableMap<Int, Int>
        get() {
            val set = prefs.getStringSet("key-codes", mutableSetOf())!!
            val map = mutableMapOf<Int, Int>()
            set.forEach {
                val codes = it.split("-")
                map[codes[0].toInt()] = codes[1].toInt()
            }
            return map
        }
        set(codesMap) {
            val list: List<String> = codesMap.map { "${it.key}-${it.value}" }
            prefs.edit().putStringSet("key-codes", list.toSet()).apply()
        }

    var exporterLogLevel: LogExporter.LogLevel
        get() = LogExporter.LogLevel.entries.getOrElse(prefs.getInt(KEY_LOG_LEVEL, LogExporter.LogLevel.INFO.ordinal)) { LogExporter.LogLevel.INFO }
        set(value) { prefs.edit().putInt(KEY_LOG_LEVEL, value.ordinal).apply() }

    enum class LogSource {
        LOGCAT,
        APPLOG_FILE
    }

    enum class LogLocation {
        DEFAULT,
        DOWNLOADS
    }

    var logSource: LogSource
        get() = LogSource.entries.getOrElse(prefs.getInt(KEY_LOG_SOURCE, LogSource.LOGCAT.ordinal)) { LogSource.LOGCAT }
        set(value) { prefs.edit().putInt(KEY_LOG_SOURCE, value.ordinal).apply() }

    var logLocation: LogLocation
        get() = LogLocation.entries.getOrElse(prefs.getInt(KEY_LOG_LOCATION, LogLocation.DEFAULT.ordinal)) { LogLocation.DEFAULT }
        set(value) { prefs.edit().putInt(KEY_LOG_LOCATION, value.ordinal).apply() }

    /** Whether log capture should be active across restarts. Default: false (disabled). */
    var exporterCaptureEnabled: Boolean
        get() = prefs.getBoolean(KEY_LOG_CAPTURE_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_LOG_CAPTURE_ENABLED, value).apply() }
    val logLevel: Int get() = exporterLogLevel.logLevel

    var viewMode: ViewMode
        get() {
            val value = prefs.getInt("view-mode", 1)
            return ViewMode.fromInt(value)!!
        }
        set(viewMode) {
            prefs.edit().putInt("view-mode", viewMode.value).apply()
        }

    var screenOrientation: ScreenOrientation
        get() {
            val value = prefs.getInt(KEY_SCREEN_ORIENTATION, 0)
            return ScreenOrientation.fromInt(value) ?: ScreenOrientation.SYSTEM
        }
        set(orientation) {
            prefs.edit().putInt(KEY_SCREEN_ORIENTATION, orientation.value).apply()
        }

    var dpiPixelDensity: Int
        get() = prefs.getInt("dpi-pixel-density", 0) // Default 0 for Auto
        set(value) {
            prefs.edit().putInt("dpi-pixel-density", value).apply()
        }

    var pixelAspectRatioE4: Int
        get() = prefs.getInt("pixel-aspect-ratio-e4", 10000) // Default 10000 = 1.0 (square pixels)
        set(value) {
            prefs.edit().putInt("pixel-aspect-ratio-e4", value).apply()
        }

    var staticBSSID: String?
        get() = try { prefs.getString("static-bssid", "0") } catch (e: Exception) { "0" } // Default 0 for Auto
        set(value) {
            prefs.edit().putString("static-bssid", value).apply()
        }

    var fakeSpeed: Boolean
        get() = prefs.getBoolean("fake_speed", true)
        set(value) {
            prefs.edit().putBoolean("fake_speed", value).apply()
        }

    var gestureHintShown: Boolean
        get() = prefs.getBoolean("gesture_hint_shown", false)
        set(value) {
            prefs.edit().putBoolean("gesture_hint_shown", value).apply()
        }

    // One-time notice telling users the app is being rebranded. Shown once on the home screen.
    // The key is versioned on purpose: an earlier build could spend the original flag behind the
    // onboarding wizard without the notice ever being seen, so a new key lets the corrected
    // notice reach everyone once, including users who still carry the old flag.
    var renameNoticeShown: Boolean
        get() = prefs.getBoolean("rename_notice_shown_v2", false)
        set(value) {
            prefs.edit().putBoolean("rename_notice_shown_v2", value).apply()
        }

    // Custom Insets (Screen Margins)
    var insetLeft: Int
        get() = prefs.getInt("inset-left", 0)
        set(value) { prefs.edit().putInt("inset-left", value).apply() }

    var insetTop: Int
        get() = prefs.getInt("inset-top", 0)
        set(value) { prefs.edit().putInt("inset-top", value).apply() }

    var insetRight: Int
        get() = prefs.getInt("inset-right", 0)
        set(value) { prefs.edit().putInt("inset-right", value).apply() }

    var insetBottom: Int
        get() = prefs.getInt("inset-bottom", 0)
        set(value) { prefs.edit().putInt("inset-bottom", value).apply() }

    // Cached Surface Dimensions (used to avoid mismatch flicker on next start)
    var cachedSurfaceWidth: Int
        get() = prefs.getInt("cached-surface-width", 0)
        set(value) { prefs.edit().putInt("cached-surface-width", value).apply() }

    var cachedSurfaceHeight: Int
        get() = prefs.getInt("cached-surface-height", 0)
        set(value) { prefs.edit().putInt("cached-surface-height", value).apply() }

    var cachedSurfaceSettingsHash: Int
        get() = prefs.getInt("cached-surface-settings-hash", 0)
        set(value) { prefs.edit().putInt("cached-surface-settings-hash", value).apply() }

    // Legacy Margins (can be removed later if unused)
    var marginLeft: Int
        get() = prefs.getInt("margin-left", 0)
        set(value) { prefs.edit().putInt("margin-left", value).apply() }

    var marginTop: Int
        get() = prefs.getInt("margin-top", 0)
        set(value) { prefs.edit().putInt("margin-top", value).apply() }

    var marginRight: Int
        get() = prefs.getInt("margin-right", 0)
        set(value) { prefs.edit().putInt("margin-right", value).apply() }

    var marginBottom: Int
        get() = prefs.getInt("margin-bottom", 0)
        set(value) { prefs.edit().putInt("margin-bottom", value).apply() }

    var fullscreenMode: FullscreenMode
        get() {
            // Migration logic
            if (!prefs.contains("fullscreen-mode") && prefs.contains("start-in-fullscreen-mode")) {
                val old = prefs.getBoolean("start-in-fullscreen-mode", true)
                val migrated = if (old) FullscreenMode.IMMERSIVE else FullscreenMode.NONE
                prefs.edit().putInt("fullscreen-mode", migrated.value).apply()
                return migrated
            }
            val value = prefs.getInt("fullscreen-mode", FullscreenMode.IMMERSIVE.value)
            return FullscreenMode.fromInt(value) ?: FullscreenMode.IMMERSIVE
        }
        set(value) { prefs.edit().putInt("fullscreen-mode", value.value).apply() }

    @Deprecated("Use fullscreenMode instead")
    var startInFullscreenMode: Boolean
        get() = fullscreenMode != FullscreenMode.NONE
        set(value) { fullscreenMode = if (value) FullscreenMode.IMMERSIVE else FullscreenMode.NONE }

    var forceSoftwareDecoding: Boolean
        get() = prefs.getBoolean("force-software-decoding", false)
        set(value) { prefs.edit().putBoolean("force-software-decoding", value).apply() }

    var softwareVideoDecoder: SoftwareVideoDecoder
        get() {
            val value = prefs.getInt("software-video-decoder", SoftwareVideoDecoder.BUNDLED_FFMPEG.value)
            return SoftwareVideoDecoder.fromInt(value) ?: SoftwareVideoDecoder.BUNDLED_FFMPEG
        }
        set(value) { prefs.edit().putInt("software-video-decoder", value.value).apply() }

    /**
     * Deliberately corrupts the projected video stream, for testing the reassembler's failure paths.
     *
     * Off, and meant to stay off. The artifact reports this exists for come from units we do not
     * have, and a healthy rig never produces the conditions that cause them - so without this there
     * is no way to show a fix works except to wait for a reporter's next drive. Every injected fault
     * is logged, so a log captured with this left on cannot be mistaken for a log of a real fault.
     */
    var debugVideoFaultInjection: VideoFaultInjector.Mode
        get() {
            val value = prefs.getInt("debug-video-fault-injection", VideoFaultInjector.Mode.OFF.value)
            return VideoFaultInjector.Mode.fromInt(value) ?: VideoFaultInjector.Mode.OFF
        }
        set(value) { prefs.edit().putInt("debug-video-fault-injection", value.value).apply() }

    /**
     * Overrides how much memory the video pipeline believes this device has.
     *
     * Null means measure it. Set, it lets a well-provisioned rig run the constrained path, which is
     * otherwise only reachable on hardware we do not have. Stored by name rather than by ordinal so
     * reordering the enum cannot silently change what a stored value means.
     */
    var debugForceMemoryProfile: DeviceMemoryProfile?
        get() = DeviceMemoryProfile.fromName(prefs.getString("debug-force-memory-profile", null))
        set(value) { prefs.edit().putString("debug-force-memory-profile", value?.name).apply() }

    /**
     * Puts the Native AA WiFi Direct group on 2.4 GHz instead of asking for 5 GHz.
     *
     * Off by default, and it is a rig lever rather than a preference: 5 GHz is what a working
     * session runs on and nothing here recommends moving off it. What it exists for is that the
     * link-outage reports come from 2.4 GHz head units and the rig could never be put on that band
     * - the group is requested as 5 GHz and any group that lands on 2.4 GHz is torn down and
     * remade. Both of those are turned off together by this one flag; see
     * [com.andrerinas.openheadunit.aap.NativeGroupBandPolicy], which is where the coupling lives.
     *
     * Applies to the next group, so it needs a reconnect rather than only a settings write.
     */
    var debugForceP2pBand24: Boolean
        get() = prefs.getBoolean("debug-force-p2p-band-24", false)
        set(value) { prefs.edit().putBoolean("debug-force-p2p-band-24", value).apply() }

    /**
     * On a device with no band API, asks the P2P stack for a 5 GHz operating channel.
     *
     * Below API 29 there is no `WifiP2pConfig.Builder`, so the group's band is the driver's choice
     * and this app has never had a say in it - which is every pre-Android-10 head unit, including
     * both units in the periodic-outage reports. The hidden `setWifiP2pChannels` is the one lever
     * left; see [com.andrerinas.openheadunit.connection.wifi.direct.WifiP2pOperatingChannelPolicy].
     *
     * **Off by default, and it should stay off until a unit reports back.** The request is a
     * disallowed-frequency list, so a unit whose P2P firmware cannot host a 5 GHz group owner is not
     * left on 2.4 GHz - it is left unable to form a group at all. The bring-up clears the restriction
     * and retries once when that happens, but an opt-in costs nothing and a wrong default costs
     * every pre-Q unit its connection.
     *
     * Ignored from API 29 up, where the supported band request does this properly.
     */
    var p2pLegacyFiveGhz: Boolean
        get() = prefs.getBoolean("p2p-legacy-5ghz", false)
        set(value) { prefs.edit().putBoolean("p2p-legacy-5ghz", value).apply() }

    /**
     * Asks for UNII-3 (channel 149) instead of UNII-1 (channel 36) when [p2pLegacyFiveGhz] is on.
     *
     * Both are non-DFS and channel 36 is what the reference implementations use, so this exists only
     * for a regulatory domain that refuses the lower range.
     */
    var p2pLegacyFiveGhzUpperBand: Boolean
        get() = prefs.getBoolean("p2p-legacy-5ghz-upper", false)
        set(value) { prefs.edit().putBoolean("p2p-legacy-5ghz-upper", value).apply() }

    /**
     * Asks the decoder for low-latency mode, through whichever key its vendor understands.
     *
     * Off by default, and it stays off until a log from a real device shows a component accepting the
     * key - which is this project's standing rule about vendor MediaFormat keys, written after
     * KEY_PRIORITY and KEY_OPERATING_RATE were both measured being rejected outright. The configure
     * ladder is what makes turning it on cheap to try: a rejected key now costs one retry instead of
     * the session. See [com.andrerinas.openheadunit.decoder.DecoderConfigLadder].
     */
    var debugVideoLowLatency: Boolean
        get() = prefs.getBoolean("debug-video-low-latency", false)
        set(value) { prefs.edit().putBoolean("debug-video-low-latency", value).apply() }

    /** One in this many of the targeted messages is faulted. See [debugVideoFaultInjection]. */
    var debugVideoFaultRate: Int
        get() = prefs.getInt("debug-video-fault-rate", VideoFaultInjector.DEFAULT_RATE)
        set(value) {
            prefs.edit()
                .putInt("debug-video-fault-rate", value.coerceIn(VideoFaultInjector.MIN_RATE, VideoFaultInjector.MAX_RATE))
                .apply()
        }

    /**
     * Stop injecting after this many faults, or 0 to keep going for the whole session.
     *
     * What a bounded run buys is the second half of the measurement: a stream that is still being
     * corrupted stays broken no matter what the recovery code does, so only a run that stops can
     * show whether the picture comes back. See [VideoFaultInjector.budget].
     */
    var debugVideoFaultBudget: Int
        get() = prefs.getInt("debug-video-fault-budget", VideoFaultInjector.UNLIMITED_BUDGET)
        set(value) {
            prefs.edit()
                .putInt("debug-video-fault-budget", value.coerceAtLeast(VideoFaultInjector.UNLIMITED_BUDGET))
                .apply()
        }

    var rightHandDrive: Boolean
        get() = prefs.getBoolean("right-hand-drive", false)
        set(value) { prefs.edit().putBoolean("right-hand-drive", value).apply() }

    // Vehicle info settings (sent to phone during Android Auto handshake)
    var vehicleDisplayName: String
        // Cosmetic: the phone shows this in its connection history and on the Android Auto
        // welcome screen. It is not the reported manufacturer, which stays "Google" unless
        // the user picks a real car brand — see the car step in OnboardingActivity.
        get() = prefs.getString("vehicle-display-name", "Open Headunit")!!
        set(value) { prefs.edit().putString("vehicle-display-name", value).apply() }

    var vehicleMake: String
        get() = prefs.getString("vehicle-make", "Google")!!
        set(value) { prefs.edit().putString("vehicle-make", value).apply() }

    var vehicleModel: String
        get() = prefs.getString("vehicle-model", "Desktop Head Unit")!!
        set(value) { prefs.edit().putString("vehicle-model", value).apply() }

    var vehicleYear: String
        get() = prefs.getString("vehicle-year", "2025")!!
        set(value) { prefs.edit().putString("vehicle-year", value).apply() }

    var vehicleId: String
        get() = prefs.getString("vehicle-id", "headlessunit-001")!!
        set(value) { prefs.edit().putString("vehicle-id", value).apply() }

    var headUnitMake: String
        get() = prefs.getString("head-unit-make", "Google")!!
        set(value) { prefs.edit().putString("head-unit-make", value).apply() }

    var headUnitModel: String
        get() = prefs.getString("head-unit-model", "Desktop Head Unit")!!
        set(value) { prefs.edit().putString("head-unit-model", value).apply() }

    var hideBatteryLevel: Boolean
        get() = prefs.getBoolean("hide-battery-level", false)
        set(value) { prefs.edit().putBoolean("hide-battery-level", value).apply() }

    var hidePhoneSignal: Boolean
        get() = prefs.getBoolean("hide-phone-signal", false)
        set(value) { prefs.edit().putBoolean("hide-phone-signal", value).apply() }

    var hideClock: Boolean
        get() = prefs.getBoolean("hide-clock", false)
        set(value) { prefs.edit().putBoolean("hide-clock", value).apply() }

    // 0 = Manual, 1 = Auto (Headunit Server), 2 = Helper (Wifi Launcher), 3 = Native AA
    var wifiConnectionMode: WifiLauncherMode
        get() {
            // Migration: Check if old helper boolean exists
            if (prefs.contains("wifi-launcher-mode")) {
                val old = prefs.getBoolean("wifi-launcher-mode", false)
                val newMode = if (old) 2 else 1
                prefs.edit().putInt("wifi-connection-mode", newMode).remove("wifi-launcher-mode").apply()
                return WifiLauncherMode.byIdOrDefault(newMode)
            }
            // Migration: Check if native-aa-wireless was true
            if (prefs.getBoolean("native-aa-wireless", false)) {
                prefs.edit().putInt("wifi-connection-mode", 3).remove("native-aa-wireless").apply()
                return WifiLauncherMode.NATIVE
            }
            return WifiLauncherMode.byIdOrDefault(prefs.getInt("wifi-connection-mode", -1))
        }
        set(value) { prefs.edit().putInt("wifi-connection-mode", value.id).apply() }

    var videoCodec: String
        get() = prefs.getString("video-codec", "Auto")!!
        set(value) { prefs.edit().putString("video-codec", value).apply() }

    var fpsLimit: Int
        get() = prefs.getInt("fps-limit", 60)
        set(value) { prefs.edit().putInt("fps-limit", value).apply() }

    var hasAcceptedDisclaimer: Boolean
        get() = prefs.getBoolean("has-accepted-disclaimer", false)
        set(value) { prefs.edit().putBoolean("has-accepted-disclaimer", value).apply() }

    var hasCompletedSetupWizard: Boolean
        get() = prefs.getBoolean("has-completed-setup-wizard", false)
        set(value) { prefs.edit().putBoolean("has-completed-setup-wizard", value).apply() }

    // Version of the onboarding flow the user has already seen. When this is lower than
    // CURRENT_ONBOARDING_VERSION the wizard is shown again (so upgraders see new steps once).
    // Migration: users who finished the old dialog wizard are treated as having seen version 1.
    var onboardingVersion: Int
        get() {
            if (!prefs.contains("onboarding-version") &&
                prefs.getBoolean("has-completed-setup-wizard", false)) {
                return 1
            }
            return prefs.getInt("onboarding-version", 0)
        }
        set(value) { prefs.edit().putInt("onboarding-version", value).apply() }

    // One-shot: the wizard changed the rendering backend, so on the next projection we ask the user
    // to confirm the picture is visible (a wrong renderer can leave a black screen while audio still
    // works, and a broken SurfaceView cannot be detected automatically). See issue #767.
    var pendingRendererConfirm: Boolean
        get() = prefs.getBoolean("pending-renderer-confirm", false)
        set(value) { prefs.edit().putBoolean("pending-renderer-confirm", value).apply() }

    // How the user primarily connects. Drives which options are surfaced in the Basic tab
    // (e.g. cable users do not see the Wireless Connection group there). UNSET shows everything.
    var primaryConnection: ConnectionKind
        get() = ConnectionKind.fromInt(prefs.getInt("primary-connection", ConnectionKind.UNSET.value))
        set(value) { prefs.edit().putInt("primary-connection", value.value).apply() }

    /**
     * The connection types the user actually uses (multi-select). Drives which settings are shown.
     * Empty = nothing chosen yet, so everything is shown. Migrated once from the legacy
     * single-choice [primaryConnection].
     */
    var connectionModes: Set<ConnectionMode>
        get() {
            val stored = prefs.getStringSet("connection-modes", null)
            if (stored != null) return stored.mapNotNull { ConnectionMode.fromKey(it) }.toSet()
            val migrated = migrateLegacyConnectionModes()
            connectionModes = migrated
            return migrated
        }
        set(value) {
            prefs.edit().putStringSet("connection-modes", value.map { it.key }.toSet()).apply()
        }

    private fun migrateLegacyConnectionModes(): Set<ConnectionMode> = when (primaryConnection) {
        ConnectionKind.USB_CABLE, ConnectionKind.USB_WIRELESS_ADAPTER -> setOf(ConnectionMode.USB)
        ConnectionKind.WIFI, ConnectionKind.NATIVE_AA -> setOf(ConnectionMode.WIFI)
        ConnectionKind.SELF_MODE -> setOf(ConnectionMode.SELF)
        ConnectionKind.ALL -> setOf(ConnectionMode.USB, ConnectionMode.WIFI)
        ConnectionKind.UNSET -> emptySet()
    }

    /** Whether USB-related settings should be shown (empty selection shows everything). */
    fun showsUsb(): Boolean = connectionModes.isEmpty() || ConnectionMode.USB in connectionModes
    /** Whether WiFi/wireless settings should be shown (empty selection shows everything). */
    fun showsWifi(): Boolean = connectionModes.isEmpty() || ConnectionMode.WIFI in connectionModes
    /** The external-GPS choice only applies when a phone is connected; false only for Self-only. */
    fun showsExternalGps(): Boolean = showsUsb() || showsWifi()

    var autoConnectLastSession: Boolean
        get() = prefs.getBoolean("auto-connect-last-session", false)
        set(value) { prefs.edit().putBoolean("auto-connect-last-session", value).apply() }

    var autoConnectSingleUsbDevice: Boolean
        get() = prefs.getBoolean("auto-connect-single-usb", false)
        set(value) { prefs.edit().putBoolean("auto-connect-single-usb", value).apply() }

    var lastConnectionType: String
        get() = prefs.getString("last-connection-type", "")!!
        set(value) { prefs.edit().putString("last-connection-type", value).apply() }

    var lastConnectionIp: String
        get() = prefs.getString("last-connection-ip", "")!!
        set(value) { prefs.edit().putString("last-connection-ip", value).apply() }

    var lastConnectionUsbDevice: String
        get() = prefs.getString("last-connection-usb-device", "")!!
        set(value) { prefs.edit().putString("last-connection-usb-device", value).apply() }

    fun saveLastConnection(type: String, ip: String = "", usbDevice: String = "") {
        lastConnectionType = type
        lastConnectionIp = ip
        lastConnectionUsbDevice = usbDevice
    }

    fun clearLastConnection() {
        lastConnectionType = ""
        lastConnectionIp = ""
        lastConnectionUsbDevice = ""
    }

    var enableAudioSink: Boolean
        get() = prefs.getBoolean("enable-audio-sink", true)
        set(value) { prefs.edit().putBoolean("enable-audio-sink", value).apply() }

    var staticAudioFocus: Boolean
        get() = prefs.getBoolean("static-audio-focus", false)
        set(value) { prefs.edit().putBoolean("static-audio-focus", value).apply() }

    // Whether AA playback takes system audio focus, so another local player (typically the car
    // radio) pauses while it runs. AUTO skips it when a Bluetooth media link is up, because the
    // A2DP sink answers our focus grab by pausing the phone that is feeding us. See
    // PlaybackFocusPolicy for the whole story; ALWAYS and NEVER are the manual overrides.
    var playbackFocusMode: PlaybackFocusPolicy.Mode
        get() = PlaybackFocusPolicy.Mode.fromInt(
            prefs.getInt("playback-focus-mode", PlaybackFocusPolicy.Mode.AUTO.value)
        )
        set(value) { prefs.edit().putInt("playback-focus-mode", value.value).apply() }

    // Whether the physical media buttons reach Android Auto, or are left to the Bluetooth side that
    // may already act on the same press — two consumers of one button skip two tracks. See
    // MediaKeyRoutingPolicy. ALWAYS is the stored zero value so an unset preference keeps the
    // behaviour every existing install has.
    var mediaKeyRouting: MediaKeyRoutingPolicy.Mode
        get() = MediaKeyRoutingPolicy.Mode.fromInt(
            prefs.getInt("media-key-routing", MediaKeyRoutingPolicy.Mode.ALWAYS.value)
        )
        set(value) { prefs.edit().putInt("media-key-routing", value.value).apply() }

    var separateAudioStreams: Boolean
        get() = prefs.getBoolean("separate-audio-streams", false)
        set(value) { prefs.edit().putBoolean("separate-audio-streams", value).apply() }

    var micInputSource: Int
        get() = prefs.getInt("mic-input-source", 0) // Default: DEFAULT
        set(value) { prefs.edit().putInt("mic-input-source", value).apply() }

    var audioLatencyMultiplier: Int
        get() = prefs.getInt("audio-latency-multiplier", 8)
        set(value) { prefs.edit().putInt("audio-latency-multiplier", value).apply() }

    // Chunks the audio thread may hold before it starts dropping, or 0 for no limit. Bounded by
    // default: with no limit a link that stalls for a few hundred milliseconds hands over the
    // backlog in one burst and every chunk of it is played, so audio ends up running that far
    // behind the picture and never catches up. Dropping instead costs a moment of sound and keeps
    // the two together. 0 remains selectable for anyone who prefers the gap-free audio.
    //
    // The bound has to clear the window we hand the phone, or we drop sound the protocol told it
    // to send: AapControl advertises max_unacked 30 for wireless audio, so a burst that size is
    // legal and must fit. A chunk is one AAP message and its duration follows the channel's
    // config: around 20ms on 48kHz stereo media, several times that on the 16kHz mono guidance
    // channel, so this cannot be stated in milliseconds from here.
    var audioQueueCapacity: Int
        get() = prefs.getInt("audio-queue-capacity", 50)
        set(value) { prefs.edit().putInt("audio-queue-capacity", value).apply() }

    var useAacAudio: Boolean
        get() = prefs.getBoolean("use-aac-audio", false)
        set(value) { prefs.edit().putBoolean("use-aac-audio", value).apply() }

    var micEchoCanceler: Boolean
        get() = prefs.getBoolean("mic-echo-canceler", false)
        set(value) { prefs.edit().putBoolean("mic-echo-canceler", value).apply() }

    // Attach dummy Equalizer effect to AudioTrack/AudioMixer session to force HW DSP hardware routing
    var attachHwDspEqualizer: Boolean
        get() = prefs.getBoolean("attach_hw_dsp_equalizer", false)
        set(value) { prefs.edit().putBoolean("attach_hw_dsp_equalizer", value).apply() }

    var micNoiseSuppressor: Boolean
        get() = prefs.getBoolean("mic-noise-suppressor", false)
        set(value) { prefs.edit().putBoolean("mic-noise-suppressor", value).apply() }

    var micAutoGainControl: Boolean
        get() = prefs.getBoolean("mic-auto-gain-control", false)
        set(value) { prefs.edit().putBoolean("mic-auto-gain-control", value).apply() }

    var useNativeSsl: Boolean
        get() = prefs.getBoolean("use-native-ssl", false)
        set(value) { prefs.edit().putBoolean("use-native-ssl", value).apply() }

    var autoStartSelfMode: Boolean
        get() = prefs.getBoolean("auto-start-self-mode", false)
        set(value) { prefs.edit().putBoolean("auto-start-self-mode", value).apply() }

    var autoStartOnUsb: Boolean
        get() = prefs.getBoolean("auto-start-on-usb", false)
        set(value) { prefs.edit().putBoolean("auto-start-on-usb", value).apply() }

    var autoStartOnBoot: Boolean
        get() = prefs.getBoolean("auto-start-on-boot", false)
        set(value) { prefs.edit().putBoolean("auto-start-on-boot", value).apply() }

    var autoStartOnScreenOn: Boolean
        get() = prefs.getBoolean("auto-start-on-screen-on", false)
        set(value) { prefs.edit().putBoolean("auto-start-on-screen-on", value).apply() }

    var autoStartOnWifi: Boolean
        get() = prefs.getBoolean("auto-start-on-wifi", false)
        set(value) { prefs.edit().putBoolean("auto-start-on-wifi", value).apply() }

    var autoStartWifiSsid: String
        get() = prefs.getString("auto-start-wifi-ssid", "")!!
        set(value) { prefs.edit().putString("auto-start-wifi-ssid", value).apply() }

    var listenForUsbDevices: Boolean
        get() = prefs.getBoolean("listen-for-usb-devices", true)
        set(value) { prefs.edit().putBoolean("listen-for-usb-devices", value).apply() }

    var usbBlacklist: Set<String>
        get() = prefs.getStringSet("usb-blacklist", null)?.toSet() ?: emptySet()
        set(value) { prefs.edit().putStringSet("usb-blacklist", value).apply() }

    fun formatUsbVidPidKey(vid: Int, pid: Int): String {
        return String.format(java.util.Locale.US, "%04x:%04x", vid, pid).lowercase()
    }

    fun formatUsbVidPidDisplay(vid: Int, pid: Int): String {
        return String.format(java.util.Locale.US, "VID: 0x%04X, PID: 0x%04X", vid, pid)
    }

    fun isUsbDeviceBlacklisted(vid: Int, pid: Int): Boolean {
        val key = formatUsbVidPidKey(vid, pid)
        return usbBlacklist.contains(key)
    }

    fun addUsbDeviceToBlacklist(vid: Int, pid: Int) {
        val key = formatUsbVidPidKey(vid, pid)
        val set = usbBlacklist.toMutableSet()
        set.add(key)
        usbBlacklist = set
    }

    fun removeUsbDeviceFromBlacklist(vid: Int, pid: Int) {
        val key = formatUsbVidPidKey(vid, pid)
        val set = usbBlacklist.toMutableSet()
        set.remove(key)
        usbBlacklist = set
    }

    var showToastMessages: Boolean
        get() = prefs.getBoolean("show-toast-messages", true)
        set(value) { prefs.edit().putBoolean("show-toast-messages", value).apply() }

    var reopenOnReconnection: Boolean
        get() = prefs.getBoolean("reopen-on-reconnection", true)
        set(value) { prefs.edit().putBoolean("reopen-on-reconnection", value).apply() }

    var autoConnectPriorityOrder: List<String>
        get() {
            val stored = prefs.getString("auto-connect-priority-order", null)
            val order = if (stored.isNullOrEmpty()) {
                DEFAULT_AUTO_CONNECT_ORDER.toMutableList()
            } else {
                stored.split(",").toMutableList()
            }
            // Migration safety: append any missing methods at end
            for (method in DEFAULT_AUTO_CONNECT_ORDER) {
                if (method !in order) {
                    order.add(method)
                }
            }
            // Remove unknown methods
            order.retainAll(DEFAULT_AUTO_CONNECT_ORDER)
            return order
        }
        set(value) {
            prefs.edit().putString("auto-connect-priority-order", value.joinToString(",")).apply()
        }

    var autoStartBluetoothDeviceMacs: Set<String>
        get() {
            var macs = prefs.getStringSet("auto-start-bt-macs", null)
            if (macs == null) {
                val legacyMac = prefs.getString("auto-start-bt-mac", "") ?: ""
                macs = if (legacyMac.isNotEmpty()) setOf(legacyMac) else emptySet()
                prefs.edit().putStringSet("auto-start-bt-macs", macs).apply()
            }
            return macs
        }
        set(value) {
            prefs.edit().putStringSet("auto-start-bt-macs", value).apply()
        }

    var autoStartBluetoothDeviceName: String
        get() = prefs.getString("auto-start-bt-name", "")!!
        set(value) { prefs.edit().putString("auto-start-bt-name", value).apply() }

    var autoStartBluetoothDeviceMac: String
        get() = autoStartBluetoothDeviceMacs.firstOrNull() ?: ""
        set(value) {
            val current = autoStartBluetoothDeviceMacs.toMutableSet()
            if (value.isNotEmpty()) {
                current.add(value)
            } else {
                current.clear()
            }
            autoStartBluetoothDeviceMacs = current
        }

    var appLanguage: String
        get() = prefs.getString("app-language", "")!!
        set(value) { prefs.edit().putString("app-language", value).apply() }

    var mediaVolumeOffset: Int
        get() = prefs.getInt("media-volume-offset", 0)
        set(value) { prefs.edit().putInt("media-volume-offset", value).apply() }

    var assistantVolumeOffset: Int
        get() = prefs.getInt("assistant-volume-offset", 0)
        set(value) { prefs.edit().putInt("assistant-volume-offset", value).apply() }

    var navigationVolumeOffset: Int
        get() = prefs.getInt("navigation-volume-offset", 0)
        set(value) { prefs.edit().putInt("navigation-volume-offset", value).apply() }

    // Custom loading screen
    var loadingScreenMediaPath: String
        get() = prefs.getString("loading-screen-media-path", "")!!
        set(value) { prefs.edit().putString("loading-screen-media-path", value).apply() }

    var loadingScreenMediaType: String
        get() = prefs.getString("loading-screen-media-type", "")!!
        set(value) { prefs.edit().putString("loading-screen-media-type", value).apply() }

    var loadingScreenShowText: Boolean
        get() = prefs.getBoolean("loading-screen-show-text", false)
        set(value) { prefs.edit().putBoolean("loading-screen-show-text", value).apply() }

    var loadingScreenKeepAspectRatio: Boolean
        get() = prefs.getBoolean("loading-screen-keep-aspect-ratio", true)
        set(value) { prefs.edit().putBoolean("loading-screen-keep-aspect-ratio", value).apply() }

    var loadingScreenLoopVideo: Boolean
        get() = prefs.getBoolean("loading-screen-loop-video", true)
        set(value) { prefs.edit().putBoolean("loading-screen-loop-video", value).apply() }

    var loadingScreenScalePercent: Int
        get() = prefs.getInt("loading-screen-scale-percent", 100)
        set(value) { prefs.edit().putInt("loading-screen-scale-percent", value).apply() }

    // Custom home screen background image
    var homeBackgroundImagePath: String
        get() = prefs.getString("home-background-image-path", "") ?: ""
        set(value) { prefs.edit().putString("home-background-image-path", value).apply() }

    // Custom button colors for Home screen (0 = default gradient)
    var customSelfModeButtonColor: Int
        get() = prefs.getInt("custom-self-mode-button-color", 0)
        set(value) { prefs.edit().putInt("custom-self-mode-button-color", value).apply() }

    var customUsbButtonColor: Int
        get() = prefs.getInt("custom-usb-button-color", 0)
        set(value) { prefs.edit().putInt("custom-usb-button-color", value).apply() }

    var customWifiButtonColor: Int
        get() = prefs.getInt("custom-wifi-button-color", 0)
        set(value) { prefs.edit().putInt("custom-wifi-button-color", value).apply() }

    var customSettingsButtonColor: Int
        get() = prefs.getInt("custom-settings-button-color", 0)
        set(value) { prefs.edit().putInt("custom-settings-button-color", value).apply() }

    // Custom button scaling percentage for Home screen (default = 100%, valid range 60..120)
    var homeButtonScalePercent: Int
        get() {
            val saved = prefs.getInt("home-button-scale-percent", 100)
            return if (saved in 60..120) saved else 100
        }
        set(value) {
            val valid = if (value in 60..120) value else 100
            prefs.edit().putInt("home-button-scale-percent", valid).apply()
        }

    @SuppressLint("ApplySharedPref")
    fun commit() {
        prefs.edit().commit()
    }

    @SuppressLint("ApplySharedPref")
    fun reset() {
        prefs.edit().clear().commit()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            syncAutoStartOnBootToDeviceStorage(context, false)
            syncAutoStartOnScreenOnToDeviceStorage(context, false)
            syncAutoStartOnUsbToDeviceStorage(context, false)
            syncAutoStartOnWifiToDeviceStorage(context, false)
            syncListenForUsbDevicesToDeviceStorage(context, true)
            syncAutoStartBtMacToDeviceStorage(context, "")
        }
    }

    enum class Resolution(val id: Int, val resName: String, val width: Int, val height: Int, val codec: Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType?) {
        AUTO(0, "Auto",0, 0, null),
        _800x480(1, "480p", 800, 480, Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._800x480),
        _1280x720(2, "720p", 1280, 720, Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720),
        _1920x1080(3, "1080p", 1920, 1080, Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1920x1080),
        _2560x1440(4, "1440p (2K)", 2560, 1440, Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._2560x1440),
        _3840x2160(5, "2160p (4K)", 3840, 2160, Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._3840x2160);

        companion object {
            private val map = values().associateBy(Resolution::id)
            fun fromId(id: Int) = map[id]
            val allRes: Array<String>
                get() = values().map { it.resName }.toTypedArray()
            val allResolutions: Array<Resolution>
                get() = values()
        }
    }

    enum class SoftwareVideoDecoder(val value: Int) {
        DEVICE_MEDIACODEC(0),
        BUNDLED_FFMPEG(1);

        companion object {
            private val map = values().associateBy(SoftwareVideoDecoder::value)
            fun fromInt(value: Int) = map[value]
        }
    }

    enum class ConnectionKind(val value: Int) {
        UNSET(-1),
        USB_CABLE(0),
        USB_WIRELESS_ADAPTER(1),
        WIFI(2),
        NATIVE_AA(3),
        SELF_MODE(4),
        ALL(5);

        val isWireless: Boolean
            get() = this == USB_WIRELESS_ADAPTER || this == WIFI || this == NATIVE_AA

        /** Whether USB-only settings should be hidden for this connection choice. */
        fun hidesUsb(): Boolean = this == WIFI || this == NATIVE_AA || this == SELF_MODE

        /** Whether WiFi/Bluetooth settings should be hidden for this connection choice.
         * (Bluetooth is used to bridge WiFi, so it counts as WiFi scope.) */
        fun hidesWifi(): Boolean = this == USB_CABLE || this == USB_WIRELESS_ADAPTER || this == SELF_MODE

        companion object {
            private val map = values().associateBy(ConnectionKind::value)
            fun fromInt(value: Int) = map[value] ?: UNSET
        }
    }

    /** A connection type the user can pick in the multi-select (USB, WiFi or Self Mode). */
    enum class ConnectionMode(val key: String) {
        USB("usb"), WIFI("wifi"), SELF("self");

        companion object {
            fun fromKey(k: String) = values().firstOrNull { it.key == k }
        }
    }

    enum class NightMode(val value: Int) {
        AUTO(0),
        DAY(1),
        NIGHT(2),
        MANUAL_TIME(3),
        LIGHT_SENSOR(4),
        SCREEN_BRIGHTNESS(5),
        CAR_SIGNAL(6),
        LOCATION(7);

        companion object {
            private val map = NightMode.values().associateBy(NightMode::value)
            fun fromInt(value: Int) = map[value]
        }
    }

    var nightModeManualStart: Int
        get() = prefs.getInt("night-mode-manual-start", 1140) // Default 19:00 (19 * 60)
        set(value) {
            prefs.edit().putInt("night-mode-manual-start", value).apply()
        }

    var nightModeManualEnd: Int
        get() = prefs.getInt("night-mode-manual-end", 420) // Default 07:00 (7 * 60)
        set(value) {
            prefs.edit().putInt("night-mode-manual-end", value).apply()
        }

    // Shared fixed point used as the sunrise/sunset reference when [useFixedSunriseLocation]
    // is on. Used by BOTH the app theme (AppTheme.AUTO_SUNRISE) and the Android Auto night
    // mode (NightMode.AUTO) so head units without GPS still compute correct day/night.
    // Defaults mirror lastKnownLocation.
    var useFixedSunriseLocation: Boolean
        get() = prefs.getBoolean("use-fixed-sun-location", false)
        set(value) { prefs.edit().putBoolean("use-fixed-sun-location", value).apply() }

    var fixedSunriseLatitude: Double
        get() = java.lang.Double.longBitsToDouble(
            prefs.getLong("fixed-sun-latitude", (32.0864169).toRawBits())
        )
        set(value) {
            prefs.edit().putLong("fixed-sun-latitude", value.toRawBits()).apply()
        }

    var fixedSunriseLongitude: Double
        get() = java.lang.Double.longBitsToDouble(
            prefs.getLong("fixed-sun-longitude", (34.7557871).toRawBits())
        )
        set(value) {
            prefs.edit().putLong("fixed-sun-longitude", value.toRawBits()).apply()
        }


    // Suppresses the "internet required" notice before opening the map picker once the
    // user has chosen "don't show again".
    var hideMapInternetNotice: Boolean
        get() = prefs.getBoolean("hide-map-internet-notice", false)
        set(value) { prefs.edit().putBoolean("hide-map-internet-notice", value).apply() }

    // User-defined places (Home, Garage, ...) for the "Location (by area)" theme mode.
    var geofenceLocations: List<com.andrerinas.openheadunit.location.GeofenceLocation>
        get() = com.andrerinas.openheadunit.location.GeofenceLocation.listFromJson(
            prefs.getString("geofence-locations", "[]")
        )
        set(value) {
            val json = com.andrerinas.openheadunit.location.GeofenceLocation.listToJson(value)
            prefs.edit().putString("geofence-locations", json).apply()
        }

    // Appearance to use in "Location (by area)" mode when outside every saved place.
    var locationOutsideNight: Boolean
        get() = prefs.getBoolean("location-outside-night", false)
        set(value) { prefs.edit().putBoolean("location-outside-night", value).apply() }

    // App Theme independent threshold/time settings (separate from Night Mode)
    var appThemeThresholdLux: Int
        get() = prefs.getInt("app-theme-threshold-lux", 100)
        set(value) { prefs.edit().putInt("app-theme-threshold-lux", value).apply() }

    var appThemeThresholdBrightness: Int
        get() = prefs.getInt("app-theme-threshold-brightness", 100)
        set(value) { prefs.edit().putInt("app-theme-threshold-brightness", value).apply() }

    var appThemeManualStart: Int
        get() = prefs.getInt("app-theme-manual-start", 1140)
        set(value) { prefs.edit().putInt("app-theme-manual-start", value).apply() }

    var appThemeManualEnd: Int
        get() = prefs.getInt("app-theme-manual-end", 420)
        set(value) { prefs.edit().putInt("app-theme-manual-end", value).apply() }
    var showFpsCounter: Boolean
        get() = prefs.getBoolean("show-fps-counter", false)
        set(value) {
            prefs.edit().putBoolean("show-fps-counter", value).apply()
        }

    companion object {
        const val PREFS_NAME = "settings"

        const val CONNECTION_TYPE_WIFI = "wifi"
        const val CONNECTION_TYPE_USB = "usb"
        const val CONNECTION_TYPE_NEARBY = "nearby"

        /** SharedPreferences key; also used by [AapService] for change listener. */
        const val KEY_SYNC_MEDIA_SESSION_AA_METADATA = "sync-media-session-aa-metadata"

        /** SharedPreferences key; also used by [AapService] for change listener. */
        const val KEY_LOG_LEVEL = "log-level"
        const val KEY_LOG_SOURCE = "log-source"
        const val KEY_LOG_LOCATION = "log-location"
        /** Persist whether log capture should be active across restarts. */
        const val KEY_LOG_CAPTURE_ENABLED = "log-capture-enabled"

        const val KEY_MEDIA_VOLUME_OFFSET = "media-volume-offset"
        const val KEY_ASSISTANT_VOLUME_OFFSET = "assistant-volume-offset"
        const val KEY_NAVIGATION_VOLUME_OFFSET = "navigation-volume-offset"

        const val AUTO_CONNECT_LAST_SESSION = "last-session"
        const val AUTO_CONNECT_SELF_MODE = "self-mode"
        const val AUTO_CONNECT_SINGLE_USB = "single-usb"

        val DEFAULT_AUTO_CONNECT_ORDER = listOf(
            AUTO_CONNECT_LAST_SESSION,
            AUTO_CONNECT_SELF_MODE,
            AUTO_CONNECT_SINGLE_USB
        )

        private const val DEVICE_PREFS_NAME = "settings_device_protected"
        private const val KEY_AUTO_START_ON_BOOT = "auto-start-on-boot"

        /**
         * Reads auto-start-on-boot from device-protected storage (API 24+),
         * falling back to regular prefs on older devices.
         * Safe to call during locked boot when credential storage is unavailable.
         */
        fun isAutoStartOnBootEnabled(context: Context): Boolean {
            val prefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
            } else {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            return prefs.getBoolean(KEY_AUTO_START_ON_BOOT, false)
        }

        /**
         * Syncs the auto-start-on-boot value to device-protected storage.
         * Call this whenever the user saves settings.
         */
        fun syncAutoStartOnBootToDeviceStorage(context: Context, enabled: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_AUTO_START_ON_BOOT, enabled)
                    .apply()
            }
        }

        private const val KEY_BOOT_LOOP_STRIKES = "boot-loop-strikes"
        private const val KEY_WIRELESS_PAUSED_BY_BOOT_LOOP = "wireless-paused-by-boot-loop"

        /**
         * The device-protected store, which is readable at LOCKED_BOOT_COMPLETED — before the user
         * has unlocked, and so before ordinary preferences exist. The boot-loop counters have to
         * live here for the same reason the auto-start flags do: they are read and written by
         * [com.andrerinas.openheadunit.app.BootCompleteReceiver] on a device that has just booted.
         */
        private fun bootPrefs(context: Context) =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
                    .getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
            } else {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }

        /** Consecutive boot-started runs that did not last. See `aap/BootLoopPolicy`. */
        fun getBootLoopStrikes(context: Context): Int = try {
            bootPrefs(context).getInt(KEY_BOOT_LOOP_STRIKES, 0)
        } catch (e: Exception) {
            AppLog.d("Settings: Could not read the boot-loop strike count: ${e.message}")
            0
        }

        fun setBootLoopStrikes(context: Context, strikes: Int) {
            try {
                bootPrefs(context).edit().putInt(KEY_BOOT_LOOP_STRIKES, strikes).apply()
            } catch (e: Exception) {
                AppLog.d("Settings: Could not store the boot-loop strike count: ${e.message}")
            }
        }

        /**
         * Whether wireless bring-up is currently paused because it looked like it was crashing the
         * device. Sticky on purpose: cleared when the user opens the app, not by surviving a run,
         * so the cycle ends outright instead of resuming every few boots.
         */
        fun isWirelessPausedByBootLoop(context: Context): Boolean = try {
            bootPrefs(context).getBoolean(KEY_WIRELESS_PAUSED_BY_BOOT_LOOP, false)
        } catch (e: Exception) {
            AppLog.d("Settings: Could not read the boot-loop pause flag: ${e.message}")
            false
        }

        fun setWirelessPausedByBootLoop(context: Context, paused: Boolean) {
            try {
                bootPrefs(context).edit()
                    .putBoolean(KEY_WIRELESS_PAUSED_BY_BOOT_LOOP, paused)
                    .apply()
            } catch (e: Exception) {
                AppLog.d("Settings: Could not store the boot-loop pause flag: ${e.message}")
            }
        }

        /** Both counters back to their defaults, for when the user is present and can act. */
        fun clearBootLoopState(context: Context) {
            if (getBootLoopStrikes(context) == 0 && !isWirelessPausedByBootLoop(context)) return
            AppLog.i("Settings: Clearing the boot-loop guard — the app was opened by hand.")
            setBootLoopStrikes(context, 0)
            setWirelessPausedByBootLoop(context, false)
        }

        private const val KEY_AUTO_START_ON_SCREEN_ON = "auto-start-on-screen-on"

        /**
         * Reads auto-start-on-screen-on from device-protected storage (API 24+),
         * falling back to regular prefs on older devices.
         * Safe to call during locked boot when credential storage is unavailable.
         */
        fun isAutoStartOnScreenOnEnabled(context: Context): Boolean {
            val prefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
            } else {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            return prefs.getBoolean(KEY_AUTO_START_ON_SCREEN_ON, false)
        }

        fun syncAutoStartOnScreenOnToDeviceStorage(context: Context, enabled: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_AUTO_START_ON_SCREEN_ON, enabled)
                    .apply()
            }
        }

        private const val KEY_AUTO_START_ON_USB = "auto-start-on-usb"
        const val KEY_SCREEN_ORIENTATION = "screen-orientation"
        private const val KEY_LISTEN_FOR_USB_DEVICES = "listen-for-usb-devices"
        private const val KEY_AUTO_START_BT_MAC = "auto-start-bt-mac"
        private const val KEY_AUTO_START_BT_MACS = "auto-start-bt-macs"

        /**
         * Reads auto-start-on-usb from device-protected storage (API 24+),
         * falling back to regular prefs on older devices.
         * Safe to call during locked boot when credential storage is unavailable.
         */
        fun isAutoStartOnUsbEnabled(context: Context): Boolean {
            val prefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
            } else {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            return prefs.getBoolean(KEY_AUTO_START_ON_USB, false)
        }

        fun syncAutoStartOnUsbToDeviceStorage(context: Context, enabled: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_AUTO_START_ON_USB, enabled)
                    .apply()
            }
        }

        private const val KEY_AUTO_START_ON_WIFI = "auto-start-on-wifi"
        private const val KEY_AUTO_START_WIFI_SSID = "auto-start-wifi-ssid"

        fun isAutoStartOnWifiEnabled(context: Context): Boolean {
            val prefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
            } else {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            return prefs.getBoolean(KEY_AUTO_START_ON_WIFI, false)
        }

        fun syncAutoStartOnWifiToDeviceStorage(context: Context, enabled: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_AUTO_START_ON_WIFI, enabled)
                    .apply()
            }
        }

        fun getAutoStartWifiSsid(context: Context): String {
            val prefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
            } else {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            return prefs.getString(KEY_AUTO_START_WIFI_SSID, "") ?: ""
        }

        fun syncAutoStartWifiSsidToDeviceStorage(context: Context, ssid: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_AUTO_START_WIFI_SSID, ssid)
                    .apply()
            }
        }

        fun isListenForUsbDevicesEnabled(context: Context): Boolean {
            val prefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
            } else {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            return prefs.getBoolean(KEY_LISTEN_FOR_USB_DEVICES, true) // Default is TRUE
        }

        fun syncListenForUsbDevicesToDeviceStorage(context: Context, enabled: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_LISTEN_FOR_USB_DEVICES, enabled)
                    .apply()
            }
        }


        fun setUsbAttachedActivityEnabled(context: Context, enabled: Boolean) {
            val component = ComponentName(context, UsbAttachedActivity::class.java)
            val newState = if (enabled)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (context.packageManager.getComponentEnabledSetting(component) != newState) {
                context.packageManager.setComponentEnabledSetting(
                    component, newState, PackageManager.DONT_KILL_APP
                )
            }
        }

        /**
         * Reads the Bluetooth auto-start MAC from device-protected storage (API 24+),
         * falling back to regular prefs on older devices.
         */
        fun getAutoStartBtMac(context: Context): String {
            val macs = getAutoStartBtMacs(context)
            if (macs.isNotEmpty()) {
                return macs.first()
            }
            val prefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
            } else {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            return prefs.getString(KEY_AUTO_START_BT_MAC, "") ?: ""
        }

        fun syncAutoStartBtMacToDeviceStorage(context: Context, mac: String) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_AUTO_START_BT_MAC, mac)
                    .apply()
            }
        }

        fun getAutoStartBtMacs(context: Context): Set<String> {
            val prefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
            } else {
                context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            }
            var macs = prefs.getStringSet(KEY_AUTO_START_BT_MACS, null)
            if (macs == null) {
                val legacyMac = prefs.getString(KEY_AUTO_START_BT_MAC, "") ?: ""
                macs = if (legacyMac.isNotEmpty()) setOf(legacyMac) else emptySet()
            }
            return macs
        }

        fun syncAutoStartBtMacsToDeviceStorage(context: Context, macs: Set<String>) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val deviceContext = context.createDeviceProtectedStorageContext()
                deviceContext.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putStringSet(KEY_AUTO_START_BT_MACS, macs)
                    .apply()
            }
        }

        val MicSampleRates = listOf(8000, 16000, 24000, 32000, 44100, 48000) // Changed to List

        fun getNextMicSampleRate(currentRate: Int): Int {
            val currentIndex = MicSampleRates.indexOf(currentRate)
            return if (currentIndex != -1 && currentIndex < MicSampleRates.size - 1) {
                MicSampleRates[currentIndex + 1]
            } else {
                MicSampleRates.first() // Loop back to first if at end or not found
            }
        }

        // NightMode is now an enum, so we can iterate its values directly
    }

    enum class ViewMode(val value: Int) {
        SURFACE(0),
        TEXTURE(1),
        GLES(2);

        companion object {
            private val map = values().associateBy(ViewMode::value)
            fun fromInt(value: Int) = map[value]
        }
    }

    enum class ScreenOrientation(val value: Int, val androidOrientation: Int) {
        SYSTEM(0, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER),
        AUTO(1, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR),
        LANDSCAPE(2, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
        LANDSCAPE_REVERSE(3, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE),
        PORTRAIT(4, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT),
        PORTRAIT_REVERSE(5, android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);

        companion object {
            private val map = values().associateBy(ScreenOrientation::value)
            fun fromInt(value: Int) = map[value]
        }
    }

    enum class FullscreenMode(val value: Int) {
        NONE(0),
        IMMERSIVE(1),
        STATUS_ONLY(2),
        IMMERSIVE_WITH_NOTCH(3);

        companion object {
            private val map = values().associateBy(FullscreenMode::value)
            fun fromInt(value: Int) = map[value]
        }
    }

    enum class AppTheme(val value: Int) {
        AUTOMATIC(0),
        CLEAR(1),
        DARK(2),
        EXTREME_DARK(3),
        AUTO_SUNRISE(4),
        MANUAL_TIME(5),
        LIGHT_SENSOR(6),
        SCREEN_BRIGHTNESS(7),
        CAR_SIGNAL(8),
        LOCATION(9);

        companion object {
            private val map = values().associateBy(AppTheme::value)
            fun fromInt(value: Int) = map[value] ?: AUTOMATIC
        }
    }

    var monochromeIcons: Boolean
        get() = prefs.getBoolean("monochrome-icons", false)
        set(value) { prefs.edit().putBoolean("monochrome-icons", value).apply() }

    var useExtremeDarkMode: Boolean
        get() = prefs.getBoolean("use-extreme-dark-mode", false)
        set(value) { prefs.edit().putBoolean("use-extreme-dark-mode", value).apply() }

    var useGradientBackground: Boolean
        get() = prefs.getBoolean("use-gradient-background", false)
        set(value) { prefs.edit().putBoolean("use-gradient-background", value).apply() }

    var aaMonochromeEnabled: Boolean
        get() = prefs.getBoolean("aa-monochrome-enabled", false)
        set(value) { prefs.edit().putBoolean("aa-monochrome-enabled", value).apply() }

    var aaDesaturationLevel: Int
        get() = prefs.getInt("aa-desaturation-level", 100)
        set(value) { prefs.edit().putInt("aa-desaturation-level", value).apply() }

    var appTheme: AppTheme
        get() {
            val value = prefs.getInt("app-theme", 0)
            return AppTheme.fromInt(value) ?: AppTheme.AUTOMATIC
        }
        set(theme) {
            prefs.edit().putInt("app-theme", theme.value).apply()
        }

    var enableRotary: Boolean
        get() = prefs.getBoolean("enable-rotary", false)
        set(value) { prefs.edit().putBoolean("enable-rotary", value).apply() }

    var killOnDisconnect: Boolean
        get() = prefs.getBoolean("kill-on-disconnect", false)
        set(value) { prefs.edit().putBoolean("kill-on-disconnect", value).apply() }

    var autoEnableHotspot: Boolean
        get() = prefs.getBoolean("auto-enable-hotspot", false)
        set(value) { prefs.edit().putBoolean("auto-enable-hotspot", value).apply() }

    var waitForWifiBeforeWifiDirect: Boolean
        get() = prefs.getBoolean("wait-for-wifi-before-wifi-direct", false)
        set(value) { prefs.edit().putBoolean("wait-for-wifi-before-wifi-direct", value).apply() }

    var waitForWifiTimeout: Int
        get() = prefs.getInt("wait-for-wifi-timeout", 10)
        set(value) { prefs.edit().putInt("wait-for-wifi-timeout", value).apply() }

    var helperConnectionStrategy: HelperStrategy
        get() = HelperStrategy.byIdOrDefault(prefs.getInt("helper-connection-strategy", -1))
        set(value) = prefs.edit().putInt("helper-connection-strategy", value.id).apply()

    var lastNearbyDeviceName: String
        get() = prefs.getString("last-nearby-device-name", "")!!
        set(value) = prefs.edit().putString("last-nearby-device-name", value).apply()

    var bluetoothManagerServiceName: String
        get() = prefs.getString("bluetooth-manager-service-name", "bluetooth_manager")!!
        set(value) = prefs.edit().putString("bluetooth-manager-service-name", value).apply()

    // Which network the Native AA mode (wifiConnectionMode 3) puts the phone on.
    // 0 = WiFi Direct P2P group, 1 = this head unit's own hotspot (experimental).
    //
    // Deliberately not folded into helperConnectionStrategy: that setting belongs to mode 2 and
    // means something different in every one of its five values. A wireless mode that reuses
    // another mode's selector is how the two call sites of the old usesWifiDirect() drifted apart.
    var nativeApStrategy: NativeStrategy
        get() = NativeStrategy.byIdOrDefault(prefs.getInt("native-ap-transport", -1))
        set(value) = prefs.edit().putInt("native-ap-transport", value.id).apply()

    // Whether the Native AA handshake opens with a WifiVersionRequest (Type 4), as real head units
    // and the OEM ZLink app do, instead of going straight to WifiStartRequest.
    //
    // Off by default, deliberately: this is the only change on this route that alters what a unit
    // with a working setup puts on the wire, and the version it announces is a guess — no capture
    // of a real head unit's Type 4 has been decoded. A phone under test answered that guess with
    // status=-8 and completed the handshake anyway, so the exchange is survivable on at least one
    // Gearhead, but "survivable on one" is not a default. Left as an opt-in until a reporter whose
    // unit broke on Android Auto 17.4 confirms it helps, since fixing that is the only reason to
    // send it at all.
    var nativeWifiVersionExchange: Boolean
        get() = prefs.getBoolean("native-wifi-version-exchange", false)
        set(value) = prefs.edit().putBoolean("native-wifi-version-exchange", value).apply()

    // Manual fallback for dual-radio head units whose second radio isn't discoverable via
    // ServiceManager.listServices() at all. Empty = disabled (rely on automatic discovery only).
    var manualSecondaryBluetoothServiceName: String
        get() = prefs.getString("manual-secondary-bt-service-name", "")!!
        set(value) = prefs.edit().putString("manual-secondary-bt-service-name", value).apply()

    // Which interface hosts the head unit's access point. Empty = work it out from the interface
    // list. Worth having because every other implementation of this protocol either creates the AP
    // itself or is told the name: aa-proxy-rs has an `iface` setting, the Pi dongles pin wlan0 in
    // hostapd.conf, and ZLink carries an ap_NIC_name field. We are the only one reading an AP we
    // did not create, so we are the only one that has to guess.
    var hotspotInterface: String
        get() = prefs.getString("hotspot-interface", "")!!
        set(value) = prefs.edit().putString("hotspot-interface", value).apply()

    // Manual override for the head unit's own access point, used first by
    // SoftApCredentialsProvider when nativeApTransport == 1. Empty = read the system's hotspot
    // configuration instead. Worth having because getSoftApConfiguration() is reflection over a
    // non-public API and can simply refuse on a locked-down API 30+ device.
    var hotspotSsid: String
        get() = prefs.getString("hotspot-ssid", "")!!
        set(value) = prefs.edit().putString("hotspot-ssid", value).apply()

    var hotspotPassword: String
        get() = prefs.getString("hotspot-password", "")!!
        set(value) = prefs.edit().putString("hotspot-password", value).apply()

    // Set once this device has failed to bring its own access point back up after the app took it
    // down, which is the only way to find out that it cannot — no API answers the question in
    // advance. From then on a user exit leaves the hotspot alone; see UserExitHotspotPolicy.
    // Persisted rather than kept in memory because the answer is a property of the hardware and does
    // not change between runs, and the price of re-learning it is somebody's hotspot each time.
    var hotspotTeardownProvenUnsafe: Boolean
        get() = prefs.getBoolean("hotspot-teardown-proven-unsafe", false)
        set(value) = prefs.edit().putBoolean("hotspot-teardown-proven-unsafe", value).apply()

    var useLibusb: Boolean
        get() = prefs.getBoolean("use-libusb", false)
        set(value) = prefs.edit().putBoolean("use-libusb", value).apply()

}
