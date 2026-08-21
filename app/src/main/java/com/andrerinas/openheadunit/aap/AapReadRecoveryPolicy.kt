package com.andrerinas.openheadunit.aap

/**
 * Decides, for each way a read can come up short, whether the byte stream can still be framed.
 *
 * AAP over a socket is a continuous byte stream with no resynchronisation mechanism: a message is
 * found only by having read exactly the bytes of every message before it. So the question after a
 * failed read is not "was this message important" but "do we still know where the next one starts".
 *
 * Every one of these sites used to answer "carry on" ([Outcome.CONTINUE]) - which reads as the
 * conservative choice and is the opposite. Measured on a reporter's capture: a body read that timed
 * out at 12:12:42 was followed within four seconds by 69 `WRONG FLAG` lines with channel numbers
 * like -120 and flags like 0xffffffc7, and 63 `SSL Decrypt failed / Unable to parse TLS packet
 * header`, until the socket finally closed. Nothing after that point was ever going to decode. A
 * reconnect costs seconds; carrying on costs the session.
 *
 * The sibling reader already knows this. `AapReadMultipleMessages` keeps its unread bytes in a FIFO
 * and rewinds with `mark()`/`reset()` rather than skipping, and says why in a comment: *"Do NOT
 * clear the FIFO because USB/TCP is reliable and no bytes were lost. Discarding FIFO would
 * desynchronize the stream."* The socket reader had no equivalent, and the socket path is the one
 * every wireless user is on.
 *
 * ### Why a short read means an *unknown* number of bytes were consumed
 *
 * `SocketAccessoryConnection.recvBlocking` calls `DataInputStream.readFully` and returns `0` from
 * its `SocketTimeoutException` catch. `readFully` loops internally until the buffer is full, so a
 * timeout partway through an 8 KB body has already taken an unknown number of bytes off the socket.
 * The `0` is the catch block's value, not a count. That is why [afterBodyRead] cannot subtract and
 * resume: there is no number to subtract.
 */
object AapReadRecoveryPolicy {

    /** What the reader should do next. */
    enum class Outcome {
        /** Alignment is intact and nothing was consumed that we cannot account for. Keep reading. */
        CONTINUE,

        /** The peer closed the stream. Ordinary end of session. */
        DISCONNECT_EOF,

        /**
         * Nothing arrived within the read timeout. Alignment is intact - no bytes were taken - but
         * on a socket a gap this long means the link is gone, and waiting longer only delays the
         * reconnect.
         */
        DISCONNECT_IDLE,

        /**
         * Bytes were consumed and we cannot say how many, so the next header cannot be located.
         * Only a fresh connection recovers from this.
         */
        DISCONNECT_DESYNC,
    }

    /** Return value of a read that hit end of stream. */
    const val END_OF_STREAM = -1

    /** Return value of a read that timed out having taken nothing. */
    const val NOTHING_READ = 0

    /**
     * The fixed-size message header.
     *
     * [isSocketTransport] separates the two transports' idle rules rather than their alignment
     * rules: USB tolerates a quiet bus and keeps polling, a socket does not.
     */
    fun afterHeaderRead(bytesRead: Int, expected: Int, isSocketTransport: Boolean): Outcome = when {
        bytesRead == expected -> Outcome.CONTINUE
        bytesRead == END_OF_STREAM -> Outcome.DISCONNECT_EOF
        bytesRead == NOTHING_READ -> if (isSocketTransport) Outcome.DISCONNECT_IDLE else Outcome.CONTINUE
        else -> Outcome.DISCONNECT_DESYNC
    }

    /**
     * The 4-byte total that accompanies a first fragment.
     *
     * Short here is as bad as short anywhere else even though the field is tiny: the header before
     * it is already gone, so continuing would read the body as though it were the next header.
     */
    fun afterFragmentTotalRead(bytesRead: Int, expected: Int): Outcome = when {
        bytesRead == expected -> Outcome.CONTINUE
        bytesRead == END_OF_STREAM -> Outcome.DISCONNECT_EOF
        else -> Outcome.DISCONNECT_DESYNC
    }

    /**
     * The length the header declared, before any of the body has been read.
     *
     * A length outside the buffer is nearly always a symptom of a desync that already happened -
     * garbage bytes read as a header - rather than a real message we cannot hold. Either way the
     * body cannot be consumed, so the alignment is lost from here on and skipping compounds it.
     */
    fun afterDeclaredLength(declaredLength: Int, bufferCapacity: Int): Outcome =
        if (declaredLength in 0..bufferCapacity) Outcome.CONTINUE else Outcome.DISCONNECT_DESYNC

    /** The message body. See the class KDoc for why a short read here is unrecoverable. */
    fun afterBodyRead(bytesRead: Int, expected: Int): Outcome = when {
        bytesRead == expected -> Outcome.CONTINUE
        bytesRead == END_OF_STREAM -> Outcome.DISCONNECT_EOF
        else -> Outcome.DISCONNECT_DESYNC
    }
}
