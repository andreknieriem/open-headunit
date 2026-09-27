package com.andrerinas.openheadunit.main

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.main.settings.SettingItem
import com.andrerinas.openheadunit.main.settings.SettingsAdapter
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.app.BtAutoDisconnectPolicy
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.AppPermissions
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.CarLauncherManager
import com.andrerinas.openheadunit.utils.ToastUtils
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AutoStartFragment : Fragment() {
    private lateinit var settings: Settings
    private lateinit var recyclerView: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter
    private lateinit var toolbar: MaterialToolbar
    private var saveButton: MaterialButton? = null

    private var pendingAutoStartOnBoot: Boolean? = null
    private var pendingAutoStartOnScreenOn: Boolean? = null
    private var pendingListenForUsbDevices: Boolean? = null
    private var pendingAutoStartOnUsb: Boolean? = null
    private val pendingAutoStartBtMacs = mutableSetOf<String>()
    private val pendingAutoDisconnectBtMacs = mutableSetOf<String>()
    private val pendingNativePokeBtMacs = mutableSetOf<String>()
    private var pendingNativePokeAllPaired: Boolean? = null
    private var pendingAutoDisconnectBtDelaySeconds: Int? = null
    private var pendingAutoStartOnWifi: Boolean? = null

    /** Which list the device picker edits; remembered across the permission and enable prompts. */
    private enum class BtPickerTarget { AUTO_START, AUTO_DISCONNECT, NATIVE_POKE }
    private var btPickerTarget = BtPickerTarget.AUTO_START
    private var pendingAutoStartWifiSsid: String? = null
    private var pendingReopenOnReconnection: Boolean? = null

    private var hasChanges = false
    private val SAVE_ITEM_ID = 1001

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showBluetoothDeviceSelector(btPickerTarget)
        } else {
            showBluetoothPermissionDeniedDialog()
        }
    }

    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            showBluetoothDeviceSelector(btPickerTarget)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_auto_start, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        settings = App.provide(requireContext()).settings

        pendingAutoStartOnBoot = settings.autoStartOnBoot
        pendingAutoStartOnScreenOn = settings.autoStartOnScreenOn
        pendingListenForUsbDevices = settings.listenForUsbDevices
        pendingAutoStartOnUsb = settings.autoStartOnUsb
        pendingAutoStartBtMacs.clear()
        pendingAutoStartBtMacs.addAll(settings.autoStartBluetoothDeviceMacs)
        pendingAutoDisconnectBtMacs.clear()
        pendingAutoDisconnectBtMacs.addAll(settings.autoDisconnectBluetoothDeviceMacs)
        pendingNativePokeBtMacs.clear()
        pendingNativePokeBtMacs.addAll(settings.nativePokeBtMacs)
        pendingNativePokeAllPaired = settings.nativePokeAllPairedDevices
        pendingAutoDisconnectBtDelaySeconds = settings.autoDisconnectBtDelaySeconds
        pendingAutoStartOnWifi = settings.autoStartOnWifi
        pendingAutoStartWifiSsid = settings.autoStartWifiSsid
        pendingReopenOnReconnection = settings.rawReopenOnReconnection

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        })

        toolbar = view.findViewById(R.id.toolbar)
        settingsAdapter = SettingsAdapter()
        recyclerView = view.findViewById(R.id.recycler_view)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = settingsAdapter

        updateSettingsList()
        setupToolbar()
    }

    private fun setupToolbar() {
        toolbar.setNavigationOnClickListener {
            handleBackPress()
        }

        val saveItem = toolbar.menu.add(0, SAVE_ITEM_ID, 0, getString(R.string.save))
        saveItem.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
        saveItem.setActionView(R.layout.layout_save_button)

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
        saveButton?.text = getString(R.string.save)
    }

    private fun saveSettings() {
        pendingAutoStartOnBoot?.let {
            settings.autoStartOnBoot = it
            Settings.syncAutoStartOnBootToDeviceStorage(requireContext(), it)
        }
        pendingAutoStartOnScreenOn?.let {
            settings.autoStartOnScreenOn = it
            Settings.syncAutoStartOnScreenOnToDeviceStorage(requireContext(), it)
        }
        pendingListenForUsbDevices?.let {
            settings.listenForUsbDevices = it
            Settings.syncListenForUsbDevicesToDeviceStorage(requireContext(), it)
            Settings.setUsbAttachedActivityEnabled(requireContext(), it)
        }
        pendingAutoStartOnUsb?.let {
            settings.autoStartOnUsb = it
            Settings.syncAutoStartOnUsbToDeviceStorage(requireContext(), it)
        }
        settings.autoStartBluetoothDeviceMacs = pendingAutoStartBtMacs.toSet()
        Settings.syncAutoStartBtMacsToDeviceStorage(requireContext(), pendingAutoStartBtMacs.toSet())
        settings.nativePokeBtMacs = pendingNativePokeBtMacs.toSet()
        pendingNativePokeAllPaired?.let { settings.nativePokeAllPairedDevices = it }
        if (pendingAutoStartBtMacs.isNotEmpty()) {
            val firstMac = pendingAutoStartBtMacs.first()
            val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())
            val hasBtConnectPermission = if (Build.VERSION.SDK_INT >= 31) {
                ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
            var name = ""
            if (hasBtConnectPermission && adapter?.isEnabled == true) {
                try {
                    val device = adapter.getRemoteDevice(firstMac)
                    name = device.name ?: ""
                } catch (e: Exception) {}
            }
            if (name.isEmpty()) {
                name = if (settings.autoStartBluetoothDeviceMac == firstMac) {
                    settings.autoStartBluetoothDeviceName
                } else {
                    "Unknown Device"
                }
            }
            settings.autoStartBluetoothDeviceName = name
        } else {
            settings.autoStartBluetoothDeviceName = ""
        }
        pendingAutoStartOnWifi?.let {
            settings.autoStartOnWifi = it
            Settings.syncAutoStartOnWifiToDeviceStorage(requireContext(), it)
        }
        pendingAutoStartWifiSsid?.let {
            settings.autoStartWifiSsid = it
            Settings.syncAutoStartWifiSsidToDeviceStorage(requireContext(), it)
        }
        pendingReopenOnReconnection?.let { settings.rawReopenOnReconnection = it }
        settings.autoDisconnectBluetoothDeviceMacs = pendingAutoDisconnectBtMacs.toSet()
        pendingAutoDisconnectBtDelaySeconds?.let { settings.autoDisconnectBtDelaySeconds = it }

        // Check for Overlay permission if any auto-start is configured. Auto-disconnect launches
        // nothing, so it is not part of this.
        if (launchesOnItsOwn()) {
            if (!AppPermissions.isOverlayGranted(requireContext())) {
                MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
                    .setTitle(R.string.overlay_permission_title)
                    .setMessage(R.string.overlay_permission_description)
                    .setPositiveButton(R.string.open_settings) { _, _ ->
                        try {
                            val intent = Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${requireContext().packageName}")
                            )
                            startActivity(intent)
                        } catch (e: Exception) {
                            try {
                                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
                            } catch (e2: Exception) {
                                try {
                                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = android.net.Uri.parse("package:${requireContext().packageName}")
                                    })
                                } catch (_: Exception) {}
                            }
                        }
                    }
                    .setNegativeButton(R.string.cancel, null)
                    .show()
            }
        }

        // Start the foreground service immediately when wake-detection settings
        // are enabled so it can register the dynamic SCREEN_ON receiver.
        if (settings.autoStartOnScreenOn || settings.autoStartOnBoot) {
            ContextCompat.startForegroundService(requireContext(),
                Intent(requireContext(), AapService::class.java))
        }

        AppLog.i("AutoStartFragment: saved auto-start ${pendingAutoStartBtMacs.size} device(s), " +
            "auto-disconnect ${pendingAutoDisconnectBtMacs.size}, wake ${pendingNativePokeBtMacs.size}")

        hasChanges = false
        updateSaveButtonState()

        ToastUtils.showToast(context, getString(R.string.settings_saved), Toast.LENGTH_SHORT, force = true)
    }

    private fun checkChanges() {
        hasChanges = pendingAutoStartOnBoot != settings.autoStartOnBoot ||
                pendingAutoStartOnScreenOn != settings.autoStartOnScreenOn ||
                pendingListenForUsbDevices != settings.listenForUsbDevices ||
                pendingAutoStartOnUsb != settings.autoStartOnUsb ||
                pendingAutoStartBtMacs != settings.autoStartBluetoothDeviceMacs ||
                pendingAutoDisconnectBtMacs != settings.autoDisconnectBluetoothDeviceMacs ||
                pendingNativePokeBtMacs != settings.nativePokeBtMacs ||
                pendingNativePokeAllPaired != settings.nativePokeAllPairedDevices ||
                pendingAutoDisconnectBtDelaySeconds != settings.autoDisconnectBtDelaySeconds ||
                pendingAutoStartOnWifi != settings.autoStartOnWifi ||
                pendingAutoStartWifiSsid != settings.autoStartWifiSsid ||
                pendingReopenOnReconnection != settings.rawReopenOnReconnection

        updateSaveButtonState()
    }

    private fun updateSettingsList() {
        val scrollState = recyclerView.layoutManager?.onSaveInstanceState()
        val items = mutableListOf<SettingItem>()

        items.add(SettingItem.CategoryHeader("autoStart", R.string.auto_start_settings))

        items.add(SettingItem.InfoBanner(
            stableId = "autoStartOemWarning",
            textResId = R.string.auto_start_oem_warning
        ))

        val isCarLauncherActive = settings.enableCarLauncher ||
            CarLauncherManager.isDefaultLauncher(requireContext())

        if (isCarLauncherActive) {
            items.add(SettingItem.InfoBanner(
                stableId = "carLauncherAutoStartInfo",
                textResId = R.string.car_launcher_autostart_info
            ))
        } else {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "autoStartOnBoot",
                nameResId = R.string.auto_start_on_boot_label,
                descriptionResId = R.string.auto_start_on_boot_description,
                isChecked = pendingAutoStartOnBoot!!,
                onCheckedChanged = { isChecked ->
                    pendingAutoStartOnBoot = isChecked
                    checkChanges()
                    updateSettingsList()
                }
            ))
        }

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "autoStartOnScreenOn",
            nameResId = R.string.auto_start_screen_on_label,
            descriptionResId = R.string.auto_start_screen_on_description,
            isChecked = pendingAutoStartOnScreenOn!!,
            onCheckedChanged = { isChecked ->
                pendingAutoStartOnScreenOn = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "listenForUsbDevices",
            nameResId = R.string.listen_for_usb_devices_label,
            descriptionResId = R.string.listen_for_usb_devices_description,
            isChecked = pendingListenForUsbDevices!!,
            onCheckedChanged = { isChecked ->
                pendingListenForUsbDevices = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        items.add(SettingItem.ToggleSettingEntry(
            stableId = "autoStartUsb",
            nameResId = R.string.auto_start_usb_label,
            descriptionResId = R.string.auto_start_usb_description,
            isChecked = pendingAutoStartOnUsb!!,
            onCheckedChanged = { isChecked ->
                pendingAutoStartOnUsb = isChecked
                checkChanges()
                updateSettingsList()
            }
        ))

        if (!isCarLauncherActive && pendingAutoStartOnUsb == true) {
            items.add(SettingItem.ToggleSettingEntry(
                stableId = "reopenOnReconnection",
                nameResId = R.string.reopen_on_reconnection_label,
                descriptionResId = R.string.reopen_on_reconnection_description,
                isChecked = pendingReopenOnReconnection ?: settings.rawReopenOnReconnection,
                onCheckedChanged = { isChecked ->
                    pendingReopenOnReconnection = isChecked
                    checkChanges()
                }
            ))
        }

        items.add(SettingItem.SettingEntry(
            stableId = "autoStartBt",
            nameResId = R.string.auto_start_bt_label,
            value = getBluetoothSummaryText(),
            onClick = {
                showBluetoothDeviceSelector(BtPickerTarget.AUTO_START)
            }
        ))

        if (AutoStartOverlayPolicy.showsNotice(
                overlayGranted = AppPermissions.isOverlayGranted(requireContext()),
                launches = launchesOnItsOwn(),
            )
        ) {
            items.add(SettingItem.SettingEntry(
                stableId = "autoStartOverlayMissing",
                nameResId = R.string.overlay_permission_title,
                value = getString(R.string.auto_start_overlay_missing_hint),
                onClick = { requestOverlayPermission() }
            ))
        }

        if (settings.wifiConnectionMode == WifiLauncherMode.NATIVE) {
            items.add(SettingItem.SettingEntry(
                stableId = "nativePokeBt",
                nameResId = R.string.native_poke_bt_label,
                value = BluetoothDevicePicker.summaryFor(requireContext(), pendingNativePokeBtMacs)
                    ?: getString(R.string.bt_device_not_set),
                onClick = {
                    showBluetoothDeviceSelector(BtPickerTarget.NATIVE_POKE)
                }
            ))

            // Only asked when nothing is chosen: a chosen device is never widened.
            if (pendingNativePokeBtMacs.isEmpty()) {
                items.add(SettingItem.ToggleSettingEntry(
                    stableId = "nativePokeAllPaired",
                    nameResId = R.string.native_poke_all_paired_label,
                    descriptionResId = R.string.native_poke_all_paired_description,
                    isChecked = pendingNativePokeAllPaired ?: true,
                    onCheckedChanged = { isChecked ->
                        pendingNativePokeAllPaired = isChecked
                        checkChanges()
                    }
                ))
            }
        }

        items.add(SettingItem.SettingEntry(
            stableId = "autoDisconnectBt",
            nameResId = R.string.auto_disconnect_bt_label,
            value = BluetoothDevicePicker.summaryFor(requireContext(), pendingAutoDisconnectBtMacs)
                ?: getString(R.string.bt_device_not_set),
            onClick = {
                showBluetoothDeviceSelector(BtPickerTarget.AUTO_DISCONNECT)
            }
        ))

        if (pendingAutoDisconnectBtMacs.isNotEmpty()) {
            val delaySeconds = pendingAutoDisconnectBtDelaySeconds ?: 0
            items.add(SettingItem.SettingEntry(
                stableId = "autoDisconnectBtDelay",
                nameResId = R.string.auto_disconnect_bt_delay_label,
                value = if (delaySeconds <= 0) getString(R.string.auto_disconnect_bt_delay_immediate)
                    else getString(R.string.auto_disconnect_bt_delay_value, delaySeconds),
                onClick = {
                    showAutoDisconnectDelayDialog()
                }
            ))

            items.add(SettingItem.InfoBanner(
                stableId = "autoDisconnectBtKillHint",
                textResId = R.string.auto_disconnect_bt_kill_hint
            ))
        }

        if (Build.VERSION.SDK_INT <= 32) {
            items.add(SettingItem.InfoBanner(
                stableId = "autoStartWifiWarning",
                textResId = R.string.auto_start_wifi_warning
            ))

            items.add(SettingItem.ToggleSettingEntry(
                stableId = "autoStartWifi",
                nameResId = R.string.auto_start_wifi_label,
                descriptionResId = R.string.auto_start_wifi_description,
                isChecked = pendingAutoStartOnWifi!!,
                onCheckedChanged = { isChecked ->
                    pendingAutoStartOnWifi = isChecked
                    checkChanges()
                    updateSettingsList()
                }
            ))

            if (pendingAutoStartOnWifi == true) {
                items.add(SettingItem.SettingEntry(
                    stableId = "autoStartWifiSsid",
                    nameResId = R.string.auto_start_wifi_ssid_label,
                    value = if (pendingAutoStartWifiSsid.isNullOrEmpty()) getString(R.string.wifi_ssid_not_set) else pendingAutoStartWifiSsid!!,
                    onClick = {
                        showSsidInputDialog()
                    }
                ))
            }
        }

        // Hide options that do not apply to the chosen connection types. The Bluetooth rows decide
        // when a wireless or Self Mode session starts and stops; USB has its own attach and
        // detach, so a USB-only unit does not see them.
        val usbIds = setOf("listenForUsbDevices", "autoStartUsb", "reopenOnReconnection")
        // The wake rows filter by isLikelyPhone, so on a Self-only unit they offer a picker that
        // hides the car radio the other two rows are for.
        val wifiIds = setOf(
            "autoStartWifiWarning", "autoStartWifi", "autoStartWifiSsid",
            "nativePokeBt", "nativePokeAllPaired",
        )
        val btIds = setOf("autoStartBt", "autoDisconnectBt", "autoDisconnectBtDelay", "autoDisconnectBtKillHint")
        val filtered = items.filterNot { item ->
            (item.stableId in usbIds && !settings.showsUsb()) ||
                (item.stableId in wifiIds && !settings.showsWifi()) ||
                (item.stableId in btIds && !settings.showsWifi() && !settings.showsSelf())
        }

        settingsAdapter.submitList(filtered) {
            scrollState?.let { recyclerView.layoutManager?.onRestoreInstanceState(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        // [FIX] The overlay only decides how the screen is raised - ActivityLaunchPolicy falls back
        // to a notification without it - so nothing is cleared here any more. A chosen device that
        // vanished on the next visit read as a setting that would not save. The notice row says
        // what is missing instead.
        updateSettingsList()
    }

    /** Whether anything configured here raises the screen, so the overlay permission is its own. */
    private fun launchesOnItsOwn(): Boolean = AutoStartOverlayPolicy.launches(
        onBoot = pendingAutoStartOnBoot == true,
        onScreenOn = pendingAutoStartOnScreenOn == true,
        onUsb = pendingAutoStartOnUsb == true,
        onWifi = pendingAutoStartOnWifi == true,
        btTriggerCount = pendingAutoStartBtMacs.size,
    )

    private fun requestOverlayPermission() {
        try {
            startActivity(Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${requireContext().packageName}")
            ))
        } catch (e: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION))
            } catch (e2: Exception) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.parse("package:${requireContext().packageName}")
                    })
                } catch (_: Exception) {}
            }
        }
    }

    private fun showBluetoothDeviceSelector(target: BtPickerTarget) {
        btPickerTarget = target
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            bluetoothPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
            return
        }

        val adapter = BluetoothHelper.getBluetoothAdapter(requireContext())

        if (adapter == null || !adapter.isEnabled) {
            // This unit's Bluetooth may genuinely have no android.bluetooth adapter to enable - the
            // phone pairs to the external module instead, and none of these pickers' android.bluetooth
            // device lists or MAC-targeted triggers apply there. Asking the user to turn on a radio
            // that may not exist is a dead end, not a fix.
            if (BluetoothHelper.externalBtEvidence != null) {
                ToastUtils.showToast(requireContext(), getString(R.string.bt_external_module_no_picker), Toast.LENGTH_LONG, force = true)
                return
            }
            val enableIntent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
            bluetoothEnableLauncher.launch(enableIntent)
            return
        }

        val pending = when (target) {
            BtPickerTarget.AUTO_START -> pendingAutoStartBtMacs
            BtPickerTarget.AUTO_DISCONNECT -> pendingAutoDisconnectBtMacs
            BtPickerTarget.NATIVE_POKE -> pendingNativePokeBtMacs
        }
        val titleResId = when (target) {
            BtPickerTarget.AUTO_START -> R.string.select_bt_device
            BtPickerTarget.AUTO_DISCONNECT -> R.string.select_bt_disconnect_device
            BtPickerTarget.NATIVE_POKE -> R.string.select_bt_poke_device
        }
        // Only a phone can answer Native AA, so its wake list offers phones. The other two lists
        // are not Native-AA-only: a headset is a fair auto-start or auto-disconnect trigger.
        val deviceFilter: ((android.bluetooth.BluetoothDevice) -> Boolean)? =
            if (target == BtPickerTarget.NATIVE_POKE) {
                { device ->
                    BluetoothHelper.isLikelyPhone(
                        device, settings.nativePreferredDeviceMac, settings.lastConnectedNativeMac
                    )
                }
            } else null

        BluetoothDevicePicker.show(requireContext(), titleResId, pending, deviceFilter) { chosen ->
            pending.clear()
            pending.addAll(chosen)
            checkChanges()
            updateSettingsList()
        }
    }

    private fun getBluetoothSummaryText(): String =
        BluetoothDevicePicker.summaryFor(requireContext(), pendingAutoStartBtMacs) { mac ->
            // The name saved with the list, for when Bluetooth is off or cannot be asked.
            if (settings.autoStartBluetoothDeviceMac == mac && settings.autoStartBluetoothDeviceName.isNotEmpty()) {
                settings.autoStartBluetoothDeviceName
            } else null
        } ?: getString(R.string.bt_device_not_set)

    private fun showAutoDisconnectDelayDialog() {
        val input = EditText(requireContext())
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.setText((pendingAutoDisconnectBtDelaySeconds ?: 0).toString())
        input.setSelection(input.text.length)

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.enter_auto_disconnect_bt_delay)
            .setMessage(R.string.auto_disconnect_bt_delay_hint)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                val seconds = input.text.toString().toIntOrNull() ?: 0
                pendingAutoDisconnectBtDelaySeconds = seconds.coerceIn(0, BtAutoDisconnectPolicy.MAX_DELAY_SECONDS)
                checkChanges()
                updateSettingsList()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.window?.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        dialog.show()
        input.requestFocus()
    }

    private fun showBluetoothPermissionDeniedDialog() {
        MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.bt_permission_denied_title)
            .setMessage(R.string.bt_permission_denied_message)
            .setPositiveButton(R.string.open_settings) { _, _ ->
                try {
                    val intent = Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${requireContext().packageName}")
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                    } catch (_: Exception) {}
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSsidInputDialog() {
        val input = EditText(requireContext())
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setText(pendingAutoStartWifiSsid)
        input.setSelection(input.text.length)

        val dialog = MaterialAlertDialogBuilder(requireContext(), R.style.DarkAlertDialog)
            .setTitle(R.string.enter_wifi_ssid)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                pendingAutoStartWifiSsid = input.text.toString()
                checkChanges()
                updateSettingsList()
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        dialog.window?.clearFlags(
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )
        dialog.show()
        input.requestFocus()
    }
}
