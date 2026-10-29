package com.andrerinas.openheadunit.utils

/** Checked arithmetic without java.lang.Math's API-24-only exact methods (GitHub supports API16). */
internal object CheckedLong {
    fun add(a: Long, b: Long): Long? {
        val result = a + b
        return if (((a xor result) and (b xor result)) < 0) null else result
    }
    fun scale(value: Long, positiveFactor: Long): Long? {
        require(positiveFactor > 0)
        if (value > Long.MAX_VALUE / positiveFactor || value < Long.MIN_VALUE / positiveFactor) return null
        return value * positiveFactor
    }
}
