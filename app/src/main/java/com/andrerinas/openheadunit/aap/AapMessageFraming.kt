package com.andrerinas.openheadunit.aap

/**
 * What an AAP header's flags say about the plaintext behind it. These are independent bits,
 * not an enum: CONTROL and ENCRYPTED do not change the FIRST/LAST fragmentation rules.
 *
 * | encrypted payload flags | FIRST | LAST | contents |
 * |---|---|---|---|
 * | `0x0b` | 1 | 1 | complete message, beginning with its two-byte type |
 * | `0x09` | 1 | 0 | beginning of a message; header also carries its total plaintext length |
 * | `0x08` | 0 | 0 | continuation bytes, without a new type or timestamp |
 * | `0x0a` | 0 | 1 | final continuation bytes, without a new type or timestamp |
 *
 * A fragment boundary may split even the type or timestamp. FIRST identifies where that header
 * begins, not whether it is already complete; [AapMessageReassembler] validates it before dispatch.
 * Zero- and one-byte continuations are legal. Rejecting every payload shorter than two bytes once
 * dropped valid video tails, losing an entire access unit and leaving corruption until a keyframe.
 * Keep these rules shared by both readers and the reassembler rather than guessing from length.
 */
object AapMessageFraming {
    const val FLAG_BIT_FIRST = 0x01
    const val FLAG_BIT_LAST = 0x02
    /** Routes a control message independently of the channel on which it arrived. */
    const val FLAG_BIT_CONTROL = 0x04
    const val FLAG_BIT_ENCRYPTED = 0x08

    fun isLast(flags: Int): Boolean = flags and FLAG_BIT_LAST != 0
    fun carriesTotalLength(flags: Int): Boolean = carriesMessageType(flags) && !isLast(flags)

    /** The type begins here, but a short first fragment may not contain both bytes yet. */
    fun carriesMessageType(flags: Int): Boolean = flags and FLAG_BIT_FIRST != 0

    /** One permit per complete DATA message; CSD and partial fragments consume none. */
    fun completesMediaData(type: Int, flags: Int): Boolean = type == 0 && isLast(flags)
}
