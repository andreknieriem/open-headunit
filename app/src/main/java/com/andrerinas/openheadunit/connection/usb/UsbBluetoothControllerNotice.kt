package com.andrerinas.openheadunit.connection.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.usb.UsbBluetoothControllerPolicy.RadioFacts
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalBtTransportPolicy.Route
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeAaHandshakeManager
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.esp32.Esp32RelaySession
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.ZbtDiscovery
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.utils.ConnectionIssue
import com.andrerinas.openheadunit.utils.ConnectionIssues
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Logs each USB Bluetooth controller once per plug-in and keeps the
 * [ConnectionIssue.USB_BLUETOOTH_NOT_USABLE] record in step with the bus and the radio.
 */
object UsbBluetoothControllerNotice {

    private val sightings = UsbBluetoothControllerPolicy.Sightings()
    private val _changed = MutableStateFlow(0)

    /** Moves when the record changes, so the main screen redraws its banner while it is visible. */
    val changed: StateFlow<Int> = _changed

    /** [gone] is a device that just detached and may still be listed. */
    fun review(context: Context, gone: UsbDevice? = null) {
        try {
            val usbManager = UsbDeviceCompat.usbManager(context.applicationContext ?: context) ?: return
            val devices = usbManager.deviceList.values.filter { it.deviceName != gone?.deviceName }
            val present = devices.map { it.deviceName }
            var controllerPresent = false
            for (device in devices) {
                val controller = UsbBluetoothControllerPolicy.classify(UsbDeviceCompat.describe(device)) ?: continue
                controllerPresent = true
                if (sightings.firstSighting(device.deviceName, present)) {
                    AppLog.i(UsbBluetoothControllerPolicy.attachLine(controller))
                }
            }
            settle(context, UsbBluetoothControllerPolicy.noticeStanding(controllerPresent, wanted(context, controllerPresent)))
        } catch (e: Exception) {
            AppLog.w("UsbBluetoothControllerNotice: review failed, leaving the record: ${e.message}")
        }
    }

    internal fun settle(context: Context, standing: Boolean?) {
        if (ConnectionIssues.settle(context, ConnectionIssue.USB_BLUETOOTH_NOT_USABLE, standing)) {
            _changed.value = _changed.value + 1
        }
    }

    /** Safe before unlock: it touches no settings. */
    fun noteDetach(path: String) = sightings.detached(path)

    private fun wanted(context: Context, controllerPresent: Boolean): Boolean? {
        if (!controllerPresent) return false
        val mode = App.provide(context).settings.wifiConnectionMode.id
        // The route inputs matter only in mode 3, so other modes skip reading them.
        val facts = if (mode != NATIVE_AA_MODE) {
            RadioFacts(mode, null, Route.NORMAL, relaySaved = false, hasAdapter = false)
        } else {
            RadioFacts(
                mode, NativeAaHandshakeManager.moduleRefusal(context),
                // The answer moduleRefusal read: an expired one here reads as NORMAL on a working module.
                NativeAaHandshakeManager.transportRoute(context, ZbtDiscovery.lastKnownSnapshot()),
                Esp32RelaySession.hasSavedEsp32Relay(context),
                BluetoothHelper.getBluetoothAdapter(context) != null,
            )
        }
        return UsbBluetoothControllerPolicy.noticeWanted(facts)
    }

    private const val NATIVE_AA_MODE = 3
}
