package com.andrerinas.openheadunit.connection.self.launchers

import android.content.Intent
import android.os.Build
import com.andrerinas.openheadunit.connection.self.SelfLauncher
import com.andrerinas.openheadunit.connection.self.SelfLauncherManager
import com.andrerinas.openheadunit.connection.self.SelfLauncherManager.Companion.AA_PACKAGE
import com.andrerinas.openheadunit.connection.self.SelfLauncherServices
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.BluetoothHelper

class SelfLauncherBTDiscovery(
    manager: SelfLauncherManager,
    services: SelfLauncherServices) : SelfLauncher(manager, services) {

    override val name = "Fallback: BT Discovery"

    override suspend fun run(): Boolean {
        if (Build.VERSION.SDK_INT <= 29)
            return false

        val bondedAddress = try {
            val adapter = BluetoothHelper.getBluetoothAdapter(services.aap)
            val bonded = adapter?.bondedDevices
            val connectedDevice = bonded?.firstOrNull { dev ->
                try {
                    val m = dev.javaClass.getMethod("isConnected")
                    (m.invoke(dev) as? Boolean) == true
                } catch (e: Exception) { false }
            }
            val targetDev = connectedDevice ?: bonded?.firstOrNull()
            val selfAddr: String? = try { adapter?.address } catch (se: SecurityException) { null }
            AppLog.i("SelfMode: SelfMode BT Discovery: bondedCount=${bonded?.size ?: 0}, connectedMac=${connectedDevice?.address}, selectedMac=${targetDev?.address}")
            targetDev?.address ?: if (!selfAddr.isNullOrBlank() && selfAddr != "02:00:00:00:00:00") selfAddr else null
        } catch (e: Throwable) {
            AppLog.w("SelfMode: Failed to get bonded BT device address: ${e.message}")
            null
        } ?: "00:11:22:33:44:55"

        val btReceiverIntent = Intent("com.google.android.projection.gearhead.START_WIRELESS_PROJECTION").apply {
            setClassName(
                AA_PACKAGE,
                "com.google.android.apps.auto.wireless.bluetooth.WifiBluetoothReceiver"
            )
            putExtra("DEVICE_ADDRESS", bondedAddress)
            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
        }
        services.ensureLaunchAllowed()
        services.aap.sendBroadcast(btReceiverIntent)
        AppLog.i("SelfMode: Broadcast fallback 2 (WifiBluetoothReceiver START_WIRELESS_PROJECTION with MAC $bondedAddress) sent.")
        return true
    }
}
