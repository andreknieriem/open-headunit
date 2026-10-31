package com.andrerinas.openheadunit.connection.usb

/**
 * "Open the app when plugged in" acts only while a plug-in connects and plug-ins are detected.
 * Only automatic readers ask this; a user's press and the boot start do not.
 */
object UsbAutoStartPolicy {
    fun effective(autoStartOnUsb: Boolean, connectOnPlugIn: Boolean, listenForUsbDevices: Boolean): Boolean =
        autoStartOnUsb && connectOnPlugIn && listenForUsbDevices
}
