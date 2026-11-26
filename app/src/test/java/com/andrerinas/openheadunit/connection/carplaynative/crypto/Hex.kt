package com.andrerinas.openheadunit.connection.carplaynative.crypto

internal fun hex(s: String): ByteArray {
    val t = s.filter { !it.isWhitespace() }
    return ByteArray(t.length / 2) { t.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
