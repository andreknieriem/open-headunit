package com.andrerinas.openheadunit.main

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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

    /** Alias first, hardware name in brackets when they differ. */
    fun labelFor(device: BluetoothDevice): String {
        val hardwareName = device.name ?: "Unknown Device"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val alias = device.alias
            if (!alias.isNullOrEmpty() && alias != hardwareName) {
                return "$alias ($hardwareName)"
            }
            return alias ?: hardwareName
        }
        return hardwareName
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
        // A stored address whose device is no longer paired still gets a row, by address, or the
        // only way to clear it would be to pair the device again.
        val bondedMacs = bondedDevices.map { it.address }.toSet()
        val orphanMacs = selectedMacs.filterNot { it in bondedMacs }
        if (bondedDevices.isEmpty() && orphanMacs.isEmpty()) {
            if (showManufacturerDevices(context, discovery.inventory)) return true
            ToastUtils.showToast(context, R.string.no_paired_bt_devices, Toast.LENGTH_LONG, force = true)
            return false
        }

        val rowMacs = bondedDevices.map { it.address } + orphanMacs
        val deviceLabels = (bondedDevices.map { labelFor(it) } + orphanMacs.map { mac ->
            discovery.inventory.peer(mac)?.name?.let { "$it ($mac)" } ?: mac
        }).toTypedArray()
        val checkedItems = rowMacs.map { selectedMacs.contains(it) }.toBooleanArray()
        val working = selectedMacs.toMutableSet()

        MaterialAlertDialogBuilder(context, R.style.DarkAlertDialog)
            .setTitle(titleResId)
            .setMultiChoiceItems(deviceLabels, checkedItems) { _, which, isChecked ->
                val mac = rowMacs[which]
                if (isChecked) working.add(mac) else working.remove(mac)
            }
            .setPositiveButton(android.R.string.ok) { _, _ -> onCommit(working.toSet()) }
            .setNeutralButton(R.string.remove) { _, _ -> onCommit(emptySet()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return true
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
