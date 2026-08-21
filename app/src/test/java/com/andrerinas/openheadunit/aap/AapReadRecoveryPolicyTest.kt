package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.AapReadRecoveryPolicy.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The table is spelled out per read site because the four sites used to answer the same way -
 * "skip this message and carry on" - and that answer is only correct for one of them.
 *
 * The case that matters is a short read of a body: it leaves an unknown number of bytes consumed,
 * so nothing downstream can find the next header. A reporter's capture measured exactly that,
 * followed by 69 `WRONG FLAG` and 63 `SSL Decrypt failed` lines in half a second.
 */
class AapReadRecoveryPolicyTest {

    private val headerSize = 4

    @Test
    fun `a complete read of any field continues`() {
        assertEquals(Outcome.CONTINUE, AapReadRecoveryPolicy.afterHeaderRead(headerSize, headerSize, true))
        assertEquals(Outcome.CONTINUE, AapReadRecoveryPolicy.afterFragmentTotalRead(4, 4))
        assertEquals(Outcome.CONTINUE, AapReadRecoveryPolicy.afterBodyRead(8231, 8231))
    }

    @Test
    fun `end of stream is an ordinary disconnect at every site`() {
        assertEquals(Outcome.DISCONNECT_EOF, AapReadRecoveryPolicy.afterHeaderRead(-1, headerSize, true))
        assertEquals(Outcome.DISCONNECT_EOF, AapReadRecoveryPolicy.afterFragmentTotalRead(-1, 4))
        assertEquals(Outcome.DISCONNECT_EOF, AapReadRecoveryPolicy.afterBodyRead(-1, 8231))
    }

    @Test
    fun `a header that never arrived took no bytes, so only the socket gives up`() {
        // Nothing was consumed either way - this is a liveness rule, not an alignment one. USB
        // tolerates a quiet bus; a socket silent for its whole timeout is gone.
        assertEquals(Outcome.DISCONNECT_IDLE, AapReadRecoveryPolicy.afterHeaderRead(0, headerSize, true))
        assertEquals(Outcome.CONTINUE, AapReadRecoveryPolicy.afterHeaderRead(0, headerSize, false))
    }

    @Test
    fun `a partial header cannot be resumed`() {
        assertEquals(Outcome.DISCONNECT_DESYNC, AapReadRecoveryPolicy.afterHeaderRead(1, headerSize, true))
        assertEquals(Outcome.DISCONNECT_DESYNC, AapReadRecoveryPolicy.afterHeaderRead(3, headerSize, false))
    }

    @Test
    fun `a short fragment total is fatal even though the field is four bytes`() {
        // The header before it is already consumed, so continuing would read the body as a header.
        assertEquals(Outcome.DISCONNECT_DESYNC, AapReadRecoveryPolicy.afterFragmentTotalRead(0, 4))
        assertEquals(Outcome.DISCONNECT_DESYNC, AapReadRecoveryPolicy.afterFragmentTotalRead(2, 4))
    }

    @Test
    fun `a body that timed out reports zero but consumed an unknown amount`() {
        // The reporter's line read "Expected 8231, got 0" and the stream was already unframed. The
        // zero is the timeout catch's return value, not a count of what readFully took.
        assertEquals(Outcome.DISCONNECT_DESYNC, AapReadRecoveryPolicy.afterBodyRead(0, 8231))
        assertEquals(Outcome.DISCONNECT_DESYNC, AapReadRecoveryPolicy.afterBodyRead(4096, 8231))
    }

    @Test
    fun `a declared length inside the buffer continues, including an empty body`() {
        val capacity = 4 * 1024 * 1024
        assertEquals(Outcome.CONTINUE, AapReadRecoveryPolicy.afterDeclaredLength(0, capacity))
        assertEquals(Outcome.CONTINUE, AapReadRecoveryPolicy.afterDeclaredLength(8231, capacity))
        assertEquals(Outcome.CONTINUE, AapReadRecoveryPolicy.afterDeclaredLength(capacity, capacity))
    }

    @Test
    fun `a declared length outside the buffer is a desync, not a big message`() {
        // Both shapes come from reading garbage as a header, which is what a previous skip produces.
        val capacity = 4 * 1024 * 1024
        assertEquals(Outcome.DISCONNECT_DESYNC, AapReadRecoveryPolicy.afterDeclaredLength(capacity + 1, capacity))
        assertEquals(Outcome.DISCONNECT_DESYNC, AapReadRecoveryPolicy.afterDeclaredLength(-120, capacity))
    }

    @Test
    fun `no site answers CONTINUE once bytes have gone missing`() {
        // The regression guard for the whole change: the only CONTINUE on a short read is the
        // header case that consumed nothing.
        val shortReads = listOf(
            AapReadRecoveryPolicy.afterFragmentTotalRead(1, 4),
            AapReadRecoveryPolicy.afterBodyRead(1, 2),
            AapReadRecoveryPolicy.afterHeaderRead(2, 4, true),
            AapReadRecoveryPolicy.afterHeaderRead(2, 4, false),
        )
        shortReads.forEach { assertEquals(Outcome.DISCONNECT_DESYNC, it) }
    }
}
