package com.andrerinas.openheadunit.connection.carplaynative

/**
 * Our NCM host MAC, as the iPhone must see it in 0x4301. The descriptor names it. Without one the session
 * uses a fixed locally administered address, as xcertplay and DiPlay do.
 */
object HostMacPolicy {
    val FALLBACK: ByteArray get() = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x02)

    class Resolved(val mac: ByteArray, val fromDescriptor: Boolean)

    fun resolve(descriptorMac: ByteArray?): Resolved =
        if (descriptorMac != null && descriptorMac.size == 6) Resolved(descriptorMac.copyOf(), true) else Resolved(FALLBACK, false)

    fun text(mac: ByteArray): String = mac.joinToString(":") { "%02x".format(it.toInt() and 0xff) }
}
