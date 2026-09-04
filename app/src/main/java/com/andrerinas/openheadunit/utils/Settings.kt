package com.andrerinas.openheadunit.utils

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.location.Location
import android.media.AudioManager
import android.os.Build
import com.andrerinas.openheadunit.input.MediaKeyRoutingPolicy
import com.andrerinas.openheadunit.decoder.video.VideoFaultInjector
import com.andrerinas.openheadunit.decoder.video.DeviceMemoryProfile
import com.andrerinas.openheadunit.decoder.audio.PlaybackFocusPolicy
import com.andrerinas.openheadunit.aap.VehicleTypePolicy
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.app.UsbAttachedActivity
import com.andrerinas.openheadunit.connection.usb.UsbBlacklistPolicy
import com.andrerinas.openheadunit.connection.usb.UsbDeviceCompat
import com.andrerinas.openheadunit.connection.wifi.direct.ObservedP2pGroup
import com.andrerinas.openheadunit.connection.wifi.direct.StoredP2pIdentity
import com.andrerinas.openheadunit.connection.wifi.modes.helper.HelperStrategy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeDriverSelectionPolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeStrategy
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
        @Synchronized get() = prefs.getStringSet("allow-devices", null)?.toSet() ?: emptySet()
        @Synchronized set(devices) {
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

    /**
     * The rate the microphone hardware is opened at when 16 kHz cannot be opened. Not what the phone
     * receives: that is always 16 kHz mono, because Android Auto validates the announced config and
     * rejects anything outside {16000, 48000} Hz.
     *
     * The list used to run 8000 to 48000 and the announcement was hardcoded at 16000, so every other
     * choice sent the phone PCM at a rate it had never been told about. A stored value from then is
     * migrated here rather than in a one-shot, since only the two remaining rates convert cleanly.
     */
    var micSampleRate: Int
        get() {
            val stored = prefs.getInt("mic-sample-rate", MicSampleRates.first())
            return if (stored in MicSampleRates) stored else MicSampleRates.first()
        }
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

    /** Auto-resume media playback on quick reconnect if music was playing before disconnect. */
    var autoResumePlaybackOnReconnect: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RESUME_PLAYBACK_ON_RECONNECT, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_RESUME_PLAYBACK_ON_RECONNECT, value).apply()
        }

    /**
     * In Self Mode, put the projection back on top when a call covers it.
     *
     * Android Auto's own call UI is already on the projected surface, so the phone's call screen
     * only hides it. Self Mode only: anywhere else the call screen is on the phone, not on the
     * head unit.
     */
    var raiseProjectionDuringCall: Boolean
        get() = prefs.getBoolean("raise-projection-during-call", true)
        set(value) {
            prefs.edit().putBoolean("raise-projection-during-call", value).apply()
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

    /**
     * Where captured logs come from. Read on every [com.andrerinas.openheadunit.utils.AppLog.init],
     * including the one in `App.onCreate`, so this getter must not do any work.
     *
     * It used to resolve its default by spawning `logcat` to see whether the ROM allows it. That ran
     * on the main thread at every process start, even when the preference below already answered the
     * question, and on Android 13+ each spawn raises the system log-access consent dialog. A
     * restricted ROM is now detected from the capture that actually runs, in [LogExporter]'s
     * zero-byte branch.
     */
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

    var aa174NoticeShown: Boolean
        get() = prefs.getBoolean("aa174_notice_shown", false)
        set(value) {
            prefs.edit().putBoolean("aa174_notice_shown", value).apply()
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
     * Which band to ask for when this app creates the Native AA WiFi Direct group.
     * 0 = automatic, 1 = 5 GHz only, 2 = 2.4 GHz only.
     *
     * The same question the hotspot route answers with [hotspotBand], and it is here because having
     * it on one transport and not the other was the accident rather than the design. `0` is exactly
     * what shipped before this setting existed: ask for 5 GHz, remake a group that lands on 2.4 GHz,
     * and drop to the platform's own choice if 5 GHz will not form at all.
     *
     * `1` stops that last step. A session on 2.4 GHz can connect, look entirely healthy and show
     * nothing, which is harder to diagnose than a group that never forms, so this is for a unit
     * whose fallback lands somewhere that shows no picture. `2` is for a radio that will not host a
     * 5 GHz group owner, and it disarms the band-mismatch retry with it - see
     * [com.andrerinas.openheadunit.connection.wifi.direct.NativeGroupBandPolicy], which is where that coupling lives.
     *
     * **Below API 29 none of this is a band request.** There is no `WifiP2pConfig.Builder` there, so
     * the app asks for an *operating channel* through hidden reflection instead, walking 5 GHz then
     * 2.4 GHz then no restriction at all - one rung per bring-up that forms no group. That ladder is
     * why 5 GHz is now tried by default on those units: the request is a disallowed-frequency list,
     * so a unit that cannot host a group owner on the band it names used to be left unable to form
     * a group rather than left on the other band, and a wrong answer cost it the connection. With
     * somewhere to fall back to it costs a round trip. See
     * [com.andrerinas.openheadunit.aap.P2pOperatingChannelPolicy]. Pre-Q also cannot read a group's
     * frequency back, so what the log records there is the request, never the result.
     *
     * Replaces the `debug-force-p2p-band-24` and `p2p-legacy-5ghz` flags, whose values migrate in
     * the getter below. Applies to the next group, so it needs a reconnect rather than only a write.
     */
    var wifiDirectBand: Int
        get() {
            if (prefs.contains("wifi-direct-band")) return prefs.getInt("wifi-direct-band", 0)
            // One-time migration off the two booleans this replaces. 2.4 GHz wins because it was the
            // deliberate override; the 5 GHz opt-in only ever meant "ask for it on pre-Q", which is
            // now what position 1 says.
            val migrated = when {
                prefs.getBoolean("debug-force-p2p-band-24", false) -> 2
                prefs.getBoolean("p2p-legacy-5ghz", false) -> 1
                else -> 0
            }
            prefs.edit().putInt("wifi-direct-band", migrated).apply()
            return migrated
        }
        set(value) = prefs.edit().putInt("wifi-direct-band", value).apply()

    /**
     * Which 5 GHz channel to ask for, on whichever transport is hosting the network.
     * 0 = automatic, otherwise the channel number: 36, 40, 44 or 48 (UNII-1), or 149 (UNII-3).
     *
     * A phone's regulatory domain decides which 5 GHz channels it will join, and a network on a
     * channel that domain forbids is one the phone never lists at all - not a weak connection, an
     * invisible one. Several domains refuse UNII-3, and asking Android for "the 5 GHz band" is
     * answered by picking a random one of eight candidates, four of which are UNII-3. That is what
     * this setting takes out of the hands of chance.
     *
     * One setting for both transports because the answer belongs to the user's phone and country
     * rather than to WiFi Direct or the access point. What each transport can do with it is not
     * equal: WiFi Direct asks for it through supported API and gets it, while the access point's
     * configuration call is refused outright on most head units, so there it is a request the unit
     * is free to ignore. See
     * [com.andrerinas.openheadunit.connection.wifi.FiveGhzChannelPolicy].
     *
     * Ignored when the matching band setting is 2.4 GHz only. Replaces `p2p-legacy-5ghz-upper`,
     * whose value migrates in the getter below. Applies to the next bring-up, so it needs a
     * reconnect rather than only a write.
     */
    var fiveGhzChannel: Int
        get() {
            if (prefs.contains("wifi-5ghz-channel")) return prefs.getInt("wifi-5ghz-channel", 0)
            // The boolean this replaces said only "UNII-3 rather than UNII-1", which is exactly
            // channel 149, and it only ever applied below API 29. Nothing is lost by widening it.
            val migrated = if (prefs.getBoolean("p2p-legacy-5ghz-upper", false)) 149 else 0
            prefs.edit().putInt("wifi-5ghz-channel", migrated).apply()
            return migrated
        }
        set(value) = prefs.edit().putInt("wifi-5ghz-channel", value).apply()

    /**
     * Keeps this app's dummy VPN up for the life of a Native AA session, not only offline Self Mode.
     *
     * Two reporters on different hardware describe the same periodic media outage going quiet while
     * the dummy VPN happened to be active. **The mechanism is unproven.** The setting's own
     * description tells the user it is so the chip stops looking for other networks, which is the
     * working theory and the right thing to tell somebody deciding whether to try it, but nothing
     * in this app has measured that and this comment must not be read as saying otherwise.
     *
     * Off by default. While it is up, every *other* app on the head unit loses IPv4: the tun routes
     * 0.0.0.0/0 into a descriptor nobody reads. This app is excluded from it, so the projection
     * link is unaffected.
     *
     * Inert on the Play Store flavor, which ships no VPN at all: [VpnControl.isVpnAvailable] is
     * false there, so the toggle is never rendered and nothing acts on this value.
     *
     * Only acted on for a Native AA wireless session; see
     * [com.andrerinas.openheadunit.utils.DummyVpnPolicy.shouldStartForSession], which explains why
     * the mode has to be re-tested at the point of use rather than trusted from this flag.
     */
    var keepDummyVpnDuringSession: Boolean
        get() = prefs.getBoolean("keep-dummy-vpn-during-session", false)
        set(value) { prefs.edit().putBoolean("keep-dummy-vpn-during-session", value).apply() }

    /**
     * Drops this unit's own WiFi association while the Native AA WiFi Direct group is brought up.
     *
     * On a single-radio unit an associated station leaves the group owner no free channel, so
     * wpa_supplicant forces the group onto the station's channel or refuses to make one at all. That
     * is the shape of a unit whose group never forms, and of one whose picture stutters because the
     * group is sharing the home network's channel.
     *
     * Off by default, and deliberately not a general remedy: the same association is measured to be
     * the *better* state once a session is running, on this same class of hardware. It is bounded to
     * the bring-up and the network is put back on teardown. See
     * [com.andrerinas.openheadunit.connection.wifi.direct.StationStandDownPolicy], which also holds
     * the rule for whether the platform will honour it at all.
     */
    var standDownStationForWifiDirect: Boolean
        get() = prefs.getBoolean("stand-down-station-for-wifi-direct", false)
        set(value) { prefs.edit().putBoolean("stand-down-station-for-wifi-direct", value).apply() }

    /**
     * The network id disabled by the stand-down above, or -1 for none standing.
     *
     * Written before the network is disabled rather than after, so a crash in between still leaves a
     * record to restore from. Not a user setting; it exists so a force-stop cannot leave the unit
     * unable to rejoin its own home network.
     */
    var stationStandDownNetworkId: Int
        get() = prefs.getInt("station-stand-down-network-id", -1)
        set(value) { prefs.edit().putInt("station-stand-down-network-id", value).apply() }

    /**
     * Keeps the Native AA WiFi Direct group's name and passphrase between bring-ups.
     *
     * On, the group is asked for as a persistent one with a pair drawn once and kept, so the phone
     * rejoins a network it already saved instead of being provisioned a new one every session. Off,
     * every create names a new temporary network, which is what every 3.2.x and 3.3.x build did.
     * See [com.andrerinas.openheadunit.connection.wifi.direct.P2pGroupIdentityPolicy].
     */
    var wifiDirectStableIdentity: Boolean
        get() = prefs.getBoolean("wifi-direct-stable-identity", true)
        set(value) { prefs.edit().putBoolean("wifi-direct-stable-identity", value).apply() }

    /**
     * The kept pair, or null before the first bring-up draws one.
     *
     * Written by WifiDirectManager when the policy draws it, and by the settings screen's "new
     * identity" action. Always both halves together: a name reused with a different passphrase is
     * the one combination a phone's saved profile cannot recover from on its own.
     */
    var wifiDirectGroupIdentity: StoredP2pIdentity?
        get() {
            val name = prefs.getString("wifi-direct-group-name", null) ?: return null
            val passphrase = prefs.getString("wifi-direct-group-passphrase", null) ?: return null
            return StoredP2pIdentity(name, passphrase)
        }
        set(value) {
            prefs.edit()
                .putString("wifi-direct-group-name", value?.networkName)
                .putString("wifi-direct-group-passphrase", value?.passphrase)
                .apply()
        }

    /**
     * The last Native AA group this unit hosted, as the phone saw it, so the next one can be
     * compared to it. Whether the BSSID repeats is a per-unit fact the platform does not expose,
     * so it is measured across bring-ups (GroupIdentityStabilityPolicy). Not a user setting.
     */
    var wifiDirectLastGroup: ObservedP2pGroup?
        get() {
            val ssid = prefs.getString("wifi-direct-last-group-ssid", null) ?: return null
            val bssid = prefs.getString("wifi-direct-last-group-bssid", null) ?: return null
            return ObservedP2pGroup(ssid, bssid)
        }
        set(value) {
            prefs.edit()
                .putString("wifi-direct-last-group-ssid", value?.ssid)
                .putString("wifi-direct-last-group-bssid", value?.bssid)
                .apply()
        }

    /**
     * Asks the decoder for low-latency mode, through whichever key its vendor understands.
     *
     * Off by default, and it stays off until a log from a real device shows the key changing the
     * decode latency on the throughput line - not merely being accepted, which a component can do
     * while ignoring it. The configure ladder is what makes turning it on cheap to try: a rejected
     * key costs one retry instead of the session. See
     * [com.andrerinas.openheadunit.decoder.video.DecoderConfigLadder].
     */
    var debugVideoLowLatency: Boolean
        get() = prefs.getBoolean("debug-video-low-latency", false)
        set(value) { prefs.edit().putBoolean("debug-video-low-latency", value).apply() }

    /**
     * Holds the feed thread for this many milliseconds after every frame it hands the codec,
     * simulating a decoder slower than the stream.
     *
     * Off at 0, and meant to stay off. The backpressure path only engages when a codec cannot
     * drain the negotiated rate, which a healthy rig never produces - so without this there is
     * no way to show the pacing works except to wait for a reporter's next drive. The hold sits
     * after the feed rather than inside the codec's own wait, so the codec stays healthy and
     * rendering while the queue fills: the same shape as a real decoder at its ceiling, which
     * keeps decoding all through the event. A session with this on announces it loudly at
     * feed-thread start, so a captured log can never be mistaken for a log of a real fault.
     * Capped so a forgotten setting slows a session to a crawl rather than reading as a dead
     * decoder.
     */
    var debugVideoFeedHoldMs: Int
        get() = prefs.getInt("debug-video-feed-hold-ms", 0)
        set(value) { prefs.edit().putInt("debug-video-feed-hold-ms", value.coerceIn(0, 250)).apply() }

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

    var vehicleType: Int
        get() = VehicleTypePolicy.sanitised(prefs.getInt("vehicle-type", VehicleTypePolicy.CAR))
        set(value) { prefs.edit().putInt("vehicle-type", value).apply() }

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

    /**
     * Whether a wireless session on a radio with no 5 GHz band is asked for less than the settings
     * above say. On by default: measured on such a link, a full-rate stream carried no frame at all.
     */
    var narrowBandProfileCap: Boolean
        get() = prefs.getBoolean("narrow-band-profile-cap", true)
        set(value) { prefs.edit().putBoolean("narrow-band-profile-cap", value).apply() }

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

    /** Whether Self Mode settings should be shown (empty selection shows everything). */
    fun showsSelf(): Boolean = connectionModes.isEmpty() || ConnectionMode.SELF in connectionModes

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

    /**
     * Whether the head unit records at all. Off leaves the microphone to the phone.
     *
     * Off does not omit the microphone service: Android Auto's required-service check refuses a
     * head unit that does not declare one. It declares it, declines every request, and sends
     * nothing, which is what frees the physical microphone for a Bluetooth headset or intercom.
     */
    var useHeadUnitMicrophone: Boolean
        get() = prefs.getBoolean("use-head-unit-microphone", true)
        set(value) {
            prefs.edit().putBoolean("use-head-unit-microphone", value).apply()
        }

    var enableAudioSink: Boolean
        get() = prefs.getBoolean("enable-audio-sink", true)
        set(value) { prefs.edit().putBoolean("enable-audio-sink", value).apply() }

    var staticAudioFocus: Boolean
        get() = prefs.getBoolean("static-audio-focus", false)
        set(value) { prefs.edit().putBoolean("static-audio-focus", value).apply() }

    // Whether AA playback takes system audio focus, so another local player (typically the car
    // radio) pauses while it runs. AUTO takes it until the phone is seen cutting its own audio in
    // response, which is what happens when this head unit is its Bluetooth A2DP sink. See
    // PlaybackFocusPolicy for the whole story; ALWAYS and NEVER are the manual overrides.
    var playbackFocusMode: PlaybackFocusPolicy.Mode
        get() = PlaybackFocusPolicy.Mode.fromInt(
            prefs.getInt("playback-focus-mode", PlaybackFocusPolicy.Mode.AUTO.value)
        )
        set(value) { prefs.edit().putInt("playback-focus-mode", value.value).apply() }

    // What AUTO learned: taking system audio focus stops the phone's own playback on this head
    // unit, so stop asking for it. Remembered because the trial that finds it out costs the user a
    // couple of interrupted tracks, and there is no reason to pay that at every connect. Re-picking
    // the focus mode clears it, which is the way back from a false positive.
    var playbackFocusSelfDefeating: Boolean
        get() = prefs.getBoolean("playback-focus-self-defeating", false)
        set(value) { prefs.edit().putBoolean("playback-focus-self-defeating", value).apply() }

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

    // Which system stream each Android Auto audio channel is played on, stored as the
    // AudioManager.STREAM_* constant. Named after the AudioStreamType the channel is declared as
    // in ServiceDiscoveryResponse - MEDIA on ID_AUD, SPEECH on ID_AU1, SYSTEM on ID_AU2 - because
    // the Android stream a channel is pointed at is exactly what these choose, and naming them
    // after the streams instead made the two impossible to talk about apart.
    //
    // The defaults reproduce the mapping that separate streams always used, so an install that
    // never opens the screen behaves exactly as before; the pickers exist because head units route
    // the streams differently (a unit whose amplifier only unmutes on STREAM_MUSIC needs the
    // guidance channel moved off STREAM_VOICE_CALL to be heard at all).
    //
    // Only the media stream applies while separateAudioStreams is off: every channel then shares
    // it, which is what "a single multimedia stream" means.
    var mediaAudioStream: Int
        get() = prefs.getInt("media-audio-stream", AudioManager.STREAM_MUSIC)
        set(value) { prefs.edit().putInt("media-audio-stream", value).apply() }

    var guidanceAudioStream: Int
        get() = prefs.getInt("guidance-audio-stream", AudioManager.STREAM_VOICE_CALL)
        set(value) { prefs.edit().putInt("guidance-audio-stream", value).apply() }

    var systemAudioStream: Int
        get() = prefs.getInt("system-audio-stream", AudioManager.STREAM_NOTIFICATION)
        set(value) { prefs.edit().putInt("system-audio-stream", value).apply() }

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

    var autoConnectDelaySeconds: Int
        get() = prefs.getInt("auto-connect-delay-seconds", 0)
        set(value) { prefs.edit().putInt("auto-connect-delay-seconds", value).apply() }

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
        @Synchronized get() = prefs.getStringSet(KEY_USB_BLACKLIST, null)?.toSet() ?: emptySet()
        @Synchronized set(value) { storeUsbBlacklist(UsbBlacklistPolicy.normalise(value)) }

    fun formatUsbVidPidDisplay(vid: Int, pid: Int): String = Companion.formatUsbVidPidDisplay(vid, pid)

    @Synchronized
    fun isUsbDeviceBlacklisted(device: UsbDevice): Boolean = UsbBlacklistPolicy.isBlacklisted(
        usbBlacklist, UsbDeviceCompat.blacklistKey(device), device.vendorId, device.productId
    )

    @Synchronized
    fun addUsbDeviceToBlacklist(device: UsbDevice) {
        storeUsbBlacklist(UsbBlacklistPolicy.add(usbBlacklist, UsbDeviceCompat.blacklistKey(device)))
    }

    @Synchronized
    fun removeUsbDeviceFromBlacklist(device: UsbDevice) {
        storeUsbBlacklist(UsbBlacklistPolicy.remove(
            usbBlacklist, UsbDeviceCompat.blacklistKey(device), device.vendorId, device.productId
        ))
    }

    /**
     * Every write goes through here so the device-protected mirror can never fall behind. The
     * mirror is what [isUsbDeviceBlacklisted] reads on a locked boot, where credential storage
     * is unavailable and the check used to be skipped entirely.
     */
    private fun storeUsbBlacklist(value: Set<String>) {
        prefs.edit().putStringSet(KEY_USB_BLACKLIST, value).apply()
        syncUsbBlacklistToDeviceStorage(context, value)
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

    /**
     * Devices the Native AA wake poke connects to, to make Android Auto start on the phone.
     *
     * Its own setting since the auto-start list was both this and the trigger for
     * `AutoStartReceiver`, and the handshake wrote a completed peer back into it - so clearing
     * auto-start undid itself on the next poke cycle. Seeded from the auto-start list once, so a
     * unit that has been poking a phone keeps poking it. Read after unlock only, so no mirror.
     */
    var nativePokeBtMacs: Set<String>
        get() {
            var macs = prefs.getStringSet("native-poke-bt-macs", null)
            if (macs == null) {
                macs = autoStartBluetoothDeviceMacs
                prefs.edit().putStringSet("native-poke-bt-macs", macs).apply()
            }
            return macs
        }
        set(value) {
            prefs.edit().putStringSet("native-poke-bt-macs", value).apply()
        }

    /**
     * Whether an empty [nativePokeBtMacs] pokes every paired device instead of nothing.
     *
     * What the empty list used to do implicitly, kept as the default because the poke is the only
     * thing that starts Android Auto on some units and a unit with nothing chosen yet must still
     * connect. Turning it off is how a user says "this phone only".
     */
    var nativePokeAllPairedDevices: Boolean
        get() = prefs.getBoolean("native-poke-all-paired", true)
        set(value) { prefs.edit().putBoolean("native-poke-all-paired", value).apply() }

    /**
     * Devices whose Bluetooth link going away ends a session. Deliberately not the auto-start list,
     * which doubles as the wake-poke targets. Read after unlock only, so no device-protected mirror.
     */
    var autoDisconnectBluetoothDeviceMacs: Set<String>
        get() = prefs.getStringSet("auto-disconnect-bt-macs", null) ?: emptySet()
        set(value) { prefs.edit().putStringSet("auto-disconnect-bt-macs", value).apply() }

    /** Grace period before a watched device's absence counts. 0 ends the session at once. */
    var autoDisconnectBtDelaySeconds: Int
        get() = prefs.getInt("auto-disconnect-bt-delay-seconds", 5)
        set(value) { prefs.edit().putInt("auto-disconnect-bt-delay-seconds", value).apply() }

    var appLanguage: String
        get() = prefs.getString("app-language", "")!!
        set(value) { prefs.edit().putString("app-language", value).apply() }

    // Per-channel playback gain, as a percentage offset applied by AudioDecoder.setGain. Named
    // after the channel each one drives - MEDIA on ID_AUD, guidance on ID_AU1, system sounds on
    // ID_AU2 - which is not what the stored keys say: "assistant" and "navigation" were the old
    // names for the last two, and ID_AU2 was never the navigation channel (spoken directions
    // arrive on ID_AU1 with the assistant). The keys keep the old spelling because they are
    // shipped data and renaming them would reset every existing user's offsets.
    var mediaVolumeOffset: Int
        get() = prefs.getInt("media-volume-offset", 0)
        set(value) { prefs.edit().putInt("media-volume-offset", value).apply() }

    var guidanceVolumeOffset: Int
        get() = prefs.getInt("assistant-volume-offset", 0)
        set(value) { prefs.edit().putInt("assistant-volume-offset", value).apply() }

    var systemVolumeOffset: Int
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
            // The legacy key alone left the set AutoStartReceiver actually reads intact, so a
            // reset kept auto-starting on the old phone.
            syncAutoStartBtMacsToDeviceStorage(context, emptySet())
            syncUsbBlacklistToDeviceStorage(context, emptySet())
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

        /** SharedPreferences key; also used by [com.andrerinas.openheadunit.aap.AapService] for change listener. */
        const val KEY_SYNC_MEDIA_SESSION_AA_METADATA = "sync-media-session-aa-metadata"
        const val KEY_AUTO_RESUME_PLAYBACK_ON_RECONNECT = "auto-resume-playback-on-reconnect"

        /** SharedPreferences key; also used by [com.andrerinas.openheadunit.aap.AapService] for change listener. */
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
        const val KEY_USB_BLACKLIST = "usb-blacklist"

        fun formatUsbVidPidDisplay(vid: Int, pid: Int): String =
            String.format(java.util.Locale.US, "VID: 0x%04X, PID: 0x%04X", vid, pid)

        /**
         * Reads the USB blacklist from device-protected storage, so a blacklisted device is
         * refused during a locked boot too. Falls back to regular prefs below API 24.
         */
        fun isUsbDeviceBlacklisted(context: Context, device: UsbDevice): Boolean {
            val prefs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
                    .getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
            } else {
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }
            val blacklist = prefs.getStringSet(KEY_USB_BLACKLIST, null) ?: return false
            return UsbBlacklistPolicy.isBlacklisted(
                blacklist, UsbDeviceCompat.blacklistKey(device), device.vendorId, device.productId
            )
        }

        fun syncUsbBlacklistToDeviceStorage(context: Context, blacklist: Set<String>) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                context.createDeviceProtectedStorageContext()
                    .getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putStringSet(KEY_USB_BLACKLIST, blacklist)
                    .apply()
            }
        }

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

        /**
         * The two rates Android Auto accepts. 48000 is the fallback because it is a whole multiple
         * of 16000, so converting it needs no filter; 44100 and the rest were never usable.
         */
        val MicSampleRates = listOf(16000, 48000)

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

    enum class BackgroundNightMode(val value: Int) {
        DIM(0),
        PURE_BLACK(1),
        NONE(2);

        companion object {
            private val map = values().associateBy(BackgroundNightMode::value)
            fun fromInt(value: Int) = map[value] ?: DIM
        }
    }

    var homeBackgroundNightMode: BackgroundNightMode
        get() {
            val value = prefs.getInt("home-background-night-mode", BackgroundNightMode.DIM.value)
            return BackgroundNightMode.fromInt(value)
        }
        set(mode) {
            prefs.edit().putInt("home-background-night-mode", mode.value).apply()
        }

    var autoMonochromeButtonsAtNight: Boolean
        get() = prefs.getBoolean("auto-monochrome-buttons-at-night", false)
        set(value) { prefs.edit().putBoolean("auto-monochrome-buttons-at-night", value).apply() }

    var monochromeIcons: Boolean
        get() = autoMonochromeButtonsAtNight
        set(value) { autoMonochromeButtonsAtNight = value }

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
    // 0 = WiFi Direct P2P group, 1 = this head unit's own hotspot.
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
    // Off by default: it is the one change on this route that alters what a unit with a working
    // setup puts on the wire, so it stays opt-in. What it buys is the WPP-over-TCP endpoint, which
    // from Android Auto 17.4 is how a reconnect happens with nothing running on the phone. That
    // endpoint goes out on the hotspot transport, and on WiFi Direct once this unit's group has been
    // seen to keep its name and address across bring-ups (see WppEndpointPolicy). Fields 3 and 4 of
    // the request are still undecoded and are the shape a channel hint would take.
    var nativeWifiVersionExchange: Boolean
        get() = prefs.getBoolean("native-wifi-version-exchange", false)
        set(value) = prefs.edit().putBoolean("native-wifi-version-exchange", value).apply()

    var nativeDriverSelectionMode: NativeDriverSelectionPolicy.Mode
        get() = NativeDriverSelectionPolicy.Mode.fromId(
            prefs.getInt("native-driver-selection-mode", NativeDriverSelectionPolicy.Mode.AUTO.id)
        )
        set(value) = prefs.edit().putInt("native-driver-selection-mode", value.id).apply()

    var nativeDriverSelectionTimeoutSec: Int
        get() = prefs.getInt("native-driver-selection-timeout", NativeDriverSelectionPolicy.DEFAULT_TIMEOUT_SEC)
        set(value) = prefs.edit().putInt("native-driver-selection-timeout", NativeDriverSelectionPolicy.sanitizeTimeout(value)).apply()

    var nativePreferredDeviceMac: String
        get() = prefs.getString("native-preferred-device-mac", "") ?: ""
        set(value) = prefs.edit().putString("native-preferred-device-mac", value).apply()

    var lastConnectedNativeMac: String
        get() = prefs.getString("last-connected-native-mac", "") ?: ""
        set(value) = prefs.edit().putString("last-connected-native-mac", value).apply()

    // ---------------------------------------------------------------------------------------------
    // Standing connection failures.
    //
    // A record of observation rather than a preference, so these follow different rules from
    // everything else in this file: nothing in a manager's stop()/start(), a mode change or a user
    // exit clears them. Each is cleared only by the condition itself ceasing to be true, at the one
    // site that can know that. See utils/ConnectionIssues.
    //
    // Wall clock, not SystemClock.elapsedRealtime(): the banner renders a time and these outlive a
    // reboot, which an elapsed-realtime stamp does not survive. Every other stamp on this route is
    // elapsed realtime, so the AtEpochMs suffix is the reminder that these are not.
    //
    // 0 means "not standing" in all five.
    // ---------------------------------------------------------------------------------------------

    /** The phone connected over Bluetooth, we wrote, and nothing ever came back. */
    var connectionIssueBluetoothSilentAtEpochMs: Long
        get() = prefs.getLong("connection-issue-bt-silent", 0L)
        set(value) = prefs.edit().putLong("connection-issue-bt-silent", value).apply()

    /** No usable BSSID, which the WiFi Direct route aborts on rather than sending. */
    var connectionIssueBssidAtEpochMs: Long
        get() = prefs.getLong("connection-issue-bssid", 0L)
        set(value) = prefs.edit().putLong("connection-issue-bssid", value).apply()

    /** The device will not name its own access point, so the phone had nothing to join. */
    var connectionIssueHotspotConfigAtEpochMs: Long
        get() = prefs.getLong("connection-issue-hotspot-config", 0L)
        set(value) = prefs.edit().putLong("connection-issue-hotspot-config", value).apply()

    /** No access point was up at all, so there was no network to send the phone to. */
    var connectionIssueHotspotOffAtEpochMs: Long
        get() = prefs.getLong("connection-issue-hotspot-off", 0L)
        set(value) = prefs.edit().putLong("connection-issue-hotspot-off", value).apply()

    /** The platform refused to create a WiFi Direct group, so there was no network to hand over. */
    var connectionIssueWifiDirectRefusedAtEpochMs: Long
        get() = prefs.getLong("connection-issue-p2p-refused", 0L)
        set(value) = prefs.edit().putLong("connection-issue-p2p-refused", value).apply()

    /** Something else on this unit kept switching WiFi Direct off and on, so no group could form. */
    var connectionIssueWifiDirectCycledAtEpochMs: Long
        get() = prefs.getLong("connection-issue-p2p-cycled", 0L)
        set(value) = prefs.edit().putLong("connection-issue-p2p-cycled", value).apply()

    /** Sessions kept starting and ending without a single video frame, so the link cannot carry it. */
    var connectionIssueVideoLinkTooSlowAtEpochMs: Long
        get() = prefs.getLong("connection-issue-video-starved", 0L)
        set(value) = prefs.edit().putLong("connection-issue-video-starved", value).apply()

    /**
     * When the user last dismissed the failure banner.
     *
     * Compared against the raise stamps rather than clearing them, so a dismissal hides the
     * occurrence the user has seen and the next failure brings the banner back on its own.
     */
    var connectionIssueDismissedAtEpochMs: Long
        get() = prefs.getLong("connection-issue-dismissed-at", 0L)
        set(value) = prefs.edit().putLong("connection-issue-dismissed-at", value).apply()

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

    // Which band to ask for when this app brings the head unit's access point up.
    // 0 = automatic (5 GHz, falling back to 2.4 GHz), 1 = 5 GHz only, 2 = 2.4 GHz only.
    //
    // 5 GHz is the default because it is the only band measured to carry a full-resolution stream:
    // on 2.4 GHz a 1080p/60 session connects, opens the video channel and dies having sent nothing,
    // while the same access point carries 800x480/30 indefinitely. So "5 GHz only" is the setting
    // for a unit whose fallback lands somewhere that shows no picture, and "2.4 GHz only" is for a
    // radio that will not host 5 GHz at all, at a lower resolution and frame rate.
    //
    // Plenty of head units refuse setSoftApConfiguration() outright, and on those this has no
    // effect whatever it is set to: the band request never reaches the framework and the access
    // point comes up on whatever the device already had. The log says which of those happened.
    //
    // Applies at the next hotspot bring-up, so it needs a reconnect rather than only a write. See
    // SoftApBandPolicy, which is where the ordering lives.
    var hotspotBand: Int
        get() = prefs.getInt("hotspot-band", 0)
        set(value) = prefs.edit().putInt("hotspot-band", value).apply()

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

    // Publish the Android Auto RFCOMM record as insecure, so a phone can open it without the link
    // having been authenticated first. It changes nothing where the two devices are already bonded,
    // which is every setup that has ever reached wireless Android Auto; it is for the case where
    // the bond or its link key has been dropped on one side, which a phone standing in for a head
    // unit reaches more easily than a car does.
    //
    // Off by default, and a security trade rather than a free win: the handshake behind that record
    // hands the peer this network's name, passphrase and BSSID, so anything in radio range can ask
    // for them. The bond check on accept is what still refuses an unknown device.
    var insecureAaRfcommListener: Boolean
        get() = prefs.getBoolean("insecure-aa-rfcomm-listener", false)
        set(value) = prefs.edit().putBoolean("insecure-aa-rfcomm-listener", value).apply()

    // Open a hands-free service level connection instead of holding a silent channel. Android Auto
    // will not start wireless setup unless the head unit is connected with a Bluetooth profile, and
    // a silent channel leaves the phone's hands-free state machine half-open until it times out, so
    // it never counts. Measured on hardware: a full wireless session with this on, none with it off.
    //
    // On by default, because it only runs where this app stands in for a radio with no hands-free
    // stack of its own, and stands down again where a real hands-free link is already up.
    //
    // What a completed stand-in link does to call routing is not measured: no round has placed or
    // taken a call with this on, and the responder negotiates neither a codec nor a SCO link, so it
    // could not carry one. The switch is here for the user who would rather not find out.
    var nativeAaCompleteHfpSlc: Boolean
        get() = prefs.getBoolean("native-aa-complete-hfp-slc", true)
        set(value) = prefs.edit().putBoolean("native-aa-complete-hfp-slc", value).apply()

    // Run the Native AA Bluetooth route on a unit ExternalBtPolicy has flagged, instead of refusing
    // to start it. The detection marks a class of hardware rather than measuring the unit in front
    // of us, so it must not be the one refusal a user cannot argue with.
    //
    // Off by default, and unlikely to help: on the one such unit examined closely the Android radio
    // accepted every write, flushed, and put nothing on the air.
    var nativeAaIgnoreExternalBt: Boolean
        get() = prefs.getBoolean("native-aa-ignore-external-bt", false)
        set(value) = prefs.edit().putBoolean("native-aa-ignore-external-bt", value).apply()

}
