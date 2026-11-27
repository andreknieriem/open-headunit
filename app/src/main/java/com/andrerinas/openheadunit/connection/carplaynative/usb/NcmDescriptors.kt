package com.andrerinas.openheadunit.connection.carplaynative.usb

/**
 * Reads our host MAC out of the raw USB descriptors: the CDC Ethernet functional descriptor of the control interface names a string,
 * and the string holds the MAC as twelve hex digits. Also the retry rule for a claim that a kernel driver can win back.
 * Follows xcertplay (https://github.com/shilapi/xcertplay) `transport/NcmUsbBridge.kt` `readNcmHostMac` and `retryUsbSetup`.
 */
object NcmDescriptors {
    const val STRING_DESCRIPTOR_TYPE = 0x03
    const val ENGLISH_US = 0x0409
    const val SETUP_ATTEMPTS = 5
    const val SETUP_PAUSE_MS = 100L

    private const val INTERFACE_DESCRIPTOR_TYPE = 0x04
    private const val CDC_FUNCTIONAL_TYPE = 0x24
    private const val CDC_ETHERNET_SUBTYPE = 0x0f

    /** The string index the control interface's CDC Ethernet descriptor names, or null. */
    fun macStringIndex(raw: ByteArray, controlInterfaceId: Int): Int? {
        var offset = 0
        var current = -1
        while (offset + 2 <= raw.size) {
            val length = raw[offset].toInt() and 0xff
            val type = raw[offset + 1].toInt() and 0xff
            if (length < 2 || offset + length > raw.size) return null
            if (type == INTERFACE_DESCRIPTOR_TYPE && length >= 9) {
                current = raw[offset + 2].toInt() and 0xff
            } else if (type == CDC_FUNCTIONAL_TYPE && length >= 4 && current == controlInterfaceId &&
                (raw[offset + 2].toInt() and 0xff) == CDC_ETHERNET_SUBTYPE
            ) {
                return (raw[offset + 3].toInt() and 0xff).takeIf { it != 0 }
            }
            offset += length
        }
        return null
    }

    /** The MAC in a USB string descriptor (UTF-16LE hex digits), or null unless it holds exactly twelve digits. */
    fun parseMac(descriptor: ByteArray, length: Int): ByteArray? {
        if (length < 4 || (descriptor[1].toInt() and 0xff) != STRING_DESCRIPTOR_TYPE) return null
        val declared = (descriptor[0].toInt() and 0xff).coerceAtMost(length)
        if (declared < 4) return null
        val text = String(descriptor, 2, declared - 2, Charsets.UTF_16LE)
        val hex = text.filter { Character.digit(it, 16) >= 0 }
        if (hex.length != 12) return null
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** Runs [action] up to [attempts] times with [pause] between, and returns the attempt that worked, or null. */
    fun retry(attempts: Int = SETUP_ATTEMPTS, pause: (Long) -> Unit, action: () -> Boolean): Int? {
        for (attempt in 1..attempts) {
            if (action()) return attempt
            if (attempt < attempts) pause(SETUP_PAUSE_MS)
        }
        return null
    }
}
