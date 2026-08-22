package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.AapMessageFraming.FLAG_BIT_ENCRYPTED
import com.andrerinas.openheadunit.aap.AapMessageFraming.FLAG_BIT_FIRST
import com.andrerinas.openheadunit.aap.AapMessageFraming.FLAG_BIT_LAST
import com.andrerinas.openheadunit.aap.AapMessageFraming.carriesMessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flag byte decides whether there is a message type to read, and getting it wrong costs a whole
 * video frame - see [AapMessageFraming] for the measurement. These are the five values the protocol
 * actually puts on the wire, so this test is the table rather than a sample of it.
 */
class AapMessageFramingTest {

    // The four payload flags, spelled as literals on purpose: VideoFragmentAssembler writes them as
    // decimal 11/9/8/10 and AapMediaPlayback as 0x09/0x08/0x0A, and this is the one place both
    // spellings have to agree with the bits.
    private val complete = 0x0b
    private val firstFragment = 0x09
    private val middleFragment = 0x08
    private val lastFragment = 0x0a

    /** What AapMessage.flags() emits for a control-type message on a non-control channel. */
    private val controlOnPayloadChannel = 0x0f

    @Test
    fun `a message that begins an AAP message carries a type`() {
        assertTrue("complete message", carriesMessageType(complete))
        assertTrue("first fragment", carriesMessageType(firstFragment))
        assertTrue("control type on a payload channel", carriesMessageType(controlOnPayloadChannel))
    }

    @Test
    fun `a continuation fragment does not`() {
        // The whole point. These two are raw payload from byte zero, which is why AapVideo appends
        // them from offset 0 and why a one-byte last fragment is legal rather than malformed.
        assertFalse("middle fragment", carriesMessageType(middleFragment))
        assertFalse("last fragment", carriesMessageType(lastFragment))
    }

    @Test
    fun `the bits are what the four flag values are made of`() {
        // Guards the constants against the literals rather than restating them: if someone renumbers
        // a bit, the flag values stop decomposing and this fails before any behaviour changes.
        assertEquals(FLAG_BIT_ENCRYPTED or FLAG_BIT_FIRST or FLAG_BIT_LAST, complete)
        assertEquals(FLAG_BIT_ENCRYPTED or FLAG_BIT_FIRST, firstFragment)
        assertEquals(FLAG_BIT_ENCRYPTED, middleFragment)
        assertEquals(FLAG_BIT_ENCRYPTED or FLAG_BIT_LAST, lastFragment)
    }

    @Test
    fun `the answer comes from bit zero alone`() {
        // Not from a value match. A flag byte with bits we have never seen set still has to answer
        // correctly, because the guard in AapMessageIncoming.decrypt runs before anything has
        // established that the stream is sane.
        for (highBits in 0..0x0f) {
            val extra = highBits shl 4
            assertTrue("0x%02x".format(extra or FLAG_BIT_FIRST), carriesMessageType(extra or FLAG_BIT_FIRST))
            assertFalse("0x%02x".format(extra or FLAG_BIT_LAST), carriesMessageType(extra or FLAG_BIT_LAST))
        }
    }
}
