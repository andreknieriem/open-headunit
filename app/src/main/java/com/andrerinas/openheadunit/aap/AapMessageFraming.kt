package com.andrerinas.openheadunit.aap

/**
 * What an AAP message's flag byte says about the bytes behind it.
 *
 * The flag byte is a bitfield, not an enum, and the four values the payload channels actually use
 * are the two low bits plus the encryption bit that [AapMessageIncoming.decrypt] already tests:
 *
 * | flags | bit 0 | bit 1 | meaning | 2-byte message type at offset 0 |
 * |---|---|---|---|---|
 * | `0x0b` (11) | 1 | 1 | complete message | yes |
 * | `0x09` (9) | 1 | 0 | first fragment | yes |
 * | `0x08` (8) | 0 | 0 | middle fragment | **no** |
 * | `0x0a` (10) | 0 | 1 | last fragment | **no** |
 *
 * Bit 0 means "this message begins an AAP message", so it is the bit that says whether there is a
 * message type to read. A continuation fragment is raw payload from its first byte, and **any**
 * length is legal there, including one byte and zero.
 *
 * ### Why this exists as its own object
 *
 * The rule was already implemented twice, from opposite ends of the app, and written down nowhere.
 * [AapVideo.process] copies `Append` and `AppendAndDecode` fragments from offset 0 while
 * `BeginAssembly` and `DecodeWhole` start at offset 10 or 2; `AapMediaPlayback.processMetadataPacket`
 * does the same on MUSIC_PLAYBACK, taking the first fragment from `dataOffset` and the middle and
 * last from 0. Both are right. The place that got it wrong was the one that never had the rule in
 * front of it.
 *
 * ### The bug this was extracted for
 *
 * `AapMessageIncoming.decrypt` rejected every payload shorter than two bytes, on every channel and
 * every flag, because it wanted to read a message type at offset 0. The check began as a real
 * crash fix - the SSL layer then returned an exactly sized per-message array, so reading two bytes
 * out of a one-byte payload threw - but the remedy was wrong rather than the diagnosis: it dropped
 * the message instead of declining to read a type that was never there. On the video channel,
 * video fragments are 16KB, so any access unit whose size mod 16384 is 1 ends in a legal one-byte
 * tail; dropping it costs the whole access unit, the reassembler reports `TRUNCATED_PREVIOUS`, and
 * the picture drifts until a keyframe repairs it. That signature - short-payload line, truncation,
 * and a silent framing audit - is how the fault was identified in the field.
 *
 * The crash the old guard prevented can no longer happen. `AapSslContext.plaintextBuffer` is sized
 * to the session's `rxBuffer` capacity and reused per message, so offsets 0 and 1 are in bounds
 * whatever the payload length is.
 *
 * Pure: no clock, no logging, no Android. Its test is the record of the table above.
 */
object AapMessageFraming {

    /** Set when this message begins an AAP message, so a 2-byte type follows at offset 0. */
    const val FLAG_BIT_FIRST = 0x01

    /** Set when this message ends one. Both bits set is a message that was never fragmented. */
    const val FLAG_BIT_LAST = 0x02

    /**
     * Set on every message that arrives encrypted, which after the handshake is all of them.
     * Named here so the table above is complete; [AapMessageIncoming.decrypt] owns the check.
     */
    const val FLAG_BIT_ENCRYPTED = 0x08

    /**
     * Whether [flags] describes a message carrying the 2-byte type at offset 0.
     *
     * False for the middle and last fragments of a run, whose payload starts at byte zero. Callers
     * that read a type, or that guard a read of one, have to ask this first.
     */
    fun carriesMessageType(flags: Int): Boolean = flags and FLAG_BIT_FIRST != 0
}
