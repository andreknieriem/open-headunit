package com.andrerinas.openheadunit.main

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.graphics.Color
import android.content.res.ColorStateList
import android.widget.*
import android.view.View
import android.view.ViewGroup
import android.view.LayoutInflater
import android.view.ViewTreeObserver
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.repeatOnLifecycle
import androidx.activity.result.contract.ActivityResultContracts
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import androidx.fragment.app.Fragment
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import android.os.Build
import android.bluetooth.BluetoothDevice
import android.os.CountDownTimer
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeDriverSelectionPolicy
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapProjectionActivity
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.wifi.modes.helper.NearbyManager
import com.andrerinas.openheadunit.connection.usb.UsbDeviceCompat
import com.andrerinas.openheadunit.connection.usb.UsbDeviceDiagnostics
import android.content.res.Configuration
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppPermissions
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ColorUtils
import com.andrerinas.openheadunit.utils.HomeUiHelper
import com.andrerinas.openheadunit.utils.VpnControl
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.connection.usb.UsbReceiver
import com.andrerinas.openheadunit.connection.usb.UsbAccessoryMode
import com.andrerinas.openheadunit.connection.wifi.modes.helper.HelperStrategy
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

class HomeFragment : Fragment() {

    private val commManager get() = App.provide(requireContext()).commManager

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            AppLog.i("VPN permission granted. Starting DummyVpnService and Self Mode.")
            VpnControl.startVpn(requireContext());
            startSelfModeInternal()
        } else {
            AppLog.w("VPN permission denied. Offline Self Mode might fail.")
            Toast.makeText(requireContext(), getString(R.string.failed_start_android_auto), Toast.LENGTH_LONG).show()
        }
    }

    private val bluetoothPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) {
            showNativeAaDeviceSelector()
        } else {
            Toast.makeText(requireContext(), R.string.bt_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    private lateinit var self_mode_button: Button
    private lateinit var usb: Button
    private lateinit var settings: Button
    private lateinit var wifi: Button
    private lateinit var wifi_text_view: TextView
    private lateinit var exitButton: Button
    private lateinit var self_mode_text: TextView
    private var hasAttemptedAutoConnect = false
    private var hasAttemptedSingleUsbAutoConnect = false
    private var activeDialog: androidx.appcompat.app.AlertDialog? = null
    private var driverCountdownTimer: CountDownTimer? = null
    private var portraitLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    private fun updateWifiButtonFeedback(scanning: Boolean) {
        if (scanning) {
            wifi_text_view.text = getString(R.string.searching)
            wifi.alpha = 0.6f
        } else {
            wifi_text_view.text = getString(R.string.wifi)
            wifi.alpha = 1.0f
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_home, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        self_mode_button = view.findViewById(R.id.self_mode_button)
        usb = view.findViewById(R.id.usb_button)
        settings = view.findViewById(R.id.settings_button)
        wifi = view.findViewById(R.id.wifi_button)
        wifi_text_view = view.findViewById(R.id.wifi_text)
        exitButton = view.findViewById(R.id.exit_button)
        self_mode_text = view.findViewById(R.id.self_mode_text)

        // Portrait layout: cap grid width so square buttons never overflow
        // into the WiFi-pill or Exit-button areas on compact/square devices.
        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            constrainPortraitGridWidth(view)
        }

        setupListeners()
        updateProjectionButtonText()
        updateButtonStyle()
        updateButtonScale()

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                commManager.connectionState.collect { updateProjectionButtonText() }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AapService.scanningState.collect { updateWifiButtonFeedback(it) }
            }
        }

        val appSettings = App.provide(requireContext()).settings

        if (appSettings.autoStartOnScreenOn || appSettings.autoStartOnBoot) {
            ContextCompat.startForegroundService(requireContext(),
                Intent(requireContext(), AapService::class.java))
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val isAutoConnectEnabled = appSettings.autoStartSelfMode ||
                appSettings.autoConnectLastSession ||
                appSettings.autoConnectSingleUsbDevice

            val delaySec = appSettings.autoConnectDelaySeconds
            if (isAutoConnectEnabled && delaySec > 0 && !forceSelfModeLaunch && !commManager.isConnected) {
                AppLog.i("HomeFragment: Waiting ${delaySec}s before attempting auto-connect...")
                delay(delaySec * 1000L)
            }

            if (!isAdded || commManager.isConnected) return@launch

            for (methodId in appSettings.autoConnectPriorityOrder) {
                if (commManager.isConnected) break
                when (methodId) {
                    Settings.AUTO_CONNECT_LAST_SESSION -> {
                        if (appSettings.autoConnectLastSession && !hasAttemptedAutoConnect && !commManager.isConnected) {
                            hasAttemptedAutoConnect = true
                            if (attemptAutoConnect()) {
                                (requireActivity() as? MainActivity)?.beginAutoConnect(
                                    "auto-connect last session",
                                    MainActivity.ConnectionUiMode.OVERLAY
                                )
                            }
                        }
                    }
                    Settings.AUTO_CONNECT_SELF_MODE -> {
                        if ((appSettings.autoStartSelfMode || forceSelfModeLaunch) && !hasAutoStarted && !commManager.isConnected) {
                            hasAutoStarted = true
                            forceSelfModeLaunch = false // Reset once processed
                            (requireActivity() as? MainActivity)?.beginAutoConnect(
                                "auto-start self mode",
                                MainActivity.ConnectionUiMode.OVERLAY
                            )
                            startSelfMode()
                        }
                    }
                    Settings.AUTO_CONNECT_SINGLE_USB -> {
                        if (appSettings.autoConnectSingleUsbDevice && !hasAttemptedSingleUsbAutoConnect && !commManager.isConnected) {
                            hasAttemptedSingleUsbAutoConnect = true
                            if (attemptSingleUsbAutoConnect()) {
                                (requireActivity() as? MainActivity)?.beginAutoConnect(
                                    "auto-connect single USB",
                                    MainActivity.ConnectionUiMode.OVERLAY
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun startSelfModeInternal() {
        val intent = Intent(requireContext(), AapService::class.java)
        intent.action = AapService.ACTION_START_SELF_MODE
        ContextCompat.startForegroundService(requireContext(), intent)
        AppLog.i("Auto start selfmode")
    }

    private fun startSelfMode() {
        val connectivityManager = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.activeNetwork
        } else null

        if (activeNetwork == null && VpnControl.isVpnAvailable()) {
            AppLog.i("Device is offline. Preparing Dummy VPN for Self Mode.")
            val vpnIntent = VpnControl.consentIntent(requireContext())
            if (vpnIntent != null) {
                vpnPermissionLauncher.launch(vpnIntent)
                return
            } else {
                AppLog.i("VPN permission already granted. Starting VPN service.")
                VpnControl.startVpn(requireContext());
            }
        } else if (activeNetwork == null) {
            AppLog.i("Device is offline and VPN is not available in this build. Self Mode may fail.")
        }
        startSelfModeInternal()
    }

    /**
     * Tries to start an auto-reconnect to the last session.
     *
     * @return `true` if a connection attempt was actually dispatched (so the
     *   caller should surface the pill), `false` if nothing was started (e.g.
     *   Native AA mode, no last session, missing USB device or permission).
     *   Returning a flag prevents the pill from being shown for 30 s when no
     *   work was queued.
     */
    private fun attemptAutoConnect(): Boolean {
        val ctx = context ?: return false
        val appSettings = App.provide(ctx).settings

        // [FIX] Skip manual WiFi connection if Native AA is selected.
        // Native AA handles its own handshake via Bluetooth/P2P.
        if (appSettings.wifiConnectionMode == WifiLauncherMode.NATIVE) {
            AppLog.i("HomeFragment: Native AA mode active. Skipping manual auto-connect attempt.")
            return false
        }

        if (!appSettings.autoConnectLastSession ||
            !appSettings.hasAcceptedDisclaimer ||
            commManager.isConnected) {
            return false
        }

        val connectionType = appSettings.lastConnectionType
        if (connectionType.isEmpty()) {
            AppLog.i("Auto-connect: No last session to reconnect to")
            return false
        }

        return when (connectionType) {
            Settings.CONNECTION_TYPE_WIFI -> {
                if (appSettings.wifiConnectionMode == WifiLauncherMode.AUTO) {
                    val ip = appSettings.lastConnectionIp
                    if (ip.isNotEmpty()) {
                        AppLog.i("Auto-connect: Attempting WiFi connection to $ip")
                        Toast.makeText(ctx, getString(R.string.auto_connecting_to, ip), Toast.LENGTH_SHORT).show()
                        lifecycleScope.launch(Dispatchers.IO) { App.provide(ctx).commManager.connect(ip, 5277) }
                        ContextCompat.startForegroundService(ctx, Intent(ctx, AapService::class.java).apply {
                            action = AapService.ACTION_CONNECT_SOCKET
                        })
                        true
                    } else false
                } else {
                    AppLog.i("Auto-connect: Last session was WiFi, but connection mode is not Headunit Server. Skipping active connect.")
                    false
                }
            }
            Settings.CONNECTION_TYPE_USB -> {
                val lastUsbDevice = appSettings.lastConnectionUsbDevice
                if (lastUsbDevice.isNotEmpty()) {
                    val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
                    val matchingDevice = usbManager.deviceList.values.find { device ->
                        UsbDeviceCompat.getUniqueName(device) == lastUsbDevice
                    }
                    if (matchingDevice != null && usbManager.hasPermission(matchingDevice)) {
                        AppLog.i("Auto-connect: Attempting USB connection to $lastUsbDevice")
                        Toast.makeText(requireContext(), getString(R.string.auto_connecting_usb), Toast.LENGTH_SHORT).show()
                        ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java).apply {
                            action = AapService.ACTION_CHECK_USB
                        })
                        true
                    } else {
                        AppLog.i("Auto-connect: USB device $lastUsbDevice not found or no permission")
                        false
                    }
                } else false
            }
            Settings.CONNECTION_TYPE_NEARBY -> {
                AppLog.i("Auto-connect: Last session was via Google Nearby. AapService will handle discovery.")
                // No manual connect(ip) needed, NearbyManager in AapService manages this automatically on start.
                true
            }
            else -> false
        }
    }

    /**
     * @return `true` if a single-USB connection attempt was dispatched,
     *   `false` if guards (setting disabled, disclaimer pending, already
     *   connected) blocked it. Same intent as [attemptAutoConnect].
     */
    private fun attemptSingleUsbAutoConnect(): Boolean {
        val ctx = context ?: return false
        val appSettings = App.provide(ctx).settings
        if (!appSettings.autoConnectSingleUsbDevice ||
            !appSettings.hasAcceptedDisclaimer ||
            commManager.isConnected) return false

        AppLog.i("HomeFragment: Requesting single-USB auto-connect via AapService")
        ContextCompat.startForegroundService(ctx,
            Intent(ctx, AapService::class.java).apply {
                action = AapService.ACTION_CHECK_USB
            })
        return true
    }

    private fun updateButtonStyle() {
        val ctx = context ?: return
        val v = view ?: return
        val appSettings = App.provide(ctx).settings
        val isNightActive = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        HomeUiHelper.applyButtonStyles(ctx, v, appSettings, isNightActive)
    }

    private fun updateButtonScale() {
        val v = view ?: return
        val ctx = context ?: return
        val appSettings = App.provide(ctx).settings
        val density = resources.displayMetrics.density
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        HomeUiHelper.applyButtonScale(v, appSettings.homeButtonScalePercent, isPortrait, density)
    }

    private fun setupListeners() {
        exitButton.setOnClickListener {
            val appSettings = App.provide(requireContext()).settings
            val keepServiceAlive = appSettings.autoStartOnBoot ||
                appSettings.autoStartOnScreenOn ||
                (appSettings.autoStartOnUsb && appSettings.reopenOnReconnection)
            if (keepServiceAlive) {
                val disconnectIntent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_DISCONNECT
                }
                ContextCompat.startForegroundService(requireContext(), disconnectIntent)
            } else {
                val stopServiceIntent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_STOP_SERVICE
                }
                ContextCompat.startForegroundService(requireContext(), stopServiceIntent)
            }
            requireActivity().finishAffinity()
        }

        self_mode_button.setOnClickListener {
            if (commManager.isConnected) {
                val aapIntent = Intent(requireContext(), AapProjectionActivity::class.java)
                aapIntent.putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                startActivity(aapIntent)
            } else {
                if (!AppPermissions.isOverlayGranted(requireContext())) {
                    MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                        .setTitle(R.string.overlay_permission_title)
                        .setMessage(R.string.self_mode_overlay_permission_description)
                        .setPositiveButton(R.string.open_settings) { _, _ ->
                            val intent = Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${requireContext().packageName}")
                            )
                            startActivity(intent)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .show()
                } else {
                    (requireActivity() as? MainActivity)?.beginAutoConnect(
                        "manual self mode",
                        MainActivity.ConnectionUiMode.OVERLAY
                    )
                    startSelfMode()
                }
            }
        }

        usb.setOnClickListener {
            // Already connected to Android Auto - just show projection
            if (commManager.isConnected) {
                val aapIntent = Intent(requireContext(), AapProjectionActivity::class.java)
                aapIntent.putExtra(AapProjectionActivity.EXTRA_FOCUS, true)
                startActivity(aapIntent)
                return@setOnClickListener
            }

            // Get list of Android USB devices
            val usbManager = requireContext().getSystemService(Context.USB_SERVICE) as UsbManager
            UsbDeviceDiagnostics.logDeviceList(requireContext(), usbManager, "USB button")
            val androidDevices = usbManager.deviceList.values
                .filter { UsbDeviceCompat.isConnectable(requireContext(), it) }

            // If exactly one device found - auto-connect
            if (androidDevices.size == 1) {
                val device = UsbDeviceCompat(androidDevices[0])
                AppLog.i("USB button: Single device found - ${device.uniqueName}, auto-connecting")
                (requireActivity() as? MainActivity)?.beginAutoConnect(
                    "USB button auto-connect",
                    MainActivity.ConnectionUiMode.OVERLAY
                )

                if (device.isInAccessoryMode) {
                    ContextCompat.startForegroundService(requireContext(),
                        Intent(requireContext(), AapService::class.java).apply {
                            action = AapService.ACTION_CHECK_USB
                        })
                } else {
                    if (usbManager.hasPermission(device.wrappedDevice)) {
                        val usbMode = UsbAccessoryMode(usbManager)
                        val useLibusb = App.provide(requireContext()).settings.useLibusb
                        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                            val success = usbMode.connectAndSwitch(device.wrappedDevice, useLibusb)
                            withContext(Dispatchers.Main) {
                                context?.let { ctx ->
                                    if (success) {
                                        Toast.makeText(ctx, R.string.switching_to_android_auto, Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(ctx, R.string.switch_failed, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                    } else {
                        Toast.makeText(requireContext(), R.string.requesting_usb_permission, Toast.LENGTH_SHORT).show()
                        ContextCompat.startForegroundService(requireContext(), Intent(requireContext(), AapService::class.java))
                        usbManager.requestPermission(
                            device.wrappedDevice,
                            UsbReceiver.createPermissionPendingIntent(requireContext())
                        )
                    }
                }
            } else {
                // 0 or multiple devices - open the list
                val controller = findNavController()
                if (controller.currentDestination?.id == R.id.homeFragment) {
                    controller.navigate(R.id.action_homeFragment_to_usbListFragment)
                }
            }
        }

        settings.setOnClickListener {
            val intent = Intent(requireContext(), SettingsActivity::class.java)
            startActivity(intent)
        }

        wifi.setOnClickListener {
            val mode = App.provide(requireContext()).settings.wifiConnectionMode
            when (mode) {
                WifiLauncherMode.AUTO -> { // Auto (Headunit Server) - One-Shot Scan
                    if (commManager.isConnected) {
                        // Already connected
                    } else if (AapService.scanningState.value) {
                        Toast.makeText(requireContext(), getString(R.string.already_scanning), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.searching_headunit_server), Toast.LENGTH_SHORT).show()
                        (requireActivity() as? MainActivity)?.beginAutoConnect(
                            "manual WiFi headunit server scan",
                            MainActivity.ConnectionUiMode.OVERLAY
                        )
                        val intent = Intent(requireContext(), AapService::class.java).apply {
                            action = AapService.ACTION_START_WIRELESS_SCAN
                        }
                        ContextCompat.startForegroundService(requireContext(), intent)
                    }
                }
                WifiLauncherMode.HELPER -> { // Helper (Wireless Launcher)
                    if (commManager.isConnected) {
                        // Already connected
                    } else {
                        val strategy = App.provide(requireContext()).settings.helperConnectionStrategy
                        if (strategy == HelperStrategy.HEADUNIT_HOTSPOT) {
                            if (!AapService.scanningState.value) {
                                (requireActivity() as? MainActivity)?.beginAutoConnect(
                                    "manual WiFi helper scan",
                                    MainActivity.ConnectionUiMode.OVERLAY
                                )
                                val intent = Intent(requireContext(), AapService::class.java).apply {
                                    action = AapService.ACTION_START_WIRELESS_SCAN
                                }
                                ContextCompat.startForegroundService(requireContext(), intent)
                            }
                            com.andrerinas.openheadunit.utils.ShareHotspotQrDialog.show(
                                requireContext()
                            )
                        } else if (strategy == HelperStrategy.NEARBY_DEVICES) {
                            // Nearby Devices — show live discovery dialog
                            showNearbyDeviceSelector()
                        } else if (AapService.scanningState.value) {
                            Toast.makeText(requireContext(), getString(R.string.already_searching_phone), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(requireContext(), getString(R.string.searching_phone), Toast.LENGTH_SHORT).show()
                            (requireActivity() as? MainActivity)?.beginAutoConnect(
                                "manual WiFi helper scan",
                                MainActivity.ConnectionUiMode.OVERLAY
                            )
                            val intent = Intent(requireContext(), AapService::class.java).apply {
                                action = AapService.ACTION_START_WIRELESS_SCAN
                            }
                            ContextCompat.startForegroundService(requireContext(), intent)
                        }
                    }
                }
                WifiLauncherMode.NATIVE -> { // Native AA
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                        bluetoothPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        val appSettings = App.provide(requireContext()).settings
                        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())
                        val bonded = adapter?.bondedDevices?.toList() ?: emptyList()
                        val connected = BluetoothHelper.getConnectedBluetoothDevices(requireContext())
                        val likelyPhones = bonded.filter {
                            BluetoothHelper.isLikelyPhone(it, appSettings.nativePreferredDeviceMac, appSettings.lastConnectedNativeMac)
                        }
                        val candidates = if (likelyPhones.isNotEmpty()) likelyPhones else bonded
                        val hasHistory = appSettings.lastConnectedNativeMac.isNotEmpty() ||
                            appSettings.nativePreferredDeviceMac.isNotEmpty() ||
                            appSettings.autoStartBluetoothDeviceMacs.isNotEmpty()

                        val autoTargetMac = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
                            preferredMac = appSettings.nativePreferredDeviceMac,
                            lastUsedMac = appSettings.lastConnectedNativeMac.ifEmpty {
                                appSettings.autoStartBluetoothDeviceMacs.firstOrNull().orEmpty()
                            },
                            connectedMacs = connected.map { it.address },
                            pairedMacs = candidates.map { it.address }
                        )

                        val shouldShow = NativeDriverSelectionPolicy.shouldShowSelector(
                            mode = appSettings.nativeDriverSelectionMode,
                            pairedCount = candidates.size,
                            connectedCount = connected.size,
                            hasHistory = hasHistory
                        )

                        if (appSettings.nativeDriverSelectionMode == NativeDriverSelectionPolicy.Mode.DISABLED) {
                            if (autoTargetMac != null) {
                                val targetDev = bonded.firstOrNull { it.address.equals(autoTargetMac, ignoreCase = true) }
                                val devName = targetDev?.name ?: autoTargetMac
                                connectToNativeDevice(autoTargetMac, devName)
                            } else {
                                Toast.makeText(requireContext(), getString(R.string.searching_phone), Toast.LENGTH_SHORT).show()
                                val intent = Intent(requireContext(), AapService::class.java).apply {
                                    action = AapService.ACTION_NATIVE_AA_POKE
                                    if (candidates.size == 1) {
                                        putExtra(AapService.EXTRA_MAC, candidates[0].address)
                                    }
                                }
                                ContextCompat.startForegroundService(requireContext(), intent)
                            }
                        } else if (!shouldShow && autoTargetMac != null) {
                            val targetDev = bonded.firstOrNull { it.address.equals(autoTargetMac, ignoreCase = true) }
                            val devName = targetDev?.name ?: autoTargetMac
                            connectToNativeDevice(autoTargetMac, devName)
                        } else {
                            showNativeAaDeviceSelector(autoCountdown = false)
                        }
                    }
                }
                WifiLauncherMode.MANUAL -> { // Manual (0) -> Open List
                    val controller = findNavController()
                    if (controller.currentDestination?.id == R.id.homeFragment) {
                        controller.navigate(R.id.action_homeFragment_to_networkListFragment)
                    }
                }
            }
        }

        wifi.setOnLongClickListener {
            val controller = findNavController()
            if (controller.currentDestination?.id == R.id.homeFragment) {
                controller.navigate(R.id.action_homeFragment_to_networkListFragment)
            }
            true
        }
    }

    /**
     * Portrait-only: after the first layout pass we know the exact pixel
     * dimensions of the screen and the reserved areas (WiFi-pill spacer at
     * the top, Exit button at the bottom).  We calculate the largest square
     * button that fits in a 2-row grid and constrain the grid's max-width
     * accordingly.  This prevents overflow on square / wide-portrait tablets
     * while allowing full-width buttons on tall phones.
     *
     * Formula:
     *   availableH  = containerH − topSpacerH − exitButtonH
     *   maxBtnSize  = min(containerW / 2, availableH / 2) − cell-padding
     *   maxGridW    = maxBtnSize * 2 + cell-padding * 4   (2 cols, padding each side)
     */
    private fun constrainPortraitGridWidth(rootView: View) {
        val gridLayout = rootView.findViewById<View>(R.id.main_buttons_layout) as? android.widget.LinearLayout
            ?: return
        val density = resources.displayMetrics.density

        portraitLayoutListener?.let {
            if (gridLayout.viewTreeObserver.isAlive) {
                gridLayout.viewTreeObserver.removeOnGlobalLayoutListener(it)
            }
        }

        val listener = object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (!isAdded) return

                val container = gridLayout.parent as? View ?: return
                val containerW = container.width
                val containerH = container.height
                if (containerW == 0 || containerH == 0) return

                // Reserved vertical space: 64 dp top-spacer + ~56 dp exit button (margin incl.)
                val reservedPx = (64 + 56) * density
                // Extra per-row overhead: label (~20 sp≈20dp) + marginTop (6 dp) + cell padding top+bottom (24 dp)
                val rowOverheadPx = 50 * density

                val availableH = containerH - reservedPx
                // Max button edge that fits in one row without labels overflowing
                val maxBtnFromH = ((availableH / 2f) - rowOverheadPx).toInt()
                val maxBtnFromW = containerW / 2
                val maxBtn = minOf(maxBtnFromH, maxBtnFromW)

                if (maxBtn <= 0) return

                // Grid max-width = 2 buttons + 4 × cell-horizontal-padding (12 dp each side)
                val cellPadPx = (12 * 2 * 2 * density).toInt() // 2 cols × 2 sides × 12 dp
                val maxGridW = maxBtn * 2 + cellPadPx

                val defaultMaxW = (1200 * density).toInt()
                val targetMaxWidth = if (maxGridW < containerW) maxGridW else defaultMaxW
                val params = gridLayout.layoutParams as? ConstraintLayout.LayoutParams ?: return
                if (params.matchConstraintMaxWidth != targetMaxWidth) {
                    params.matchConstraintMaxWidth = targetMaxWidth
                    gridLayout.layoutParams = params
                }
            }
        }
        portraitLayoutListener = listener
        gridLayout.viewTreeObserver.addOnGlobalLayoutListener(listener)
    }

    private fun updateProjectionButtonText() {
        if (commManager.isConnected) {
            self_mode_text.text = getString(R.string.to_android_auto)
        } else {
            self_mode_text.text = getString(R.string.self_mode)
        }
    }

    override fun onResume() {
        super.onResume()
        AppLog.i("HomeFragment: onResume. isConnected=${commManager.isConnected}")
        updateProjectionButtonText()
        updateButtonStyle()
        updateButtonScale()
        updateTextColors()
        if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
            view?.let { constrainPortraitGridWidth(it) }
        }

        val appSettings = App.provide(requireContext()).settings
        if (requestDriverSelection) {
            requestDriverSelection = false
            showNativeAaDeviceSelector(autoCountdown = false)
        } else if (appSettings.wifiConnectionMode == WifiLauncherMode.NATIVE &&
            !hasCheckedNativeDriverSelection &&
            !commManager.isConnected
        ) {
            checkNativeDriverSelectionOnStartup()
        }

        activity?.let { act ->
            val isDestroyed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1)
                act.isDestroyed
            else
                false

            if (!act.isFinishing && !isDestroyed) {
                RenameNotice.maybeShow(act, App.provide(requireContext()).settings)
                Aa174Notice.maybeShow(act, App.provide(requireContext()).settings)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        driverCountdownTimer?.cancel()
        driverCountdownTimer = null
        activeDialog?.dismiss()
        activeDialog = null
        RenameNotice.dismiss()
        Aa174Notice.dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        portraitLayoutListener?.let {
            view?.findViewById<View>(R.id.main_buttons_layout)?.viewTreeObserver?.let { vto ->
                if (vto.isAlive) vto.removeOnGlobalLayoutListener(it)
            }
            portraitLayoutListener = null
        }
    }

    private fun checkNativeDriverSelectionOnStartup() {
        if (!isAdded) return
        hasCheckedNativeDriverSelection = true
        val appSettings = App.provide(requireContext()).settings
        if (appSettings.nativeDriverSelectionMode == NativeDriverSelectionPolicy.Mode.DISABLED) return
        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())
        if (adapter == null || !adapter.isEnabled) return

        val bonded = adapter.bondedDevices?.toList() ?: emptyList()
        val connected = BluetoothHelper.getConnectedBluetoothDevices(requireContext())

        // Filter likely phones so non-phone accessories (speakers, headphones, watches) don't trigger multi-device prompt
        val likelyPhones = bonded.filter {
            BluetoothHelper.isLikelyPhone(it, appSettings.nativePreferredDeviceMac, appSettings.lastConnectedNativeMac)
        }
        val targetList = if (likelyPhones.isNotEmpty()) likelyPhones else bonded

        val effectiveLastUsedMac = appSettings.lastConnectedNativeMac.ifEmpty {
            appSettings.autoStartBluetoothDeviceMacs.firstOrNull().orEmpty()
        }
        val hasHistory = effectiveLastUsedMac.isNotEmpty() || appSettings.nativePreferredDeviceMac.isNotEmpty()

        val shouldShow = NativeDriverSelectionPolicy.shouldShowSelector(
            mode = appSettings.nativeDriverSelectionMode,
            pairedCount = targetList.size,
            connectedCount = connected.size,
            hasHistory = hasHistory
        )

        val autoTargetMac = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = appSettings.nativePreferredDeviceMac,
            lastUsedMac = effectiveLastUsedMac,
            connectedMacs = connected.map { it.address },
            pairedMacs = targetList.map { it.address }
        )

        if (shouldShow) {
            showNativeAaDeviceSelector(autoCountdown = true)
        } else if (appSettings.nativeDriverSelectionMode == NativeDriverSelectionPolicy.Mode.AUTO && autoTargetMac != null) {
            val targetDev = bonded.firstOrNull { it.address.equals(autoTargetMac, ignoreCase = true) }
            val devName = targetDev?.name ?: autoTargetMac
            AppLog.i("HomeFragment: Unambiguous driver ($devName) - auto-connecting directly without prompt")
            connectToNativeDevice(autoTargetMac, devName)
        }
    }

    private fun showNativeAaDeviceSelector(autoCountdown: Boolean = false) {
        if (!isAdded) return
        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())

        if (adapter == null || !adapter.isEnabled) {
            Toast.makeText(requireContext(), getString(R.string.bt_not_enabled), Toast.LENGTH_SHORT).show()
            return
        }

        val bondedDevices = adapter.bondedDevices?.toList() ?: emptyList()
        if (bondedDevices.isEmpty()) {
            Toast.makeText(requireContext(), "No paired Bluetooth devices found", Toast.LENGTH_SHORT).show()
            return
        }

        val appSettings = App.provide(requireContext()).settings
        val connectedDevices = BluetoothHelper.getConnectedBluetoothDevices(requireContext())
        val connectedMacs = connectedDevices.map { it.address }
        val preferredMac = appSettings.nativePreferredDeviceMac
        val lastUsedMac = appSettings.lastConnectedNativeMac

        // Filter out obvious non-phone accessories (BT speakers, headphones, wearables)
        val likelyPhones = bondedDevices.filter {
            BluetoothHelper.isLikelyPhone(it, preferredMac, lastUsedMac)
        }
        var showAllDevices = likelyPhones.isEmpty()
        val initialCandidates = if (showAllDevices) bondedDevices else likelyPhones

        fun sortDeviceList(devices: List<BluetoothDevice>): List<BluetoothDevice> {
            return devices.sortedWith(
                compareByDescending<BluetoothDevice> { it.address in connectedMacs }
                    .thenByDescending { it.address.equals(preferredMac, ignoreCase = true) }
                    .thenByDescending { it.address.equals(lastUsedMac, ignoreCase = true) }
                    .thenBy { it.name ?: "" }
            )
        }

        val currentDevices = mutableListOf<BluetoothDevice>()
        currentDevices.addAll(sortDeviceList(initialCandidates))

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_driver_selector, null)
        val countdownContainer = dialogView.findViewById<View>(R.id.countdownContainer)
        val countdownSubtitle = dialogView.findViewById<TextView>(R.id.countdownSubtitle)
        val countdownProgress = dialogView.findViewById<ProgressBar>(R.id.countdownProgress)
        val deviceListView = dialogView.findViewById<ListView>(R.id.driverDeviceList)
        val btnToggleFilter = dialogView.findViewById<TextView>(R.id.btnToggleFilter)

        val brandTeal = ContextCompat.getColor(requireContext(), R.color.brand_teal)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            countdownProgress.progressTintList = ColorStateList.valueOf(brandTeal)
        }

        val connectedMacSet = connectedMacs.toSet()
        val listAdapter = object : ArrayAdapter<BluetoothDevice>(requireContext(), R.layout.list_item_driver_device, currentDevices) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.list_item_driver_device, parent, false)
                val device = getItem(position) ?: return view
                val nameView = view.findViewById<TextView>(R.id.deviceName)
                val statusView = view.findViewById<TextView>(R.id.deviceStatus)
                val badgeView = view.findViewById<TextView>(R.id.badgeText)

                val iconView = view.findViewById<ImageView>(R.id.deviceIcon)
                if (BluetoothHelper.isLikelyPhone(device, preferredMac, lastUsedMac)) {
                    iconView.setImageResource(R.drawable.ic_phone)
                } else {
                    iconView.setImageResource(R.drawable.ic_headphones)
                }

                nameView.text = device.name ?: "Unknown Device"

                val isConnected = device.address in connectedMacSet
                if (isConnected) {
                    statusView.text = "🟢 " + context.getString(R.string.driver_device_connected)
                    statusView.setTextColor(ContextCompat.getColor(context, R.color.brand_teal))
                } else {
                    statusView.text = context.getString(R.string.driver_device_paired)
                    statusView.setTextColor(Color.LTGRAY)
                }

                when {
                    device.address.equals(preferredMac, ignoreCase = true) -> {
                        badgeView.text = "⭐ " + context.getString(R.string.driver_device_preferred)
                        badgeView.visibility = View.VISIBLE
                        badgeView.setBackgroundResource(R.drawable.bg_setting_single)
                    }
                    device.address.equals(lastUsedMac, ignoreCase = true) -> {
                        badgeView.text = context.getString(R.string.driver_device_last_used)
                        badgeView.visibility = View.VISIBLE
                        badgeView.setBackgroundResource(R.drawable.bg_setting_single)
                    }
                    else -> {
                        badgeView.visibility = View.GONE
                    }
                }

                val isTop = position == 0
                val isBottom = position == count - 1
                val bgRes = when {
                    isTop && isBottom -> R.drawable.bg_setting_single
                    isTop -> R.drawable.bg_setting_top
                    isBottom -> R.drawable.bg_setting_bottom
                    else -> R.drawable.bg_setting_middle
                }
                view.setBackgroundResource(bgRes)
                return view
            }
        }

        val dm = resources.displayMetrics
        val isLandscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val maxListHeight = if (isLandscape) {
            (dm.heightPixels * 0.38).toInt().coerceIn((120 * dm.density).toInt(), (200 * dm.density).toInt())
        } else {
            (dm.heightPixels * 0.45).toInt().coerceIn((180 * dm.density).toInt(), (320 * dm.density).toInt())
        }
        deviceListView.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            maxListHeight
        )
        deviceListView.adapter = listAdapter

        val hiddenCount = bondedDevices.size - likelyPhones.size
        if (hiddenCount > 0 && likelyPhones.isNotEmpty()) {
            btnToggleFilter.visibility = View.VISIBLE
            fun updateToggleLabel() {
                btnToggleFilter.text = if (showAllDevices) {
                    getString(R.string.show_only_phones)
                } else {
                    "${getString(R.string.show_all_devices)} ($hiddenCount)"
                }
            }
            updateToggleLabel()
            btnToggleFilter.setOnClickListener {
                // Pause/cancel auto-countdown timer since the user is actively interacting with the device list
                driverCountdownTimer?.cancel()
                driverCountdownTimer = null
                countdownContainer.visibility = View.GONE

                showAllDevices = !showAllDevices
                currentDevices.clear()
                val nextCandidates = if (showAllDevices) bondedDevices else likelyPhones
                currentDevices.addAll(sortDeviceList(nextCandidates))
                listAdapter.notifyDataSetChanged()
                updateToggleLabel()
            }
        } else {
            btnToggleFilter.visibility = View.GONE
        }

        val cancelDriverSelection = {
            driverCountdownTimer?.cancel()
            driverCountdownTimer = null
            activeDialog = null
            val cancelIntent = Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_NATIVE_AA_CANCEL_POKE
            }
            ContextCompat.startForegroundService(requireContext(), cancelIntent)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.driver_selection_dialog_title)
            .setView(dialogView)
            .setNegativeButton(R.string.cancel) { d, _ ->
                cancelDriverSelection()
                d.dismiss()
            }
            .create()

        dialog.setOnCancelListener {
            cancelDriverSelection()
        }

        dialog.setOnDismissListener {
            driverCountdownTimer?.cancel()
            driverCountdownTimer = null
            activeDialog = null
        }

        val effectiveLastUsed = lastUsedMac.ifEmpty {
            appSettings.autoStartBluetoothDeviceMacs.firstOrNull().orEmpty()
        }
        val autoTargetMac = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = preferredMac,
            lastUsedMac = effectiveLastUsed,
            connectedMacs = connectedMacs,
            pairedMacs = currentDevices.map { it.address }
        )
        val autoTargetDevice = bondedDevices.firstOrNull { it.address.equals(autoTargetMac, ignoreCase = true) }
        val targetName = autoTargetDevice?.name ?: autoTargetMac ?: ""

        deviceListView.setOnItemClickListener { _, _, position, _ ->
            driverCountdownTimer?.cancel()
            driverCountdownTimer = null
            dialog.dismiss()
            val chosen = currentDevices.getOrNull(position) ?: return@setOnItemClickListener
            connectToNativeDevice(chosen.address, chosen.name ?: "Device")
        }

        val timeoutSec = NativeDriverSelectionPolicy.sanitizeTimeout(appSettings.nativeDriverSelectionTimeoutSec)

        if (autoCountdown && autoTargetMac != null) {
            countdownContainer.visibility = View.VISIBLE
            countdownProgress.max = timeoutSec * 1000
            countdownProgress.progress = timeoutSec * 1000

            var lastSecondsRemaining = -1
            driverCountdownTimer?.cancel()
            driverCountdownTimer = object : CountDownTimer(timeoutSec * 1000L, 100L) {
                override fun onTick(millisUntilFinished: Long) {
                    if (!isAdded || dialog.isShowing != true) return
                    countdownProgress.progress = millisUntilFinished.toInt()
                    val secondsRemaining = ((millisUntilFinished + 999) / 1000).toInt()
                    if (secondsRemaining != lastSecondsRemaining) {
                        lastSecondsRemaining = secondsRemaining
                        countdownSubtitle.text = getString(R.string.driver_selection_auto_in, secondsRemaining, targetName)
                    }
                }

                override fun onFinish() {
                    if (!isAdded || dialog.isShowing != true) return
                    dialog.dismiss()
                    connectToNativeDevice(autoTargetMac, targetName)
                }
            }
            countdownSubtitle.text = getString(R.string.driver_selection_auto_in, timeoutSec, targetName)
        } else {
            countdownContainer.visibility = View.GONE
        }

        dialog.setOnShowListener {
            (activity as? MainActivity)?.dismissSplashImmediately()
            val promptIntent = Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_NATIVE_AA_PROMPT_SHOWN
            }
            ContextCompat.startForegroundService(requireContext(), promptIntent)
            if (autoCountdown && autoTargetMac != null) {
                driverCountdownTimer?.start()
            }
        }

        activeDialog = dialog
        dialog.show()
    }

    private fun connectToNativeDevice(mac: String, name: String) {
        AppLog.i("HomeFragment: Connecting to Native-AA device: $name ($mac)")
        (requireActivity() as? MainActivity)?.beginAutoConnect(
            "Native-AA driver: $name",
            MainActivity.ConnectionUiMode.OVERLAY
        )
        val intent = Intent(requireContext(), AapService::class.java).apply {
            action = AapService.ACTION_NATIVE_AA_POKE
            putExtra(AapService.EXTRA_MAC, mac)
        }
        ContextCompat.startForegroundService(requireContext(), intent)
        Toast.makeText(requireContext(), getString(R.string.connecting_to_device, name), Toast.LENGTH_SHORT).show()
    }

    private fun showNearbyDeviceSelector() {
        // Ensure NearbyManager discovery is running via AapService
        ContextCompat.startForegroundService(requireContext(),
            Intent(requireContext(), AapService::class.java).apply {
                action = AapService.ACTION_START_WIRELESS_SCAN
            })

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_nearby_selection, null)
        val deviceListView = dialogView.findViewById<ListView>(R.id.deviceList)
        val searchingText = dialogView.findViewById<TextView>(R.id.searchingText)
        val connectionProgress = dialogView.findViewById<ProgressBar>(R.id.connectionProgress)

        // Ensure the loading spinner is visible in both Light and Dark modes by forcing our brand color.
        val brandTeal = ContextCompat.getColor(requireContext(), R.color.brand_teal)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            connectionProgress.indeterminateTintList = ColorStateList.valueOf(brandTeal)
            connectionProgress.indeterminateTintMode = android.graphics.PorterDuff.Mode.SRC_IN
        } else {
            @Suppress("DEPRECATION")
            connectionProgress.indeterminateDrawable?.setColorFilter(brandTeal, android.graphics.PorterDuff.Mode.SRC_IN)
        }

        // Custom adapter to handle rounded backgrounds like in USB/Network lists
        val listAdapter = object : ArrayAdapter<NearbyManager.DiscoveredEndpoint>(requireContext(), R.layout.list_item_nearby) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.list_item_nearby, parent, false)
                val endpoint = getItem(position)
                view.findViewById<TextView>(R.id.deviceName).text = endpoint?.name ?: "Unknown"

                // Apply rounded backgrounds based on position
                val isTop = position == 0
                val isBottom = position == count - 1
                val bgRes = when {
                    isTop && isBottom -> R.drawable.bg_setting_single
                    isTop -> R.drawable.bg_setting_top
                    isBottom -> R.drawable.bg_setting_bottom
                    else -> R.drawable.bg_setting_middle
                }
                view.setBackgroundResource(bgRes)
                return view
            }
        }
        deviceListView.adapter = listAdapter

        var collectJob: Job? = null

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(getString(R.string.searching)) // Initial title
            .setView(dialogView)
            .setNegativeButton(R.string.cancel, null)
            .setOnDismissListener {
                collectJob?.cancel()
                if (activeDialog == it) activeDialog = null
            }
            .create()

        activeDialog = dialog

        deviceListView.setOnItemClickListener { _, _, which, _ ->
            val endpoints = NearbyManager.discoveredEndpoints.value
            if (which < endpoints.size) {
                val endpoint = endpoints[which]
                AppLog.i("HomeFragment: Selected Nearby device: ${endpoint.name} (${endpoint.id})")

                // Hand off to the auto-connect overlay so the user gets the same
                // visual treatment as every other connection path. Pass the
                // endpoint name so the loading screen can show "Connecting to
                // <device>…" instead of the generic status text.
                val statusText = getString(R.string.connecting_to_nearby, endpoint.name)
                dialog.dismiss()
                (requireActivity() as? MainActivity)?.beginAutoConnect(
                    "manual Nearby select ${endpoint.name}",
                    MainActivity.ConnectionUiMode.OVERLAY,
                    statusText
                )

                val intent = Intent(requireContext(), AapService::class.java).apply {
                    action = AapService.ACTION_NEARBY_CONNECT
                    putExtra(AapService.EXTRA_ENDPOINT_ID, endpoint.id)
                }
                ContextCompat.startForegroundService(requireContext(), intent)
            }
        }

        dialog.show()

        // Live-update the dialog list as endpoints are discovered
        collectJob = viewLifecycleOwner.lifecycleScope.launch {
            NearbyManager.discoveredEndpoints.collect { endpoints ->
                listAdapter.clear()
                listAdapter.addAll(endpoints)
                listAdapter.notifyDataSetChanged()

                if (endpoints.isEmpty()) {
                    dialog.setTitle(getString(R.string.searching))
                    searchingText.visibility = View.GONE
                } else {
                    dialog.setTitle(getString(R.string.nearby_device_found))
                    searchingText.visibility = View.VISIBLE
                    searchingText.text = getString(R.string.select_nearby_device) + " (${endpoints.size})"
                }
            }
        }
    }

    private fun updateTextColors() {
        val appSettings = App.provide(requireContext()).settings
        val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        val isLightMode = nightModeFlags != Configuration.UI_MODE_NIGHT_YES

        val labelViews = listOf(self_mode_text, wifi_text_view,
            view?.findViewById<TextView>(R.id.usb_text),
            view?.findViewById<TextView>(R.id.settings_text))

        if (appSettings.useGradientBackground && isLightMode) {
            val darkColor = Color.parseColor("#1a1a1a")
            labelViews.filterNotNull().forEach { tv ->
                tv.setTextColor(darkColor)
                tv.setShadowLayer(2f, 0f, 0f, Color.WHITE)
            }
        } else {
            val lightColor = Color.parseColor("#f7f7f7")
            labelViews.filterNotNull().forEach { tv ->
                tv.setTextColor(lightColor)
                tv.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
            }
        }

        exitButton.setTextColor(Color.WHITE)
    }

    companion object {
        private var hasAutoStarted = false
        private var hasCheckedNativeDriverSelection = false
        var forceSelfModeLaunch = false
        var requestDriverSelection = false
        fun resetAutoStart() {
            hasAutoStarted = false
            hasCheckedNativeDriverSelection = false
        }
    }
}
