package com.andrerinas.openheadunit.utils

/**
 * Whether this head unit's Bluetooth is an *external module* — a separate chip on a serial link,
 * driven by the vendor's own daemon — rather than the radio behind `android.bluetooth`.
 *
 * The four signals are the ones the reference head unit software uses to decide the same thing,
 * and they were confirmed against a unit whose own vendor log reports `bttype:extra` while its
 * Bluetooth module answers to a different name (`CAR8032`) and a different MAC than the Android
 * adapter reports. On that unit the vendor software routes Android Auto entirely through the
 * external module and never opens an RFCOMM server on the Android radio.
 *
 * These signals are a marker for that class of hardware, not a diagnosis of a specific fault. What
 * is measured on the one such unit examined closely: the phone opens an RFCOMM channel to the
 * Android radio, flow control stays open, and the radio then transmits no payload at all — writes
 * succeed, `flush()` returns, the log looks healthy, and not one byte goes on the air. Whether
 * every unit carrying these signals fails the same way is not established; what is established is
 * that the vendor does not use this path on them, so it is not a path to recommend.
 */
object ExternalBtPolicy {

    /** Serial device nodes that only exist when a discrete Bluetooth module is wired to a UART. */
    private val DEVICE_NODES = listOf("/dev/rf_serial", "/dev/zj_bt_serial")

    /** Vendor properties that name the Bluetooth topology outright. */
    private val PROPERTY_KEYS = listOf("rw.zlink.bt.type", "rw.zj.bt.type")

    /** The value those properties carry on units whose Bluetooth is the external module. */
    private const val EXTERNAL_VALUE = "extra"

    /**
     * The first piece of evidence that this unit's Bluetooth is external, or null if none of the
     * signals fire.
     *
     * Returns the evidence rather than a bare boolean so the caller can name it: "external
     * Bluetooth module detected" is not actionable in a bug report, "/dev/rf_serial exists" is.
     *
     * @param nodeExists whether a given absolute path exists on the filesystem
     * @param property   the value of a given system property, or null/empty when unset
     */
    fun detect(nodeExists: (String) -> Boolean, property: (String) -> String?): String? {
        for (node in DEVICE_NODES) {
            if (nodeExists(node)) return "$node exists"
        }
        for (key in PROPERTY_KEYS) {
            val value = property(key)?.trim().orEmpty()
            if (value.equals(EXTERNAL_VALUE, ignoreCase = true)) return "$key=$value"
        }
        return null
    }

    /**
     * Holds a positive answer for good and re-reads a negative one: the vendor app sets the `rw.*`
     * properties at runtime, so a read made before it has run after a boot is not final.
     */
    class Latch(private val read: () -> String?) {
        @Volatile private var held: String? = null

        fun evidence(): String? = held ?: read()?.also { held = it }
    }

    /** Convenience over [detect] for callers that only need the yes/no. */
    fun isExternal(nodeExists: (String) -> Boolean, property: (String) -> String?): Boolean =
        detect(nodeExists, property) != null
}
