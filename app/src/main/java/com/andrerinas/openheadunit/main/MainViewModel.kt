package com.andrerinas.openheadunit.main

import android.app.Application
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.usb.UsbDeviceCompat
import com.andrerinas.openheadunit.connection.usb.UsbDeviceDiagnostics
import com.andrerinas.openheadunit.connection.usb.UsbReceiver
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

class MainViewModel(application: Application): AndroidViewModel(application), UsbReceiver.Listener {

    val usbDevices = MutableLiveData<List<UsbDeviceCompat>>()

    /**
     * Set each time the Home button lands on this app as the launcher, so the home goes back to
     * its first page the way any launcher does. Kept, not consumed: a home view created later
     * starts on the first page anyway, so seeing an old value again changes nothing.
     */
    val homePressed = MutableLiveData<Long>()

    /**
     * True while the home is on screen. Its main button and its connection tiles tell the
     * bring-up there, so the status pill would only cover the favorites row with the same words.
     */
    val homeShowsBringUp = MutableLiveData(false)

    private val app: App
        get() = getApplication()
    private val settings = Settings(application)
    private val usbReceiver = UsbReceiver(this)
    private var isRegistered = false

    fun register() {
        // The model outlives a recreated MainActivity (a rotation, for one), and a second
        // registration would deliver every USB event twice.
        if (!isRegistered) {
            ContextCompat.registerReceiver(app, usbReceiver, UsbReceiver.createFilter(), ContextCompat.RECEIVER_NOT_EXPORTED)
            ContextCompat.registerReceiver(app, usbReceiver, UsbReceiver.createPermissionResultFilter(), ContextCompat.RECEIVER_NOT_EXPORTED)
            isRegistered = true
        }
        usbDevices.value = createDeviceList(settings.allowedDevices)
    }

    // Only MainActivity registers. SettingsActivity shares the nav graph, so it briefly hosts a
    // HomeFragment that asks for this model too, and unregistering there throws.
    override fun onCleared() {
        if (isRegistered) app.unregisterReceiver(usbReceiver)
        super.onCleared()
    }

    override fun onUsbDetach(device: android.hardware.usb.UsbDevice) {
        usbDevices.value = createDeviceList(settings.allowedDevices)
    }

    override fun onUsbAccessoryDetach() {
        usbDevices.value = createDeviceList(settings.allowedDevices)
    }

    override fun onUsbAttach(device: android.hardware.usb.UsbDevice) {
        usbDevices.value = createDeviceList(settings.allowedDevices)
    }

    override fun onUsbPermission(granted: Boolean, connect: Boolean, device: UsbDevice, ticket: Long, token: String?) {
        usbDevices.value = createDeviceList(settings.allowedDevices)
    }

    private fun createDeviceList(allowDevices: Set<String>): List<UsbDeviceCompat> {
        val manager = UsbDeviceCompat.usbManager(app) ?: run {
            AppLog.w("MainViewModel: USB service is unavailable; USB device list is empty")
            return emptyList()
        }
        UsbDeviceDiagnostics.logDeviceList(app, manager, "USB list")
        val devices = manager.deviceList.values.map { UsbDeviceCompat(it) }
        return filterAndSort(devices, allowDevices)
    }

    companion object {
        /**
         * During AOA mode switching the phone briefly appears as two separate USB entries:
         * normal mode (e.g. "Samsung Galaxy") and accessory mode ("18D1:2D00").
         * When both are present we hide the accessory-mode entry — it requires no user
         * action and causes the duplicate-device confusion reported as Bug 2.
         */
        @JvmStatic
        internal fun shouldIncludeDevice(isInAccessoryMode: Boolean, anyNonAccessoryPresent: Boolean): Boolean =
            !(isInAccessoryMode && anyNonAccessoryPresent)

        /** Removes duplicate names — same phone can get different kernel paths on each reconnect. */
        @JvmStatic
        internal fun deduplicateNames(names: List<String>): List<String> = names.distinct()

        @JvmStatic
        internal fun filterAndSort(devices: List<UsbDeviceCompat>, allowDevices: Set<String>): List<UsbDeviceCompat> {
            val anyNonAccessory = devices.any { !it.isInAccessoryMode }
            return devices
                .filter { shouldIncludeDevice(it.isInAccessoryMode, anyNonAccessory) }
                .distinctBy { it.uniqueName }
                                .sortedWith(Comparator { lhs, rhs ->
                    if (lhs.isInAccessoryMode != rhs.isInAccessoryMode) {
                        return@Comparator if (lhs.isInAccessoryMode) -1 else 1
                    }
                    val lhsAllowed = allowDevices.contains(lhs.uniqueName)
                    val rhsAllowed = allowDevices.contains(rhs.uniqueName)
                    if (lhsAllowed != rhsAllowed) {
                        return@Comparator if (lhsAllowed) -1 else 1
                    }
                    lhs.uniqueName.compareTo(rhs.uniqueName)
                })
        }
    }
}
