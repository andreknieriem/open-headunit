package com.andrerinas.openheadunit.app

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

/**
 * Lightweight, opt-in BLE proximity scanner owned by [com.andrerinas.openheadunit.aap.AapService].
 *
 * Uses a single long-lived [ScanSettings.SCAN_MODE_LOW_POWER] scan with a non-empty [ScanFilter]
 * (required for screen-off delivery on Android 8.1+) and delegates trigger decisions to
 * [BleProximityTriggerPolicy]. Invokes [onTriggered] on match, leaving transport and activity
 * launching to the existing `ACTION_START_WIRELESS_SCAN` pipeline.
 */
class BleProximityTrigger(
    private val context: Context,
    private val settings: Settings,
    private val environmentProvider: () -> BleProximityTriggerPolicy.Environment,
    private val onTriggered: (label: String) -> Unit,
) {
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var activeTarget: String = ""
    @Volatile
    private var lastTriggerAtMs: Long? = null

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            for (result in results) {
                handleResult(result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            AppLog.w("BleProximityTrigger: BLE scan failed (errorCode=$errorCode)")
            scanning = false
            activeTarget = ""
        }
    }

    /** Reconciles scanner state with current settings and session activity. */
    fun reconcile(sessionBusy: Boolean) {
        val enabled = settings.autoStartOnBleProximity
        val target = settings.autoStartBleTarget.trim()
        if (!BleProximityTriggerPolicy.shouldScan(enabled, target, sessionBusy)) {
            stop()
            return
        }
        if (scanning && activeTarget.equals(target, ignoreCase = true)) return
        stop()
        start(target)
    }

    @SuppressLint("MissingPermission")
    private fun start(target: String) {
        if (!hasScanPermission()) {
            AppLog.w("BleProximityTrigger: missing BLE scan permission; proximity trigger not started")
            return
        }
        val adapter: BluetoothAdapter? = BluetoothHelper.getBluetoothAdapter(context)
        if (adapter == null || !adapter.isEnabled) {
            AppLog.i("BleProximityTrigger: Bluetooth adapter unavailable or off; not scanning")
            return
        }
        val bleScanner = adapter.bluetoothLeScanner
        if (bleScanner == null) {
            AppLog.i("BleProximityTrigger: BluetoothLeScanner unavailable on this device")
            return
        }

        val filters = buildFilters(target)
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .build()

        try {
            bleScanner.startScan(filters, scanSettings, scanCallback)
            scanner = bleScanner
            scanning = true
            activeTarget = target
            AppLog.i("BleProximityTrigger: started low-power BLE proximity scan (target configured)")
        } catch (e: Exception) {
            AppLog.w("BleProximityTrigger: failed to start BLE scan: ${e.message}")
            scanning = false
            activeTarget = ""
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!scanning) return
        try {
            if (hasScanPermission()) {
                scanner?.stopScan(scanCallback)
            }
        } catch (e: Exception) {
            AppLog.d("BleProximityTrigger: stopScan error: ${e.message}")
        } finally {
            scanning = false
            activeTarget = ""
            scanner = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleResult(result: ScanResult) {
        val name = result.scanRecord?.deviceName ?: runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            ) {
                result.device?.name
            } else {
                null
            }
        }.getOrNull()
        val address = result.device?.address
        val nowMs = SystemClock.elapsedRealtime()
        val config = BleProximityTriggerPolicy.Config(
            enabled = settings.autoStartOnBleProximity,
            target = settings.autoStartBleTarget,
            cooldownMs = settings.autoStartBleCooldownSeconds.coerceIn(
                BleProximityTriggerPolicy.MIN_COOLDOWN_SECONDS,
                BleProximityTriggerPolicy.MAX_COOLDOWN_SECONDS,
            ) * 1000L,
        )
        val decision = BleProximityTriggerPolicy.evaluate(
            config = config,
            env = environmentProvider(),
            sighting = BleProximityTriggerPolicy.Sighting(
                deviceName = name,
                deviceAddress = address,
                rssi = result.rssi,
                nowMs = nowMs,
            ),
            lastTriggerAtMs = lastTriggerAtMs,
        )
        if (decision is BleProximityTriggerPolicy.Decision.Trigger) {
            lastTriggerAtMs = nowMs
            val label = name?.takeIf { it.isNotBlank() } ?: address ?: "BLE target"
            AppLog.i("BleProximityTrigger: matched nearby target (rssi=${result.rssi}dBm); firing wireless scan")
            onTriggered(label)
        }
    }

    private fun buildFilters(target: String): List<ScanFilter> {
        val builder = ScanFilter.Builder()
        if (BleProximityTriggerPolicy.isMacAddress(target)) {
            builder.setDeviceAddress(BleProximityTriggerPolicy.normalizeMac(target))
        } else {
            builder.setDeviceName(target)
        }
        return listOf(builder.build())
    }

    private fun hasScanPermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }
}
