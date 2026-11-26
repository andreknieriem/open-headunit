package com.andrerinas.openheadunit.connection.carplaynative.usb

/**
 * Picks the iPhone's CarPlay configuration and its usbmux and NCM functions from a plain descriptor model.
 * Ids differ between iPhone models, so the choice reads interface classes. Ported from xcertplay (https://github.com/shilapi/xcertplay) `IphoneCarPlayConfiguration.kt` and `NcmFunctionDiscovery.kt`.
 */
object IphoneConfiguration {
    const val USBMUX_OUT = 0x04
    const val USBMUX_IN = 0x85
    const val NCM_DATA_ALTERNATE = 1

    private const val USBMUX_CLASS = 0xff
    private const val USBMUX_SUBCLASS = 0xfe
    private const val USBMUX_PROTOCOL = 0x02
    private const val APPLE_ETHERNET_CLASS = 0xff
    private const val APPLE_ETHERNET_SUBCLASS = 0xfd
    private const val APPLE_ETHERNET_PROTOCOL = 0x01
    private const val NCM_CONTROL_CLASS = 0x02
    private const val NCM_CONTROL_SUBCLASS = 0x0d
    private const val NCM_DATA_CLASS = 0x0a

    class Endpoint(val address: Int, val isIn: Boolean, val isBulk: Boolean, val isInterrupt: Boolean = false)

    /** One interface descriptor. An interface with alternates shows once per alternate. */
    class Interface(
        val id: Int,
        val alternate: Int,
        val interfaceClass: Int,
        val subclass: Int,
        val protocol: Int,
        val endpoints: List<Endpoint> = emptyList(),
    )

    class Configuration(val id: Int, val interfaces: List<Interface>)

    class NcmFunction(val control: Interface, val data: Interface, val statusIn: Endpoint?, val bulkIn: Endpoint, val bulkOut: Endpoint)

    /** The configuration with usbmux and CDC NCM, one that also holds Apple Ethernet first. */
    fun find(configurations: List<Configuration>): Configuration? =
        configurations.firstOrNull { usbmuxInterface(it) != null && hasCdcNcm(it) && hasAppleEthernet(it) }
            ?: configurations.firstOrNull { usbmuxInterface(it) != null && hasCdcNcm(it) }

    fun usbmuxInterface(configuration: Configuration): Interface? = configuration.interfaces.firstOrNull {
        it.interfaceClass == USBMUX_CLASS && it.subclass == USBMUX_SUBCLASS && it.protocol == USBMUX_PROTOCOL
    }

    /** Bulk OUT 0x04 and IN 0x85 first, else the only bulk endpoint of each direction. Returns (out, in). */
    fun usbmuxEndpoints(usbInterface: Interface): Pair<Endpoint, Endpoint>? {
        val bulk = usbInterface.endpoints.filter { it.isBulk }
        val out = bulk.firstOrNull { it.address == USBMUX_OUT && !it.isIn } ?: bulk.singleOrNull { !it.isIn }
        val input = bulk.firstOrNull { it.address == USBMUX_IN && it.isIn } ?: bulk.singleOrNull { it.isIn }
        return if (out != null && input != null) out to input else null
    }

    /** The CDC NCM control interface (02/0D) and the CDC data interface (class 0A), alternate 1 first. Never Apple Ethernet. */
    fun ncm(configuration: Configuration): NcmFunction? {
        val control = configuration.interfaces.firstOrNull { it.interfaceClass == NCM_CONTROL_CLASS && it.subclass == NCM_CONTROL_SUBCLASS }
            ?: return null
        val data = configuration.interfaces
            .filter { it.interfaceClass == NCM_DATA_CLASS && bulkPair(it) != null }
            .minByOrNull { if (it.alternate == NCM_DATA_ALTERNATE) 0 else 1 }
            ?: return null
        val (bulkIn, bulkOut) = bulkPair(data) ?: return null
        val status = control.endpoints.singleOrNull { it.isIn && it.isInterrupt }
        return NcmFunction(control, data, status, bulkIn, bulkOut)
    }

    fun describe(configuration: Configuration): String = configuration.interfaces.joinToString(",") {
        "${it.id}/${it.alternate}:${it.interfaceClass.toString(16)}.${it.subclass.toString(16)}.${it.protocol.toString(16)}x${it.endpoints.size}"
    }

    private fun bulkPair(usbInterface: Interface): Pair<Endpoint, Endpoint>? {
        val bulk = usbInterface.endpoints.filter { it.isBulk }
        val input = bulk.singleOrNull { it.isIn }
        val output = bulk.singleOrNull { !it.isIn }
        return if (input != null && output != null) input to output else null
    }

    private fun hasCdcNcm(configuration: Configuration) =
        configuration.interfaces.any { it.interfaceClass == NCM_CONTROL_CLASS && it.subclass == NCM_CONTROL_SUBCLASS }

    private fun hasAppleEthernet(configuration: Configuration) = configuration.interfaces.any {
        it.interfaceClass == APPLE_ETHERNET_CLASS && it.subclass == APPLE_ETHERNET_SUBCLASS && it.protocol == APPLE_ETHERNET_PROTOCOL
    }
}
