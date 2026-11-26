package com.andrerinas.openheadunit.connection.carplaynative.codec

/** Raised when a plist, TLV8 or RTSP input is malformed. The message says where. */
class PlistException(message: String) : Exception(message)

/**
 * The value set both plist codecs read and write. A dictionary keeps its key order.
 * An [Integer] holds the raw 64 bits, so a high-bit identifier survives a round trip as unsigned.
 * Follows LIVI (https://github.com/f-io/LIVI), crate livi-cp, `bplist.rs` `Value`.
 */
sealed class PlistValue {
    data class Bool(val value: Boolean) : PlistValue()
    data class Integer(val value: Long) : PlistValue()
    data class Real(val value: Double) : PlistValue()
    data class Text(val value: String) : PlistValue()
    data class Items(val items: List<PlistValue>) : PlistValue()
    data class Dict(val entries: List<Pair<String, PlistValue>>) : PlistValue() {
        operator fun get(key: String): PlistValue? = entries.firstOrNull { it.first == key }?.second
    }

    class Data(val bytes: ByteArray) : PlistValue() {
        override fun equals(other: Any?): Boolean = other is Data && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = bytes.contentHashCode()
        override fun toString(): String = "Data(${bytes.size} bytes)"
    }

    val asLong: Long? get() = (this as? Integer)?.value
    val asText: String? get() = (this as? Text)?.value
    val asBytes: ByteArray? get() = (this as? Data)?.bytes
    val asBool: Boolean? get() = (this as? Bool)?.value
    val asItems: List<PlistValue>? get() = (this as? Items)?.items

    companion object {
        const val MAX_DEPTH = 64

        fun dict(vararg entries: Pair<String, PlistValue>): Dict = Dict(entries.toList())
    }
}
