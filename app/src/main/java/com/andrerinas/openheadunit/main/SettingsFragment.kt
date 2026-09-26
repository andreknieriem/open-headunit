package com.andrerinas.openheadunit.main

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SystemSettings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import android.content.ClipData
import android.content.ClipboardManager
import com.andrerinas.openheadunit.utils.OemAppManager
import com.andrerinas.openheadunit.utils.CarLauncherManager
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.CredentialField
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeAaWakeDamagePolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.ZbtProbe
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.ZbtDaemonReachability
import com.andrerinas.openheadunit.input.MediaKeyRoutingPolicy
import com.andrerinas.openheadunit.connection.wifi.direct.ObservedP2pCredentials
import com.andrerinas.openheadunit.connection.wifi.direct.P2pIdentityEdit
import com.andrerinas.openheadunit.connection.wifi.direct.P2pIdentityEditPolicy
import com.andrerinas.openheadunit.connection.wifi.direct.P2pIdentityRejection
import com.andrerinas.openheadunit.connection.wifi.direct.P2pIdentityRotationPolicy
import com.andrerinas.openheadunit.connection.wifi.direct.StoredP2pIdentity
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeCredentialsPreflightPolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeDriverSelectionPolicy
import com.andrerinas.openheadunit.aap.NativeTransport
import com.andrerinas.openheadunit.connection.wifi.FiveGhzChannelPolicy
import com.andrerinas.openheadunit.connection.wifi.direct.P2pBandPreference
import com.andrerinas.openheadunit.connection.wifi.direct.StationStandDownPolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.HotspotBandPreference
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.PreflightReport
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.SoftApBssidPolicy
import com.andrerinas.openheadunit.decoder.audio.PlaybackFocusPolicy
import com.andrerinas.openheadunit.decoder.video.VideoFaultInjector
import com.andrerinas.openheadunit.decoder.video.DeviceMemoryProfile
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppPermissions
import com.andrerinas.openheadunit.utils.AppThemeManager
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.LocaleHelper
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.utils.LogExporter
import com.andrerinas.openheadunit.utils.SettingsBackupManager
import com.andrerinas.openheadunit.utils.ToastUtils
import com.andrerinas.openheadunit.utils.VpnControl
import com.andrerinas.openheadunit.utils.DialogUtils
import com.andrerinas.openheadunit.utils.ProjectionSetupQrDialog
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeAaHandshakeManager
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeCredentialsPreflight
import com.andrerinas.openheadunit.utils.BluetoothHelper
import androidx.lifecycle.lifecycleScope
import com.andrerinas.openheadunit.connection.wifi.modes.helper.HelperStrategy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeStrategy
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.WirelessRearmPolicy
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {
    private lateinit var settings: Settings

    /** The in-flight credentials pre-flight, so a second one replaces it rather than racing it. */
    private var preflightJob: Job? = null
    private lateinit var settingsRecyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar
    private var saveButton: MaterialButton? = null
    private var resetButton: MaterialButton? = null

    // Basic/Advanced tab + search state (Feature A)
    private var settingsTabGroup: com.google.android.material.button.MaterialButtonToggleGroup? = null
    private var searchInput: com.google.android.material.textfield.TextInputEditText? = null
    private var searchQuery: String = ""
    // The complete, unfiltered list built by updateSettingsList(); rendering filters this.
    private var fullSettingsList: List<SettingItem> = emptyList()

    // Curated everyday options shown in the Basic tab. Advanced shows everything.
    // Wireless items here only appear in Basic when the user connects wirelessly (see filterSettings).
    private val basicSettingIds = setOf(
        // General
        "autoOptimize", "connectionMode", "appLanguage",
        // Wireless (shown in Basic only when WiFi is among the selected connection modes).
        // The hotspot entries come along with the transport choice: they only render once Hotspot
        // is picked, and on a device that will not let an app read its hotspot configuration the
        // manual name is the only way to finish setting the route up.
        "wifiConnectionMode",
        "nativeApTransport", "nativeApTransportHint", "hotspotBand", "hotspotBandHint",
        // The WiFi Direct band, for the same reason as the hotspot one beside it: it is the first
        // thing to try when a wireless session connects and shows no picture, and a user sent to
        // Advanced to find it is a user who never finds it.
        "wifiDirectBand", "wifiDirectBandHint",
        // And the channel within the band, which is the same kind of first thing to try: a
        // network on a channel the phone's regulatory domain forbids is one it never lists.
        "fiveGhzChannel",
        "hotspotSsidOverride", "hotspotPasswordOverride",
        // "What is my head unit's WiFi password" is an everyday question, not an Advanced one.
        "wifiDirectLastNetwork", "wifiDirectGroupIdentity",
        "hotspotInterfaceOverride",
        // Dark mode
        "darkModeSettings",
        // Automation
        "autoStartSettings", "autoConnectSettings", "enableCarLauncher",
        // Navigation
        "gpsNavigation",
        // Graphic
        "resolution", "dpiPixelDensity", "viewMode", "screenOrientation", "startInFullscreenMode",
        // Theming
        "theming", "loadingScreen", "customization",
        // Video
        "videoCodec", "fpsLimit",
        // Input
        "keymap",
        // Audio
        "enableAudioSink", "audioStreamSettings", "useAAudioOutput", "micSettings", "audioVolumeOffsets",
        // Info
        "version", "about", "support"
    )

    // Local state to hold changes before saving
    private var pendingAdvancedSettings: Boolean? = null
    private var pendingUseGps: Boolean? = null
    private var pendingShowNavigationNotifications: Boolean? = null
    private var pendingSyncMediaSessionAaMetadata: Boolean? = null
    private var pendingAutoResumePlaybackOnReconnect: Boolean? = null
    private var pendingResolution: Int? = null
    private var pendingDpi: Int? = null
    private var pendingPixelAspectRatioE4: Int? = null
    private var pendingStaticBSSID: String? = null
    private var pendingStaticP2pBSSID: String? = null
    private var pendingFullscreenMode: Settings.FullscreenMode? = null
    private var pendingViewMode: Settings.ViewMode? = null
    private var pendingForceSoftware: Boolean? = null
    private var pendingSoftwareVideoDecoder: Settings.SoftwareVideoDecoder? = null
    private var pendingVideoCodec: String? = null
    private var pendingFpsLimit: Int? = null
    private var pendingBluetoothAddress: String? = null
    private var pendingEnableAudioSink: Boolean? = null
    private var pendingStaticAudioFocus: Boolean? = null
    private var pendingPlaybackFocusMode: PlaybackFocusPolicy.Mode? = null
    private var pendingUseAacAudio: Boolean? = null
    private var pendingUseAAudioOutput: Boolean? = null
    private var pendingAttachHwDspEqualizer: Boolean? = null
    private var pendingMicInputSource: Int? = null
    private var pendingEnableRotary: Boolean? = null
    private var pendingMediaKeyRouting: MediaKeyRoutingPolicy.Mode? = null
    private var pendingAudioLatencyMultiplier: Int? = null
    private var pendingUseLibusb: Boolean? = null
    private var pendingAudioQueueCapacity: Int? = null
    private var pendingShowPerformanceOverlay: Boolean? = null
    private var pendingOverlayPosition: Settings.OverlayPosition? = null
    private var pendingShowToastMessages: Boolean? = null
    private var pendingScreenOrientation: Settings.ScreenOrientation? = null
    private var pendingAppLanguage: String? = null
    private var pendingFakeSpeed: Boolean? = null
    private var pendingNarrowBandProfileCap: Boolean? = null
    private var pendingDebugVideoLowLatency: Boolean? = null
    private var pendingAllowExternalConfiguration: Boolean? = null
    private var pendingKeepDummyVpnDuringSession: Boolean? = null

    private var pendingWifiConnectionMode: WifiLauncherMode? = null
    private var pendingHelperConnectionStrategy: HelperStrategy? = null
    private var pendingAutoEnableHotspot: Boolean? = null
    private var pendingWaitForWifi: Boolean? = null
    private var pendingWaitForWifiTimeout: Int? = null
    private var pendingBluetoothManagerServiceName: String? = null
    private var pendingNativeAaIgnoreExternalBt: Boolean? = null
    private var pendingExternalBtZbtTransport: Boolean? = null
    private var pendingNativeAaCompleteHfpSlc: Boolean? = null
    private var pendingAnnounceConnectionConfiguration: Boolean? = null

    // The probe's verdict is not a pending setting: it changes nothing and there is nothing to
    // save. It lives in the companion object with the job that produces it. This one is
    // per-view, because it is the thing that repaints the row.
    private var zbtProbeFollowJob: Job? = null
    /** Resolves which external-BT route the Native AA dialog should describe. Cancelled on exit. */
    private var externalBtRouteJob: Job? = null
    private var pendingNativeApTransport: NativeStrategy? = null
    private var pendingNativeDriverSelectionMode: NativeDriverSelectionPolicy.Mode? = null
    private var pendingNativeDriverSelectionTimeout: Int? = null
    private var pendingNativePreferredDeviceMac: String? = null
    private var pendingWifiDirectBand: Int? = null
    private var pendingWifiDirectStableIdentity: Boolean? = null
    // The pair and its provenance move together; null on the second means neither was edited,
    // which is the only way null on the first can mean "back to a pair the app draws".
    private var pendingWifiDirectGroupIdentity: StoredP2pIdentity? = null
    private var pendingWifiDirectIdentityUserSet: Boolean? = null
    private var pendingStationStandDownMode: Int? = null
    private var pendingHotspotBand: Int? = null
    private var pendingFiveGhzChannel: Int? = null
    private var pendingHotspotSsid: String? = null
    private var pendingHotspotPassword: String? = null
    private var pendingHotspotInterface: String? = null

    private var pendingEnableCarLauncher: Boolean? = null
    private var pendingEnableFloatingButton: Boolean? = null
    private var pendingFloatingButtonSizeDp: Int? = null
    private var pendingFloatingButtonOpacityPercent: Int? = null
    private var pendingFloatingButtonXPercent: Int? = null
    private var pendingFloatingButtonYPercent: Int? = null
    private var pendingAaExitAction: Settings.ExitAction? = null
    private var pendingVideoFitMode: Settings.VideoFitMode? = null
    private var pendingForcedScale: Boolean? = null
    private var pendingHudMirroring: Boolean? = null

    private var pendingKillOnDisconnect: Boolean? = null
    private var pendingAutoKillOemApps: Boolean? = null
    private var pendingRaiseProjectionDuringCall: Boolean? = null

    // Custom Insets
    private var pendingInsetLeft: Int? = null
    private var pendingInsetTop: Int? = null
    private var pendingInsetRight: Int? = null
    private var pendingInsetBottom: Int? = null



    private var pendingMediaVolumeOffset: Int? = null
    private var pendingGuidanceVolumeOffset: Int? = null
    private var pendingSystemVolumeOffset: Int? = null

    private var pendingHideBatteryLevel: Boolean? = null
    private var pendingHidePhoneSignal: Boolean? = null
    private var pendingHideClock: Boolean? = null

    private var requiresRestart = false
    private var hasChanges = false
    private val SAVE_ITEM_ID = 1001
    private val RESET_ITEM_ID = 1002
    private var pendingStorageAction: (() -> Unit)? = null

    private val bluetoothPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            handleNativeAaSelection()
        } else {
            ToastUtils.showToast(requireContext(), R.string.bt_permission_denied, Toast.LENGTH_LONG, force = true)
        }
    }

    // VpnControl.consentIntent() needs an Activity the first time and returns null forever
    // afterwards, so this dialog is the one moment a Fragment has to be involved. AapService can
    // start the VPN with no Activity once this has run. On the Play Store flavor the toggle that
    // launches this is never rendered, because VpnControl.isVpnAvailable() is false there.
    private var vpnConsentRequested = false
    private val vpnConsentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val granted = result.resultCode == android.app.Activity.RESULT_OK
        if (vpnConsentRequested) {
            pendingKeepDummyVpnDuringSession = granted
            checkChanges()
        }
        vpnConsentRequested = false
        if (!granted && VpnControl.consentDeniedRes != 0) {
            ToastUtils.showToast(requireContext(), VpnControl.consentDeniedRes, Toast.LENGTH_LONG, force = true)
        }
        updateSettingsList()
    }

    private val storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        val action = pendingStorageAction
        pendingStorageAction = null
        if (isGranted) {
            action?.invoke()
        } else {
            ToastUtils.showToast(requireContext(), R.string.storage_permission_denied_backup, Toast.LENGTH_LONG, force = true)
        }
    }

    private val exportSettingsLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument(SettingsBackupManager.MIME_TYPE)
    ) { uri ->
        uri?.let { exportSettingsToUri(it) }
    }

    private val importSettingsLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importSettingsFromUri(it) }
    }

    private val legacyImportSettingsLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { importSettingsFromUri(it) }
    }

    // Re-running the onboarding wizard can change restart-sensitive display settings;
    // reload pending state and recreate on return, mirroring the old SetupWizard callback.
    private val onboardingLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (isAdded) {
            reloadPendingStateFromSettings()
            checkChanges()
            requireActivity().recreate()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = App.provide(requireContext()).settings

        // Initialize local state with current values
        pendingAdvancedSettings = settings.isAdvancedSettingsActive
        pendingUseGps = settings.useGpsForNavigation
        pendingShowNavigationNotifications = settings.showNavigationNotifications
        pendingSyncMediaSessionAaMetadata = settings.syncMediaSessionWithAaMetadata
        pendingAutoResumePlaybackOnReconnect = settings.autoResumePlaybackOnReconnect
        pendingResolution = settings.resolutionId
        pendingDpi = settings.dpiPixelDensity
        pendingPixelAspectRatioE4 = settings.pixelAspectRatioE4
        pendingStaticBSSID = settings.staticBSSID
        pendingStaticP2pBSSID = settings.staticP2pBSSID
        pendingFullscreenMode = settings.fullscreenMode
        pendingViewMode = settings.viewMode
        pendingForceSoftware = settings.forceSoftwareDecoding
        pendingSoftwareVideoDecoder = settings.softwareVideoDecoder
        pendingVideoCodec = settings.videoCodec
        pendingFpsLimit = settings.fpsLimit
        pendingBluetoothAddress = settings.bluetoothAddress
        pendingEnableAudioSink = settings.enableAudioSink
        pendingStaticAudioFocus = settings.staticAudioFocus
        pendingPlaybackFocusMode = settings.playbackFocusMode
        pendingUseAacAudio = settings.useAacAudio
        pendingUseAAudioOutput = settings.useAAudioOutput
        pendingAttachHwDspEqualizer = settings.attachHwDspEqualizer
        pendingMicInputSource = settings.micInputSource
        pendingEnableRotary = settings.enableRotary
        pendingMediaKeyRouting = settings.mediaKeyRouting
        pendingAudioLatencyMultiplier = settings.audioLatencyMultiplier
        pendingAudioQueueCapacity = settings.audioQueueCapacity
        pendingShowPerformanceOverlay = settings.showPerformanceOverlay
        pendingOverlayPosition = settings.overlayPosition
        pendingShowToastMessages = settings.showToastMessages
        pendingScreenOrientation = settings.screenOrientation
        pendingAppLanguage = settings.appLanguage

        pendingEnableCarLauncher = settings.enableCarLauncher
        pendingEnableFloatingButton = settings.enableFloatingButton
        pendingFloatingButtonSizeDp = settings.floatingButtonSizeDp
        pendingFloatingButtonOpacityPercent = settings.floatingButtonOpacityPercent
        pendingFloatingButtonXPercent = settings.floatingButtonXPercent
        pendingFloatingButtonYPercent = settings.floatingButtonYPercent
        pendingAaExitAction = settings.rawAaExitAction
        pendingVideoFitMode = settings.videoFitMode
        pendingForcedScale = settings.forcedScale
        pendingHudMirroring = settings.hudMirroring

        pendingKillOnDisconnect = settings.rawKillOnDisconnect
        pendingAutoKillOemApps = settings.autoKillOemApps
        pendingRaiseProjectionDuringCall = settings.raiseProjectionDuringCall
        pendingAutoEnableHotspot = settings.autoEnableHotspot
        pendingFakeSpeed = settings.fakeSpeed
        pendingUseLibusb = settings.useLibusb
        pendingNarrowBandProfileCap = settings.narrowBandProfileCap
        pendingDebugVideoLowLatency = settings.debugVideoLowLatency
        pendingAllowExternalConfiguration = settings.allowExternalConfiguration
        pendingKeepDummyVpnDuringSession = settings.keepDummyVpnDuringSession

        pendingWifiConnectionMode = settings.wifiConnectionMode
        pendingHelperConnectionStrategy = settings.helperConnectionStrategy
        pendingWaitForWifi = settings.waitForWifiBeforeWifiDirect
        pendingWaitForWifiTimeout = settings.waitForWifiTimeout
        pendingBluetoothManagerServiceName = settings.bluetoothManagerServiceName
        pendingNativeAaIgnoreExternalBt = settings.nativeAaIgnoreExternalBt
        pendingExternalBtZbtTransport = settings.externalBtZbtTransport
        pendingNativeAaCompleteHfpSlc = settings.nativeAaCompleteHfpSlc
        pendingAnnounceConnectionConfiguration = settings.announceConnectionConfiguration
        pendingNativeApTransport = settings.nativeApStrategy
        pendingNativeDriverSelectionMode = settings.nativeDriverSelectionMode
        pendingNativeDriverSelectionTimeout = settings.nativeDriverSelectionTimeoutSec
        pendingNativePreferredDeviceMac = settings.nativePreferredDeviceMac
        pendingWifiDirectBand = settings.wifiDirectBand
        pendingWifiDirectStableIdentity = settings.wifiDirectStableIdentity
        pendingWifiDirectGroupIdentity = settings.wifiDirectGroupIdentity
        pendingWifiDirectIdentityUserSet = settings.wifiDirectIdentityUserSet
        pendingStationStandDownMode = settings.stationStandDownMode
        pendingHotspotBand = settings.hotspotBand
        pendingFiveGhzChannel = settings.fiveGhzChannel
        pendingHotspotSsid = settings.hotspotSsid
        pendingHotspotPassword = settings.hotspotPassword
        pendingHotspotInterface = settings.hotspotInterface

        pendingInsetLeft = settings.insetLeft
        pendingInsetTop = settings.insetTop
        pendingInsetRight = settings.insetRight
        pendingInsetBottom = settings.insetBottom


        pendingMediaVolumeOffset = settings.mediaVolumeOffset
        pendingGuidanceVolumeOffset = settings.guidanceVolumeOffset
        pendingSystemVolumeOffset = settings.systemVolumeOffset

        pendingHidePhoneSignal = settings.hidePhoneSignal
        pendingHideBatteryLevel = settings.hideBatteryLevel
        pendingHideClock = settings.hideClock

        // Loading screen settings are handled in LoadingScreenFragment (saves directly)

        // Intercept system back button
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })

        toolbar = view.findViewById(R.id.toolbar)
        settingsAdapter = SettingsAdapter()
        settingsRecyclerView = view.findViewById(R.id.settingsRecyclerView)
        settingsRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        settingsRecyclerView.adapter = settingsAdapter

        setupTabsAndSearch(view)

        // Receive the DPI chosen in the DPI sub-screen and feed it into the pending flow,
        // so the main "Save (Reconnect needed)" applies it.
        findNavController().currentBackStackEntry
            ?.savedStateHandle
            ?.getLiveData<Int>(DpiSettingsFragment.KEY_DPI_RESULT)
            ?.observe(viewLifecycleOwner) { newDpi ->
                if (newDpi != pendingDpi) {
                    pendingDpi = newDpi
                    checkChanges()
                    updateSettingsList()
                }
            }

        updateSettingsList()
        setupToolbar()

        savedInstanceState?.getParcelable<android.os.Parcelable>("recycler_scroll")?.let {
            settingsRecyclerView.layoutManager?.onRestoreInstanceState(it)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::settingsRecyclerView.isInitialized) {
            settingsRecyclerView.layoutManager?.onSaveInstanceState()?.let {
                outState.putParcelable("recycler_scroll", it)
            }
        }
    }

    private fun reloadPendingStateFromSettings() {
        pendingAdvancedSettings = settings.isAdvancedSettingsActive
        pendingUseGps = settings.useGpsForNavigation
        pendingShowNavigationNotifications = settings.showNavigationNotifications
        pendingSyncMediaSessionAaMetadata = settings.syncMediaSessionWithAaMetadata
        pendingAutoResumePlaybackOnReconnect = settings.autoResumePlaybackOnReconnect
        pendingResolution = settings.resolutionId
        pendingDpi = settings.dpiPixelDensity
        pendingPixelAspectRatioE4 = settings.pixelAspectRatioE4
        pendingFullscreenMode = settings.fullscreenMode
        pendingViewMode = settings.viewMode
        pendingForceSoftware = settings.forceSoftwareDecoding
        pendingSoftwareVideoDecoder = settings.softwareVideoDecoder
        pendingVideoCodec = settings.videoCodec
        pendingFpsLimit = settings.fpsLimit
        pendingBluetoothAddress = settings.bluetoothAddress
        pendingEnableAudioSink = settings.enableAudioSink
        pendingStaticAudioFocus = settings.staticAudioFocus
        pendingPlaybackFocusMode = settings.playbackFocusMode
        pendingUseAacAudio = settings.useAacAudio
        pendingUseAAudioOutput = settings.useAAudioOutput
        pendingAttachHwDspEqualizer = settings.attachHwDspEqualizer
        pendingEnableRotary = settings.enableRotary
        pendingMediaKeyRouting = settings.mediaKeyRouting
        pendingAudioLatencyMultiplier = settings.audioLatencyMultiplier
        pendingAudioQueueCapacity = settings.audioQueueCapacity
        pendingShowPerformanceOverlay = settings.showPerformanceOverlay
        pendingOverlayPosition = settings.overlayPosition
        pendingShowToastMessages = settings.showToastMessages
        pendingScreenOrientation = settings.screenOrientation
        pendingAppLanguage = settings.appLanguage
        pendingEnableCarLauncher = settings.enableCarLauncher
        CarLauncherManager.syncWithSettings(requireContext(), settings.enableCarLauncher)
        pendingEnableFloatingButton = settings.enableFloatingButton
        pendingFloatingButtonSizeDp = settings.floatingButtonSizeDp
        pendingFloatingButtonOpacityPercent = settings.floatingButtonOpacityPercent
        pendingFloatingButtonXPercent = settings.floatingButtonXPercent
        pendingFloatingButtonYPercent = settings.floatingButtonYPercent
        pendingAaExitAction = settings.rawAaExitAction
        pendingVideoFitMode = settings.videoFitMode
        pendingForcedScale = settings.forcedScale
        pendingHudMirroring = settings.hudMirroring
        pendingKillOnDisconnect = settings.rawKillOnDisconnect
        pendingAutoKillOemApps = settings.autoKillOemApps
        pendingRaiseProjectionDuringCall = settings.raiseProjectionDuringCall
        pendingAutoEnableHotspot = settings.autoEnableHotspot
        pendingFakeSpeed = settings.fakeSpeed
        pendingUseLibusb = settings.useLibusb
        pendingNarrowBandProfileCap = settings.narrowBandProfileCap
        pendingDebugVideoLowLatency = settings.debugVideoLowLatency
        pendingAllowExternalConfiguration = settings.allowExternalConfiguration
        pendingKeepDummyVpnDuringSession = settings.keepDummyVpnDuringSession
        pendingWifiConnectionMode = settings.wifiConnectionMode
        pendingHelperConnectionStrategy = settings.helperConnectionStrategy
        pendingWaitForWifi = settings.waitForWifiBeforeWifiDirect
        pendingWaitForWifiTimeout = settings.waitForWifiTimeout
        pendingBluetoothManagerServiceName = settings.bluetoothManagerServiceName
        pendingNativeAaIgnoreExternalBt = settings.nativeAaIgnoreExternalBt
        pendingExternalBtZbtTransport = settings.externalBtZbtTransport
        pendingNativeAaCompleteHfpSlc = settings.nativeAaCompleteHfpSlc
        pendingAnnounceConnectionConfiguration = settings.announceConnectionConfiguration
        pendingNativeApTransport = settings.nativeApStrategy
        pendingNativeDriverSelectionMode = NativeDriverSelectionPolicy.Mode.AUTO
        pendingNativeDriverSelectionTimeout = NativeDriverSelectionPolicy.DEFAULT_TIMEOUT_SEC
        pendingNativePreferredDeviceMac = ""
        pendingWifiDirectBand = settings.wifiDirectBand
        pendingWifiDirectStableIdentity = settings.wifiDirectStableIdentity
        pendingWifiDirectGroupIdentity = settings.wifiDirectGroupIdentity
        pendingWifiDirectIdentityUserSet = settings.wifiDirectIdentityUserSet
        pendingStationStandDownMode = settings.stationStandDownMode
        pendingHotspotBand = settings.hotspotBand
        pendingFiveGhzChannel = settings.fiveGhzChannel
        pendingHotspotSsid = settings.hotspotSsid
        pendingHotspotPassword = settings.hotspotPassword
        pendingHotspotInterface = settings.hotspotInterface
        pendingInsetLeft = settings.insetLeft
        pendingInsetTop = settings.insetTop
        pendingInsetRight = settings.insetRight
        pendingInsetBottom = settings.insetBottom

        pendingMediaVolumeOffset = settings.mediaVolumeOffset
        pendingGuidanceVolumeOffset = settings.guidanceVolumeOffset
        pendingSystemVolumeOffset = settings.systemVolumeOffset
        pendingHideBatteryLevel = settings.hideBatteryLevel
        pendingHidePhoneSignal = settings.hidePhoneSignal
        pendingHideClock = settings.hideClock
    }

    /**
     * Explain what the probe does, then run it off the main thread if the user agrees.
     *
     * Asked rather than run on tap because the name alone does not say that it touches nothing. On
     * a head unit that already refuses to connect, an unexplained "test" invites the fear that it
     * will make things worse.
     */
    private fun confirmAndRunZbtProbe() {
        // Same builder and theme as every other dialog here. A bare AlertDialog.Builder came up
        // unthemed on a 1024x600 unit with no reachable button, and two rounds were spent on a probe
        // that never ran. The message stays short for the same reason: a taller one pushes the
        // button bar off a 600-pixel screen.
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.zbt_probe_title)
            .setMessage(R.string.zbt_probe_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.zbt_probe_watch_only) { _, _ ->
                startZbtProbe(askModuleToReconnect = false)
            }
            .setPositiveButton(R.string.zbt_probe_wake_and_watch) { _, _ ->
                startZbtProbe(askModuleToReconnect = true)
            }
            .show()
    }

    /**
     * Start the probe, unless one is already running, and follow it on screen while it does.
     *
     * The work and the UI refresh are on deliberately different scopes. The probe outlives this
     * screen (see the companion object); the refresh must not, so it is a view-scoped loop that
     * reads the shared verdict and stops when the run does.
     */
    private fun startZbtProbe(askModuleToReconnect: Boolean) {
        if (zbtProbeJob?.isActive != true) {
            zbtProbeResult = getString(R.string.zbt_probe_running)
            zbtProbeJob = zbtProbeScope.launch {
                val verdict = try {
                    // The row shows every stage because the run lasts about two minutes. A probe
                    // showing one unchanging word for that long reads as hung.
                    ZbtProbe.run(
                        askModuleToReconnect = askModuleToReconnect,
                        onProgress = { text -> zbtProbeResult = text },
                        keepGoing = { isActive }
                    )
                } catch (e: Throwable) {
                    // Never let a probe take the settings screen down with it.
                    AppLog.e("SettingsFragment: external Bluetooth probe failed", e)
                    "Probe failed: ${e.javaClass.simpleName}"
                }
                zbtProbeResult = verdict
            }
        }
        followZbtProbe()
    }

    /** Repaint the probe's row until the run ends. Cheap, and only alive while the view is. */
    private fun followZbtProbe() {
        if (zbtProbeFollowJob?.isActive == true) return
        zbtProbeFollowJob = viewLifecycleOwner.lifecycleScope.launch {
            do {
                updateSettingsList()
                delay(500)
            } while (zbtProbeJob?.isActive == true)
            updateSettingsList()
        }
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            handleBackPress()
        }

        val resetItem = toolbar.menu.add(0, RESET_ITEM_ID, 0, getString(R.string.reset_settings))
        resetItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        resetItem.setActionView(R.layout.layout_reset_button)

        resetButton = resetItem.actionView?.findViewById(R.id.reset_button_widget)
        resetButton?.contentDescription = getString(R.string.reset_settings)
        resetButton?.setOnClickListener {
            startResetSettings()
        }

        // Add the Save item with custom layout
        val saveItem = toolbar.menu.add(0, SAVE_ITEM_ID, 1, getString(R.string.save))
        saveItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        saveItem.setActionView(R.layout.layout_save_button)

        // Get the button from the action view
        saveButton = saveItem.actionView?.findViewById(R.id.save_button_widget)
        saveButton?.setOnClickListener {
            saveSettings()
        }

        updateSaveButtonState()
    }

    private fun handleBackPress() {
        if (hasChanges) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.unsaved_changes)
                .setMessage(R.string.unsaved_changes_message)
                .setPositiveButton(R.string.discard) { _, _ ->
                    navigateBack()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            navigateBack()
        }
    }

    private fun navigateBack() {
        try {
            val navController = findNavController()
            if (!navController.navigateUp()) {
                requireActivity().finish()
            }
        } catch (e: Exception) {
            requireActivity().finish()
        }
    }

    private fun updateSaveButtonState() {
        saveButton?.isEnabled = hasChanges
        saveButton?.text = if (requiresRestart) getString(R.string.save_and_restart) else getString(R.string.save)
    }

    private fun saveSettings() {
        val languageChanged = pendingAppLanguage != settings.appLanguage

        pendingAdvancedSettings?.let { settings.isAdvancedSettingsActive = it }
        pendingUseGps?.let { settings.useGpsForNavigation = it }
        pendingShowNavigationNotifications?.let { settings.showNavigationNotifications = it }
        pendingSyncMediaSessionAaMetadata?.let { settings.syncMediaSessionWithAaMetadata = it }
        pendingAutoResumePlaybackOnReconnect?.let { settings.autoResumePlaybackOnReconnect = it }
        // The way back from a link the app measured as too slow: it lowered the profile after three
        // sessions that rendered nothing, and nothing else retires that, because the cap is what
        // makes the next session render. See VideoStarvationPolicy.shouldCap.
        val videoProfileChanged =
            (pendingResolution != null && pendingResolution != settings.resolutionId) ||
                (pendingFpsLimit != null && pendingFpsLimit != settings.fpsLimit)
        if (videoProfileChanged) settings.videoProfileStarvationCap = false
        pendingResolution?.let { settings.resolutionId = it }
        pendingDpi?.let { settings.dpiPixelDensity = it }
        pendingPixelAspectRatioE4?.let { settings.pixelAspectRatioE4 = it }
        pendingStaticBSSID?.let { settings.staticBSSID = it }
        pendingStaticP2pBSSID?.let { settings.staticP2pBSSID = it }
        pendingFullscreenMode?.let { settings.fullscreenMode = it }
        val oldViewMode = settings.viewMode
        pendingViewMode?.let { settings.viewMode = it }
        pendingForceSoftware?.let { settings.forceSoftwareDecoding = it }
        pendingSoftwareVideoDecoder?.let { settings.softwareVideoDecoder = it }
        pendingVideoCodec?.let { settings.videoCodec = it }
        pendingFpsLimit?.let { settings.fpsLimit = it }
        pendingBluetoothAddress?.let { settings.bluetoothAddress = it }
        pendingEnableAudioSink?.let { settings.enableAudioSink = it }
        pendingStaticAudioFocus?.let { settings.staticAudioFocus = it }
        // Re-picking the focus mode is the way back from a wrong verdict: AUTO learns that taking
        // system audio focus stops the phone's own playback and then stops asking for it, and two
        // tracks that happened to end quickly can teach it that wrongly.
        val focusModeChanged = pendingPlaybackFocusMode != null && pendingPlaybackFocusMode != settings.playbackFocusMode
        pendingPlaybackFocusMode?.let { settings.playbackFocusMode = it }
        if (focusModeChanged) settings.playbackFocusSelfDefeating = false
        pendingUseAacAudio?.let { settings.useAacAudio = it }
        pendingUseAAudioOutput?.let { settings.useAAudioOutput = it }
        pendingAttachHwDspEqualizer?.let { settings.attachHwDspEqualizer = it }
        pendingMicInputSource?.let { settings.micInputSource = it }
        pendingEnableRotary?.let { settings.enableRotary = it }
        pendingMediaKeyRouting?.let { settings.mediaKeyRouting = it }
        pendingAudioLatencyMultiplier?.let { settings.audioLatencyMultiplier = it }
        pendingAudioQueueCapacity?.let { settings.audioQueueCapacity = it }
        pendingShowPerformanceOverlay?.let { settings.showPerformanceOverlay = it }
        pendingOverlayPosition?.let { settings.overlayPosition = it }
        pendingShowToastMessages?.let { settings.showToastMessages = it }
        pendingScreenOrientation?.let { settings.screenOrientation = it }

        pendingMediaVolumeOffset?.let { settings.mediaVolumeOffset = it }
        pendingGuidanceVolumeOffset?.let { settings.guidanceVolumeOffset = it }
        pendingSystemVolumeOffset?.let { settings.systemVolumeOffset = it }


        pendingAppLanguage?.let { settings.appLanguage = it }

        val hudMirroringChanged = pendingHudMirroring != null && pendingHudMirroring != settings.hudMirroring

        pendingEnableCarLauncher?.let {
            settings.enableCarLauncher = it
            CarLauncherManager.setLauncherEnabled(requireContext(), it)
        }
        pendingEnableFloatingButton?.let { settings.enableFloatingButton = it }
        pendingFloatingButtonSizeDp?.let { settings.floatingButtonSizeDp = it }
        pendingFloatingButtonOpacityPercent?.let { settings.floatingButtonOpacityPercent = it }
        pendingFloatingButtonXPercent?.let { settings.floatingButtonXPercent = it }
        pendingFloatingButtonYPercent?.let { settings.floatingButtonYPercent = it }
        pendingAaExitAction?.let { settings.rawAaExitAction = it }
        FloatingButtonManager.update(requireContext())
        pendingVideoFitMode?.let { settings.videoFitMode = it }
        pendingForcedScale?.let { settings.forcedScale = it }
        pendingHudMirroring?.let { settings.hudMirroring = it }

        pendingKillOnDisconnect?.let { settings.rawKillOnDisconnect = it }
        pendingAutoKillOemApps?.let { settings.autoKillOemApps = it }
        pendingRaiseProjectionDuringCall?.let { settings.raiseProjectionDuringCall = it }
        pendingAutoEnableHotspot?.let { settings.autoEnableHotspot = it }
        pendingFakeSpeed?.let { settings.fakeSpeed = it }
        pendingUseLibusb?.let { settings.useLibusb = it }
        pendingNarrowBandProfileCap?.let { settings.narrowBandProfileCap = it }
        pendingDebugVideoLowLatency?.let { settings.debugVideoLowLatency = it }
        pendingAllowExternalConfiguration?.let { settings.allowExternalConfiguration = it }
        pendingKeepDummyVpnDuringSession?.let { settings.keepDummyVpnDuringSession = it }

        val wirelessConfigBefore = wirelessRearmConfig()
        pendingWifiConnectionMode?.let { settings.wifiConnectionMode = it }
        pendingHelperConnectionStrategy?.let { settings.helperConnectionStrategy = it }
        pendingWaitForWifi?.let { settings.waitForWifiBeforeWifiDirect = it }
        pendingWaitForWifiTimeout?.let { settings.waitForWifiTimeout = it }
        pendingBluetoothManagerServiceName?.let { settings.bluetoothManagerServiceName = it }
        pendingNativeAaIgnoreExternalBt?.let { settings.nativeAaIgnoreExternalBt = it }
        pendingExternalBtZbtTransport?.let { settings.externalBtZbtTransport = it }
        pendingNativeAaCompleteHfpSlc?.let { settings.nativeAaCompleteHfpSlc = it }
        pendingAnnounceConnectionConfiguration?.let { settings.announceConnectionConfiguration = it }
        pendingNativeApTransport?.let { settings.nativeApStrategy = it }
        pendingNativeDriverSelectionMode?.let { settings.nativeDriverSelectionMode = it }
        pendingNativeDriverSelectionTimeout?.let { settings.nativeDriverSelectionTimeoutSec = it }
        pendingNativePreferredDeviceMac?.let { settings.nativePreferredDeviceMac = it }
        pendingWifiDirectBand?.let { settings.wifiDirectBand = it }
        pendingWifiDirectStableIdentity?.let { settings.wifiDirectStableIdentity = it }
        // The pair is written whole, null included, so a Reset really does hand the choice back.
        pendingWifiDirectIdentityUserSet?.let {
            val moved = settings.wifiDirectGroupIdentity != pendingWifiDirectGroupIdentity
            settings.wifiDirectIdentityUserSet = it
            settings.wifiDirectGroupIdentity = pendingWifiDirectGroupIdentity
            if (moved) rotateWifiDirectIdentityNow()
        }
        pendingStationStandDownMode?.let { settings.stationStandDownMode = it }
        pendingHotspotBand?.let { settings.hotspotBand = it }
        pendingFiveGhzChannel?.let { settings.fiveGhzChannel = it }
        pendingHotspotSsid?.let { settings.hotspotSsid = it }
        pendingHotspotPassword?.let { settings.hotspotPassword = it }
        pendingHotspotInterface?.let { settings.hotspotInterface = it }
        pendingInsetLeft?.let { settings.insetLeft = it }
        pendingInsetTop?.let { settings.insetTop = it }
        pendingInsetRight?.let { settings.insetRight = it }
        pendingInsetBottom?.let { settings.insetBottom = it }
        pendingHidePhoneSignal?.let { settings.hidePhoneSignal = it }
        pendingHideBatteryLevel?.let { settings.hideBatteryLevel = it }
        pendingHideClock?.let { settings.hideClock = it }

        settings.commit()
        AppLog.init(settings, requireContext().applicationContext)

        // View mode is only the local rendering backend, so apply it to a running projection
        // live instead of requiring a restart, the same path Quick Settings and the stall
        // recovery use via recreateProjectionView(). If another setting is already forcing a
        // restart below, that path covers it; with no active session the new value is simply
        // used on the next launch.
        if (oldViewMode != settings.viewMode && !requiresRestart) {
            LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(
                Intent(QuickSettingsFragment.ACTION_SETTINGS_CHANGED)
                    .putExtra(QuickSettingsFragment.EXTRA_NEEDS_VIEW_RECREATE, true)
            )
        }

        if (WirelessRearmPolicy.requiresRearm(wirelessConfigBefore, wirelessRearmConfig())) {
            val intent = Intent(requireContext(), AapService::class.java).apply {
                val mode = settings.wifiConnectionMode
                action = if (mode != WifiLauncherMode.MANUAL && settings.showsWifi())
                    AapService.ACTION_START_WIRELESS else AapService.ACTION_STOP_WIRELESS
            }
            requireContext().startService(intent)
        }

        if (requiresRestart) {
            if (App.provide(requireContext()).commManager.isConnected) {
                ToastUtils.showToast(context, getString(R.string.stopping_service), Toast.LENGTH_SHORT, force = true)
                val stopServiceIntent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_STOP_SERVICE
                }
                ContextCompat.startForegroundService(requireContext(), stopServiceIntent)
            }
        }

        // Reset change tracking
        hasChanges = false
        requiresRestart = false
        updateSaveButtonState()
        updateSettingsList()

        ToastUtils.showToast(context, getString(R.string.settings_saved), Toast.LENGTH_SHORT, force = true)

        if (languageChanged || hudMirroringChanged) {
            requireActivity().recreate()
        }
    }

    private fun checkChanges() {
        // Check for any changes
        val anyChange = pendingAdvancedSettings != settings.isAdvancedSettingsActive ||
                        pendingUseGps != settings.useGpsForNavigation ||
                        pendingShowNavigationNotifications != settings.showNavigationNotifications ||
                        pendingSyncMediaSessionAaMetadata != settings.syncMediaSessionWithAaMetadata ||
                        pendingAutoResumePlaybackOnReconnect != settings.autoResumePlaybackOnReconnect ||
                        pendingResolution != settings.resolutionId ||
                        pendingDpi != settings.dpiPixelDensity ||
                        pendingPixelAspectRatioE4 != settings.pixelAspectRatioE4 ||
                        pendingStaticBSSID != settings.staticBSSID ||
                        pendingStaticP2pBSSID != settings.staticP2pBSSID ||
                        pendingFullscreenMode != settings.fullscreenMode ||
                        pendingViewMode != settings.viewMode ||
                        pendingForceSoftware != settings.forceSoftwareDecoding ||
                        pendingSoftwareVideoDecoder != settings.softwareVideoDecoder ||
                        pendingVideoCodec != settings.videoCodec ||
                        pendingFpsLimit != settings.fpsLimit ||
                        pendingBluetoothAddress != settings.bluetoothAddress ||
                        pendingEnableAudioSink != settings.enableAudioSink ||
                        pendingStaticAudioFocus != settings.staticAudioFocus ||
                        pendingPlaybackFocusMode != settings.playbackFocusMode ||
                        pendingUseAacAudio != settings.useAacAudio ||
                        pendingUseAAudioOutput != settings.useAAudioOutput ||
                        pendingAttachHwDspEqualizer != settings.attachHwDspEqualizer ||
                        pendingMicInputSource != settings.micInputSource ||
                        pendingEnableRotary != settings.enableRotary ||
                        pendingMediaKeyRouting != settings.mediaKeyRouting ||
                        pendingAudioLatencyMultiplier != settings.audioLatencyMultiplier ||
                        pendingAudioQueueCapacity != settings.audioQueueCapacity ||
                        pendingShowPerformanceOverlay != settings.showPerformanceOverlay ||
                        pendingOverlayPosition != settings.overlayPosition ||
                        pendingShowToastMessages != settings.showToastMessages ||
                        pendingScreenOrientation != settings.screenOrientation ||
                        pendingAppLanguage != settings.appLanguage ||
                        pendingEnableCarLauncher != settings.enableCarLauncher ||
                        pendingEnableFloatingButton != settings.enableFloatingButton ||
                        pendingFloatingButtonSizeDp != settings.floatingButtonSizeDp ||
                        pendingFloatingButtonOpacityPercent != settings.floatingButtonOpacityPercent ||
                        pendingFloatingButtonXPercent != settings.floatingButtonXPercent ||
                        pendingFloatingButtonYPercent != settings.floatingButtonYPercent ||
                        pendingAaExitAction != settings.rawAaExitAction ||
                        pendingVideoFitMode != settings.videoFitMode ||
                        pendingForcedScale != settings.forcedScale ||
                        pendingHudMirroring != settings.hudMirroring ||
                        pendingInsetLeft != settings.insetLeft ||
                        pendingInsetTop != settings.insetTop ||
                        pendingInsetRight != settings.insetRight ||
                        pendingInsetBottom != settings.insetBottom ||
                        pendingMediaVolumeOffset != settings.mediaVolumeOffset ||
                        pendingGuidanceVolumeOffset != settings.guidanceVolumeOffset ||
                        pendingSystemVolumeOffset != settings.systemVolumeOffset ||
                        pendingKillOnDisconnect != settings.rawKillOnDisconnect ||
                        pendingAutoKillOemApps != settings.autoKillOemApps ||
                        pendingRaiseProjectionDuringCall != settings.raiseProjectionDuringCall ||
                        pendingAutoEnableHotspot != settings.autoEnableHotspot ||
                        pendingFakeSpeed != settings.fakeSpeed ||
                        pendingWifiConnectionMode != settings.wifiConnectionMode ||
                        pendingHelperConnectionStrategy != settings.helperConnectionStrategy ||
                        pendingWaitForWifi != settings.waitForWifiBeforeWifiDirect ||
                        pendingWaitForWifiTimeout != settings.waitForWifiTimeout ||
                        pendingBluetoothManagerServiceName != settings.bluetoothManagerServiceName ||
                        pendingNativeAaIgnoreExternalBt != settings.nativeAaIgnoreExternalBt ||
                        pendingExternalBtZbtTransport != settings.externalBtZbtTransport ||
                        pendingNativeAaCompleteHfpSlc != settings.nativeAaCompleteHfpSlc ||
                        pendingAnnounceConnectionConfiguration != settings.announceConnectionConfiguration ||
                        pendingNativeApTransport != settings.nativeApStrategy ||
                        pendingNativeDriverSelectionMode != settings.nativeDriverSelectionMode ||
                        pendingNativeDriverSelectionTimeout != settings.nativeDriverSelectionTimeoutSec ||
                        pendingNativePreferredDeviceMac != settings.nativePreferredDeviceMac ||
                        pendingWifiDirectBand != settings.wifiDirectBand ||
                        pendingWifiDirectStableIdentity != settings.wifiDirectStableIdentity ||
                        pendingWifiDirectIdentityUserSet != settings.wifiDirectIdentityUserSet ||
                        pendingWifiDirectGroupIdentity != settings.wifiDirectGroupIdentity ||
                        pendingStationStandDownMode != settings.stationStandDownMode ||
                        pendingHotspotBand != settings.hotspotBand ||
                        pendingFiveGhzChannel != settings.fiveGhzChannel ||
                        pendingHotspotSsid != settings.hotspotSsid ||
                        pendingHotspotPassword != settings.hotspotPassword ||
                        pendingHotspotInterface != settings.hotspotInterface ||
                        pendingUseLibusb != settings.useLibusb ||
                        pendingNarrowBandProfileCap != settings.narrowBandProfileCap ||
                        pendingDebugVideoLowLatency != settings.debugVideoLowLatency ||
                        pendingAllowExternalConfiguration != settings.allowExternalConfiguration ||
                        pendingKeepDummyVpnDuringSession != settings.keepDummyVpnDuringSession ||
                        pendingHideBatteryLevel != settings.hideBatteryLevel ||
                        pendingHidePhoneSignal != settings.hidePhoneSignal ||
                        pendingHideClock != settings.hideClock

        hasChanges = anyChange

        // Check for restart requirement
        requiresRestart = pendingResolution != settings.resolutionId ||
                          pendingVideoFitMode != settings.videoFitMode ||
                          pendingVideoCodec != settings.videoCodec ||
                          pendingFpsLimit != settings.fpsLimit ||
                          pendingDpi != settings.dpiPixelDensity ||
                          pendingPixelAspectRatioE4 != settings.pixelAspectRatioE4 ||
                          pendingStaticBSSID != settings.staticBSSID ||
                          pendingStaticP2pBSSID != settings.staticP2pBSSID ||
                          pendingForceSoftware != settings.forceSoftwareDecoding ||
                          pendingSoftwareVideoDecoder != settings.softwareVideoDecoder ||
                          pendingEnableRotary != settings.enableRotary ||
                          pendingEnableAudioSink != settings.enableAudioSink ||
                          pendingStaticAudioFocus != settings.staticAudioFocus ||
                          pendingPlaybackFocusMode != settings.playbackFocusMode ||
                          pendingUseAacAudio != settings.useAacAudio ||
                          pendingUseAAudioOutput != settings.useAAudioOutput ||
                          pendingAttachHwDspEqualizer != settings.attachHwDspEqualizer ||
                          pendingAudioLatencyMultiplier != settings.audioLatencyMultiplier ||
                          pendingAudioQueueCapacity != settings.audioQueueCapacity ||
                          pendingInsetLeft != settings.insetLeft ||
                          pendingInsetTop != settings.insetTop ||
                          pendingInsetRight != settings.insetRight ||
                          pendingInsetBottom != settings.insetBottom ||
                          pendingWifiConnectionMode != settings.wifiConnectionMode ||
                          pendingUseLibusb != settings.useLibusb

        updateSaveButtonState()
    }

    /** Reads a fault budget for the settings row, so 0 says what it means rather than showing "0". */
    private fun describeFaultBudget(budget: Int): String =
        if (budget == VideoFaultInjector.UNLIMITED_BUDGET) "Whole session" else "$budget faults"

    private fun updateSettingsList() {
        val app = App.provide(requireContext())
        val scrollState = settingsRecyclerView.layoutManager?.onSaveInstanceState()
        val items = mutableListOf<SettingItem>()

        // --- General Settings ---
        items.add(SettingItem.CategoryHeader("general", R.string.category_general))

        // Auto-Optimize Wizard
        items.add(SettingItem.SettingEntry(
            stableId = "autoOptimize",
            nameResId = R.string.auto_optimize,
            value = getString(R.string.auto_optimize_desc),
            onClick = { _ ->
                onboardingLauncher.launch(Intent(requireContext(), OnboardingActivity::class.java))
            }
        ))

        // Permissions checklist (same list the setup wizard shows)
        items.add(SettingItem.SettingEntry(
            stableId = "permissions",
            nameResId = R.string.permissions,
            value = getString(R.string.permissions_desc),
            onClick = { _ ->
                try {
                    findNavController().navigate(R.id.action_settingsFragment_to_permissionsFragment)
                } catch (e: Exception) {
                    // Failover
                }
            }
        ))

        // Connection mode (Feature B): drives which options appear in the Basic tab.
        items.add(SettingItem.SettingEntry(
            stableId = "connectionMode",
            nameResId = R.string.connection_mode,
            value = connectionModesLabel(),
            onClick = { showConnectionModeDialog() }
        ))

        // Language Selector
        val availableLocales = LocaleHelper.getAvailableLocales(requireContext())
        val currentLocale = LocaleHelper.stringToLocale(pendingAppLanguage ?: "")
        val currentLanguageDisplay = if (currentLocale != null) {
            LocaleHelper.getDisplayName(currentLocale)
        } else {
            getString(R.string.system_default)
        }

        items.add(SettingItem.SettingEntry(
            stableId = "appLanguage",
            nameResId = R.string.app_language,
            value = currentLanguageDisplay,
            onClick = { _ ->
                val languageNames = mutableListOf(getString(R.string.system_default))
                val localeCodes = mutableListOf("")

                availableLocales.forEach { locale ->
                    languageNames.add(LocaleHelper.getDisplayName(locale))
                    localeCodes.add(LocaleHelper.localeToString(locale))
                }

                val currentIndex = localeCodes.indexOf(pendingAppLanguage ?: "").coerceAtLeast(0)

                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.change_language)
                    .setSingleChoiceItems(languageNames.toTypedArray(), currentIndex) { dialog, which ->
                        pendingAppLanguage = localeCodes[which]
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "vehicleInfoSettings",
            nameResId = R.string.vehicle_info_settings,
            value = getString(R.string.vehicle_info_settings_description),
            onClick = {
                try {
                    findNavController().navigate(R.id.action_settingsFragment_to_vehicleInfoFragment)
                } catch (e: Exception) { }
            }
        ))



        items.add(SettingItem.ToggleSettingEntry(
            stableId = "showToastMessages",
            nameResId = R.string.show_toast_messages,
            descriptionResId = R.string.show_toast_messages_description,
            isChecked = pendingShowToastMessages ?: true,
            onCheckedChanged = { isChecked ->
                pendingShowToastMessages = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "useLibusb",
                nameResId = R.string.use_libusb,
                descriptionResId = R.string.use_libusb_description,
                isChecked = pendingUseLibusb ?: false,
                onCheckedChanged = { isChecked ->
                    pendingUseLibusb = isChecked
                    checkChanges()
                    updateSettingsList()
                }
            ))
        }

        // --- Wireless Connection ---
        items.add(SettingItem.CategoryHeader("wirelessConnection", R.string.category_wireless))

        // Add 2.4GHz Warning Banner
        items.add(SettingItem.InfoBanner(
            stableId = "wireless24ghzWarning",
            textResId = R.string.wireless_24ghz_warning
        ))

        val wirelessModeOptions = listOf(
            getString(R.string.wireless_mode_helper),
            getString(R.string.wireless_mode_native),
            getString(R.string.wireless_mode_server)
        )

        val wirelessSelectedIndex = when (pendingWifiConnectionMode) {
            WifiLauncherMode.HELPER -> 0 // Helper
            WifiLauncherMode.NATIVE -> 1 // Native
            WifiLauncherMode.MANUAL, WifiLauncherMode.AUTO -> 2 // Server
            else -> 1
        }

        items.add(SettingItem.SegmentedButtonSettingEntry(
            stableId = "wifiConnectionMode",
            nameResId = R.string.wireless_mode,
            options = wirelessModeOptions,
            selectedIndex = wirelessSelectedIndex,
            onOptionSelected = { index ->
                val newMode = when (index) {
                    0 -> WifiLauncherMode.HELPER // Helper
                    1 -> WifiLauncherMode.NATIVE // Native
                    2 -> if (pendingWifiConnectionMode == WifiLauncherMode.MANUAL) WifiLauncherMode.MANUAL else WifiLauncherMode.AUTO // Keep manual/auto choice if already in server mode
                    else -> WifiLauncherMode.NATIVE
                }

                if (newMode == WifiLauncherMode.NATIVE) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        bluetoothPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        handleNativeAaSelection()
                    }
                } else {
                    pendingWifiConnectionMode = newMode
                    checkChanges()
                    updateSettingsList()
                }
            }
        ))

        if (pendingWifiConnectionMode == WifiLauncherMode.NATIVE) {
            items.add(SettingItem.SegmentedButtonSettingEntry(
                stableId = "nativeApTransport",
                nameResId = R.string.native_ap_transport,
                options = listOf(
                    getString(R.string.native_ap_transport_wifi_direct),
                    getString(R.string.native_ap_transport_hotspot)
                ),
                selectedIndex = (pendingNativeApTransport ?: NativeStrategy.DEFAULT).id,
                onOptionSelected = { index ->
                    val newStrategy = NativeStrategy.byIdOrDefault(index)
                    val changed = pendingNativeApTransport != newStrategy
                    pendingNativeApTransport = newStrategy
                    checkChanges()
                    updateSettingsList()
                    // The two transports need different things of the device, and the hotspot one
                    // needs the two fields most units cannot supply — so this is the second moment
                    // worth checking, not just mode selection.
                    if (changed) runCredentialsPreflight()
                }
            ))

            if (pendingNativeTransport() == NativeTransport.HOTSPOT) {
                items.add(SettingItem.InfoBanner(
                    stableId = "nativeApTransportHint",
                    textResId = R.string.native_ap_transport_hint
                ))

                // This route reads autoEnableHotspot in two places - SoftApCredentialsProvider
                // switches the access point on when none is found, and UserExitHotspotPolicy
                // decides from it whether a user exit takes the network down - so the toggle
                // belongs here. It used to render only on mode 1 and mode 2 strategy 4, which left
                // the setting governing this route unreachable from the screen that selects it.
                addHotspotToggle(items)
                addHotspotBandSetting(items)
                if (pendingHotspotBandPreference() != HotspotBandPreference.FORCE_2_4GHZ) {
                    addFiveGhzChannelSetting(items)
                }

                // The automatic read goes through the same non-public API that a locked-down
                // device refuses outright, so on those units this override is the only way the
                // route can learn the network name at all.
                //
                // Both rows carry the banner's own search phrase as a keyword, and the banner
                // seeds the same string: the condition needs the pair, and a search on either
                // title alone reaches one of them.
                val manualSsid = pendingHotspotSsid.orEmpty()
                items.add(SettingItem.SettingEntry(
                    stableId = "hotspotSsidOverride",
                    nameResId = R.string.hotspot_ssid_override,
                    value = manualSsid.ifEmpty { getString(R.string.auto) },
                    searchKeywords = getString(R.string.connection_issue_remedy_hotspot_query),
                    onClick = { _ ->
                        DialogUtils.showTextInputDialogWithMessage(
                            requireContext(),
                            R.string.hotspot_ssid_override,
                            R.string.hotspot_ssid_override_message,
                            manualSsid,
                            { newVal ->
                                pendingHotspotSsid = newVal.trim()
                                checkChanges()
                                updateSettingsList()
                            }
                        )
                    }
                ))

                val manualPassword = pendingHotspotPassword.orEmpty()
                items.add(SettingItem.SettingEntry(
                    stableId = "hotspotPasswordOverride",
                    nameResId = R.string.hotspot_password_override,
                    value = if (manualPassword.isEmpty()) getString(R.string.auto) else "\u2022".repeat(manualPassword.length),
                    searchKeywords = getString(R.string.connection_issue_remedy_hotspot_query),
                    onClick = { _ ->
                        DialogUtils.showTextInputDialogWithMessage(
                            requireContext(),
                            R.string.hotspot_password_override,
                            R.string.hotspot_password_override_message,
                            manualPassword,
                            { newVal ->
                                pendingHotspotPassword = newVal.trim()
                                checkChanges()
                                updateSettingsList()
                            }
                        )
                    }
                ))

                val manualInterface = pendingHotspotInterface.orEmpty()
                items.add(SettingItem.SettingEntry(
                    stableId = "hotspotInterfaceOverride",
                    nameResId = R.string.hotspot_interface_override,
                    value = manualInterface.ifEmpty { getString(R.string.auto) },
                    onClick = { _ ->
                        DialogUtils.showTextInputDialogWithMessage(
                            requireContext(),
                            R.string.hotspot_interface_override,
                            R.string.hotspot_interface_override_message,
                            manualInterface,
                            { newVal ->
                                pendingHotspotInterface = newVal.trim()
                                checkChanges()
                                updateSettingsList()
                            }
                        )
                    }
                ))

                // The one route onto a unit whose Bluetooth cannot carry the handshake: the phone
                // reads the network and our TCP endpoint straight off the screen. Only on this
                // transport, and only from here, because the QR is worth showing exactly where the
                // network it names is configured.
                items.add(SettingItem.SettingEntry(
                    stableId = "projectionSetupQr",
                    nameResId = R.string.native_aa_setup_qr_title,
                    value = getString(R.string.native_aa_setup_qr_description),
                    onClick = { _ -> ProjectionSetupQrDialog.show(requireContext()) }
                ))
            }

            // The band choice lives here rather than under Debug because it is read in exactly
            // one place - WifiDirectManager.createQuietGroup(), reachable only from
            // startNativeAaQuietHost(), which runs on this mode's WiFi Direct arm and nowhere else.
            // Hence the transport gate: on the hotspot route these used to render anyway and do
            // nothing, sitting next to the hotspot's own band control.
            //
            // Not WifiModePolicy.usesWifiDirect, which is the obvious reuse and is wrong here: it
            // also claims mode 2 strategy 1, and the helper's WiFi Direct strategy does not reach
            // createQuietGroup(), so gating on it would move this bug rather than fix it.
            //
            // Switching transport hides these without clearing them, deliberately. The read site is
            // already unreachable from the hotspot route, and resetting somebody's setting behind
            // a UI change is worse than leaving it set.
            if (pendingNativeTransport() == NativeTransport.WIFI_DIRECT) {
                addWifiDirectBandSetting(items)

                // Only where a 5 GHz channel is asked for at all. Replaces a two-position toggle
                // that could say 36 or 149 and nothing between.
                if (pendingP2pBandPreference() != P2pBandPreference.FORCE_2_4GHZ) {
                    addFiveGhzChannelSetting(items)
                }

                addWifiDirectIdentitySettings(items)
                addStationStandDownSetting(items)
            }

            // Multi-Driver Selection settings for Native AA
            val currentDriverMode = pendingNativeDriverSelectionMode ?: NativeDriverSelectionPolicy.Mode.AUTO
            items.add(SettingItem.SegmentedButtonSettingEntry(
                stableId = "nativeDriverSelectionMode",
                nameResId = R.string.native_driver_selection_title,
                options = listOf(
                    getString(R.string.native_driver_selection_mode_off),
                    getString(R.string.native_driver_selection_mode_auto),
                    getString(R.string.native_driver_selection_mode_always)
                ),
                selectedIndex = currentDriverMode.id,
                onOptionSelected = { index ->
                    pendingNativeDriverSelectionMode = NativeDriverSelectionPolicy.Mode.fromId(index)
                    checkChanges()
                    updateSettingsList()
                }
            ))

            if (currentDriverMode != NativeDriverSelectionPolicy.Mode.DISABLED) {
                val currentTimeout = pendingNativeDriverSelectionTimeout ?: NativeDriverSelectionPolicy.DEFAULT_TIMEOUT_SEC
                items.add(SettingItem.SliderSettingEntry(
                    stableId = "nativeDriverSelectionTimeout",
                    nameResId = R.string.native_driver_selection_timeout,
                    value = "${currentTimeout}s",
                    sliderValue = currentTimeout.toFloat(),
                    valueFrom = NativeDriverSelectionPolicy.MIN_TIMEOUT_SEC.toFloat(),
                    valueTo = NativeDriverSelectionPolicy.MAX_TIMEOUT_SEC.toFloat(),
                    stepSize = 1f,
                    onValueChanged = { newVal ->
                        pendingNativeDriverSelectionTimeout = newVal.toInt()
                        checkChanges()
                    }
                ))

                val currentPrefMac = pendingNativePreferredDeviceMac.orEmpty()
                val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())
                val bonded = adapter?.bondedDevices?.toList() ?: emptyList()
                val prefDeviceName = bonded.firstOrNull { it.address.equals(currentPrefMac, ignoreCase = true) }?.name
                    ?: if (currentPrefMac.isNotEmpty()) currentPrefMac else getString(R.string.driver_none)

                items.add(SettingItem.SettingEntry(
                    stableId = "nativePreferredDevice",
                    nameResId = R.string.native_driver_preferred_device,
                    value = prefDeviceName,
                    onClick = { _ ->
                        // Only a phone can be the preferred phone: a watch chosen here used to
                        // count as one everywhere. A stored non-phone is cleared with None.
                        val likelyPhones = bonded.filter {
                            BluetoothHelper.isLikelyPhone(it, preferredMac = currentPrefMac)
                        }

                        val options = mutableListOf<Pair<String, String>>()
                        options.add("" to getString(R.string.driver_none))
                        likelyPhones.forEach { dev ->
                            val name = dev.name ?: "Unknown"
                            options.add(dev.address to "$name (${dev.address})")
                        }
                        val labels = options.map { it.second }.toTypedArray()
                        val selectedIdx = options.indexOfFirst { it.first.equals(currentPrefMac, ignoreCase = true) }.coerceAtLeast(0)

                        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                            .setTitle(R.string.native_driver_preferred_device)
                            .setSingleChoiceItems(labels, selectedIdx) { dialog, which ->
                                dialog.dismiss()
                                pendingNativePreferredDeviceMac = options[which].first
                                checkChanges()
                                updateSettingsList()
                            }
                            .setNegativeButton(R.string.cancel, null)
                            .show()
                    }
                ))
            }

            val currentServiceName = pendingBluetoothManagerServiceName ?: "bluetooth_manager"
            items.add(SettingItem.SettingEntry(
                stableId = "bluetoothAdapterServiceName",
                nameResId = R.string.bluetooth_adapter_label,
                value = BluetoothHelper.getAdapterDescription(requireContext(), currentServiceName),
                onClick = { _ ->
                    val serviceNames = BluetoothHelper.listBluetoothServices()
                    val displayNames = serviceNames.map { name ->
                        BluetoothHelper.getAdapterDescription(requireContext(), name)
                    }.toTypedArray()

                    val selectedIndex = serviceNames.indexOf(currentServiceName).coerceAtLeast(0)

                    MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                        .setTitle(R.string.select_bt_adapter)
                        .setSingleChoiceItems(displayNames, selectedIndex) { dialog, which ->
                            dialog.dismiss()
                            pendingBluetoothManagerServiceName = serviceNames[which]
                            checkChanges()
                            updateSettingsList()
                        }
                        .show()
                }
            ))

            // Only where the detection has fired: everywhere else this governs a gate that never
            // closes, and a switch that changes nothing is what sends people down a wrong
            // diagnosis. The evidence is a lazy, so asking on every rebuild costs one field read.
            if (BluetoothHelper.externalBtEvidence != null) {
                items.add(SettingItem.ToggleSettingEntry(
                    stableId = "nativeAaIgnoreExternalBt",
                    nameResId = R.string.native_aa_ignore_external_bt,
                    descriptionResId = R.string.native_aa_ignore_external_bt_description,
                    isChecked = pendingNativeAaIgnoreExternalBt ?: settings.nativeAaIgnoreExternalBt,
                    searchKeywords = "external bluetooth module rfcomm override compatibility anyway",
                    onCheckedChanged = { isChecked ->
                        pendingNativeAaIgnoreExternalBt = isChecked
                        checkChanges()
                        updateSettingsList()
                    }
                ))
            }

            items.add(SettingItem.ToggleSettingEntry(
                stableId = "nativeAaCompleteHfpSlc",
                nameResId = R.string.native_aa_complete_hfp_slc,
                descriptionResId = R.string.native_aa_complete_hfp_slc_description,
                isChecked = pendingNativeAaCompleteHfpSlc ?: settings.nativeAaCompleteHfpSlc,
                searchKeywords = "bluetooth hfp hands-free handsfree calls profile wireless",
                onCheckedChanged = { isChecked ->
                    pendingNativeAaCompleteHfpSlc = isChecked
                    checkChanges()
                    updateSettingsList()
                }
            ))

            // An action, not a switch: the wake's cost is a property of this unit's own Bluetooth
            // stack, which is measured rather than asked. What a user can do is ask for the
            // measurement again, which is the only way back for a unit condemned by one reading.
            items.add(SettingItem.SettingEntry(
                stableId = "nativeAaWakeRemeasure",
                nameResId = R.string.native_aa_wake_remeasure,
                value = getString(
                    when (NativeAaWakeDamagePolicy.Verdict.of(settings.nativeAaWakeDamageVerdict)) {
                        NativeAaWakeDamagePolicy.Verdict.UNKNOWN -> R.string.native_aa_wake_verdict_unmeasured
                        NativeAaWakeDamagePolicy.Verdict.SAFE -> R.string.native_aa_wake_verdict_safe
                        NativeAaWakeDamagePolicy.Verdict.DESTRUCTIVE -> R.string.native_aa_wake_verdict_destructive
                    }
                ),
                searchKeywords = "bluetooth wake poke hands-free handsfree link reset measure again",
                onClick = { _ ->
                    MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                        .setTitle(R.string.native_aa_wake_remeasure)
                        .setMessage(R.string.native_aa_wake_remeasure_confirm)
                        .setPositiveButton(android.R.string.ok) { _, _ ->
                            settings.nativeAaWakeDamageVerdict = 0
                            settings.nativeAaRadioCycleVerdict = 0
                            settings.nativeAaWakeArmingsWithoutSession = 0
                            ToastUtils.showToast(
                                requireContext(),
                                R.string.native_aa_wake_remeasure_done,
                                Toast.LENGTH_LONG,
                                force = true
                            )
                            updateSettingsList()
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            ))

            // Rendering it here is half the gate: AapService re-tests the connection mode before
            // acting on it, because a preference turned on under Native AA and then hidden by a
            // mode change would otherwise put a blackholing tun on a USB session. The other half
            // is DummyVpnPolicy.shouldStartForSession.
            if (VpnControl.isVpnAvailable()) {
                items.add(SettingItem.ToggleSettingEntry(
                    stableId = "keepDummyVpnDuringSession",
                    // Through VpnControl, not R: this copy lives in the github flavor's
                    // resources so it is absent from the Play Store build entirely.
                    nameResId = VpnControl.toggleNameRes,
                    descriptionResId = VpnControl.toggleDescriptionRes,
                    isChecked = pendingKeepDummyVpnDuringSession ?: settings.keepDummyVpnDuringSession,
                    searchKeywords = "vpn offline tun stutter dropout audio video 2.4 ghz network scan",
                    onCheckedChanged = { isChecked ->
                        if (!isChecked) {
                            pendingKeepDummyVpnDuringSession = false
                            checkChanges()
                            updateSettingsList()
                        } else {
                            // Null once this app is already the prepared VPN app, which is the
                            // state AapService needs to start it with no Activity.
                            val consent = VpnControl.consentIntent(requireContext())
                            if (consent == null) {
                                pendingKeepDummyVpnDuringSession = true
                                checkChanges()
                                updateSettingsList()
                            } else {
                                vpnConsentRequested = true
                                vpnConsentLauncher.launch(consent)
                            }
                        }
                    }
                ))
            }
        }

        // Only on units whose Bluetooth is an external module, where the native route is refused
        // outright and this is the one thing that might change that. Everywhere else it would be an
        // action with no meaning.
        //
        // Deliberately outside the Native AA block: the reporters who need this are on units where
        // that mode does not work, so requiring them to select it first would hide the diagnostic
        // behind the very setting it is diagnosing.
        if (BluetoothHelper.externalBtEvidence != null) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "externalBtZbtTransport",
                nameResId = R.string.external_bt_transport,
                descriptionResId = R.string.external_bt_transport_description,
                isChecked = pendingExternalBtZbtTransport ?: settings.externalBtZbtTransport,
                searchKeywords = "zbt zlink external bluetooth module transport handshake vendor daemon",
                onCheckedChanged = { isChecked ->
                    pendingExternalBtZbtTransport = isChecked
                    checkChanges()
                    updateSettingsList()
                }
            ))

            items.add(SettingItem.SettingEntry(
                stableId = "zbtProbe",
                nameResId = R.string.zbt_probe_title,
                value = zbtProbeResult ?: getString(R.string.zbt_probe_idle),
                searchKeywords = "zbt zlink external bluetooth module probe test vendor daemon rfcomm",
                onClick = { _ -> confirmAndRunZbtProbe() }
            ))
        }

        // Sub-setting for Headunit Server (Manual vs Auto)
        if (pendingWifiConnectionMode == WifiLauncherMode.MANUAL || pendingWifiConnectionMode == WifiLauncherMode.AUTO) {
            items.add(SettingItem.SegmentedButtonSettingEntry(
                stableId = "serverModeSelection",
                nameResId = R.string.server_mode_label,
                options = listOf(getString(R.string.server_mode_manual), getString(R.string.server_mode_auto)),
                selectedIndex = if (pendingWifiConnectionMode == WifiLauncherMode.MANUAL) 0 else 1,
                onOptionSelected = { index ->
                    pendingWifiConnectionMode = if (index == 0) WifiLauncherMode.MANUAL else WifiLauncherMode.AUTO
                    checkChanges()
                    updateSettingsList()
                }
            ))

            // Mode 1 (Auto Server) can also use the auto-hotspot feature
            if (pendingWifiConnectionMode == WifiLauncherMode.AUTO) {
                addHotspotToggle(items)
            }
        }

        // Sub-setting for Wireless Helper Strategy
        if (pendingWifiConnectionMode == WifiLauncherMode.HELPER) {
            val helperStrategies = resources.getStringArray(R.array.helper_strategies)
            val currentStrategyId = pendingHelperConnectionStrategy?.id ?: settings.helperConnectionStrategy.id
            items.add(SettingItem.SettingEntry(
                stableId = "helperStrategy",
                nameResId = R.string.helper_strategy_label,
                value = helperStrategies.getOrElse(currentStrategyId) { "" },
                onClick = {
                    MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                        .setTitle(R.string.helper_strategy_label)
                        .setSingleChoiceItems(helperStrategies, currentStrategyId) { dialog, which ->
                            pendingHelperConnectionStrategy = HelperStrategy.byIdOrDefault(which)
                            checkChanges()
                            dialog.dismiss()
                            updateSettingsList()
                        }
                        .show()
                }
            ))

            // Mode 2 only shows Hotspot toggle for Strategy 4 (Headunit Hotspot)
            if (pendingHelperConnectionStrategy == HelperStrategy.HEADUNIT_HOTSPOT) {
                addHotspotToggle(items)
                // Strategy 4 reaches the same HotspotManager sweep as the Native AA hotspot
                // transport, so the band choice applies here too and would otherwise be invisible.
                addHotspotBandSetting(items)
                if (pendingHotspotBandPreference() != HotspotBandPreference.FORCE_2_4GHZ) {
                    addFiveGhzChannelSetting(items)
                }
            }

            if (pendingHelperConnectionStrategy == HelperStrategy.WIFI_DIRECT) { // WiFi Direct (P2P)
                items.add(SettingItem.ToggleSettingEntry(
                    stableId = "waitForWifi",
                    nameResId = R.string.wait_for_wifi,
                    descriptionResId = R.string.wait_for_wifi_description,
                    isChecked = pendingWaitForWifi ?: false,
                    onCheckedChanged = { isChecked ->
                        pendingWaitForWifi = isChecked
                        checkChanges()
                        updateSettingsList()
                    }
                ))

                if (pendingWaitForWifi == true) {
                    items.add(SettingItem.SliderSettingEntry(
                        stableId = "waitForWifiTimeout",
                        nameResId = R.string.wait_for_wifi_timeout,
                        value = "${pendingWaitForWifiTimeout}s",
                        sliderValue = (pendingWaitForWifiTimeout ?: 10).toFloat(),
                        valueFrom = 5f,
                        valueTo = 30f,
                        stepSize = 1f,
                        onValueChanged = { value ->
                            pendingWaitForWifiTimeout = value.toInt()
                            checkChanges()
                            updateSettingsList()
                        }
                    ))
                }
            }
        }

        // Only where something reads it. Three sites do: WifiDirectManager resolves it into the
        // group's BSSID, SoftApCredentialsProvider hands it to the hotspot transport, and the
        // preflight probe reports on it - which between them is Native AA on either transport, and
        // Helper's WiFi Direct strategy. Headunit Server never looks at it, so a row there is a
        // question with no answer attached.
        //
        // Not WifiModePolicy.usesWifiDirect, the obvious reuse: it drops mode 3 on the hotspot
        // transport, which does read the override. Nor anything stricter, because the banner's own
        // remedy deep-links here by searching for this row's title, and search bypasses the Basic
        // and Advanced tiers but not this gate - a stricter one would land that tap on an empty
        // result. Hidden without clearing pendingStaticBSSID, as the band levers above are.
        if (pendingWifiConnectionMode == WifiLauncherMode.NATIVE ||
            (pendingWifiConnectionMode == WifiLauncherMode.HELPER && pendingHelperConnectionStrategy == HelperStrategy.WIFI_DIRECT)
        ) {
            // One row, not two. An access point and a P2P group are different interfaces and keep
            // separate addresses, but only one of them is ever in force, so the row edits the one
            // the selected transport announces and the dialog's message says which. The title is
            // fixed because the banner's remedy deep-links by searching for it.
            val forP2p = pendingWifiConnectionMode == WifiLauncherMode.HELPER ||
                pendingNativeTransport() == NativeTransport.WIFI_DIRECT
            val bssid = if (forP2p) pendingStaticP2pBSSID else pendingStaticBSSID
            items.add(SettingItem.SettingEntry(
                stableId = "staticBSSID",
                nameResId = R.string.static_bssid_title,
                searchKeywords = "bssid mac address wifi direct group access point hotspot",
                value = if (bssid == "0" || bssid == null) getString(R.string.auto) else bssid,
                onClick = { _ ->
                    DialogUtils.showTextInputDialogWithMessage(
                        requireContext(),
                        R.string.static_bssid_title,
                        if (forP2p) R.string.static_p2p_bssid_desc else R.string.static_bssid_desc,
                        if (bssid == "0" || bssid == null) "" else bssid,
                        { newVal ->
                            val trimmed = newVal.trim()
                            // Validated here rather than accepted and dealt with later. A value that is
                            // not MAC-shaped still beats every automatic source, so it does not fail at
                            // entry — it fails 30 s into a connection with a message about location
                            // services, which is the wrong thing to send somebody looking for.
                            val stored = when {
                                trimmed.isEmpty() -> "0"
                                SoftApBssidPolicy.isUsable(trimmed) -> trimmed
                                else -> {
                                    ToastUtils.showToast(
                                        requireContext(), R.string.preflight_invalid_bssid, Toast.LENGTH_LONG, force = true
                                    )
                                    null
                                }
                            }
                            if (stored != null) {
                                if (forP2p) pendingStaticP2pBSSID = stored else pendingStaticBSSID = stored
                            }
                            checkChanges()
                            updateSettingsList()
                        }
                    )
                }
            ))
        }


        // Ungated, unlike the static BSSID above: this address is announced as carAddress in
        // every connection mode, and the setup QR's own refusal deep-links here by searching for
        // this row's title, which bypasses the Basic and Advanced tiers but not a construction gate.
        val btAddress = pendingBluetoothAddress.orEmpty()
        items.add(SettingItem.SettingEntry(
            stableId = "bluetoothAddress",
            nameResId = R.string.bluetooth_address_s,
            value = btAddress.ifEmpty { getString(R.string.not_set) },
            searchKeywords = "bluetooth mac address car identity hands-free calls qr",
            onClick = { _ ->
                DialogUtils.showTextInputDialogWithMessage(
                    requireContext(),
                    R.string.enter_bluetooth_mac,
                    R.string.bluetooth_address_message,
                    btAddress,
                    { newVal ->
                        // Validated at entry, for the reason the static BSSID row records: an
                        // address that is not MAC-shaped is announced verbatim and fails much later.
                        val trimmed = newVal.trim()
                        when {
                            trimmed.isEmpty() -> pendingBluetoothAddress = ""
                            SoftApBssidPolicy.isUsable(trimmed) -> pendingBluetoothAddress = trimmed
                            else -> ToastUtils.showToast(
                                requireContext(), R.string.invalid_bluetooth_address, Toast.LENGTH_LONG, force = true
                            )
                        }
                        checkChanges()
                        updateSettingsList()
                    }
                )
            }
        ))

        // Ungated like the address above: ServiceDiscoveryResponse carries these parameters on
        // every transport, so a mode gate would hide the row from the connection it was asked for.
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "announceConnectionConfiguration",
            nameResId = R.string.announce_connection_configuration,
            descriptionResId = R.string.announce_connection_configuration_description,
            isChecked = pendingAnnounceConnectionConfiguration ?: settings.announceConnectionConfiguration,
            searchKeywords = "ping timeout socket buffer link drop session stall scan connection",
            onCheckedChanged = { isChecked ->
                pendingAnnounceConnectionConfiguration = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        // --- Automation ---
        items.add(SettingItem.CategoryHeader("automation", R.string.category_automation))

        items.add(SettingItem.SettingEntry(
            stableId = "autoStartSettings",
            nameResId = R.string.auto_start_settings,
            value = getString(R.string.auto_start_settings_description),
            searchKeywords = kw(
                R.string.auto_start_on_boot_label, R.string.auto_start_screen_on_label,
                R.string.auto_start_usb_label, R.string.auto_start_bt_label, R.string.auto_start_wifi_label,
                R.string.auto_disconnect_bt_label
            ),
            onClick = {
                try {
                    findNavController().navigate(R.id.action_settingsFragment_to_autoStartFragment)
                } catch (e: Exception) { }
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "autoConnectSettings",
            nameResId = R.string.auto_connect_settings,
            value = getAutoConnectSummary(),
            searchKeywords = kw(
                R.string.auto_connect_last_session, R.string.auto_connect_single_usb,
                R.string.auto_start_self_mode
            ),
            onClick = {
                try {
                    findNavController().navigate(R.id.action_settingsFragment_to_autoConnectFragment)
                } catch (e: Exception) { }
            }
        ))

        val isCarLauncherActive = (pendingEnableCarLauncher ?: settings.enableCarLauncher) ||
            CarLauncherManager.isDefaultLauncher(requireContext())

        if (!isCarLauncherActive) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "killOnDisconnect",
                nameResId = R.string.kill_on_disconnect,
                descriptionResId = R.string.kill_on_disconnect_description,
                isChecked = pendingKillOnDisconnect ?: settings.rawKillOnDisconnect,
                onCheckedChanged = { isChecked ->
                    if (isChecked) {
                        val conflicts = getKillOnDisconnectConflicts()
                        val hasAutoStartOnBoot = settings.autoStartOnBoot
                        val hasAutoStartOnScreenOn = settings.autoStartOnScreenOn
                        if (conflicts.isNotEmpty() || hasAutoStartOnBoot || hasAutoStartOnScreenOn) {
                            pendingKillOnDisconnect = true
                            updateSettingsList()
                            showKillOnDisconnectWarning(conflicts, hasAutoStartOnBoot, hasAutoStartOnScreenOn)
                        } else {
                            pendingKillOnDisconnect = true
                            checkChanges()
                            updateSettingsList()
                        }
                    } else {
                        pendingKillOnDisconnect = false
                        checkChanges()
                        updateSettingsList()
                    }
                }
            ))
        }

        // Self Mode is the only mode where the phone's call screen and the projection share a
        // screen, so it is the only mode this can do anything in.
        if (settings.showsSelf()) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "raiseProjectionDuringCall",
                nameResId = R.string.raise_projection_during_call,
                descriptionResId = R.string.raise_projection_during_call_description,
                isChecked = pendingRaiseProjectionDuringCall ?: settings.raiseProjectionDuringCall,
                onCheckedChanged = { isChecked ->
                    pendingRaiseProjectionDuringCall = isChecked
                    checkChanges()
                    updateSettingsList()
                }
            ))
        }

        // --- More Features Settings ---
        items.add(SettingItem.CategoryHeader("moreFeatures", R.string.category_more_features))

        val isCarLauncherEnabled = pendingEnableCarLauncher ?: settings.enableCarLauncher

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "enableCarLauncher",
            nameResId = R.string.pref_enable_car_launcher_title,
            descriptionResId = R.string.pref_enable_car_launcher_summary,
            isChecked = isCarLauncherEnabled,
            onCheckedChanged = { isChecked ->
                pendingEnableCarLauncher = isChecked
                CarLauncherManager.setLauncherEnabled(requireContext(), isChecked)
                checkChanges()
                updateSettingsList()
            }
        ))

        if (isCarLauncherEnabled) {
            val isDefault = CarLauncherManager.isDefaultLauncher(requireContext())
            items.add(SettingItem.SettingEntry(
                stableId = "setDefaultLauncher",
                nameResId = R.string.pref_set_default_launcher_title,
                value = getString(if (isDefault) R.string.pref_default_launcher_status_active else R.string.pref_default_launcher_status_inactive),
                onClick = {
                    CarLauncherManager.promptSetDefaultLauncher(requireContext())
                }
            ))
        }

        val isFloatingButtonEnabled = pendingEnableFloatingButton ?: settings.enableFloatingButton

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "enableFloatingButton",
            nameResId = R.string.pref_enable_floating_button_title,
            descriptionResId = R.string.pref_enable_floating_button_summary,
            isChecked = isFloatingButtonEnabled,
            onCheckedChanged = { isChecked ->
                pendingEnableFloatingButton = isChecked
                if (isChecked) {
                    FloatingButtonManager.requestOverlayPermission(requireContext())
                }
                checkChanges()
                updateSettingsList()
            }
        ))

        if (isFloatingButtonEnabled) {
            val size = pendingFloatingButtonSizeDp ?: settings.floatingButtonSizeDp
            items.add(SettingItem.SliderSettingEntry(
                stableId = "floatingButtonSizeDp",
                nameResId = R.string.pref_floating_button_size_title,
                value = "${size}dp",
                sliderValue = size.toFloat(),
                valueFrom = 32f,
                valueTo = 120f,
                stepSize = 1f,
                onValueChanged = { newVal ->
                    pendingFloatingButtonSizeDp = newVal.toInt()
                    checkChanges()
                }
            ))

            val opacity = pendingFloatingButtonOpacityPercent ?: settings.floatingButtonOpacityPercent
            items.add(SettingItem.SliderSettingEntry(
                stableId = "floatingButtonOpacityPercent",
                nameResId = R.string.pref_floating_button_opacity_title,
                value = "${opacity}%",
                sliderValue = opacity.toFloat(),
                valueFrom = 10f,
                valueTo = 100f,
                stepSize = 5f,
                onValueChanged = { newVal ->
                    pendingFloatingButtonOpacityPercent = newVal.toInt()
                    checkChanges()
                }
            ))

            val xPos = pendingFloatingButtonXPercent ?: settings.floatingButtonXPercent
            items.add(SettingItem.SliderSettingEntry(
                stableId = "floatingButtonXPercent",
                nameResId = R.string.pref_floating_button_x_title,
                value = "${xPos}%",
                sliderValue = xPos.toFloat(),
                valueFrom = 0f,
                valueTo = 100f,
                stepSize = 1f,
                onValueChanged = { newVal ->
                    pendingFloatingButtonXPercent = newVal.toInt()
                    checkChanges()
                }
            ))

            val yPos = pendingFloatingButtonYPercent ?: settings.floatingButtonYPercent
            items.add(SettingItem.SliderSettingEntry(
                stableId = "floatingButtonYPercent",
                nameResId = R.string.pref_floating_button_y_title,
                value = "${yPos}%",
                sliderValue = yPos.toFloat(),
                valueFrom = 0f,
                valueTo = 100f,
                stepSize = 1f,
                onValueChanged = { newVal ->
                    pendingFloatingButtonYPercent = newVal.toInt()
                    checkChanges()
                }
            ))
        }

        if (!isCarLauncherActive) {
            val exitActions = arrayOf(
                getString(R.string.aa_exit_action_oem_launcher),
                getString(R.string.aa_exit_action_app_home),
                getString(R.string.aa_exit_action_disconnect)
            )
            val currentExitActionIdx = (pendingAaExitAction ?: settings.rawAaExitAction).value
            items.add(SettingItem.SettingEntry(
                stableId = "aaExitAction",
                nameResId = R.string.pref_aa_exit_action_title,
                value = exitActions.getOrElse(currentExitActionIdx) { exitActions[0] },
                searchKeywords = getString(R.string.pref_aa_exit_action_summary),
                onClick = { _ ->
                    MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                        .setTitle(R.string.pref_aa_exit_action_title)
                        .setSingleChoiceItems(exitActions, currentExitActionIdx) { dialog, which ->
                            Settings.ExitAction.fromInt(which)?.let { pendingAaExitAction = it }
                            checkChanges()
                            dialog.dismiss()
                            updateSettingsList()
                        }
                        .show()
                }
            ))
        }

        // --- Navigation Settings ---
                items.add(SettingItem.SettingEntry(
            stableId = "oemAppManagement",
            nameResId = R.string.oem_app_management,
            value = if (pendingAutoKillOemApps ?: settings.autoKillOemApps) getString(R.string.oem_app_autokill_enabled) else getString(R.string.oem_app_management_desc),
            searchKeywords = "zlink autokit speedplay tlink carplay adb root oem conflict kill stop disable",
            onClick = {
                showOemAppManagementDialog()
            }
        ))

        items.add(SettingItem.CategoryHeader("navigation", R.string.category_navigation))

        // The GPS source choice (this device vs the connected phone) only applies when a phone is
        // connected. With Self Mode as the only connection there is no phone, so hide it.
        if (settings.showsExternalGps()) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "gpsNavigation",
                nameResId = R.string.gps_for_navigation,
                descriptionResId = R.string.gps_for_navigation_description,
                isChecked = pendingUseGps ?: settings.useGpsForNavigation,
                onCheckedChanged = { isChecked ->
                    pendingUseGps = isChecked
                    checkChanges()
                    updateSettingsList()
                }
            ))
        }

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "showNavigationNotifications",
            nameResId = R.string.show_navigation_notifications,
            descriptionResId = R.string.show_navigation_notifications_description,
            isChecked = pendingShowNavigationNotifications ?: settings.showNavigationNotifications,
            onCheckedChanged = { isChecked ->
                pendingShowNavigationNotifications = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "fakeSpeed",
            nameResId = R.string.fake_speed_title,
            descriptionResId = R.string.fake_speed_description,
            isChecked = pendingFakeSpeed ?: settings.fakeSpeed,
            onCheckedChanged = { isChecked ->
                pendingFakeSpeed = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        // --- Graphic Settings ---
        items.add(SettingItem.CategoryHeader("graphic", R.string.category_graphic))

        items.add(SettingItem.SettingEntry(
            stableId = "resolution",
            nameResId = R.string.resolution,
            value = Settings.Resolution.fromId(pendingResolution ?: settings.resolutionId)?.resName ?: "",
            searchKeywords = Settings.Resolution.allRes.joinToString(" "),
            onClick = { showResolutionDialog() }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "dpiPixelDensity",
            nameResId = R.string.dpi,
            value = if (pendingDpi == 0) getString(R.string.auto) else pendingDpi.toString(),
            onClick = {
                try {
                    findNavController().navigate(R.id.action_settingsFragment_to_dpiSettingsFragment)
                } catch (e: Exception) {
                    // Failover
                }
            }
        ))



        items.add(SettingItem.SettingEntry(
            stableId = "pixelAspectRatioE4",
            nameResId = R.string.pixel_aspect_ratio,
            value = if ((pendingPixelAspectRatioE4 ?: 10000) <= 0) "10000" else pendingPixelAspectRatioE4.toString(),
            onClick = { _ ->
                showNumericInputDialog(
                    title = getString(R.string.enter_pixel_aspect_ratio_value),
                    message = null,
                    initialValue = if ((pendingPixelAspectRatioE4 ?: 10000) <= 0) 10000 else (pendingPixelAspectRatioE4 ?: 10000),
                    onConfirm = { newVal ->
                        pendingPixelAspectRatioE4 = if (newVal <= 0) 10000 else newVal
                        checkChanges()
                        updateSettingsList()
                    }
                )
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "customInsets",
            nameResId = R.string.custom_insets,
            value = "${pendingInsetLeft ?: 0}, ${pendingInsetTop ?: 0}, ${pendingInsetRight ?: 0}, ${pendingInsetBottom ?: 0}",
            onClick = {
                showCustomInsetsDialog()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "startInFullscreenMode",
            nameResId = R.string.start_in_fullscreen_mode,
            value = when (pendingFullscreenMode) {
                Settings.FullscreenMode.NONE -> getString(R.string.fullscreen_none)
                Settings.FullscreenMode.IMMERSIVE -> getString(R.string.fullscreen_immersive)
                Settings.FullscreenMode.STATUS_ONLY -> getString(R.string.fullscreen_status_only)
                Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH -> getString(R.string.fullscreen_immersive_avoid_notch)
                else -> getString(R.string.auto)
            },
            onClick = {
                val modes = arrayOf(
                    getString(R.string.fullscreen_none),
                    getString(R.string.fullscreen_immersive),
                    getString(R.string.fullscreen_status_only),
                    getString(R.string.fullscreen_immersive_avoid_notch)
                )
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.start_in_fullscreen_mode)
                    .setSingleChoiceItems(modes, pendingFullscreenMode?.value ?: 0) { dialog, which ->
                        val newMode = Settings.FullscreenMode.fromInt(which) ?: Settings.FullscreenMode.NONE
                        pendingFullscreenMode = newMode

                        // PERSIST IMMEDIATELY (Rescue Mode)
                        settings.fullscreenMode = newMode
                        settings.commit()

                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()

                        // Apply immediately to current UI
                        requireActivity().recreate()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "viewMode",
            nameResId = R.string.view_mode,
            searchKeywords = kw(R.string.surface_view, R.string.texture_view, R.string.gles_view),
            value = when (pendingViewMode) {
                Settings.ViewMode.SURFACE -> getString(R.string.surface_view)
                Settings.ViewMode.TEXTURE -> getString(R.string.texture_view)
                Settings.ViewMode.GLES -> getString(R.string.gles_view)
                else -> getString(R.string.surface_view)
            },
            onClick = { _ ->
                val viewModes = arrayOf(getString(R.string.surface_view), getString(R.string.texture_view), getString(R.string.gles_view))
                val currentIdx = pendingViewMode?.value ?: settings.viewMode.value
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.change_view_mode)
                    .setSingleChoiceItems(viewModes, currentIdx) { dialog, which ->
                        Settings.ViewMode.fromInt(which)?.let { pendingViewMode = it }
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        val orientationOptions = resources.getStringArray(R.array.screen_orientation)
        val currentOrientationIdx = pendingScreenOrientation?.value ?: settings.screenOrientation.value
        items.add(SettingItem.SettingEntry(
            stableId = "screenOrientation",
            nameResId = R.string.screen_orientation,
            value = orientationOptions.getOrElse(currentOrientationIdx) { "" },
            searchKeywords = orientationOptions.joinToString(" "),
            onClick = { _ ->
                val currentIdx = pendingScreenOrientation?.value ?: settings.screenOrientation.value
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.change_screen_orientation)
                    .setSingleChoiceItems(orientationOptions, currentIdx) { dialog, whiches ->
                        val newOrientation = Settings.ScreenOrientation.fromInt(whiches) ?: Settings.ScreenOrientation.SYSTEM
                        pendingScreenOrientation = newOrientation

                        // Apply immediately
                        settings.screenOrientation = newOrientation
                        settings.commit()

                        requireActivity().requestedOrientation = newOrientation.androidOrientation
                        requireContext().sendBroadcast(Intent(AapService.ACTION_ORIENTATION_CHANGED).apply {
                            setPackage(requireContext().packageName)
                        })

                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // Video fit: how a mismatched-aspect video is fitted into the panel (object-fit style).
        items.add(SettingItem.SettingEntry(
            stableId = "videoFitMode",
            nameResId = R.string.video_fit_mode,
            value = resources.getStringArray(R.array.video_fit_mode)[(pendingVideoFitMode ?: settings.videoFitMode).value],
            searchKeywords = resources.getStringArray(R.array.video_fit_mode).joinToString(" "),
            onClick = { _ ->
                val fitOptions = resources.getStringArray(R.array.video_fit_mode)
                val currentIdx = (pendingVideoFitMode ?: settings.videoFitMode).value
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.change_video_fit_mode)
                    .setSingleChoiceItems(fitOptions, currentIdx) { dialog, which ->
                        pendingVideoFitMode = Settings.VideoFitMode.fromInt(which) ?: Settings.VideoFitMode.FILL
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "hudMirroring",
            nameResId = R.string.hud_mirroring,
            descriptionResId = R.string.hud_mirroring_description,
            isChecked = pendingHudMirroring ?: false,
            onCheckedChanged = { isChecked ->
                pendingHudMirroring = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        if (pendingViewMode == Settings.ViewMode.SURFACE) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "forcedScale",
                nameResId = R.string.forced_scale,
                descriptionResId = R.string.forced_scale_description,
                isChecked = pendingForcedScale ?: settings.forcedScale,
                onCheckedChanged = { isChecked ->
                    pendingForcedScale = isChecked
                    requiresRestart = true
                    checkChanges()
                    updateSettingsList()
                }
            ))
        }

        // --- Theming Settings ---
        items.add(SettingItem.CategoryHeader("theming", R.string.category_theming))

        items.add(SettingItem.SettingEntry(
            stableId = "loadingScreen",
            nameResId = R.string.loading_screen,
            value = if (settings.loadingScreenMediaPath.isNullOrEmpty())
                getString(R.string.loading_screen_default)
            else getString(R.string.loading_screen_custom),
            onClick = {
                findNavController().navigate(R.id.action_settingsFragment_to_loadingScreenFragment)
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "customization",
            nameResId = R.string.customization_title,
            value = getString(R.string.customization_description),
            onClick = {
                findNavController().navigate(R.id.action_settingsFragment_to_customizationFragment)
            }
        ))

        val appThemeTitles = resources.getStringArray(R.array.app_theme)
        val nightModeTitles = resources.getStringArray(R.array.night_mode)
        val darkModeValue = "${getString(R.string.app_theme_short)}: ${appThemeTitles[settings.appTheme.value]} · " +
                "${getString(R.string.night_mode_short)}: ${nightModeTitles[settings.nightMode.value]}"
        items.add(SettingItem.SettingEntry(
            stableId = "darkModeSettings",
            nameResId = R.string.dark_mode_settings,
            value = darkModeValue,
            searchKeywords = kw(
                R.string.night_mode, R.string.app_theme, R.string.threshold_light_title,
                R.string.threshold_brightness_title, R.string.monochrome_icons,
                R.string.use_gradient_background, R.string.use_extreme_dark, R.string.aa_monochrome,
                R.string.sunrise_location_title, R.string.location_section
            ),
            onClick = {
                try {
                    findNavController().navigate(R.id.action_settingsFragment_to_darkModeFragment)
                } catch (e: Exception) {
                    // Failover
                }
            }
        ))

        // --- Video Settings ---
        items.add(SettingItem.CategoryHeader("video", R.string.category_video))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "forceSoftwareDecoding",
            nameResId = R.string.force_software_decoding,
            descriptionResId = R.string.force_software_decoding_description,
            isChecked = pendingForceSoftware ?: settings.forceSoftwareDecoding,
            onCheckedChanged = { isChecked ->
                pendingForceSoftware = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        if (pendingForceSoftware == true) {
            items.add(SettingItem.SettingEntry(
                stableId = "softwareVideoDecoder",
                nameResId = R.string.software_video_decoder,
                value = when (pendingSoftwareVideoDecoder) {
                    Settings.SoftwareVideoDecoder.DEVICE_MEDIACODEC -> getString(R.string.software_video_decoder_device)
                    Settings.SoftwareVideoDecoder.BUNDLED_FFMPEG -> getString(R.string.software_video_decoder_bundled)
                    null -> ""
                },
                onClick = { _ ->
                    val decoders = arrayOf(
                        getString(R.string.software_video_decoder_bundled),
                        getString(R.string.software_video_decoder_device)
                    )
                    val decoderValues = arrayOf(
                        Settings.SoftwareVideoDecoder.BUNDLED_FFMPEG,
                        Settings.SoftwareVideoDecoder.DEVICE_MEDIACODEC
                    )
                    val currentDecoderIndex = decoderValues.indexOf(pendingSoftwareVideoDecoder).coerceAtLeast(0)
                    MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                        .setTitle(R.string.software_video_decoder)
                        .setSingleChoiceItems(decoders, currentDecoderIndex) { dialog, which ->
                            pendingSoftwareVideoDecoder = decoderValues[which]
                            checkChanges()
                            dialog.dismiss()
                            updateSettingsList()
                        }
                        .show()
                }
            ))
        }

        items.add(SettingItem.SettingEntry(
            stableId = "videoCodec",
            nameResId = R.string.video_codec,
            value = pendingVideoCodec ?: settings.videoCodec,
            searchKeywords = "Auto H.264 H.265",
            onClick = { _ ->
                val codecs = arrayOf("Auto", "H.264", "H.265")
                val currentCodecIndex = codecs.indexOf(pendingVideoCodec)
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.video_codec)
                    .setSingleChoiceItems(codecs, currentCodecIndex) { dialog, which ->
                        pendingVideoCodec = codecs[which]
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "fpsLimit",
            nameResId = R.string.fps_limit,
            value = "${pendingFpsLimit} FPS",
            onClick = { _ ->
                val fpsOptions = arrayOf("30", "60")
                val currentFpsIndex = fpsOptions.indexOf(pendingFpsLimit.toString())
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.fps_limit)
                    .setSingleChoiceItems(fpsOptions, currentFpsIndex) { dialog, which ->
                        pendingFpsLimit = fpsOptions[which].toInt()
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // Beside the two rows it overrides, because that is where a user looking at 1080p/60 and
        // not getting it will come looking.
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "narrowBandProfileCap",
            nameResId = R.string.narrow_band_profile_cap,
            descriptionResId = R.string.narrow_band_profile_cap_description,
            isChecked = pendingNarrowBandProfileCap ?: settings.narrowBandProfileCap,
            searchKeywords = "2.4 GHz band resolution fps limit hotspot wifi direct video",
            onCheckedChanged = { isChecked ->
                pendingNarrowBandProfileCap = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        // Safe to try: the configure ladder falls back on its own if the decoder rejects the key.
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "debugVideoLowLatency",
            nameResId = R.string.debug_video_low_latency,
            descriptionResId = R.string.debug_video_low_latency_description,
            isChecked = pendingDebugVideoLowLatency ?: settings.debugVideoLowLatency,
            searchKeywords = "low latency vendor key decoder mediatek amlogic qualcomm exynos",
            onCheckedChanged = { isChecked ->
                pendingDebugVideoLowLatency = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        // --- Input Settings ---
        items.add(SettingItem.CategoryHeader("input", R.string.category_input))

        items.add(SettingItem.SettingEntry(
            stableId = "keymap",
            nameResId = R.string.keymap,
            value = getString(R.string.keymap_description),
            onClick = { _ ->
                try {
                    findNavController().navigate(R.id.action_settingsFragment_to_keymapFragment)
                } catch (e: Exception) {
                    // Failover
                }
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "enableRotary",
            nameResId = R.string.enable_rotary,
            descriptionResId = R.string.enable_rotary_description,
            isChecked = pendingEnableRotary ?: false,
            onCheckedChanged = { isChecked ->
                pendingEnableRotary = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        // Media buttons only. The rotary controller, D-pad and the rest are never affected by this,
        // which is the point: the head unit's Bluetooth side competes for the transport controls
        // and for nothing else.
        val mediaKeyModes = listOf(
            MediaKeyRoutingPolicy.Mode.ALWAYS,
            MediaKeyRoutingPolicy.Mode.AUTO,
            MediaKeyRoutingPolicy.Mode.NEVER
        )
        val currentMediaKeyMode = pendingMediaKeyRouting ?: MediaKeyRoutingPolicy.Mode.ALWAYS
        items.add(SettingItem.SegmentedButtonSettingEntry(
            stableId = "mediaKeyRouting",
            nameResId = R.string.media_key_routing,
            options = listOf(
                getString(R.string.media_key_routing_always),
                getString(R.string.media_key_routing_auto),
                getString(R.string.media_key_routing_never)
            ),
            selectedIndex = mediaKeyModes.indexOf(currentMediaKeyMode).coerceAtLeast(0),
            onOptionSelected = { index ->
                pendingMediaKeyRouting = mediaKeyModes.getOrElse(index) { MediaKeyRoutingPolicy.Mode.ALWAYS }
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.InfoBanner(
            stableId = "mediaKeyRoutingHint",
            textResId = when (currentMediaKeyMode) {
                MediaKeyRoutingPolicy.Mode.AUTO -> R.string.media_key_routing_auto_hint
                MediaKeyRoutingPolicy.Mode.NEVER -> R.string.media_key_routing_never_hint
                else -> R.string.media_key_routing_always_hint
            }
        ))

        // Only worth saying once the setting is actually holding something back: the reason to reach
        // for this is a doubled track skip, and the fear it raises is losing the rotary with it.
        if (currentMediaKeyMode != MediaKeyRoutingPolicy.Mode.ALWAYS) {
            items.add(SettingItem.InfoBanner(
                stableId = "mediaKeyRoutingScopeHint",
                textResId = R.string.media_key_routing_hint_common
            ))
        }

        // --- Audio Settings ---
        items.add(SettingItem.CategoryHeader("audio", R.string.category_audio))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "enableAudioSink",
            nameResId = R.string.enable_audio_sink,
            descriptionResId = R.string.enable_audio_sink_description,
            isChecked = pendingEnableAudioSink ?: settings.enableAudioSink,
            onCheckedChanged = { isChecked ->
                pendingEnableAudioSink = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        if (pendingEnableAudioSink == true) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "staticAudioFocus",
                nameResId = R.string.static_audio_focus,
                descriptionResId = R.string.static_audio_focus_description,
                isChecked = pendingStaticAudioFocus ?: false,
                onCheckedChanged = { isChecked ->
                    pendingStaticAudioFocus = isChecked
                    checkChanges()
                    updateSettingsList()
                }
            ))

            // Applies to both focus routes: the dynamic one that runs while an AA audio channel
            // plays, and static mode's permanent grab at connect.
            val focusModes = listOf(
                PlaybackFocusPolicy.Mode.AUTO,
                PlaybackFocusPolicy.Mode.ALWAYS,
                PlaybackFocusPolicy.Mode.NEVER
            )
            val currentMode = pendingPlaybackFocusMode ?: PlaybackFocusPolicy.Mode.AUTO
            items.add(SettingItem.SegmentedButtonSettingEntry(
                stableId = "playbackFocusMode",
                nameResId = R.string.playback_focus_mode,
                options = listOf(
                    getString(R.string.playback_focus_mode_auto),
                    getString(R.string.playback_focus_mode_always),
                    getString(R.string.playback_focus_mode_never)
                ),
                selectedIndex = focusModes.indexOf(currentMode).coerceAtLeast(0),
                onOptionSelected = { index ->
                    pendingPlaybackFocusMode = focusModes.getOrElse(index) { PlaybackFocusPolicy.Mode.AUTO }
                    checkChanges()
                    updateSettingsList()
                }
            ))

            items.add(SettingItem.InfoBanner(
                stableId = "playbackFocusModeHint",
                textResId = when (currentMode) {
                    PlaybackFocusPolicy.Mode.ALWAYS -> R.string.playback_focus_mode_always_hint
                    PlaybackFocusPolicy.Mode.NEVER -> R.string.playback_focus_mode_never_hint
                    else -> R.string.playback_focus_mode_auto_hint
                }
            ))

            // The hints above are written for the dynamic path, which takes focus only while
            // audio plays. Static mode takes it for the whole session, so say so rather than
            // maintaining a second set of three.
            if (pendingStaticAudioFocus == true) {
                items.add(SettingItem.InfoBanner(
                    stableId = "playbackFocusModeStaticHint",
                    textResId = R.string.playback_focus_mode_static_hint
                ))
            }
        }

        items.add(SettingItem.SettingEntry(
            stableId = "audioStreamSettings",
            nameResId = R.string.audio_stream_settings,
            value = getString(R.string.audio_stream_settings_description),
            searchKeywords = kw(
                R.string.separate_audio_streams,
                R.string.audio_channel_media,
                R.string.audio_channel_guidance,
                R.string.audio_channel_system
            ),
            onClick = { _ ->
                findNavController().navigate(R.id.action_settingsFragment_to_audioStreamSettingsFragment)
            }
        ))

        if (Build.VERSION.SDK_INT >= 26) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "useAAudioOutput",
                nameResId = R.string.aaudio_output,
                descriptionResId = R.string.aaudio_output_description,
                isChecked = pendingUseAAudioOutput ?: settings.useAAudioOutput,
                onCheckedChanged = { isChecked ->
                    pendingUseAAudioOutput = isChecked
                    checkChanges()
                    updateSettingsList()
                }
            ))
        }

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "useAacAudio",
            nameResId = R.string.use_aac_audio,
            descriptionResId = R.string.use_aac_audio_description,
            isChecked = pendingUseAacAudio ?: settings.useAacAudio,
            onCheckedChanged = { isChecked ->
                pendingUseAacAudio = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "attachHwDspEqualizer",
            nameResId = R.string.attach_hw_dsp_equalizer,
            descriptionResId = R.string.attach_hw_dsp_equalizer_description,
            isChecked = pendingAttachHwDspEqualizer ?: false,
            onCheckedChanged = { isChecked ->
                pendingAttachHwDspEqualizer = isChecked
                requiresRestart = true
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "syncMediaSessionAaMetadata",
            nameResId = R.string.sync_media_session_aa_metadata,
            descriptionResId = R.string.sync_media_session_aa_metadata_description,
            isChecked = pendingSyncMediaSessionAaMetadata ?: settings.syncMediaSessionWithAaMetadata,
            onCheckedChanged = { isChecked ->
                pendingSyncMediaSessionAaMetadata = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "autoResumePlaybackOnReconnect",
            nameResId = R.string.auto_resume_playback_on_reconnect,
            descriptionResId = R.string.auto_resume_playback_on_reconnect_description,
            isChecked = pendingAutoResumePlaybackOnReconnect ?: settings.autoResumePlaybackOnReconnect,
            onCheckedChanged = { isChecked ->
                pendingAutoResumePlaybackOnReconnect = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "micSettings",
            nameResId = R.string.microphone_settings,
            value = getString(R.string.microphone_settings_description),
            searchKeywords = kw(R.string.mic_sample_rate, R.string.use_head_unit_microphone),
            onClick = { _ ->
                findNavController().navigate(R.id.action_settingsFragment_to_micSettingsFragment)
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "audioVolumeOffsets",
            nameResId = R.string.audio_volume_offset,
            value = "${(100 + (pendingMediaVolumeOffset ?: 0))}% / ${(100 + (pendingGuidanceVolumeOffset ?: 0))}% / ${(100 + (pendingSystemVolumeOffset ?: 0))}%",
            onClick = {
                showAudioOffsetsDialog()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "audioLatencyMultiplier",
            nameResId = R.string.audio_latency_multiplier,
            value = "${pendingAudioLatencyMultiplier}x",
            onClick = { _ ->
                val options = arrayOf(
                    "1x (lowest latency)", "2x (low latency, default)", "4x (medium)",
                    "8x (deep)", "16x (deepest)"
                )
                val values = intArrayOf(1, 2, 4, 8, 16)
                val currentIndex = values.indexOf(pendingAudioLatencyMultiplier ?: com.andrerinas.openheadunit.decoder.audio.AudioJitterBufferPolicy.DEFAULT_MULTIPLIER).coerceAtLeast(0)
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.audio_latency_multiplier)
                    .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                        pendingAudioLatencyMultiplier = values[which]
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "audioQueueCapacity",
            nameResId = R.string.audio_queue_capacity,
            value = if (pendingAudioQueueCapacity == 0) "Unbounded (Legacy)" else "${pendingAudioQueueCapacity} chunks",
            onClick = { _ ->
                val options = arrayOf("10 chunks (Low Latency)", "20 chunks (Balanced)", "50 chunks (High Latency)", "Unbounded (Max Backlog)")
                val values = intArrayOf(10, 20, 50, 0)
                val currentIndex = values.indexOf(pendingAudioQueueCapacity ?: 0).coerceAtLeast(0)
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.audio_queue_capacity)
                    .setSingleChoiceItems(options, currentIndex) { dialog, which ->
                        pendingAudioQueueCapacity = values[which]
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                .show()
            }
        ))

        // --- UI Settings ---
        items.add(SettingItem.CategoryHeader("UI", R.string.category_ui))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "hideClock",
            nameResId = R.string.hide_clock_label,
            descriptionResId = null,
            isChecked = pendingHideClock ?: settings.hideClock,
            onCheckedChanged = { isChecked ->
                pendingHideClock = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "hidePhoneSignal",
            nameResId = R.string.hide_phone_signal_label,
            descriptionResId = R.string.might_broken_on_newer_aa_versions,
            isChecked = pendingHidePhoneSignal ?: settings.hidePhoneSignal,
            onCheckedChanged = { isChecked ->
                pendingHidePhoneSignal = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "hideBatteryLevel",
            nameResId = R.string.hide_battery_level_label,
            descriptionResId = R.string.might_broken_on_newer_aa_versions,
            isChecked = pendingHideBatteryLevel ?: settings.hideBatteryLevel,
            onCheckedChanged = { isChecked ->
                pendingHideBatteryLevel = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        // --- Backup Settings ---
        items.add(SettingItem.CategoryHeader("backup", R.string.category_backup))

        items.add(SettingItem.SettingEntry(
            stableId = "exportSettings",
            nameResId = R.string.export_settings,
            value = getString(R.string.export_settings_description),
            onClick = { _ -> startExportSettings() }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "importSettings",
            nameResId = R.string.import_settings,
            value = getString(R.string.import_settings_description),
            onClick = { _ -> startImportSettings() }
        ))

        // --- Reset Settings ---
        items.add(SettingItem.CategoryHeader("resetSettingsCategory", R.string.reset))
        items.add(SettingItem.SettingEntry(
            stableId = "resetSettings",
            nameResId = R.string.reset_settings,
            value = getString(R.string.reset_settings_description),
            onClick = {
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.reset_settings)
                    .setMessage(R.string.reset_settings_confirm)
                    .setPositiveButton(R.string.reset) { _, _ ->
                        settings.reset()

                        // Proper App Restart
                        val intent = requireActivity().packageManager.getLaunchIntentForPackage(requireActivity().packageName)
                        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        requireActivity().startActivity(intent)
                        requireActivity().finish()
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        ))

        // --- Debug Settings ---
        items.add(SettingItem.CategoryHeader("debug", R.string.category_debug))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "showPerformanceOverlay",
            nameResId = R.string.show_performance_overlay,
            descriptionResId = R.string.show_performance_overlay_description,
            isChecked = pendingShowPerformanceOverlay ?: settings.showPerformanceOverlay,
            onCheckedChanged = { isChecked ->
                pendingShowPerformanceOverlay = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        // The overlay sits in a top corner, and on a panel with an OEM bar that corner is covered.
        val overlayPositions = arrayOf(getString(R.string.margin_left), getString(R.string.margin_right))
        items.add(SettingItem.SettingEntry(
            stableId = "overlayPosition",
            nameResId = R.string.overlay_position,
            searchKeywords = kw(R.string.margin_left, R.string.margin_right),
            value = overlayPositions.getOrElse((pendingOverlayPosition ?: settings.overlayPosition).value) { "" },
            onClick = { _ ->
                val currentIdx = (pendingOverlayPosition ?: settings.overlayPosition).value
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.overlay_position)
                    .setSingleChoiceItems(overlayPositions, currentIdx) { dialog, which ->
                        Settings.OverlayPosition.fromInt(which)?.let { pendingOverlayPosition = it }
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // Lets a well-provisioned rig run the constrained video pipeline, which is otherwise only
        // reachable on 1GB hardware we do not have. Applies on the next connection; the configure
        // line reports the profile and marks it FORCED.
        val memoryProfileNames = listOf("Measure") +
            DeviceMemoryProfile.entries.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }
        val memoryProfileValues = listOf<DeviceMemoryProfile?>(null) + DeviceMemoryProfile.entries
        items.add(SettingItem.SettingEntry(
            stableId = "debugForceMemoryProfile",
            nameResId = R.string.debug_force_memory_profile,
            value = memoryProfileNames[memoryProfileValues.indexOf(settings.debugForceMemoryProfile).coerceAtLeast(0)],
            searchKeywords = "memory profile low ram constrained buffers queue",
            onClick = {
                val currentIndex = memoryProfileValues.indexOf(settings.debugForceMemoryProfile).coerceAtLeast(0)
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.debug_force_memory_profile)
                    .setSingleChoiceItems(memoryProfileNames.toTypedArray(), currentIndex) { dialog, which ->
                        settings.debugForceMemoryProfile = memoryProfileValues[which]
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // Slows the feed thread so the enqueue backpressure path can be exercised on a working
        // unit - a codec that cannot drain the negotiated rate is otherwise only reachable on
        // hardware we do not have. Applies on the next connection, and the feed thread announces
        // the hold loudly at start. A tool, not a preference, so applied immediately.
        val feedHolds = listOf(0, 10, 25, 40, 100)
        val feedHoldNames = feedHolds
            .map { if (it == 0) "Off" else "${it}ms per frame" }
            .toTypedArray()
        items.add(SettingItem.SettingEntry(
            stableId = "debugVideoFeedHold",
            nameResId = R.string.debug_video_feed_hold,
            value = if (settings.debugVideoFeedHoldMs == 0) "Off" else "${settings.debugVideoFeedHoldMs}ms per frame",
            searchKeywords = "slow decoder feed hold backpressure pacing test",
            onClick = {
                val currentIndex = feedHolds.indexOf(settings.debugVideoFeedHoldMs).coerceAtLeast(0)
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.debug_video_feed_hold)
                    .setSingleChoiceItems(feedHoldNames, currentIndex) { dialog, which ->
                        settings.debugVideoFeedHoldMs = feedHolds[which]
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        // Deliberately corrupts the video stream so the reassembler's failure paths can be
        // exercised on a working unit. Applies on the next connection, and every injected fault is
        // logged loudly - see VideoFaultInjector. Applied immediately rather than through the
        // pending/save flow, like the log level below: this is a tool, not a preference.
        val faultModes = VideoFaultInjector.Mode.entries
        val faultModeNames = faultModes
            .map { it.name.lowercase().replace('_', ' ').replaceFirstChar { c -> c.uppercase() } }
            .toTypedArray()
        items.add(SettingItem.SettingEntry(
            stableId = "debugVideoFaultInjection",
            nameResId = R.string.debug_video_fault_injection,
            value = faultModeNames[faultModes.indexOf(settings.debugVideoFaultInjection).coerceAtLeast(0)],
            searchKeywords = "fault injection corrupt fragment reassembly test",
            onClick = {
                val currentIndex = faultModes.indexOf(settings.debugVideoFaultInjection).coerceAtLeast(0)
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.debug_video_fault_injection)
                    .setSingleChoiceItems(faultModeNames, currentIndex) { dialog, which ->
                        settings.debugVideoFaultInjection = faultModes[which]
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        if (settings.debugVideoFaultInjection != VideoFaultInjector.Mode.OFF) {
            val faultRates = listOf(10, 30, 100, 300, 1000, 3000)
            val faultRateNames = faultRates.map { "1 in " + it }.toTypedArray()
            items.add(SettingItem.SettingEntry(
                stableId = "debugVideoFaultRate",
                nameResId = R.string.debug_video_fault_rate,
                value = "1 in " + settings.debugVideoFaultRate,
                onClick = {
                    val currentIndex = faultRates.indexOf(settings.debugVideoFaultRate).coerceAtLeast(0)
                    MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                        .setTitle(R.string.debug_video_fault_rate)
                        .setSingleChoiceItems(faultRateNames, currentIndex) { dialog, which ->
                            settings.debugVideoFaultRate = faultRates[which]
                            dialog.dismiss()
                            updateSettingsList()
                        }
                        .show()
                }
            ))

            val faultBudgets = listOf(VideoFaultInjector.UNLIMITED_BUDGET, 5, 10, 30, 100)
            val faultBudgetNames = faultBudgets.map { describeFaultBudget(it) }.toTypedArray()
            items.add(SettingItem.SettingEntry(
                stableId = "debugVideoFaultBudget",
                nameResId = R.string.debug_video_fault_budget,
                value = describeFaultBudget(settings.debugVideoFaultBudget),
                onClick = {
                    val currentIndex = faultBudgets.indexOf(settings.debugVideoFaultBudget).coerceAtLeast(0)
                    MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                        .setTitle(R.string.debug_video_fault_budget)
                        .setSingleChoiceItems(faultBudgetNames, currentIndex) { dialog, which ->
                            settings.debugVideoFaultBudget = faultBudgets[which]
                            dialog.dismiss()
                            updateSettingsList()
                        }
                        .show()
                }
            ))
        }

        // Sits beside the log settings because those are half of what it unlocks: with this off,
        // another app can still connect and disconnect, but cannot rewrite this unit's setup or
        // drive the capture.
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "allowExternalConfiguration",
            nameResId = R.string.allow_external_configuration,
            descriptionResId = R.string.allow_external_configuration_description,
            isChecked = pendingAllowExternalConfiguration ?: settings.allowExternalConfiguration,
            onCheckedChanged = { isChecked ->
                pendingAllowExternalConfiguration = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        val logLevels = LogExporter.LogLevel.entries
        val logLevelNames = logLevels.map { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } }.toTypedArray()
        items.add(SettingItem.SettingEntry(
            stableId = "logLevel",
            nameResId = R.string.log_level,
            value = settings.exporterLogLevel.name.lowercase().replaceFirstChar { it.uppercase() },
            onClick = {
                val currentIndex = logLevels.indexOf(settings.exporterLogLevel)
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.log_level)
                    .setSingleChoiceItems(logLevelNames, currentIndex) { dialog, which ->
                        val newLevel = logLevels[which]
                        settings.exporterLogLevel = newLevel
                        if (newLevel == LogExporter.LogLevel.SILENT) {
                            settings.exporterCaptureEnabled = false
                            if (settings.logSource == Settings.LogSource.APPLOG_FILE) {
                                AppLog.init(settings, requireContext().applicationContext)
                            } else if (LogExporter.isCapturing) {
                                LogExporter.stopCapture()
                            }
                        }
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        val logSources = Settings.LogSource.entries
        val logSourceNames = logSources.map {
            when (it) {
                Settings.LogSource.LOGCAT -> getString(R.string.log_source_logcat)
                Settings.LogSource.APPLOG_FILE -> getString(R.string.log_source_applog_file)
            }
        }.toTypedArray()
        items.add(SettingItem.SettingEntry(
            stableId = "logSource",
            nameResId = R.string.log_source,
            value = when (settings.logSource) {
                Settings.LogSource.LOGCAT -> getString(R.string.log_source_logcat)
                Settings.LogSource.APPLOG_FILE -> getString(R.string.log_source_applog_file)
            },
            onClick = {
                val currentIndex = logSources.indexOf(settings.logSource)
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.log_source)
                    .setSingleChoiceItems(logSourceNames, currentIndex) { dialog, which ->
                        val newSource = logSources[which]
                        settings.logSource = newSource
                        if (newSource == Settings.LogSource.APPLOG_FILE && LogExporter.isCapturing) {
                            LogExporter.stopCapture()
                        }
                        AppLog.init(settings, requireContext().applicationContext)
                        if (newSource == Settings.LogSource.APPLOG_FILE && settings.exporterCaptureEnabled && !AppLog.isCapturing) {
                            settings.exporterCaptureEnabled = false
                        }
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))

        val logLocations = Settings.LogLocation.entries
        val logLocationNames = logLocations.map {
            when (it) {
                Settings.LogLocation.DEFAULT -> getString(R.string.log_location_default)
                Settings.LogLocation.DOWNLOADS -> getString(R.string.log_location_downloads)
            }
        }.toTypedArray()
        items.add(SettingItem.SettingEntry(
            stableId = "logLocation",
            nameResId = R.string.log_location,
            value = when (settings.logLocation) {
                Settings.LogLocation.DEFAULT -> getString(R.string.log_location_default)
                Settings.LogLocation.DOWNLOADS -> getString(R.string.log_location_downloads)
            },
            onClick = {
                val currentIndex = logLocations.indexOf(settings.logLocation)
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.log_location)
                    .setSingleChoiceItems(logLocationNames, currentIndex) { dialog, which ->
                        val newLocation = logLocations[which]
                        val applyLocation: () -> Unit = {
                            settings.logLocation = newLocation
                            if (settings.logSource == Settings.LogSource.APPLOG_FILE) {
                                AppLog.init(settings, requireContext().applicationContext)
                            }
                            dialog.dismiss()
                            updateSettingsList()
                        }
                        if (newLocation == Settings.LogLocation.DOWNLOADS) {
                            runWithDownloadsStoragePermission(applyLocation)
                        } else {
                            applyLocation()
                        }
                    }
                    .show()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "captureLog",
            nameResId = if (if (settings.logSource == Settings.LogSource.APPLOG_FILE) AppLog.isCapturing else LogExporter.isCapturing) R.string.stop_log_capture else R.string.start_log_capture,
            value = when {
                settings.exporterLogLevel == LogExporter.LogLevel.SILENT -> getString(R.string.start_log_capture_description)
                LogExporter.isCapturing -> getString(R.string.stop_log_capture_description)
                else -> getString(R.string.start_log_capture_description)
            },
            onClick = {
                val context = requireContext()
                val exporterLevel = settings.exporterLogLevel
                if (exporterLevel == LogExporter.LogLevel.SILENT) {
                    ToastUtils.showToast(context, getString(R.string.start_log_capture_in_silent), Toast.LENGTH_LONG, force = true)
                    return@SettingEntry
                }

                if (settings.logSource == Settings.LogSource.APPLOG_FILE) {
                    val shouldStart = !AppLog.isCapturing
                    settings.exporterCaptureEnabled = shouldStart
                    AppLog.init(settings, context.applicationContext)
                    if (shouldStart && !AppLog.isCapturing) {
                        settings.exporterCaptureEnabled = false
                    }
                } else {
                    if (LogExporter.isCapturing) {
                        LogExporter.stopCapture()
                        settings.exporterCaptureEnabled = false
                    } else {
                        LogExporter.startCapture(context, exporterLevel)
                        settings.exporterCaptureEnabled = true
                    }
                }
                updateSettingsList()
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "exportLogs",
            nameResId = R.string.export_logs,
            value = getString(R.string.export_logs_description),
            onClick = {
                val context = requireContext()
                val exporterLevel = settings.exporterLogLevel
                if (exporterLevel == LogExporter.LogLevel.SILENT) {
                    ToastUtils.showToast(context, getString(R.string.failed_export_in_silent_logs), Toast.LENGTH_LONG, force = true)
                    return@SettingEntry
                }

                if (settings.logSource == Settings.LogSource.APPLOG_FILE) {
                    if (AppLog.isCapturing) {
                        settings.exporterCaptureEnabled = false
                        AppLog.init(settings, context.applicationContext)
                    }
                } else if (LogExporter.isCapturing) {
                    LogExporter.stopCapture()
                }
                // The export reads the logcat ring buffer, which on some ROMs waits on a consent
                // dialog. Off the main thread so that wait cannot take the UI down with it.
                viewLifecycleOwner.lifecycleScope.launch {
                    val logFile = LogExporter.saveLogToPublicFile(context, exporterLevel)
                    updateSettingsList()

                    if (logFile != null) {
                        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
                            .setTitle(R.string.logs_exported)
                            .setMessage(getString(R.string.log_saved_to, logFile.absolutePath))
                            .setPositiveButton(R.string.share) { _, _ ->
                                LogExporter.shareLogFile(context, logFile)
                            }
                            .setNegativeButton(R.string.close) { dialog, _ ->
                                dialog.dismiss()
                            }
                            .show()
                    } else {
                        ToastUtils.showToast(context, getString(R.string.failed_export_logs), Toast.LENGTH_SHORT, force = true)
                    }
                }
            }
        ))

        // --- Info Settings ---
        items.add(SettingItem.CategoryHeader("info", R.string.category_info))

        items.add(SettingItem.SettingEntry(
            stableId = "version",
            nameResId = R.string.version,
            value = BuildConfig.VERSION_NAME,
            onClick = { /* Read only */ }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "support",
            nameResId = R.string.support,
            value = getString(R.string.support_description),
            onClick = {
                try {
                    findNavController().navigate(R.id.action_settingsFragment_to_supportFragment)
                } catch (e: Exception) {
                    // Failover
                }
            }
        ))

        items.add(SettingItem.SettingEntry(
            stableId = "about",
            nameResId = R.string.about,
            value = getString(R.string.about_description),
            onClick = {
                try {
                    findNavController().navigate(R.id.action_settingsFragment_to_aboutFragment)
                } catch (e: Exception) {
                    // Failover
                }
            }
        ))

        // Add a dedicated Save button at the bottom if there are changes
        if (hasChanges) {
            items.add(SettingItem.ActionButton(
                stableId = "bottomSaveButton",
                textResId = if (requiresRestart) R.string.save_and_restart else R.string.save,
                onClick = { saveSettings() }
            ))
        }

        fullSettingsList = items
        renderSettings(scrollState)
    }

    private fun setupTabsAndSearch(view: View) {
        settingsTabGroup = view.findViewById(R.id.settingsTabGroup)
        searchInput = view.findViewById(R.id.settingsSearch)

        settingsTabGroup?.check(if (pendingAdvancedSettings == false) R.id.tabBasic else R.id.tabAdvanced)
        settingsTabGroup?.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            pendingAdvancedSettings = (checkedId == R.id.tabAdvanced)
            checkChanges()
            renderSettings()
        }

        searchInput?.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, count: Int, before: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                searchQuery = s?.toString() ?: ""
                renderSettings()
            }
        })

        // The clear (X) icon also lowers the keyboard and drops focus. Some Chinese head units
        // cannot dismiss the keyboard easily once it is open, so give an explicit way out.
        view.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.settingsSearchLayout)
            ?.setEndIconOnClickListener {
                searchInput?.setText("")
                searchInput?.clearFocus()
                hideKeyboard(view)
            }

        applyRequestedSearchQuery()
    }

    /**
     * Seed the search box when a caller asked for one particular row.
     *
     * Setting the text goes through the watcher above, so the filter and the re-render come for
     * free. The extra is removed once used: it is an instruction for this opening of the screen,
     * and leaving it on the intent would re-apply it on every rotation and recreate, overwriting
     * whatever the user had typed since.
     */
    private fun applyRequestedSearchQuery() {
        val intent = activity?.intent ?: return
        val query = intent.getStringExtra(SettingsActivity.EXTRA_SEARCH_QUERY)
        if (query.isNullOrBlank()) return
        intent.removeExtra(SettingsActivity.EXTRA_SEARCH_QUERY)
        searchInput?.setText(query)
        // Focus would open the keyboard over the very rows we just filtered to, which on a short
        // head unit panel is the whole list.
        searchInput?.clearFocus()
    }

    private fun hideKeyboard(view: View) {
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun onPause() {
        super.onPause()
        view?.let { hideKeyboard(it) }
    }

    private fun renderSettings(scrollState: android.os.Parcelable? = null) {
        // While searching, the tab has no effect (search spans both tiers), so hint that.
        settingsTabGroup?.isEnabled = searchQuery.isBlank()
        settingsAdapter.submitList(filterSettings(fullSettingsList)) {
            scrollState?.let { settingsRecyclerView.layoutManager?.onRestoreInstanceState(it) }
        }
    }

    // Filters the full list by active tab / search query, keeping category headers only when
    // at least one of their children survives (so no empty sections show).
    private fun filterSettings(full: List<SettingItem>): List<SettingItem> {
        val query = searchQuery.trim()
        val result = mutableListOf<SettingItem>()
        var pendingHeader: SettingItem.CategoryHeader? = null
        var currentCategoryId: String? = null
        var headerMatchesQuery = false

        for (item in full) {
            if (item is SettingItem.CategoryHeader) {
                pendingHeader = item
                currentCategoryId = item.stableId
                headerMatchesQuery = query.isNotEmpty() &&
                    getString(item.titleResId).contains(query, ignoreCase = true)
                continue
            }
            if (shouldShowItem(item, currentCategoryId, query, headerMatchesQuery)) {
                pendingHeader?.let { result.add(it); pendingHeader = null }
                result.add(item)
            }
        }
        return result
    }

    private fun shouldShowItem(
        item: SettingItem,
        categoryId: String?,
        query: String,
        headerMatchesQuery: Boolean
    ): Boolean {
        // The bottom Save action is always relevant when present.
        if (item is SettingItem.ActionButton && item.stableId == "bottomSaveButton") return true

        // Connection-type filter: hide settings that do not apply to the chosen connection,
        // in BOTH tabs and in search. USB hides WiFi settings, WiFi hides USB settings,
        // Self Mode hides both, All/unset show everything.
        if (isHiddenByConnection(item, categoryId)) return false

        if (query.isNotEmpty()) {
            return headerMatchesQuery || searchableText(item).contains(query, ignoreCase = true)
        }

        if (pendingAdvancedSettings == true) return true

        return item.stableId in basicSettingIds
    }

    // Items scoped to a USB connection (the Wireless Connection category is the WiFi scope).
    private val usbScopedIds = setOf("useLibusb")

    // Resolutions this wide or more (1440p, 4K) are flagged as high-bandwidth.
    private val HIGH_BANDWIDTH_WIDTH = 2560

    private fun isHiddenByConnection(item: SettingItem, categoryId: String?): Boolean {
        if (categoryId == "wirelessConnection") return !settings.showsWifi()
        if (item.stableId in usbScopedIds) return !settings.showsUsb()
        return false
    }

    // Text used for search matching (title + description/value where applicable).
    // Joins localized labels into a keyword blob for the settings search.
    private fun kw(vararg ids: Int): String = ids.joinToString(" ") { getString(it) }

    private fun searchableText(item: SettingItem): String = when (item) {
        is SettingItem.SettingEntry ->
            "${item.nameOverride ?: getString(item.nameResId)} ${item.value} ${item.searchKeywords ?: ""}"
        is SettingItem.ToggleSettingEntry ->
            "${item.nameOverride ?: getString(item.nameResId)} ${if (item.descriptionResId != null) getString(item.descriptionResId) else ""} ${item.searchKeywords ?: ""}"
        is SettingItem.StreamSettingEntry ->
            "${getString(item.nameResId)} ${item.value} ${if (item.descriptionResId != null) getString(item.descriptionResId) else ""} ${item.searchKeywords ?: ""}"
        is SettingItem.SliderSettingEntry ->
            "${getString(item.nameResId)} ${item.value}"
        is SettingItem.SegmentedButtonSettingEntry ->
            "${getString(item.nameResId)} ${item.options.joinToString(" ")}"
        is SettingItem.InfoBanner -> item.text ?: getString(item.textResId)
        is SettingItem.ActionButton -> getString(item.textResId)
        is SettingItem.CategoryHeader -> getString(item.titleResId)
    }

    /** The chosen connection types as a readable label ("USB, WiFi"); empty shows all. */
    private fun connectionModesLabel(): String {
        val modes = settings.connectionModes
        if (modes.isEmpty()) return getString(R.string.connection_kind_all)
        val parts = mutableListOf<String>()
        if (Settings.ConnectionMode.USB in modes) parts.add(getString(R.string.connection_kind_usb))
        if (Settings.ConnectionMode.WIFI in modes) parts.add(getString(R.string.connection_kind_wifi))
        if (Settings.ConnectionMode.SELF in modes) parts.add(getString(R.string.self_mode))
        return parts.joinToString(", ")
    }

    private fun showResolutionDialog() {
        val (pw, ph) = realPanelResolution()
        val recommended = com.andrerinas.openheadunit.utils.SystemOptimizer.recommendedResolution(pw, ph)
        val labels = Settings.Resolution.allResolutions.map { r ->
            when {
                r.id == recommended.id -> getString(R.string.resolution_recommended_format, r.resName)
                r.width >= HIGH_BANDWIDTH_WIDTH -> getString(R.string.resolution_high_bandwidth_format, r.resName)
                else -> r.resName
            }
        }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.change_resolution)
            .setSingleChoiceItems(labels, pendingResolution ?: 0) { dialog, which ->
                dialog.dismiss()
                val picked = Settings.Resolution.fromId(which)
                val panelKnown = pw > 0 && ph > 0
                when {
                    picked == null || picked == Settings.Resolution.AUTO -> applyResolution(which)
                    // "Higher than the panel" now means the same thing everywhere: it exceeds the
                    // shared panel ceiling (recommended), which is also what the runtime cap and the
                    // DPI use (issue #767).
                    panelKnown && (picked.width > recommended.width || picked.height > recommended.height) ->
                        showResolutionTooHighDialog(which, recommended.id, recommended.resName)
                    picked.width >= HIGH_BANDWIDTH_WIDTH ->
                        showResolutionBandwidthDialog(which, recommended.id)
                    else -> applyResolution(which)
                }
            }
            .show()
    }

    private fun showResolutionTooHighDialog(pickedId: Int, recommendedId: Int, recommendedName: String) {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.resolution_too_high_title)
            .setMessage(getString(R.string.resolution_too_high_message, recommendedName))
            .setPositiveButton(R.string.resolution_use_recommended) { _, _ -> applyResolution(recommendedId) }
            .setNegativeButton(R.string.resolution_use_anyway) { _, _ -> applyResolution(pickedId) }
            .show()
    }

    private fun showResolutionBandwidthDialog(pickedId: Int, recommendedId: Int) {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.resolution_high_bandwidth_title)
            .setMessage(R.string.resolution_high_bandwidth_message)
            .setPositiveButton(R.string.resolution_use_recommended) { _, _ -> applyResolution(recommendedId) }
            .setNegativeButton(R.string.resolution_use_anyway) { _, _ -> applyResolution(pickedId) }
            .show()
    }

    private fun applyResolution(id: Int) {
        pendingResolution = id
        checkChanges()
        updateSettingsList()
    }

    private fun realPanelResolution(): Pair<Int, Int> {
        val m = android.util.DisplayMetrics()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                requireActivity().display?.getRealMetrics(m)
            } else {
                @Suppress("DEPRECATION")
                (requireContext().getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                    .defaultDisplay.getRealMetrics(m)
            }
        } catch (_: Exception) {
        }
        return m.widthPixels to m.heightPixels
    }

    private fun showConnectionModeDialog() {
        val order = listOf(
            Settings.ConnectionMode.USB,
            Settings.ConnectionMode.WIFI,
            Settings.ConnectionMode.SELF
        )
        val labels = arrayOf(
            getString(R.string.connection_kind_usb),
            getString(R.string.connection_kind_wifi),
            getString(R.string.self_mode)
        )
        val current = settings.connectionModes
        val checked = BooleanArray(order.size) { order[it] in current }
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.connection_mode)
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                settings.connectionModes = order.filterIndexed { i, _ -> checked[i] }.toSet()
                dialog.dismiss()
                updateSettingsList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private data class ImportSnapshot(
        val wifiConnectionMode: WifiLauncherMode,
        val helperConnectionStrategy: HelperStrategy,
        val nativeApStrategy: NativeStrategy,
        val bluetoothManagerServiceName: String,
        val wirelessSelected: Boolean,
        val wifiDirectBand: Int,
        val fiveGhzChannel: Int,
        val externalBtZbtTransport: Boolean,
        val nativeAaIgnoreExternalBt: Boolean,
        val autoEnableHotspot: Boolean,
        val insecureAaRfcommListener: Boolean,
        val appLanguage: String,
        val uiScaleSettingsPercent: Int,
        val appTheme: Settings.AppTheme,
        val useExtremeDarkMode: Boolean,
        val useGradientBackground: Boolean,
        val screenOrientation: Settings.ScreenOrientation,
        val hudMirroring: Boolean
    )

    private fun startExportSettings() {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            options.add(getString(R.string.export_settings_choose_location) to { launchExportSettingsPicker() })
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            options.add(getString(R.string.export_settings_downloads) to { exportSettingsDownloadsWithPermission() })
        }
        options.add(getString(R.string.export_settings_app_folder) to { exportSettingsLegacy() })
        options.add(getString(R.string.share_settings_backup) to { shareNewSettingsBackup() })

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.export_settings)
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                options[which].second.invoke()
            }
            .show()
    }

    private fun launchExportSettingsPicker() {
        try {
            exportSettingsLauncher.launch(SettingsBackupManager.defaultFileName())
        } catch (e: ActivityNotFoundException) {
            showNoFilePickerDialog(R.string.export_settings_app_folder) { exportSettingsLegacy() }
        }
    }

    private fun exportSettingsToUri(uri: Uri) {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    SettingsBackupManager.exportToUri(appContext, uri)
                }
                ToastUtils.showToast(requireContext(), R.string.settings_exported, Toast.LENGTH_LONG, force = true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ToastUtils.showToast(requireContext(), getString(R.string.settings_export_failed, e.localizedMessage ?: ""), Toast.LENGTH_LONG, force = true)
            }
        }
    }

    private fun exportSettingsLegacy() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    SettingsBackupManager.exportToLegacyFile(appContext)
                }
                showSettingsExportedDialog(file)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ToastUtils.showToast(requireContext(), getString(R.string.settings_export_failed, e.localizedMessage ?: ""), Toast.LENGTH_LONG, force = true)
            }
        }
    }

    private fun exportSettingsDownloadsWithPermission() {
        runWithDownloadsStoragePermission {
            exportSettingsDownloads()
        }
    }

    private fun runWithDownloadsStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingStorageAction = action
            storagePermissionLauncher.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            action()
        }
    }

    private fun exportSettingsDownloads() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    SettingsBackupManager.exportToDownloadsFile(appContext)
                }
                showSettingsExportedDialog(file)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ToastUtils.showToast(requireContext(), getString(R.string.settings_export_failed, e.localizedMessage ?: ""), Toast.LENGTH_LONG, force = true)
            }
        }
    }

    private fun showSettingsExportedDialog(file: File) {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.settings_exported)
            .setMessage(getString(R.string.settings_backup_saved_to, file.absolutePath))
            .setPositiveButton(R.string.share) { _, _ -> shareSettingsBackup(file) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    private fun shareNewSettingsBackup() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    SettingsBackupManager.exportToLegacyFile(appContext)
                }
                shareSettingsBackup(file)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ToastUtils.showToast(requireContext(), getString(R.string.settings_export_failed, e.localizedMessage ?: ""), Toast.LENGTH_LONG, force = true)
            }
        }
    }

    private fun shareSettingsBackup(file: File) {
        val context = requireContext()
        // getUriForFile is inside the try: a file outside the provider's declared roots throws
        // IllegalArgumentException, and out of a click handler that kills the app. Naming the path
        // is the same answer as having no app to share with, so both land in the one dialog.
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = SettingsBackupManager.MIME_TYPE
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, getString(R.string.share_settings_backup)))
        } catch (e: Exception) {
            if (e !is ActivityNotFoundException && e !is IllegalArgumentException) throw e
            AppLog.w("SettingsFragment: could not share ${file.absolutePath}: ${e.message}")
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.settings_exported)
                .setMessage(getString(R.string.settings_backup_saved_to, file.absolutePath))
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }

    private fun startResetSettings() {
        if (hasChanges) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.reset_settings)
                .setMessage(R.string.reset_settings_discard_pending)
                .setPositiveButton(R.string.discard) { _, _ ->
                    hasChanges = false
                    requiresRestart = false
                    reloadPendingStateFromSettings()
                    updateSaveButtonState()
                    updateSettingsList()
                    showResetSettingsConfirmation()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            showResetSettingsConfirmation()
        }
    }

    private fun showResetSettingsConfirmation() {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.reset_settings)
            .setMessage(R.string.reset_settings_confirm_message)
            .setPositiveButton(R.string.reset) { _, _ -> resetSettingsToDefaults() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun resetSettingsToDefaults() {
        val appContext = requireContext().applicationContext
        val snapshot = createImportSnapshot()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    SettingsBackupManager.resetFromContext(appContext)
                }
                handleResetSettings(snapshot, result)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ToastUtils.showToast(appContext, appContext.getString(R.string.settings_reset_failed, e.localizedMessage ?: ""), Toast.LENGTH_LONG, force = true)
            }
        }
    }

    private fun handleResetSettings(snapshot: ImportSnapshot, result: SettingsBackupManager.ResetResult) {
        val ctx = context ?: return
        settings = App.provide(ctx).settings
        applyWirelessSideEffects(snapshot, ctx)

        // Re-evaluate app theme engine to immediately apply default theme
        AppThemeManager.reapply(ctx, settings)

        // Notify Service about Night Mode changes immediately
        val nightModeUpdateIntent = Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE).apply {
            setPackage(ctx.packageName)
        }
        ctx.sendBroadcast(nightModeUpdateIntent)

        if (SettingsBackupManager.requiresProjectionRestart(result.changedKeys) && App.provide(ctx).commManager.isConnected) {
            ToastUtils.showToast(ctx, ctx.getString(R.string.stopping_service), Toast.LENGTH_SHORT, force = true)
            val stopServiceIntent = Intent(ctx, AapService::class.java).apply {
                action = AapService.ACTION_STOP_SERVICE
            }
            ContextCompat.startForegroundService(ctx, stopServiceIntent)
        }

        hasChanges = false
        requiresRestart = false
        reloadPendingStateFromSettings()
        updateSaveButtonState()
        updateSettingsList()

        ToastUtils.showToast(ctx, R.string.settings_reset, Toast.LENGTH_LONG, force = true)

        if (shouldRecreateAfterImport(snapshot)) {
            activity?.recreate()
        }
    }

    private fun startImportSettings() {
        if (hasChanges) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.import_settings)
                .setMessage(R.string.import_settings_discard_pending)
                .setPositiveButton(R.string.discard) { _, _ ->
                    hasChanges = false
                    requiresRestart = false
                    reloadPendingStateFromSettings()
                    updateSaveButtonState()
                    updateSettingsList()
                    showImportSettingsOptions()
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            showImportSettingsOptions()
        }
    }

    private fun showImportSettingsOptions() {
        val options = mutableListOf<Pair<String, () -> Unit>>()
        options.add(getString(R.string.import_settings_choose_file) to { launchImportSettingsPicker() })
        if (SettingsBackupManager.canAccessDownloadsDirectory()) {
            options.add(getString(R.string.import_settings_downloads) to { showDownloadsBackupFilePickerWithPermission() })
        }
        options.add(getString(R.string.import_settings_app_folder) to { showAppBackupFilePicker() })

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.import_settings)
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                options[which].second.invoke()
            }
            .show()
    }

    private fun launchImportSettingsPicker() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                importSettingsLauncher.launch(SettingsBackupManager.IMPORT_MIME_TYPES.copyOf())
            } else {
                legacyImportSettingsLauncher.launch(SettingsBackupManager.MIME_TYPE)
            }
        } catch (e: ActivityNotFoundException) {
            if (SettingsBackupManager.canAccessDownloadsDirectory()) {
                showNoFilePickerDialog(R.string.import_settings_downloads) {
                    showDownloadsBackupFilePickerWithPermission()
                }
            } else {
                showNoFilePickerDialog(R.string.import_settings_app_folder) { showAppBackupFilePicker() }
            }
        }
    }

    private fun showNoFilePickerDialog(fallbackLabelRes: Int, onFallback: () -> Unit) {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.no_file_picker_title)
            .setMessage(R.string.no_file_picker_message)
            .setPositiveButton(fallbackLabelRes) { _, _ -> onFallback() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showAppBackupFilePicker() {
        val backupFiles = SettingsBackupManager.findBackupFiles(appBackupDirectories())
        if (backupFiles.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.no_settings_backups_title)
                .setMessage(R.string.no_settings_backups_message)
                .setPositiveButton(R.string.export_settings_app_folder) { _, _ -> exportSettingsLegacy() }
                .setNegativeButton(R.string.close, null)
                .show()
            return
        }

        val labels = backupFiles.map { file ->
            "${file.name}\n${file.parent ?: ""}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.import_settings_app_folder)
            .setItems(labels) { _, which ->
                importSettingsFromFile(backupFiles[which])
            }
            .show()
    }

    private fun showDownloadsBackupFilePickerWithPermission() {
        runWithDownloadsStoragePermission {
            showDownloadsBackupFilePicker()
        }
    }

    private fun showDownloadsBackupFilePicker() {
        val backupFiles = SettingsBackupManager.findBackupFiles(listOf(SettingsBackupManager.downloadsDirectory()))
        if (backupFiles.isEmpty()) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.no_settings_backups_title)
                .setMessage(R.string.no_settings_backups_downloads_message)
                .setPositiveButton(R.string.export_settings_downloads) { _, _ -> exportSettingsDownloadsWithPermission() }
                .setNegativeButton(R.string.close, null)
                .show()
            return
        }

        val labels = backupFiles.map { file ->
            "${file.name}\n${file.parent ?: ""}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.import_settings_downloads)
            .setItems(labels) { _, which ->
                importSettingsFromFile(backupFiles[which])
            }
            .show()
    }

    private fun appBackupDirectories(): List<File?> {
        return SettingsBackupManager.backupSearchDirectories(
            requireContext().getExternalFilesDir(null),
            requireContext().cacheDir,
            null
        )
    }

    private fun importSettingsFromUri(uri: Uri) {
        val appContext = requireContext().applicationContext
        val snapshot = createImportSnapshot()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    SettingsBackupManager.importFromUri(appContext, uri)
                }
                handleImportedSettings(snapshot, result)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ToastUtils.showToast(requireContext(), getString(R.string.settings_import_failed, e.localizedMessage ?: ""), Toast.LENGTH_LONG, force = true)
            }
        }
    }

    private fun importSettingsFromFile(file: File) {
        val appContext = requireContext().applicationContext
        val snapshot = createImportSnapshot()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    SettingsBackupManager.importFromFile(appContext, file)
                }
                handleImportedSettings(snapshot, result)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                ToastUtils.showToast(requireContext(), getString(R.string.settings_import_failed, e.localizedMessage ?: ""), Toast.LENGTH_LONG, force = true)
            }
        }
    }

    private fun createImportSnapshot(): ImportSnapshot {
        return ImportSnapshot(
            wifiConnectionMode = settings.wifiConnectionMode,
            helperConnectionStrategy = settings.helperConnectionStrategy,
            nativeApStrategy = settings.nativeApStrategy,
            bluetoothManagerServiceName = settings.bluetoothManagerServiceName,
            wirelessSelected = settings.showsWifi(),
            wifiDirectBand = settings.wifiDirectBand,
            fiveGhzChannel = settings.fiveGhzChannel,
            externalBtZbtTransport = settings.externalBtZbtTransport,
            nativeAaIgnoreExternalBt = settings.nativeAaIgnoreExternalBt,
            autoEnableHotspot = settings.autoEnableHotspot,
            insecureAaRfcommListener = settings.insecureAaRfcommListener,
            appLanguage = settings.appLanguage,
            uiScaleSettingsPercent = settings.uiScaleSettingsPercent,
            appTheme = settings.appTheme,
            useExtremeDarkMode = settings.useExtremeDarkMode,
            useGradientBackground = settings.useGradientBackground,
            screenOrientation = settings.screenOrientation,
            hudMirroring = settings.hudMirroring
        )
    }

    private fun handleImportedSettings(snapshot: ImportSnapshot, result: SettingsBackupManager.ImportResult) {
        val ctx = context ?: return
        settings = App.provide(ctx).settings
        applyWirelessSideEffects(snapshot, ctx)

        // Re-evaluate app theme engine to immediately apply static or dynamic theme
        AppThemeManager.reapply(ctx, settings)

        // Notify Service about Night Mode changes immediately
        val nightModeUpdateIntent = Intent(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE).apply {
            setPackage(ctx.packageName)
        }
        ctx.sendBroadcast(nightModeUpdateIntent)

        if (SettingsBackupManager.requiresProjectionRestart(result.changedKeys) && App.provide(ctx).commManager.isConnected) {
            ToastUtils.showToast(ctx, getString(R.string.stopping_service), Toast.LENGTH_SHORT, force = true)
            val stopServiceIntent = Intent(ctx, AapService::class.java).apply {
                action = AapService.ACTION_STOP_SERVICE
            }
            ContextCompat.startForegroundService(ctx, stopServiceIntent)
        }

        hasChanges = false
        requiresRestart = false
        reloadPendingStateFromSettings()
        updateSaveButtonState()
        updateSettingsList()

        ToastUtils.showToast(
            ctx,
            getString(R.string.settings_imported, result.importedKeys, result.skippedKeys),
            Toast.LENGTH_LONG,
            force = true
        )

        if (shouldRecreateAfterImport(snapshot)) {
            activity?.recreate()
        }
    }

    /** What a launcher reads once at construction, for [WirelessRearmPolicy]. */
    private fun wirelessRearmConfig() = WirelessRearmPolicy.Config(
        wifiConnectionMode = settings.wifiConnectionMode,
        helperConnectionStrategy = settings.helperConnectionStrategy,
        nativeApStrategy = settings.nativeApStrategy,
        bluetoothManagerServiceName = settings.bluetoothManagerServiceName,
        wirelessSelected = settings.showsWifi(),
        wifiDirectBand = settings.wifiDirectBand,
        fiveGhzChannel = settings.fiveGhzChannel,
        externalBtZbtTransport = settings.externalBtZbtTransport,
        nativeAaIgnoreExternalBt = settings.nativeAaIgnoreExternalBt,
        autoEnableHotspot = settings.autoEnableHotspot,
        insecureAaRfcommListener = settings.insecureAaRfcommListener,
    )

    private fun applyWirelessSideEffects(snapshot: ImportSnapshot, context: Context = requireContext()) {
        val before = WirelessRearmPolicy.Config(
            wifiConnectionMode = snapshot.wifiConnectionMode,
            helperConnectionStrategy = snapshot.helperConnectionStrategy,
            nativeApStrategy = snapshot.nativeApStrategy,
            bluetoothManagerServiceName = snapshot.bluetoothManagerServiceName,
            wirelessSelected = snapshot.wirelessSelected,
            wifiDirectBand = snapshot.wifiDirectBand,
            fiveGhzChannel = snapshot.fiveGhzChannel,
            externalBtZbtTransport = snapshot.externalBtZbtTransport,
            nativeAaIgnoreExternalBt = snapshot.nativeAaIgnoreExternalBt,
            autoEnableHotspot = snapshot.autoEnableHotspot,
            insecureAaRfcommListener = snapshot.insecureAaRfcommListener,
        )
        if (WirelessRearmPolicy.requiresRearm(before, wirelessRearmConfig())) {
            val intent = Intent(context, AapService::class.java).apply {
                val mode = settings.wifiConnectionMode
                action = if (mode != WifiLauncherMode.MANUAL && settings.showsWifi())
                    AapService.ACTION_START_WIRELESS else AapService.ACTION_STOP_WIRELESS
            }
            context.startService(intent)
        }
    }

    private fun shouldRecreateAfterImport(snapshot: ImportSnapshot): Boolean {
        return snapshot.appLanguage != settings.appLanguage ||
            snapshot.uiScaleSettingsPercent != settings.uiScaleSettingsPercent ||
            snapshot.appTheme != settings.appTheme ||
            snapshot.useExtremeDarkMode != settings.useExtremeDarkMode ||
            snapshot.useGradientBackground != settings.useGradientBackground ||
            snapshot.screenOrientation != settings.screenOrientation ||
            snapshot.hudMirroring != settings.hudMirroring
    }

    private fun showAudioOffsetsDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_audio_offsets, null)

        val seekMedia = dialogView.findViewById<android.widget.SeekBar>(R.id.seek_media)
        val seekGuidance = dialogView.findViewById<android.widget.SeekBar>(R.id.seek_guidance)
        val seekSystem = dialogView.findViewById<android.widget.SeekBar>(R.id.seek_system)

        val textMedia = dialogView.findViewById<android.widget.TextView>(R.id.text_media_val)
        val textGuidance = dialogView.findViewById<android.widget.TextView>(R.id.text_guidance_val)
        val textSystem = dialogView.findViewById<android.widget.TextView>(R.id.text_system_val)

        // Mapping: 0 to 100 on SeekBar -> 0% to 200% Gain. Default is 50 (100% Gain, 0 Offset)
        // Offset = (seekValue - 50) * 2
        // seekValue = (offset / 2) + 50

        seekMedia.progress = ((pendingMediaVolumeOffset ?: 0) / 2) + 50
        seekGuidance.progress = ((pendingGuidanceVolumeOffset ?: 0) / 2) + 50
        seekSystem.progress = ((pendingSystemVolumeOffset ?: 0) / 2) + 50

        val updateLabels = {
            textMedia.text = "${(seekMedia.progress * 2)}%"
            textGuidance.text = "${(seekGuidance.progress * 2)}%"
            textSystem.text = "${(seekSystem.progress * 2)}%"
        }
        updateLabels()

        val listener = object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                updateLabels()
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        }

        seekMedia.setOnSeekBarChangeListener(listener)
        seekGuidance.setOnSeekBarChangeListener(listener)
        seekSystem.setOnSeekBarChangeListener(listener)

        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.audio_volume_offset)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                pendingMediaVolumeOffset = (seekMedia.progress - 50) * 2
                pendingGuidanceVolumeOffset = (seekGuidance.progress - 50) * 2
                pendingSystemVolumeOffset = (seekSystem.progress - 50) * 2
                checkChanges()
                updateSettingsList()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showPermissionDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.hotspot_permission_title)
            .setMessage(R.string.hotspot_permission_message)
            .setPositiveButton(R.string.open_settings) { dialog, _ ->
                try {
                    val intent = Intent(SystemSettings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:${requireContext().packageName}")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(SystemSettings.ACTION_MANAGE_WRITE_SETTINGS))
                    } catch (e2: Exception) {
                        try {
                            startActivity(Intent(SystemSettings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${requireContext().packageName}")
                            })
                        } catch (_: Exception) {}
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                pendingAutoEnableHotspot = false
                checkChanges()
                updateSettingsList()
            }
            .show()
    }

    private fun showExperimentalWarning() {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.hotspot_warning_title)
            .setMessage(R.string.hotspot_warning_message)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                pendingAutoEnableHotspot = true
                checkChanges()
                updateSettingsList()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingAutoEnableHotspot = false
                checkChanges()
                updateSettingsList()
            }
            .show()
    }

    private fun showCustomInsetsDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_custom_insets, null)

        val inputLeft = dialogView.findViewById<EditText>(R.id.input_left)
        val inputTop = dialogView.findViewById<EditText>(R.id.input_top)
        val inputRight = dialogView.findViewById<EditText>(R.id.input_right)
        val inputBottom = dialogView.findViewById<EditText>(R.id.input_bottom)

        // Set initial values from pending state
        inputLeft.setText((pendingInsetLeft ?: 0).toString())
        inputTop.setText((pendingInsetTop ?: 0).toString())
        inputRight.setText((pendingInsetRight ?: 0).toString())
        inputBottom.setText((pendingInsetBottom ?: 0).toString())

        // Helper to update pending values and UI preview
        fun updatePreview() {
            val l = inputLeft.text.toString().toIntOrNull() ?: 0
            val t = inputTop.text.toString().toIntOrNull() ?: 0
            val r = inputRight.text.toString().toIntOrNull() ?: 0
            val b = inputBottom.text.toString().toIntOrNull() ?: 0

            pendingInsetLeft = l
            pendingInsetTop = t
            pendingInsetRight = r
            pendingInsetBottom = b

            // Live Preview: Set padding on the root view of the Activity
            val root = requireActivity().findViewById<View>(R.id.settings_nav_host)
            root?.setPadding(l, t, r, b)
        }

        // Helper to bind buttons
        fun bindButton(btnId: Int, input: EditText, delta: Int) {
            dialogView.findViewById<View>(btnId).setOnClickListener {
                val current = input.text.toString().toIntOrNull() ?: 0
                val newVal = (current + delta).coerceAtLeast(0)
                input.setText(newVal.toString())
                updatePreview()
            }
        }

        bindButton(R.id.btn_left_minus, inputLeft, -10)
        bindButton(R.id.btn_left_plus, inputLeft, 10)
        bindButton(R.id.btn_top_minus, inputTop, -10)
        bindButton(R.id.btn_top_plus, inputTop, 10)
        bindButton(R.id.btn_right_minus, inputRight, -10)
        bindButton(R.id.btn_right_plus, inputRight, 10)
        bindButton(R.id.btn_bottom_minus, inputBottom, -10)
        bindButton(R.id.btn_bottom_plus, inputBottom, 10)

        // Text Watchers? Maybe overkill, buttons are safer.
        // Let's add simple focus change listener to update preview on manual entry
        val focusListener = View.OnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) updatePreview()
        }
        inputLeft.onFocusChangeListener = focusListener
        inputTop.onFocusChangeListener = focusListener
        inputRight.onFocusChangeListener = focusListener
        inputBottom.onFocusChangeListener = focusListener

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.custom_insets)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val l = inputLeft.text.toString().toIntOrNull() ?: 0
                val t = inputTop.text.toString().toIntOrNull() ?: 0
                val r = inputRight.text.toString().toIntOrNull() ?: 0
                val b = inputBottom.text.toString().toIntOrNull() ?: 0

                // PERSIST IMMEDIATELY (Rescue Mode)
                settings.insetLeft = l
                settings.insetTop = t
                settings.insetRight = r
                settings.insetBottom = b
                settings.commit()

                // Update pending to keep UI in sync
                pendingInsetLeft = l
                pendingInsetTop = t
                pendingInsetRight = r
                pendingInsetBottom = b

                checkChanges()
                updateSettingsList()
                dialog.dismiss()

                // Refresh activity to apply padding immediately
                requireActivity().recreate()
            }
            .setNegativeButton(android.R.string.cancel) { dialog, _ ->
                // Revert Preview immediately
                val root = requireActivity().findViewById<View>(R.id.settings_nav_host)
                root?.setPadding(
                    settings.insetLeft, settings.insetTop,
                    settings.insetRight, settings.insetBottom
                )
                // Reset pending to old values
                pendingInsetLeft = settings.insetLeft
                pendingInsetTop = settings.insetTop
                pendingInsetRight = settings.insetRight
                pendingInsetBottom = settings.insetBottom

                dialog.dismiss()
            }
            .setOnDismissListener {
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(inputLeft.windowToken, 0)
            }
            .create()

        dialog.window?.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        dialog.show()
    }



    override fun onResume() {
        super.onResume()
        // Refresh settings list when returning from sub-screens (e.g. AutoConnectFragment, DarkModeFragment)
        if (::settingsAdapter.isInitialized) {
            settings = App.provide(requireContext()).settings
            updateSettingsList()
        }

        // A probe started before the vendor's projection app took the screen is still running. Pick
        // its row back up, so coming back here shows where it got to rather than a stale line.
        if (zbtProbeJob?.isActive == true) followZbtProbe()
    }

    private fun getKillOnDisconnectConflicts(): List<String> {
        val conflicts = mutableListOf<String>()
        // Only reconnection-related settings conflict with close-on-disconnect.
        // Initial connection settings (auto-connect last session, single USB,
        // self mode, auto-start on USB) should keep working when the car starts.
        if (settings.reopenOnReconnection) {
            conflicts.add(getString(R.string.reopen_on_reconnection_label))
        }
        return conflicts
    }

    private fun showKillOnDisconnectWarning(conflicts: List<String>, hasAutoStartOnBoot: Boolean, hasAutoStartOnScreenOn: Boolean = false) {
        val message = buildString {
            if (conflicts.isNotEmpty()) {
                val conflictList = conflicts.joinToString("\n") { "• $it" }
                append(getString(R.string.kill_on_disconnect_warning, conflictList))
            }
            if (hasAutoStartOnBoot) {
                if (conflicts.isNotEmpty()) append("\n\n")
                append(getString(R.string.kill_on_disconnect_boot_warning))
            }
            if (hasAutoStartOnScreenOn) {
                if (conflicts.isNotEmpty() || hasAutoStartOnBoot) append("\n\n")
                append(getString(R.string.kill_on_disconnect_screen_on_warning))
            }
        }

        var confirmed = false

        val hasDisableableConflicts = conflicts.isNotEmpty()
        val positiveTextRes = if (hasDisableableConflicts) {
            R.string.kill_on_disconnect_disable_and_enable
        } else {
            R.string.kill_on_disconnect_enable_anyway
        }

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.kill_on_disconnect_warning_title)
            .setMessage(message)
            .setPositiveButton(positiveTextRes) { _, _ ->
                confirmed = true
                if (hasDisableableConflicts) {
                    disableKillOnDisconnectConflicts()
                    ToastUtils.showToast(context, getString(R.string.kill_on_disconnect_conflicts_disabled), Toast.LENGTH_LONG, force = true)
                }
                pendingKillOnDisconnect = true
                checkChanges()
                updateSettingsList()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()

        // Disable the positive button and show a countdown
        val positiveButton = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
        positiveButton.isEnabled = false
        positiveButton.alpha = 0.4f
        val baseText = getString(positiveTextRes)
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var remaining = 4

        val countdownRunnable = object : Runnable {
            override fun run() {
                if (remaining > 0) {
                    positiveButton.text = "$baseText (${remaining}s)"
                    remaining--
                    handler.postDelayed(this, 1000)
                } else {
                    positiveButton.text = baseText
                    positiveButton.isEnabled = true
                    positiveButton.alpha = 1.0f
                }
            }
        }
        handler.post(countdownRunnable)

        dialog.setOnDismissListener {
            handler.removeCallbacks(countdownRunnable)
            if (!confirmed) {
                pendingKillOnDisconnect = false
                checkChanges()
                updateSettingsList()
            }
        }
    }

    private fun disableKillOnDisconnectConflicts() {
        // Only disable reconnection-related settings.
        // Initial connection settings are kept so they work when the car starts.
        settings.reopenOnReconnection = false
    }

    private fun showHotspotPermissionDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.hotspot_permission_title)
            .setMessage(R.string.hotspot_permission_message)
            .setPositiveButton(R.string.open_settings) { dialog, _ ->
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                        data = Uri.parse("package:${requireContext().packageName}")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS))
                    } catch (e2: Exception) {
                        try {
                            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.parse("package:${requireContext().packageName}")
                            })
                        } catch (_: Exception) {}
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel) { _, _ ->
                pendingAutoEnableHotspot = false
                checkChanges()
                updateSettingsList()
            }
            .show()
    }

    /** The transport the Native AA block is currently showing settings for. */
    private fun pendingNativeTransport(): NativeTransport =
        pendingNativeApTransport ?: NativeStrategy.DEFAULT

    /** The WiFi Direct band the block is currently showing settings for. */
    private fun pendingP2pBandPreference(): P2pBandPreference =
        P2pBandPreference.fromSetting(pendingWifiDirectBand ?: 0)

    /** The hotspot band the block is currently showing settings for. */
    private fun pendingHotspotBandPreference(): HotspotBandPreference =
        HotspotBandPreference.fromSetting(pendingHotspotBand ?: 0)

    /**
     * Which 5 GHz channel to ask for, on whichever transport this block is showing.
     *
     * One setting shown in three places rather than one per transport: which channels a phone will
     * join is decided by its own regulatory domain, so the answer follows the user's phone and
     * country and not the transport, and somebody who switches transport to work around the problem
     * must not silently lose the choice.
     *
     * A dialog rather than the segmented buttons the two band settings use, because there are five
     * channels and that layout holds three.
     */
    private fun addFiveGhzChannelSetting(items: MutableList<SettingItem>) {
        val current = FiveGhzChannelPolicy.pinnedChannel(pendingFiveGhzChannel ?: 0)
        val values = listOf(FiveGhzChannelPolicy.AUTOMATIC) + FiveGhzChannelPolicy.CHANNELS
        val labels = values.map { channel ->
            if (channel == FiveGhzChannelPolicy.AUTOMATIC) getString(R.string.five_ghz_channel_auto)
            else getString(
                R.string.five_ghz_channel_option,
                channel,
                FiveGhzChannelPolicy.frequencyMhz(channel),
            )
        }.toTypedArray()
        items.add(SettingItem.SettingEntry(
            stableId = "fiveGhzChannel",
            nameResId = R.string.five_ghz_channel,
            value = labels[values.indexOf(current)],
            searchKeywords = "channel 36 40 44 48 149 unii 5ghz region country band",
            onClick = { _ ->
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.five_ghz_channel)
                    .setSingleChoiceItems(labels, values.indexOf(current)) { dialog, which ->
                        pendingFiveGhzChannel = values[which]
                        checkChanges()
                        dialog.dismiss()
                        updateSettingsList()
                    }
                    .show()
            }
        ))
        items.add(SettingItem.InfoBanner(
            stableId = "fiveGhzChannelHint",
            textResId = R.string.five_ghz_channel_hint
        ))
    }

    /**
     * Whether this unit leaves its own WiFi network for the bring-up. Only where the platform would
     * honour it: below Android 10 anything may ask, from 10 to 14 the overlay permission gets past
     * the framework's check and the hint says so, and from 15 there is no route, so no row.
     */
    private fun addStationStandDownSetting(items: MutableList<SettingItem>) {
        if (!StationStandDownPolicy.isAvailable(Build.VERSION.SDK_INT, true)) return
        items.add(SettingItem.SegmentedButtonSettingEntry(
            stableId = "stationStandDownMode",
            nameResId = R.string.stand_down_station,
            options = listOf(
                getString(R.string.stand_down_station_auto),
                getString(R.string.stand_down_station_always),
                getString(R.string.stand_down_station_never)
            ),
            selectedIndex = (pendingStationStandDownMode ?: 0).coerceIn(0, 2),
            onOptionSelected = { index ->
                pendingStationStandDownMode = index
                checkChanges()
                updateSettingsList()
            }
        ))
        val overlayGranted = AppPermissions.isOverlayGranted(requireContext())
        val hint = getString(R.string.stand_down_station_hint)
        items.add(SettingItem.InfoBanner(
            stableId = "stationStandDownHint",
            textResId = R.string.stand_down_station_hint,
            text = if (StationStandDownPolicy.isAvailable(Build.VERSION.SDK_INT, overlayGranted)) hint
                else getString(R.string.stand_down_station_needs_overlay) + " " + hint
        ))
    }

    /**
     * Whether the group keeps its name and passphrase between bring-ups, and what can change them.
     *
     * Only where the group is ours to name: the hotspot's identity is the access point's own. From
     * API 29 one row owns the pair, typed or drawn. Below it nothing can name a group, so the rotate
     * row is the only lever and it works by purging the platform's stored profile.
     */
    private fun addWifiDirectIdentitySettings(items: MutableList<SettingItem>) {
        // Below API 29 the app cannot name the group at all: the platform picks the name and keeps
        // its own profile, so the toggle and the rows describe that arrangement instead of this one.
        val appNamesGroup = Build.VERSION.SDK_INT >= P2pIdentityRotationPolicy.NAMED_CREATE_SDK
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "wifiDirectStableIdentity",
            nameResId = R.string.wifi_direct_stable_identity,
            descriptionResId = if (appNamesGroup) R.string.wifi_direct_stable_identity_description
                else R.string.wifi_direct_stable_identity_description_legacy,
            isChecked = pendingWifiDirectStableIdentity ?: settings.wifiDirectStableIdentity,
            searchKeywords = "persistent group ssid passphrase password same network reconnect faster",
            onCheckedChanged = { isChecked ->
                pendingWifiDirectStableIdentity = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))
        // Above the early return below: what the last group actually was is worth reading whether or
        // not its identity is kept, and below API 29 it is the only place the pair is ever legible.
        addWifiDirectLastNetworkRow(items)
        if (pendingWifiDirectStableIdentity == false) return
        if (appNamesGroup) {
            // One row owns the pair here, and its "pick new ones" button is what the separate
            // rotate row below does, so that row would be a second way to do the same thing.
            addWifiDirectIdentityEditRow(items)
            return
        }
        items.add(SettingItem.InfoBanner(
            stableId = "wifiDirectIdentityLegacy",
            textResId = R.string.wifi_direct_group_identity_legacy
        ))
        // Below API 29 this is the only rename lever there is: nothing can name a group, so the
        // platform's stored profile has to be purged for it to pick a different one.
        items.add(SettingItem.SettingEntry(
            stableId = "wifiDirectNewIdentity",
            nameResId = R.string.wifi_direct_new_identity,
            // The read-back record, not the stability yardstick: that one is only written on an
            // assessed create, so it can name a group older than the one on the air.
            value = settings.wifiDirectLastReadBack?.networkName
                ?: getString(R.string.wifi_direct_new_identity_none),
            searchKeywords = "forget reset ssid passphrase password group name",
            onClick = { _ ->
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.wifi_direct_new_identity)
                    .setMessage(R.string.wifi_direct_new_identity_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        settings.wifiDirectRotationPending = true
                        rotateWifiDirectIdentityNow()
                        updateSettingsList()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        ))
    }

    /**
     * Fires the rotate, which the service applies now or defers, and says which it did.
     *
     * The saved mode, not the pending one: the running launcher is what the service asks. A
     * handshake is invisible from here, so the toast can only be hopeful.
     */
    private fun rotateWifiDirectIdentityNow() {
        requireContext().startService(
            Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_ROTATE_WIFI_DIRECT_IDENTITY
            }
        )
        val appliesNow = P2pIdentityRotationPolicy.applyNow(
            sessionLive = App.provide(requireContext()).commManager.isConnected,
            handshakeInFlight = false,
            nativeWifiDirectActive = settings.wifiConnectionMode == WifiLauncherMode.NATIVE &&
                settings.nativeApStrategy == NativeStrategy.WIFI_DIRECT,
        )
        ToastUtils.showToast(
            requireContext(),
            if (appliesNow) R.string.wifi_direct_new_identity_applied
            else R.string.wifi_direct_new_identity_done,
            Toast.LENGTH_LONG,
            force = true
        )
    }

    /**
     * The name, password and address the last group actually came up with, whatever named it.
     *
     * Read from a persisted record rather than the live group, because this screen stops the
     * wireless stack before it draws. The row never shows the password; the dialog does.
     */
    private fun addWifiDirectLastNetworkRow(items: MutableList<SettingItem>) {
        val onAir = settings.wifiDirectLastReadBack
        items.add(SettingItem.SettingEntry(
            stableId = "wifiDirectLastNetwork",
            nameResId = R.string.wifi_direct_last_network,
            value = onAir?.networkName ?: getString(R.string.wifi_direct_last_network_none),
            searchKeywords = "wifi direct ssid passphrase password bssid mac address show see what is my network",
            onClick = { _ -> showWifiDirectLastNetworkDialog(onAir) }
        ))
    }

    private fun showWifiDirectLastNetworkDialog(onAir: ObservedP2pCredentials?) {
        val body = StringBuilder()
        if (onAir == null) {
            body.append(getString(R.string.wifi_direct_last_network_none))
        } else {
            body.append(
                getString(
                    R.string.wifi_direct_last_network_details,
                    onAir.networkName,
                    onAir.passphrase,
                    onAir.bssid.ifEmpty { getString(R.string.wifi_direct_last_network_address_unknown) },
                )
            )
            // The one failure this dialog is most often opened for, named in the user's terms.
            if (!SoftApBssidPolicy.isUsable(onAir.bssid)) {
                body.append("\n\n").append(getString(R.string.wifi_direct_last_network_no_bssid))
            }
            val asked = settings.wifiDirectGroupIdentity
            if (Build.VERSION.SDK_INT >= P2pIdentityRotationPolicy.NAMED_CREATE_SDK && asked != null &&
                (asked.networkName != onAir.networkName || asked.passphrase != onAir.passphrase)
            ) {
                body.append("\n\n").append(getString(R.string.wifi_direct_last_network_differs))
            }
        }
        body.append("\n\n").append(getString(R.string.wifi_direct_last_network_hint))
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.wifi_direct_last_network_title)
            .setMessage(body.toString())
            .setPositiveButton(android.R.string.ok, null)
        if (onAir != null) {
            builder.setNeutralButton(R.string.copy) { _, _ ->
                val clipboard = requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText(onAir.networkName, onAir.passphrase))
                ToastUtils.showToast(
                    requireContext(), R.string.wifi_direct_credentials_copied,
                    Toast.LENGTH_SHORT, force = true
                )
            }
        }
        builder.show()
    }

    /** The typed pair. Both fields in one dialog, because a password moves the name with it. */
    private fun addWifiDirectIdentityEditRow(items: MutableList<SettingItem>) {
        val userSet = pendingWifiDirectIdentityUserSet ?: settings.wifiDirectIdentityUserSet
        val pair = pendingWifiDirectGroupIdentity
        items.add(SettingItem.SettingEntry(
            stableId = "wifiDirectGroupIdentity",
            nameResId = R.string.wifi_direct_group_identity,
            value = if (userSet && pair != null)
                pair.networkName + "  ·  " + "•".repeat(pair.passphrase.length)
            else getString(R.string.wifi_direct_group_identity_auto),
            searchKeywords = "wifi direct ssid network name passphrase password set change custom own known",
            onClick = { _ ->
                DialogUtils.showTwoFieldDialog(
                    requireContext(),
                    R.string.wifi_direct_group_identity,
                    R.string.wifi_direct_group_identity_message,
                    R.string.wifi_direct_group_identity_name_hint,
                    if (userSet) pair?.networkName else null,
                    R.string.wifi_direct_group_identity_passphrase_hint,
                    if (userSet) pair?.passphrase else null,
                    neutralResId = R.string.wifi_direct_group_identity_pick_new,
                    onNeutral = { clearWifiDirectIdentity() },
                ) { typedName, typedPassphrase ->
                    applyWifiDirectIdentityEdit(typedName, typedPassphrase)
                }
            }
        ))
    }

    /**
     * Hands the pair back to the app, which draws a fresh one at the next create. Unlike an empty
     * OK this always applies: asking for new values is a request even when none were typed.
     */
    private fun clearWifiDirectIdentity() {
        pendingWifiDirectGroupIdentity = null
        pendingWifiDirectIdentityUserSet = false
        checkChanges()
        updateSettingsList()
    }

    private fun applyWifiDirectIdentityEdit(typedName: String, typedPassphrase: String) {
        when (val outcome = P2pIdentityEditPolicy.edit(
            current = pendingWifiDirectGroupIdentity,
            typedName = typedName,
            typedPassphrase = typedPassphrase,
            deviceName = AapService.wifiDirectName.value,
        )) {
            is P2pIdentityEdit.Rejected -> {
                // Rejected at the dialog, like the static BSSID row: a pair the platform refuses
                // fails much later, at createGroup, where nothing points back at what was typed.
                ToastUtils.showToast(
                    requireContext(),
                    when (outcome.why) {
                        P2pIdentityRejection.NAME_SHAPE ->
                            R.string.wifi_direct_group_identity_invalid_name
                        P2pIdentityRejection.PASSPHRASE_LENGTH ->
                            R.string.wifi_direct_group_identity_invalid_passphrase_length
                        P2pIdentityRejection.PASSPHRASE_CHARSET ->
                            R.string.wifi_direct_group_identity_invalid_passphrase_charset
                    },
                    Toast.LENGTH_LONG, force = true
                )
                return
            }
            P2pIdentityEdit.Unchanged -> return
            P2pIdentityEdit.Cleared -> {
                // OK on an untouched dialog must not read as "discard the pair": clearing is only a
                // change where there was typing to clear, and otherwise renames the group for nothing.
                // The neutral button is the deliberate ask and goes through clearWifiDirectIdentity.
                if (pendingWifiDirectIdentityUserSet != true) return
                pendingWifiDirectGroupIdentity = null
                pendingWifiDirectIdentityUserSet = false
            }
            is P2pIdentityEdit.Accepted -> {
                pendingWifiDirectGroupIdentity = outcome.identity
                pendingWifiDirectIdentityUserSet = true
                if (outcome.recoded) {
                    ToastUtils.showToast(
                        requireContext(),
                        getString(
                            R.string.wifi_direct_group_identity_recoded,
                            outcome.identity.networkName
                        ),
                        Toast.LENGTH_LONG, force = true
                    )
                }
            }
        }
        checkChanges()
        updateSettingsList()
    }

    /**
     * The band to ask for when this app creates the WiFi Direct group, plus what that choice costs.
     *
     * The mirror of [addHotspotBandSetting] on the other transport, and it replaces two toggles
     * that asked the same question in pieces - one that forced 2.4 GHz and one that opted a pre-Q
     * unit into asking for 5 GHz. Rendered only on the Native AA WiFi Direct arm, because
     * WifiDirectManager.createQuietGroup() is the single place it is read.
     *
     * Basic rather than Advanced, like the hotspot band beside it: this is the first thing to try
     * when a wireless session connects and shows no picture.
     */
    private fun addWifiDirectBandSetting(items: MutableList<SettingItem>) {
        items.add(SettingItem.SegmentedButtonSettingEntry(
            stableId = "wifiDirectBand",
            nameResId = R.string.wifi_direct_band,
            options = listOf(
                getString(R.string.wifi_direct_band_auto),
                getString(R.string.wifi_direct_band_5ghz),
                getString(R.string.wifi_direct_band_24ghz)
            ),
            selectedIndex = (pendingWifiDirectBand ?: 0).coerceIn(0, 2),
            onOptionSelected = { index ->
                pendingWifiDirectBand = index
                checkChanges()
                // The upper-band toggle below appears and disappears with this choice, so the list
                // is rebuilt rather than only the row redrawn.
                updateSettingsList()
            }
        ))
        items.add(SettingItem.InfoBanner(
            stableId = "wifiDirectBandHint",
            textResId = R.string.wifi_direct_band_hint
        ))
    }

    /**
     * The band to ask for when this app brings the hotspot up, plus what that choice costs.
     *
     * Rendered wherever the hotspot is switched on by us: the Native AA hotspot transport and
     * wireless mode 2 strategy 4. Deliberately not offered where nothing calls
     * HotspotManager.setHotspotEnabled(), since a control that changes nothing is worse than none.
     */
    private fun addHotspotBandSetting(items: MutableList<SettingItem>) {
        items.add(SettingItem.SegmentedButtonSettingEntry(
            stableId = "hotspotBand",
            nameResId = R.string.hotspot_band,
            options = listOf(
                getString(R.string.hotspot_band_auto),
                getString(R.string.hotspot_band_5ghz),
                getString(R.string.hotspot_band_24ghz)
            ),
            selectedIndex = (pendingHotspotBand ?: 0).coerceIn(0, 2),
            onOptionSelected = { index ->
                pendingHotspotBand = index
                checkChanges()
                updateSettingsList()
                // No credentials preflight here, unlike the transport button above: the band
                // changes what we ask the radio for, not what this unit can tell a phone.
            }
        ))
        items.add(SettingItem.InfoBanner(
            stableId = "hotspotBandHint",
            textResId = R.string.hotspot_band_hint
        ))
    }

    private fun addHotspotToggle(items: MutableList<SettingItem>) {
        items.add(SettingItem.ToggleSettingEntry(
            stableId = "autoEnableHotspot",
            nameResId = R.string.auto_enable_hotspot,
            descriptionResId = R.string.auto_enable_hotspot_description,
            isChecked = pendingAutoEnableHotspot ?: false,
            onCheckedChanged = { isChecked ->
                if (isChecked) {
                    if (!AppPermissions.isWriteSettingsGranted(requireContext())) {
                        showPermissionDialog()
                    } else {
                        showExperimentalWarning()
                    }
                } else {
                    pendingAutoEnableHotspot = false
                    checkChanges()
                    updateSettingsList()
                }
            }
        ))
    }


    private fun getAutoConnectSummary(): String {
        val order = settings.autoConnectPriorityOrder
        val enabledNames = order.mapNotNull { id ->
            val isEnabled = when (id) {
                Settings.AUTO_CONNECT_LAST_SESSION -> settings.autoConnectLastSession
                Settings.AUTO_CONNECT_SELF_MODE -> settings.autoStartSelfMode
                Settings.AUTO_CONNECT_SINGLE_USB -> settings.autoConnectSingleUsbDevice
                else -> false
            }
            if (isEnabled) {
                when (id) {
                    Settings.AUTO_CONNECT_LAST_SESSION -> getString(R.string.auto_connect_last_session)
                    Settings.AUTO_CONNECT_SELF_MODE -> getString(R.string.auto_start_self_mode)
                    Settings.AUTO_CONNECT_SINGLE_USB -> getString(R.string.auto_connect_single_usb)
                    else -> null
                }
            } else null
        }
        val delay = settings.autoConnectDelaySeconds
        val baseSummary = if (enabledNames.isEmpty()) {
            getString(R.string.auto_connect_all_disabled)
        } else {
            enabledNames.joinToString(" → ")
        }
        return if (enabledNames.isNotEmpty() && delay > 0) {
            getString(R.string.auto_connect_delay_summary_format, baseSummary, delay)
        } else {
            baseSummary
        }
    }

    private fun showNumericInputDialog(
        title: String,
        message: String?,
        initialValue: Int,
        onConfirm: (Int) -> Unit
    ) {
        val context = requireContext()
        val editView = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (initialValue == 0 && title.contains("DPI", true)) "" else initialValue.toString())
        }

        // Use a container to add padding around the EditText
        val container = android.widget.FrameLayout(context)
        val params = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val margin = (24 * context.resources.displayMetrics.density).toInt()
        params.setMargins(margin, 8, margin, 8)
        container.addView(editView, params)

        val dialog = MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(title)
            .apply { if (message != null) setMessage(message) }
            .setView(container)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                val newVal = (editView.text.toString().toIntOrNull() ?: 0).coerceAtLeast(0)
                onConfirm(newVal)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(editView.windowToken, 0)
            }
            .create()

        dialog.window?.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        dialog.show()
        editView.requestFocus()
    }

    private fun showTextInputDialog(
        context: Context,
        titleResId: Int,
        initialValue: String,
        onConfirm: (String) -> Unit
    ) {
        val editView = EditText(context).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(initialValue)
        }

        val container = android.widget.FrameLayout(context)
        val params = android.widget.FrameLayout.LayoutParams(
            android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
            android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        )
        val margin = (24 * context.resources.displayMetrics.density).toInt()
        params.setMargins(margin, 8, margin, 8)
        container.addView(editView, params)

        val dialog = MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(titleResId)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                onConfirm(editView.text.toString())
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(editView.windowToken, 0)
            }
            .create()

        dialog.window?.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        dialog.show()
        editView.requestFocus()
    }

    private fun handleNativeAaSelection() {
        // An external Bluetooth module is not a "might not work" — the phone is bonded to a chip
        // this app cannot write to, so say so plainly and name the evidence instead of offering
        // the generic "try it anyway".
        val externalBtEvidence = BluetoothHelper.externalBtEvidence
        if (externalBtEvidence != null) {
            // Unless there is a route to that chip, in which case the flat refusal below would be
            // wrong: either the user turned the transport on, or the daemon answered when asked.
            // The dial is a socket connect, so it never happens on this thread.
            val chosen = pendingExternalBtZbtTransport ?: settings.externalBtZbtTransport
            externalBtRouteJob?.cancel()
            externalBtRouteJob = viewLifecycleOwner.lifecycleScope.launch {
                val viaModule = if (chosen) true else {
                    ZbtDaemonReachability.cached()
                        ?: withContext(Dispatchers.IO) { ZbtDaemonReachability.resolve() }
                }
                if (!isAdded) return@launch
                showExternalBtNativeAaDialog(externalBtEvidence, viaModule)
            }
            return
        }
        if (NativeAaHandshakeManager.checkCompatibility(requireContext())) {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.supported_nativeaa)
                .setMessage(R.string.supported_nativeaa_desc)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    acceptNativeAaMode()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } else {
            MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                .setTitle(R.string.not_supported_nativeaa)
                .setMessage(R.string.not_supported_nativeaa_desc)
                .setPositiveButton(android.R.string.ok) { dialog, _ ->
                    acceptNativeAaMode()
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** The external-module dialog, once the route it should describe is known. */
    private fun showExternalBtNativeAaDialog(externalBtEvidence: String, viaModule: Boolean) {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(if (viaModule) R.string.external_bt_module_nativeaa else R.string.external_bt_nativeaa)
            .setMessage(
                if (viaModule) getString(R.string.external_bt_module_nativeaa_desc, externalBtEvidence)
                else getString(R.string.external_bt_nativeaa_desc, externalBtEvidence)
            )
            // Selecting the mode is still allowed either way. With a route to the module it is a
            // real choice; without one the mode is stored but the route stays switched off at
            // connection time, so turning the transport on later needs no second visit here.
            .setPositiveButton(android.R.string.ok) { dialog, _ ->
                acceptNativeAaMode()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Take the mode, then check whether this unit can actually run it.
     *
     * All three branches of [handleNativeAaSelection] end here. Selecting the mode is never blocked,
     * which is the behaviour those branches already had: they warn and let the user through, because
     * the app's own compatibility read is a prediction and the user's hardware is not.
     */
    private fun acceptNativeAaMode() {
        pendingWifiConnectionMode = WifiLauncherMode.NATIVE
        checkChanges()
        updateSettingsList()
        runCredentialsPreflight()
    }

    /**
     * Ask what this unit can tell a phone about its own network, and prompt for whatever it cannot.
     *
     * The reason this exists at the moment of *selection* rather than at connect time: every one of
     * these verdicts was already produced, correctly, during the handshake, and reported as a log
     * line and a toast over the projection screen. Users read neither, so the route gets reported as
     * broken while the two fields that would fix it sit unset a few rows below. Here the user is
     * already in Settings with a keyboard.
     *
     * Silent unless something is certain. See [NativeCredentialsPreflightPolicy].
     */
    private fun runCredentialsPreflight() {
        // The dialogs that reach here are not lifecycle-aware and are not dismissed with the view,
        // so this can be called after the view is gone — where viewLifecycleOwner throws rather
        // than returning null, before any isAdded check inside could help.
        val owner = view?.let { viewLifecycleOwner } ?: return
        val transport = pendingNativeTransport()
        // One probe at a time. The transport control fires this on every change, and each run costs
        // up to ~1.5 s waiting on requestDeviceInfo plus an `ip link` subprocess — so toggling back
        // and forth would otherwise stack coroutines and show a dialog on top of a dialog.
        preflightJob?.cancel()
        preflightJob = owner.lifecycleScope.launch {
            val report = try {
                val probe = NativeCredentialsPreflight.probe(
                    context = requireContext().applicationContext,
                    transport = transport,
                    // The pending values, not the saved ones: the user may have typed an override
                    // in this session and not saved yet, and asking for it again would be absurd.
                    manualSsid = pendingHotspotSsid.orEmpty(),
                    manualPassword = pendingHotspotPassword.orEmpty(),
                    // The value for the transport being probed: the two addresses are different
                    // interfaces and one is never an answer for the other.
                    staticBssid = if (transport == NativeTransport.WIFI_DIRECT) pendingStaticP2pBSSID
                    else pendingStaticBSSID,
                    hotspotInterface = pendingHotspotInterface.orEmpty()
                )
                NativeCredentialsPreflightPolicy.evaluate(transport, probe)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A pre-flight that fails is not a finding. Saying nothing is the same outcome the
                // user had before this existed.
                AppLog.w("SettingsFragment: the credentials pre-flight could not run: ${e.message}")
                return@launch
            }
            if (!isAdded || !report.hasFindings) return@launch

            AppLog.i("SettingsFragment: credentials pre-flight for $transport: ${report.verdicts}")
            showPreflightReport(report)
        }
    }

    /**
     * Names everything the probe is sure of, in one dialog, and offers the remedy for each.
     *
     * One dialog rather than a chain of them. Both findings can apply at once, and the entry dialogs
     * only call back when the user confirms - a cancelled one would leave a chained follow-up
     * unreachable, which is how the location advice would get lost on exactly the unit that needs
     * both.
     */
    private fun showPreflightReport(report: PreflightReport) {
        if (!isAdded) return
        val body = StringBuilder(getString(R.string.preflight_intro))
        report.mustEnter.forEach { body.append("\n\n\u2022 ").append(getString(labelFor(it))) }
        if (report.locationServicesOff) {
            body.append("\n\n\u2022 ").append(getString(R.string.preflight_item_location))
        }

        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.preflight_title)
            .setMessage(body.toString())
            // Never blocking. The mode is already selected and stays selected: the user may know
            // something the probe does not, or may simply want to try it and see.
            .setNegativeButton(R.string.preflight_later, null)

        if (report.mustEnter.isNotEmpty()) {
            builder.setPositiveButton(R.string.preflight_enter_now) { dialog, _ ->
                dialog.dismiss()
                promptForField(report.mustEnter, 0)
            }
            if (report.locationServicesOff) {
                builder.setNeutralButton(R.string.preflight_open_location) { dialog, _ ->
                    dialog.dismiss()
                    openLocationSettings()
                }
            }
        } else {
            // Location is the only finding, so the toggle is the whole remedy and gets the main
            // button. Nothing here is worth typing by hand while it is off.
            builder.setPositiveButton(R.string.open_settings) { dialog, _ ->
                dialog.dismiss()
                openLocationSettings()
            }
        }
        builder.show()
    }

    private fun labelFor(field: CredentialField): Int = when (field) {
        CredentialField.HOTSPOT_NAME -> R.string.preflight_item_hotspot_name
        CredentialField.HOTSPOT_PASSWORD -> R.string.preflight_item_hotspot_password
        CredentialField.BSSID -> R.string.preflight_item_bssid
    }

    /**
     * Walks the missing fields one dialog at a time, reusing the same entry dialogs and the same
     * explanatory copy as the settings rows themselves, so the two routes cannot drift apart.
     *
     * Recursive on [index] rather than a loop, because each dialog resolves on a callback.
     */
    private fun promptForField(missing: List<CredentialField>, index: Int) {
        if (!isAdded || index >= missing.size) return
        val next = { promptForField(missing, index + 1) }

        when (missing[index]) {
            CredentialField.HOTSPOT_NAME -> DialogUtils.showTextInputDialogWithMessage(
                requireContext(),
                R.string.hotspot_ssid_override,
                R.string.hotspot_ssid_override_message,
                pendingHotspotSsid.orEmpty()
            ) { newVal ->
                pendingHotspotSsid = newVal.trim()
                checkChanges()
                updateSettingsList()
                next()
            }

            CredentialField.HOTSPOT_PASSWORD -> DialogUtils.showTextInputDialogWithMessage(
                requireContext(),
                R.string.hotspot_password_override,
                R.string.hotspot_password_override_message,
                pendingHotspotPassword.orEmpty()
            ) { newVal ->
                pendingHotspotPassword = newVal.trim()
                checkChanges()
                updateSettingsList()
                next()
            }

            // The field for the transport the probe ran on. The two addresses are different
            // interfaces, so writing the access point's here would be the mismatch this flow exists
            // to repair.
            CredentialField.BSSID -> {
                val forP2p = pendingNativeTransport() == NativeTransport.WIFI_DIRECT
                val current = if (forP2p) pendingStaticP2pBSSID else pendingStaticBSSID
                fun store(value: String) {
                    if (forP2p) pendingStaticP2pBSSID = value else pendingStaticBSSID = value
                }
                DialogUtils.showTextInputDialogWithMessage(
                requireContext(),
                if (forP2p) R.string.static_p2p_bssid_title else R.string.static_bssid_title,
                if (forP2p) R.string.static_p2p_bssid_desc else R.string.static_bssid_desc,
                current?.takeIf { SoftApBssidPolicy.isUsable(it) }.orEmpty()
            ) { newVal ->
                // Checked here as well as at the row, because a value that is not MAC-shaped is
                // worse than none: it beats every automatic source and fails much later, at Type 3
                // time, with a message that blames location services.
                val trimmed = newVal.trim()
                when {
                    trimmed.isEmpty() -> {
                        store("0")
                        checkChanges()
                        updateSettingsList()
                        next()
                    }
                    SoftApBssidPolicy.isUsable(trimmed) -> {
                        store(trimmed)
                        checkChanges()
                        updateSettingsList()
                        next()
                    }
                    else -> {
                        ToastUtils.showToast(requireContext(), R.string.preflight_invalid_bssid, Toast.LENGTH_LONG, force = true)
                        // Ask again rather than move on: this is the field where a wrong value does
                        // more harm than no value.
                        promptForField(missing, index)
                    }
                }
            }
            }
        }
    }

    /** The same deep-link shape as [showPermissionDialog], including its refusal to crash. */
    private fun openLocationSettings() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
        } catch (e: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            } catch (e2: Exception) {
                AppLog.w("SettingsFragment: could not open the location settings: ${e2.message}")
            }
        }
    }

    companion object {
        private val SAVE_ITEM_ID = 1001

        /**
         * The external-Bluetooth probe runs here rather than on the fragment's scope, and its
         * verdict lives here rather than on the instance.
         *
         * Its listening phase asks the user to start a wireless connection, which on these units
         * means the vendor's projection app taking the display and destroying this fragment's view.
         * A view-scoped job would die with it and report that nothing arrived during a run it was
         * never allowed to finish, which is exactly the answer the probe exists to distinguish.
         *
         * Nothing here holds a Context, so outliving the screen leaks nothing.
         */
        private val zbtProbeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        @Volatile
        private var zbtProbeJob: Job? = null

        /** Latest probe verdict or progress line, or null if it has never been run. */
        @Volatile
        private var zbtProbeResult: String? = null
    }

    private fun showOemAppManagementDialog() {
        val context = requireContext()
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_oem_app_manager, null)

        val tvBackendStatus = dialogView.findViewById<TextView>(R.id.tvBackendStatus)
        val switchAutoKill = dialogView.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchAutoKill)
        val progressLoading = dialogView.findViewById<ProgressBar>(R.id.progressLoading)
        val appsContainer = dialogView.findViewById<LinearLayout>(R.id.appsContainer)
        val tvEmptyState = dialogView.findViewById<TextView>(R.id.tvEmptyState)

        val currentAutoKill = pendingAutoKillOemApps ?: settings.autoKillOemApps
        switchAutoKill.isChecked = currentAutoKill
        switchAutoKill.setOnCheckedChangeListener { _, isChecked ->
            pendingAutoKillOemApps = isChecked
            settings.autoKillOemApps = isChecked
            checkChanges()
            updateSettingsList()
        }

        val alertDialog = MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(R.string.oem_app_management)
            .setView(dialogView)
            .setPositiveButton(R.string.close) { dialog, _ -> dialog.dismiss() }
            .create()

        var updateUI: ((List<OemAppManager.OemAppStatus>, OemAppManager.ExecutionBackend) -> Unit)? = null

        val loadStatus: () -> Unit = {
            progressLoading.visibility = View.VISIBLE
            viewLifecycleOwner.lifecycleScope.launch {
                val backend = OemAppManager.checkAvailableBackend()
                val statuses = OemAppManager.detectApps(context, checkDaemons = true)
                progressLoading.visibility = View.GONE
                updateUI?.invoke(statuses, backend)
            }
        }

        updateUI = { statuses, backend ->
            when (backend) {
                OemAppManager.ExecutionBackend.ROOT -> {
                    tvBackendStatus.setText(R.string.oem_app_backend_root)
                    tvBackendStatus.setTextColor(ContextCompat.getColor(context, R.color.brand_teal))
                }
                OemAppManager.ExecutionBackend.ADB -> {
                    tvBackendStatus.setText(R.string.oem_app_backend_adb)
                    tvBackendStatus.setTextColor(ContextCompat.getColor(context, R.color.brand_teal))
                }
                OemAppManager.ExecutionBackend.NONE -> {
                    tvBackendStatus.setText(R.string.oem_app_backend_none)
                    tvBackendStatus.setTextColor(ContextCompat.getColor(context, R.color.material_orange_700))
                }
            }

            appsContainer.removeAllViews()
            var anyFound = false

            for (status in statuses) {
                val itemView = LayoutInflater.from(context).inflate(R.layout.item_oem_app, appsContainer, false)
                val tvAppName = itemView.findViewById<TextView>(R.id.tvAppName)
                val tvPackageName = itemView.findViewById<TextView>(R.id.tvPackageName)
                val tvStatusBadge = itemView.findViewById<TextView>(R.id.tvStatusBadge)
                val btnShowCommands = itemView.findViewById<MaterialButton>(R.id.btnShowCommands)
                val btnRestore = itemView.findViewById<MaterialButton>(R.id.btnRestore)
                val btnDisable = itemView.findViewById<MaterialButton>(R.id.btnDisable)

                tvAppName.text = status.target.displayName
                tvPackageName.text = status.installedPackage ?: status.target.packageCandidates.first()

                val installedStr = if (status.isInstalled) getString(R.string.oem_app_state_installed) else getString(R.string.oem_app_state_not_installed)
                val enabledStr = if (status.isEnabled) getString(R.string.oem_app_state_enabled) else getString(R.string.oem_app_state_disabled)
                val runningStr = if (status.isRunning) getString(R.string.oem_app_state_running) else getString(R.string.oem_app_state_stopped)

                tvStatusBadge.text = getString(R.string.oem_app_status_format, installedStr, enabledStr, runningStr)

                if (status.isInstalled) {
                    anyFound = true
                }

                btnDisable.isEnabled = backend != OemAppManager.ExecutionBackend.NONE && status.isInstalled
                btnRestore.isEnabled = backend != OemAppManager.ExecutionBackend.NONE && status.isInstalled

                btnShowCommands.setOnClickListener {
                    val disableCmds = OemAppManager.getDisableCommands(status).joinToString("\n")
                    val restoreCmds = OemAppManager.getRestoreCommands(status).joinToString("\n")
                    val fullMsg = "--- " + getString(R.string.oem_app_btn_disable) + " ---\n" + disableCmds + "\n\n--- " + getString(R.string.oem_app_btn_restore) + " ---\n" + restoreCmds

                    MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
                        .setTitle(R.string.oem_app_commands_title)
                        .setMessage(fullMsg)
                        .setPositiveButton(android.R.string.ok) { d, _ -> d.dismiss() }
                        .setNeutralButton(R.string.copy_to_clipboard) { _, _ ->
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("ADB Commands", fullMsg))
                            ToastUtils.showToast(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT, force = true)
                        }
                        .show()
                }

                btnDisable.setOnClickListener {
                    progressLoading.visibility = View.VISIBLE
                    viewLifecycleOwner.lifecycleScope.launch {
                        val (success, out) = OemAppManager.disableTarget(context, status)
                        progressLoading.visibility = View.GONE
                        if (success) {
                            ToastUtils.showToast(context, R.string.oem_app_action_success, Toast.LENGTH_SHORT, force = true)
                        } else {
                            ToastUtils.showToast(context, getString(R.string.oem_app_action_failed, out), Toast.LENGTH_LONG, force = true)
                        }
                        loadStatus()
                    }
                }

                btnRestore.setOnClickListener {
                    progressLoading.visibility = View.VISIBLE
                    viewLifecycleOwner.lifecycleScope.launch {
                        val (success, out) = OemAppManager.restoreTarget(context, status)
                        progressLoading.visibility = View.GONE
                        if (success) {
                            ToastUtils.showToast(context, R.string.oem_app_action_success, Toast.LENGTH_SHORT, force = true)
                        } else {
                            ToastUtils.showToast(context, getString(R.string.oem_app_action_failed, out), Toast.LENGTH_LONG, force = true)
                        }
                        loadStatus()
                    }
                }

                appsContainer.addView(itemView)
            }

            tvEmptyState.visibility = if (anyFound) View.GONE else View.VISIBLE
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val initialStatuses = OemAppManager.detectApps(context, checkDaemons = false)
            updateUI?.invoke(initialStatuses, OemAppManager.ExecutionBackend.NONE)
            loadStatus()
        }

        alertDialog.show()
    }

}
