package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalBtTransportPolicy.Route

/**
 * Names a USB Bluetooth controller (HCI class E0/01/01) and decides when its presence needs a notice.
 * The app cannot yet use one as the Native AA radio, so this only reports it.
 */
object UsbBluetoothControllerPolicy {

    enum class Family { BROADCOM, REALTEK, CSR, QUALCOMM, INTEL, MEDIATEK, UNKNOWN }

    /** [needsFirmware] is null for [Family.UNKNOWN]: no guess. */
    data class Controller(val vendorId: Int, val productId: Int, val family: Family, val needsFirmware: Boolean?)

    /**
     * What decides the notice. [moduleRefusal] is null until the daemon has answered once.
     * [hasAdapter] is whether Android exposes a Bluetooth radio.
     */
    data class RadioFacts(
        val wirelessMode: Int,
        val moduleRefusal: Boolean?,
        val route: Route,
        val relaySaved: Boolean,
        val hasAdapter: Boolean,
    )

    private const val NATIVE_AA_MODE = 3

    private val FAMILY_BY_VENDOR = mapOf(
        0x0A5C to Family.BROADCOM,
        0x0BDA to Family.REALTEK,
        0x0A12 to Family.CSR,
        0x0CF3 to Family.QUALCOMM,
        0x8087 to Family.INTEL,
        0x0E8D to Family.MEDIATEK,
    )

    /** TP-Link's vendor ID is not a chip maker's, so the UB500 is keyed by product. */
    private val FAMILY_BY_PRODUCT = mapOf((0x2357 to 0x0604) to Family.REALTEK)

    // CSR clones usually run from ROM; the rest need a patch from the host.
    private val NEEDS_FIRMWARE = mapOf(
        Family.BROADCOM to true, Family.REALTEK to true, Family.QUALCOMM to true,
        Family.INTEL to true, Family.MEDIATEK to true, Family.CSR to false,
    )

    fun classify(device: UsbDeviceIdentityPolicy.Device): Controller? {
        val deviceLevel = isHci(device.deviceClass, device.deviceSubclass, device.deviceProtocol)
        if (!deviceLevel && device.interfaces.none { isHci(it.ifaceClass, it.subclass, it.protocol) }) return null
        val family = FAMILY_BY_PRODUCT[device.vendorId to device.productId]
            ?: FAMILY_BY_VENDOR[device.vendorId] ?: Family.UNKNOWN
        return Controller(device.vendorId, device.productId, family, NEEDS_FIRMWARE[family])
    }

    fun attachLine(c: Controller): String =
        "USB: Bluetooth controller %04x:%04x %s seen, not usable as the Native AA radio"
            .format(java.util.Locale.US, c.vendorId, c.productId, c.family.name)

    /** True raises the record, false clears it, null leaves it (the daemon has not answered yet). */
    fun noticeWanted(f: RadioFacts): Boolean? = when {
        f.wirelessMode != NATIVE_AA_MODE -> false
        f.relaySaved -> false
        f.moduleRefusal == null -> null
        f.moduleRefusal -> true
        // Only the Android route uses Android's radio; a module route carries the handshake itself.
        f.route == Route.NORMAL && !f.hasAdapter -> true
        else -> false
    }

    fun noticeStanding(controllerPresent: Boolean, wanted: Boolean?): Boolean? =
        if (controllerPresent) wanted else false

    /** Remembers which bus paths already logged their attach line. */
    class Sightings {
        private val seen = HashSet<String>()

        @Synchronized
        fun firstSighting(path: String, presentPaths: Collection<String>): Boolean {
            seen.retainAll(presentPaths.toSet())
            return seen.add(path)
        }

        @Synchronized
        fun detached(path: String) {
            seen.remove(path)
        }
    }

    private fun isHci(cls: Int, sub: Int, proto: Int) = cls == 0xE0 && sub == 0x01 && proto == 0x01
}
