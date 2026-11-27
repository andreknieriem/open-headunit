package com.andrerinas.openheadunit.connection.carplaynative.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AirPlayIdentityTest {

    @Test
    fun encodeAndDecodeKeepTheKeyAndTheId() {
        val identity = AirPlayIdentity.generate()
        val back = AirPlayIdentity.decode(identity.encode())!!
        assertArrayEquals(identity.seed, back.seed)
        assertArrayEquals(identity.publicKey, back.publicKey)
        assertEquals(identity.pairingId, back.pairingId)
    }

    @Test
    fun aShortRecordIsNotAnIdentity() {
        assertNull(AirPlayIdentity.decode(ByteArray(32)))
        assertNull(AirPlayIdentity.decode(ByteArray(0)))
    }

    @Test
    fun loadOrCreateWritesOnceAndReadsItBack() {
        var stored: ByteArray? = null
        var writes = 0
        val first = AirPlayIdentity.loadOrCreate({ stored }, { stored = it; writes++ })
        val second = AirPlayIdentity.loadOrCreate({ stored }, { stored = it; writes++ })
        assertEquals(1, writes)
        assertEquals(first.pairingId, second.pairingId)
    }

    @Test
    fun anUnreadableRecordIsReplaced() {
        var stored: ByteArray? = ByteArray(5)
        val identity = AirPlayIdentity.loadOrCreate({ stored }, { stored = it })
        assertEquals(identity.pairingId, AirPlayIdentity.decode(stored!!)!!.pairingId)
    }

    @Test
    fun theTextFormNamesNoKey() {
        val identity = AirPlayIdentity.generate()
        assertFalse(identity.toString().contains(identity.seed.joinToString("")))
        assertEquals("AirPlayIdentity(${identity.pairingId})", identity.toString())
    }
}
