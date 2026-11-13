package com.andrerinas.openheadunit.main

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.ToastUtils
import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerDiscovery
import com.andrerinas.openheadunit.utils.bluetooth.BluetoothPeerInventory
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * The multi-select list of paired devices behind every "choose a device" row. The caller owns the
 * permission and enable-Bluetooth requests, which need a fragment's launchers, and asks again after.
 */
object BluetoothDevicePicker {

    /** Alias first, hardware name in brackets when they differ, followed by MAC address. */
    @SuppressLint("MissingPermission")
    fun labelFor(device: BluetoothDevice): String {
        val hardwareName = device.name ?: "Unknown Device"
        val displayName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val alias = device.alias
            if (!alias.isNullOrEmpty() && alias != hardwareName) {
                "$alias ($hardwareName)"
            } else {
                alias ?: hardwareName
            }
        } else {
            hardwareName
        }
        return "$displayName (${device.address})"
    }

    private fun canScanNearby(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADMIN) ==
                PackageManager.PERMISSION_GRANTED &&
                (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startNearbyScan(
        context: Context,
        adapter: BluetoothAdapter?,
        statusView: TextView?,
        onDeviceFound: (BluetoothDevice) -> Unit
    ): (() -> Unit) {
        if (adapter == null || !adapter.isEnabled || !canScanNearby(context)) {
            statusView?.text = context.getString(R.string.bt_scan_done)
            return {}
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice? =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }
                        if (device != null && !device.address.isNullOrBlank()) {
                            onDeviceFound(device)
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        statusView?.text = context.getString(R.string.bt_scan_done)
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)

        try {
            if (adapter.isDiscovering) adapter.cancelDiscovery()
            if (adapter.startDiscovery()) {
                statusView?.text = context.getString(R.string.bt_scanning_nearby)
            } else {
                statusView?.text = context.getString(R.string.bt_scan_done)
            }
        } catch (_: SecurityException) {
            statusView?.text = context.getString(R.string.bt_scan_done)
        }

        return {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: IllegalArgumentException) {
            }
            try {
                if (adapter.isDiscovering) adapter.cancelDiscovery()
            } catch (_: SecurityException) {
            }
        }
    }

    /**
     * Shows the list with [selectedMacs] ticked. OK commits the working set, "Remove" the empty set,
     * Cancel nothing. Returns false when there was nothing to show. Manufacturer-only pairing
     * history gets an explanation instead of an actionable Android Bluetooth row.
     *
     * [deviceFilter] narrows the paired rows for a list only some devices belong in. Ignored when it
     * would leave the list empty, and never applied to a stored MAC's own row.
     */
    fun show(
        context: Context,
        @StringRes titleResId: Int,
        selectedMacs: Set<String>,
        deviceFilter: ((BluetoothDevice) -> Boolean)? = null,
        onCommit: (Set<String>) -> Unit
    ): Boolean {
        val discovery = BluetoothPeerDiscovery.read(context)
        val allBonded = discovery.androidDevices
        val bondedDevices = deviceFilter
            ?.let { f -> allBonded.filter { f(it) || it.address in selectedMacs } }
            ?.ifEmpty { allBonded }
            ?: allBonded
        val adapter = BluetoothHelper.getBluetoothAdapter(context)
        val canScan = adapter?.isEnabled == true && canScanNearby(context)

        // A stored address whose device is no longer paired still gets a row, by address, or the
        // only way to clear it would be to pair the device again.
        val bondedMacs = bondedDevices.map { it.address }.toSet()
        val orphanMacs = selectedMacs.filterNot { it in bondedMacs }
        if (bondedDevices.isEmpty() && orphanMacs.isEmpty() && !canScan) {
            if (showManufacturerDevices(context, discovery.inventory)) return true
            ToastUtils.showToast(context, R.string.no_paired_bt_devices, Toast.LENGTH_LONG, force = true)
            return false
        }

        val rowMacs = mutableListOf<String>().apply {
            addAll(bondedDevices.map { it.address })
            addAll(orphanMacs)
        }
        val rowLabels = mutableListOf<String>().apply {
            addAll(bondedDevices.map { labelFor(it) })
            addAll(orphanMacs.map { mac ->
                discovery.inventory.peer(mac)?.name?.let { "$it ($mac)" } ?: mac
            })
        }
        val working = selectedMacs.toMutableSet()

        val density = context.resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padV = (8 * density).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, 0)
        }
        val statusView = TextView(context).apply {
            textSize = 12f
            alpha = 0.75f
            setPadding(0, 0, 0, padV)
        }
        container.addView(statusView)

        val listAdapter = ArrayAdapter(context, android.R.layout.simple_list_item_multiple_choice, rowLabels)
        val listView = ListView(context).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            this.adapter = listAdapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (280 * density).toInt()
            )
            for (i in rowMacs.indices) {
                setItemChecked(i, selectedMacs.contains(rowMacs[i]))
            }
            setOnItemClickListener { _, _, position, _ ->
                val mac = rowMacs[position]
                if (isItemChecked(position)) working.add(mac) else working.remove(mac)
            }
        }
        container.addView(listView)

        val stopScan = startNearbyScan(context, adapter, statusView) { device ->
            val mac = device.address ?: return@startNearbyScan
            if (rowMacs.any { it.equals(mac, ignoreCase = true) }) return@startNearbyScan
            if (deviceFilter != null && !deviceFilter(device)) return@startNearbyScan
            rowMacs.add(mac)
            rowLabels.add("${labelFor(device)} · ${context.getString(R.string.bt_nearby_tag)}")
            listAdapter.notifyDataSetChanged()
            if (working.any { it.equals(mac, ignoreCase = true) }) {
                listView.setItemChecked(rowMacs.lastIndex, true)
            }
        }

        val dialog = MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(titleResId)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ -> onCommit(working.toSet()) }
            .setNeutralButton(R.string.remove) { _, _ -> onCommit(emptySet()) }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.setOnDismissListener { stopScan() }
        dialog.show()
        return true
    }

    /**
     * Single-select picker for the head unit / external Bluetooth MAC address. Lists auto-detect,
     * paired Android devices, FYT/HCT manufacturer-cached devices, and live nearby Bluetooth
     * devices with their MAC addresses, plus a checkbox for USB media audio and a manual entry
     * fallback.
     */
    fun showAddressPicker(
        context: Context,
        currentMac: String,
        detectedMac: String?,
        keepMediaAudio: Boolean,
        onSelected: (mac: String, keepMedia: Boolean) -> Unit,
        onManualEntry: (keepMedia: Boolean) -> Unit
    ) {
        val discovery = BluetoothPeerDiscovery.read(context)
        val adapter = BluetoothHelper.getBluetoothAdapter(context)

        val rowMacs = mutableListOf<String>()
        val rowLabels = mutableListOf<String>()

        val autoLabel = if (!detectedMac.isNullOrBlank()) {
            context.getString(R.string.bt_auto_detect_mac, detectedMac)
        } else {
            context.getString(R.string.bt_auto_detect_clear)
        }
        rowMacs.add("")
        rowLabels.add(autoLabel)

        for (dev in discovery.androidDevices) {
            val mac = dev.address ?: continue
            if (rowMacs.any { it.equals(mac, ignoreCase = true) }) continue
            rowMacs.add(mac)
            rowLabels.add(labelFor(dev))
        }
        for (peer in discovery.inventory.manufacturerOnly) {
            val mac = peer.address
            if (rowMacs.any { it.equals(mac, ignoreCase = true) }) continue
            rowMacs.add(mac)
            rowLabels.add(peer.name?.let { "$it ($mac)" } ?: mac)
        }
        if (currentMac.isNotBlank() && rowMacs.none { it.equals(currentMac, ignoreCase = true) }) {
            rowMacs.add(currentMac)
            rowLabels.add(currentMac)
        }

        val density = context.resources.displayMetrics.density
        val padH = (20 * density).toInt()
        val padV = (8 * density).toInt()
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padH, padV, padH, 0)
        }
        val statusView = TextView(context).apply {
            textSize = 12f
            alpha = 0.75f
            setPadding(0, 0, 0, padV)
        }
        container.addView(statusView)

        val listAdapter = ArrayAdapter(context, android.R.layout.simple_list_item_single_choice, rowLabels)
        val listView = ListView(context).apply {
            choiceMode = ListView.CHOICE_MODE_SINGLE
            this.adapter = listAdapter
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (260 * density).toInt()
            )
            val checkedIdx = rowMacs.indexOfFirst { it.equals(currentMac, ignoreCase = true) }
            if (checkedIdx >= 0) setItemChecked(checkedIdx, true)
        }
        container.addView(listView)

        val keepMediaCheckBox = CheckBox(context).apply {
            text = context.getString(R.string.usb_keep_phone_media_audio)
            isChecked = keepMediaAudio
            setPadding(0, padV, 0, padV)
        }
        container.addView(keepMediaCheckBox)

        val stopScan = startNearbyScan(context, adapter, statusView) { device ->
            val mac = device.address ?: return@startNearbyScan
            if (rowMacs.any { it.equals(mac, ignoreCase = true) }) return@startNearbyScan
            rowMacs.add(mac)
            rowLabels.add("${labelFor(device)} · ${context.getString(R.string.bt_nearby_tag)}")
            listAdapter.notifyDataSetChanged()
        }

        val dialog = MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(R.string.enter_bluetooth_mac)
            .setView(container)
            .setPositiveButton(R.string.bt_done) { _, _ ->
                val checkedPos = listView.checkedItemPosition
                val chosenMac = if (checkedPos in rowMacs.indices) rowMacs[checkedPos] else currentMac
                onSelected(chosenMac, keepMediaCheckBox.isChecked)
            }
            .setNeutralButton(R.string.bt_enter_manually) { _, _ ->
                onManualEntry(keepMediaCheckBox.isChecked)
            }
            .setNegativeButton(R.string.cancel, null)
            .create()

        listView.setOnItemClickListener { _, _, position, _ ->
            val chosenMac = rowMacs[position]
            onSelected(chosenMac, keepMediaCheckBox.isChecked)
            dialog.dismiss()
        }
        dialog.setOnDismissListener { stopScan() }
        dialog.show()
    }

    /**
     * Explain a separate manufacturer pairing store without offering an unusable Android target.
     * These rows cannot drive the existing ACL-trigger settings or an Android RFCOMM wake socket.
     * Showing the names is deliberate here, in a local picker, unlike diagnostic logs.
     */
    fun showManufacturerDevices(
        context: Context,
        inventory: BluetoothPeerInventory = BluetoothPeerDiscovery.read(context).inventory
    ): Boolean {
        val peers = inventory.manufacturerOnly
        if (peers.isEmpty()) return false
        val names = peers.joinToString("\n") { peer ->
            peer.name?.let { "$it (${peer.address})" } ?: peer.address
        }
        val androidListReadable = inventory.readings.any {
            it.source == BluetoothPeerInventory.Source.ANDROID &&
                it.status == BluetoothPeerInventory.Status.READABLE
        }
        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(R.string.bt_manufacturer_devices_title)
            .setMessage(context.getString(
                if (androidListReadable) R.string.bt_manufacturer_devices_message
                else R.string.bt_manufacturer_devices_android_unavailable, names
            ))
            .setPositiveButton(android.R.string.ok, null)
            .show()
        return true
    }

    /**
     * The row summary for [macs], or null when nothing valid is selected; unpaired devices stop
     * counting. [offlineName] answers for a single address when the adapter cannot be asked.
     */
    fun summaryFor(context: Context, macs: Set<String>, offlineName: (String) -> String? = { null }): String? {
        if (macs.isEmpty()) return null

        val adapter = BluetoothHelper.getBluetoothAdapter(context)
        val hasConnectPermission = Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        val adapterUsable = adapter?.isEnabled == true && hasConnectPermission

        val bondedAddresses = if (adapterUsable) {
            try { adapter!!.bondedDevices.map { it.address }.toSet() } catch (e: SecurityException) { null }
        } else null

        // Unpaired addresses still count. A row that reads "not set" while both stores hold a MAC
        // is how a user concludes the setting will not clear.
        val valid = if (bondedAddresses != null) {
            macs.filter { it in bondedAddresses }.ifEmpty { macs.toList() }
        } else macs.toList()
        if (valid.isEmpty()) return null
        if (valid.size > 1) return "${valid.size} ${context.getString(R.string.bt_devices_selected)}"

        val mac = valid.first()
        if (adapterUsable) {
            try {
                val device = adapter!!.getRemoteDevice(mac)
                val hardwareName = device.name ?: mac
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device.alias ?: hardwareName else hardwareName
            } catch (e: Exception) {
                return mac
            }
        }
        return offlineName(mac) ?: mac
    }
}
