package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.codec.Tlv8
import com.andrerinas.openheadunit.connection.carplaynative.crypto.CarPlayCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pair-setup and pair-verify against a controller written from the message layout. */
class PairingTest {

    private val identity = AirPlayIdentity.generate()
    private val phone = PairingClient()

    private fun tlv(vararg items: Pair<Int, ByteArray>) = Tlv8.encode(items.map { Tlv8.Item(it.first, it.second) })

    private fun errorOf(reply: ByteArray): Pair<Int?, Int?> {
        val m = Tlv8.decode(reply)
        return m[PairingTlv.STATE]?.get(0)?.toInt() to m[PairingTlv.ERROR]?.get(0)?.toInt()
    }

    @Test
    fun pairSetupStoresTheControllerAndProvesTheAccessory() {
        val store = PairingStore()
        val setup = PairSetup(identity, store)
        val result = phone.pairSetup(setup)
        assertEquals(6, result.reply.getValue(PairingTlv.STATE)[0].toInt())
        assertTrue(phone.accessoryProofOk(result, identity.publicKey, identity.pairingId))
        assertTrue(setup.complete)
        assertArrayEquals(phone.publicKey, store.get("phone-1"))
    }

    @Test
    fun aWrongCodeFailsAtM4AndStoresNothing() {
        val store = PairingStore()
        val setup = PairSetup(identity, store)
        val result = phone.pairSetup(setup, code = "0000")
        assertEquals(4, result.reply.getValue(PairingTlv.STATE)[0].toInt())
        assertEquals(2, result.reply.getValue(PairingTlv.ERROR)[0].toInt())
        assertFalse(setup.complete)
        assertNull(store.get("phone-1"))
    }

    @Test
    fun aControllerSignatureForAnotherKeyIsRefused() {
        val store = PairingStore()
        val setup = PairSetup(identity, store)
        val result = phone.pairSetup(setup, announcedKey = PairingClient().publicKey)
        assertEquals(6, result.reply.getValue(PairingTlv.STATE)[0].toInt())
        assertNotNull(result.reply[PairingTlv.ERROR])
        assertNull(store.get("phone-1"))
        assertFalse(setup.complete)
    }

    @Test
    fun aReplayedM5AfterTheExchangeIsRefused() {
        val setup = PairSetup(identity, PairingStore())
        phone.pairSetup(setup)
        val reply = Tlv8.decode(setup.handle(tlv(PairingTlv.STATE to byteArrayOf(5), PairingTlv.ENCRYPTED_DATA to ByteArray(40))))
        assertEquals(2, reply.getValue(PairingTlv.ERROR)[0].toInt())
    }

    @Test
    fun anUnexpectedStateAndGarbageGetAnErrorReply() {
        val setup = PairSetup(identity, PairingStore())
        assertEquals(7 to 2, errorOf(setup.handle(tlv(PairingTlv.STATE to byteArrayOf(7)))))
        assertEquals(0 to 2, errorOf(setup.handle(ByteArray(0))))
        assertEquals(4 to 2, errorOf(setup.handle(tlv(PairingTlv.STATE to byteArrayOf(3)))))
        assertEquals(6 to 2, errorOf(setup.handle(tlv(PairingTlv.STATE to byteArrayOf(5)))))
    }

    @Test
    fun pairVerifyOpensTheControlKeysAfterASetup() {
        val store = PairingStore()
        phone.pairSetup(PairSetup(identity, store))
        val verify = PairVerify(identity, store)
        val result = phone.pairVerify(verify, identity.publicKey, identity.pairingId)
        assertTrue(result.m2Ok)
        assertEquals(4, result.m4.getValue(PairingTlv.STATE)[0].toInt())
        assertTrue(verify.isVerified)
        assertEquals("phone-1", verify.verifiedControllerId)
        assertArrayEquals(result.shared, verify.sharedSecret)
        val keys = verify.controlKeys!!
        assertArrayEquals(
            CarPlayCrypto.hkdfSha512(result.shared, "Control-Salt".toByteArray(), "Control-Write-Encryption-Key".toByteArray()),
            keys.readKey,
        )
        assertArrayEquals(
            CarPlayCrypto.hkdfSha512(result.shared, "Control-Salt".toByteArray(), "Control-Read-Encryption-Key".toByteArray()),
            keys.writeKey,
        )
    }

    @Test
    fun pairVerifyRefusesAControllerThatNeverPaired() {
        val verify = PairVerify(identity, PairingStore())
        val result = phone.pairVerify(verify, identity.publicKey, identity.pairingId)
        assertEquals(2, result.m4.getValue(PairingTlv.ERROR)[0].toInt())
        assertFalse(verify.isVerified)
        assertNull(verify.controlKeys)
    }

    @Test
    fun pairVerifyRefusesASignatureFromAnotherKey() {
        val store = PairingStore()
        store.save("phone-1", PairingClient().publicKey)
        val verify = PairVerify(identity, store)
        val result = phone.pairVerify(verify, identity.publicKey, identity.pairingId)
        assertEquals(2, result.m4.getValue(PairingTlv.ERROR)[0].toInt())
        assertFalse(verify.isVerified)
    }

    @Test
    fun aShortOrLowOrderClientKeyGetsAnErrorReply() {
        val verify = PairVerify(identity, PairingStore())
        assertEquals(2 to 2, errorOf(verify.handle(tlv(PairingTlv.STATE to byteArrayOf(1), PairingTlv.PUBLIC_KEY to ByteArray(31)))))
        assertEquals(2 to 2, errorOf(verify.handle(tlv(PairingTlv.STATE to byteArrayOf(1), PairingTlv.PUBLIC_KEY to ByteArray(32)))))
        assertEquals(4 to 2, errorOf(verify.handle(tlv(PairingTlv.STATE to byteArrayOf(3)))))
        assertFalse(verify.isVerified)
    }

    @Test
    fun aNewM1ClearsEarlierKeys() {
        val store = PairingStore()
        phone.pairSetup(PairSetup(identity, store))
        val verify = PairVerify(identity, store)
        phone.pairVerify(verify, identity.publicKey, identity.pairingId)
        assertTrue(verify.isVerified)
        verify.handle(tlv(PairingTlv.STATE to byteArrayOf(1), PairingTlv.PUBLIC_KEY to CarPlayCrypto.x25519Generate().publicKey))
        assertFalse(verify.isVerified)
        assertNull(verify.verifiedControllerId)
    }

    @Test
    fun theStoreHandsOutCopiesAndRestoreDoesNotNotify() {
        val saved = ArrayList<String>()
        val store = PairingStore { id, _ -> saved += id }
        val key = ByteArray(32) { 1 }
        store.save("a", key)
        key[0] = 9
        assertEquals(1, store.get("a")!![0].toInt())
        store.get("a")!![0] = 7
        assertEquals(1, store.get("a")!![0].toInt())
        store.restore("b", key)
        assertEquals(listOf("a"), saved)
        assertNotNull(store.get("b"))
        assertNull(store.get("c"))
    }
}
