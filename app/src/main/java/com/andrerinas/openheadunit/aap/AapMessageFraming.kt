package com.andrerinas.openheadunit.aap

/** AAP flags are independent bits. Continuations contain neither a new type nor a timestamp. */
object AapMessageFraming {
    const val FLAG_BIT_FIRST = 0x01
    const val FLAG_BIT_LAST = 0x02
    const val FLAG_BIT_CONTROL = 0x04
    const val FLAG_BIT_ENCRYPTED = 0x08

    fun isLast(flags: Int): Boolean = flags and FLAG_BIT_LAST != 0
    fun carriesTotalLength(flags: Int): Boolean = carriesMessageType(flags) && !isLast(flags)

    /** The type begins here, but a short first fragment may not contain both bytes yet. */
    fun carriesMessageType(flags: Int): Boolean = flags and FLAG_BIT_FIRST != 0

    /** One permit per complete DATA message; CSD and partial fragments consume none. */
    fun completesMediaData(type: Int, flags: Int): Boolean = type == 0 && isLast(flags)
}
