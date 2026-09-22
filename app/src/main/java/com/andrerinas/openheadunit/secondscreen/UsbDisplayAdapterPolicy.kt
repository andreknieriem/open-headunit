package com.andrerinas.openheadunit.secondscreen

import com.andrerinas.openheadunit.secondscreen.usbdisplay.UsbDisplayProtocol

/**
 * Recognises a USB device that is a second screen rather than a phone.
 *
 * The phone path must never try an accessory switch on one, so its identity policy asks here first.
 */
object UsbDisplayAdapterPolicy {

    enum class Kind { MS912X, USB_DISPLAY }

    data class InterfaceId(val ifaceClass: Int, val subclass: Int, val protocol: Int, val name: String? = null)

    /** MacroSilicon MS9120/MS912x and MS9132, as matched by moriceh/open-headunit. */
    private val MS912X_IDS = setOf(0x534D to 0x6021, 0x534D to 0x0821, 0x345F to 0x9132)

    fun kindOf(vendorId: Int, productId: Int, interfaces: List<InterfaceId>): Kind? = when {
        (vendorId to productId) in MS912X_IDS -> Kind.MS912X
        interfaces.any { UsbDisplayProtocol.isDisplayInterface(it.ifaceClass, it.subclass, it.protocol) } -> Kind.USB_DISPLAY
        else -> null
    }
}
