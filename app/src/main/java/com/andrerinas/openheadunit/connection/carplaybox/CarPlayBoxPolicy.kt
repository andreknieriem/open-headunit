package com.andrerinas.openheadunit.connection.carplaybox

import com.andrerinas.openheadunit.connection.usb.UsbDeviceIdentityPolicy.Device
import java.util.Locale

/** Which USB devices are a CarPlay box, and whether an attach may open one. */
object CarPlayBoxPolicy {

    private const val CARLINKIT_VID = 0x1314
    private const val PID_1520 = 0x1520
    private const val PID_1521 = 0x1521
    private const val CLASS_MISC = 0xEF

    enum class Attach { NotABox, Disabled, AlreadyLive, UserExited, RefusedAaLive, AskPermission, Open }

    /**
     * One bare bulk pair. 1520 comes bare or in the stock FF/F0/00 shape; 1521 only in the stock
     * shape, because a bare 1521 can pose as an Android Auto phone.
     */
    fun isBox(device: Device): Boolean {
        if (device.vendorId != CARLINKIT_VID) return false
        if (device.productId != PID_1520 && device.productId != PID_1521) return false
        val only = device.interfaces.singleOrNull() ?: return false
        if (!only.hasBulkIn || !only.hasBulkOut || only.extraEndpoints != 0) return false
        val stock = device.deviceClass == CLASS_MISC &&
            only.ifaceClass == 0xFF && only.subclass == 0xF0 && only.protocol == 0x00
        val bare = device.productId == PID_1520 && device.deviceClass == 0x00 &&
            only.ifaceClass == 0xFF && only.subclass == 0xFF && only.protocol == 0x00
        return stock || bare
    }

    fun attach(
        isBox: Boolean,
        enabled: Boolean,
        boxSessionLive: Boolean,
        boxUserExited: Boolean,
        aaSessionLive: Boolean,
        hasPermission: Boolean,
    ): Attach = when {
        !isBox -> Attach.NotABox
        !enabled -> Attach.Disabled
        boxSessionLive -> Attach.AlreadyLive
        boxUserExited -> Attach.UserExited
        aaSessionLive -> Attach.RefusedAaLive
        !hasPermission -> Attach.AskPermission
        else -> Attach.Open
    }

    /** The box id the CarPlayBox log lines carry, as `1314:1520`. */
    fun label(vendorId: Int, productId: Int): String =
        String.format(Locale.US, "%04x:%04x", vendorId, productId)
}
