package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.CarPlaySigner
import com.andrerinas.openheadunit.connection.carplaynative.EcdsaSignature
import com.andrerinas.openheadunit.connection.carplaynative.FileSigner
import com.andrerinas.openheadunit.connection.carplaynative.SignerException
import com.andrerinas.openheadunit.connection.carplaynative.crypto.CarPlayCrypto
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthSetupTest {

    private val cert = byteArrayOf(0x30, 0x03, 0x01, 0x01, 0x00)
    private val ec = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private class Counting(val inner: CarPlaySigner) : CarPlaySigner {
        var signed = 0
        override fun protocolMajor() = inner.protocolMajor()
        override fun certificate() = inner.certificate()
        override fun sign(challenge: ByteArray): ByteArray { signed++; return inner.sign(challenge) }
    }

    private class Reply(val ours: ByteArray, val cert: ByteArray, val signature: ByteArray)

    private fun u32(b: ByteArray, at: Int) =
        ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)

    /** The phone side: sends its key, reads the layout and decrypts the signature. */
    private fun ask(signer: CarPlaySigner): Pair<ByteArray, Reply> {
        val phone = CarPlayCrypto.x25519Generate()
        val out = AuthSetup.handle(byteArrayOf(1) + phone.publicKey, signer)!!
        val ours = out.copyOf(32)
        val certLength = u32(out, 32)
        val certificate = out.copyOfRange(36, 36 + certLength)
        val sigLength = u32(out, 36 + certLength)
        assertEquals(out.size, 40 + certLength + sigLength)
        val shared = CarPlayCrypto.x25519Shared(phone.privateKey, ours)
        val key = CarPlayCrypto.sha1("AES-KEY".toByteArray(), shared).copyOf(16)
        val iv = CarPlayCrypto.sha1("AES-IV".toByteArray(), shared).copyOf(16)
        val signature = CarPlayCrypto.aesCtr128(key, iv, out.copyOfRange(40 + certLength, out.size))
        return phone.publicKey to Reply(ours, certificate, signature)
    }

    @Test
    fun aMajor3ReplyHoldsASha256SignatureThePhoneCanVerify() {
        val (phoneKey, reply) = ask(FileSigner(cert, ec.private))
        assertArrayEquals(cert, reply.cert)
        assertEquals(64, reply.signature.size)
        val verify = Signature.getInstance("SHA256withECDSA").apply { initVerify(ec.public); update(reply.ours + phoneKey) }
        assertTrue(verify.verify(EcdsaSignature.rawToDer(reply.signature)))
    }

    @Test
    fun aMajor2ReplyHoldsASha1SignatureThePhoneCanVerify() {
        val (phoneKey, reply) = ask(FileSigner(cert, rsa.private))
        assertEquals(256, reply.signature.size)
        val verify = Signature.getInstance("SHA1withRSA").apply { initVerify(rsa.public); update(reply.ours + phoneKey) }
        assertTrue(verify.verify(reply.signature))
    }

    @Test
    fun theKeyOrderIsOursThenThePhones() {
        val (phoneKey, reply) = ask(FileSigner(cert, ec.private))
        val swapped = Signature.getInstance("SHA256withECDSA").apply { initVerify(ec.public); update(phoneKey + reply.ours) }
        assertTrue(!swapped.verify(EcdsaSignature.rawToDer(reply.signature)))
    }

    @Test
    fun aMalformedRequestGetsNullAndNeverReachesTheSigner() {
        val signer = Counting(FileSigner(cert, ec.private))
        val key = CarPlayCrypto.x25519Generate().publicKey
        assertNull(AuthSetup.handle(ByteArray(32), signer))
        assertNull(AuthSetup.handle(byteArrayOf(2) + key, signer))
        assertNull(AuthSetup.handle(byteArrayOf(1) + key + 0, signer))
        assertNull(AuthSetup.handle(byteArrayOf(1) + ByteArray(32), signer))
        assertEquals(0, signer.signed)
    }

    @Test
    fun aSignerFailureReachesTheCaller() {
        val failing = object : CarPlaySigner {
            override fun protocolMajor() = 3
            override fun certificate() = cert
            override fun sign(challenge: ByteArray): ByteArray = throw SignerException("chip did not answer")
        }
        assertThrows(SignerException::class.java) {
            AuthSetup.handle(byteArrayOf(1) + CarPlayCrypto.x25519Generate().publicKey, failing)
        }
    }
}
