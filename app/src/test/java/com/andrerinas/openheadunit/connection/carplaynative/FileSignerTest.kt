package com.andrerinas.openheadunit.connection.carplaynative

import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSignerTest {

    private val message = "the accessory challenge".toByteArray()
    private val cert = byteArrayOf(0x30, 0x03, 0x01, 0x01, 0x00)

    private val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val ec = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    @Test
    fun `an RSA signature over a SHA-1 digest passes the standard SHA1withRSA check`() {
        val digest = MessageDigest.getInstance("SHA-1").digest(message)
        val sig = FileSigner(cert, rsa.private).sign(digest)
        assertEquals(256, sig.size)
        val v = Signature.getInstance("SHA1withRSA").apply { initVerify(rsa.public); update(message) }
        assertTrue(v.verify(sig))
    }

    @Test
    fun `a P-256 signature is raw r and s and passes the standard SHA256withECDSA check`() {
        val digest = MessageDigest.getInstance("SHA-256").digest(message)
        val sig = FileSigner(cert, ec.private).sign(digest)
        assertEquals(64, sig.size)
        val v = Signature.getInstance("SHA256withECDSA").apply { initVerify(ec.public); update(message) }
        assertTrue(v.verify(EcdsaSignature.rawToDer(sig)))
    }

    @Test
    fun `the key type picks the protocol major`() {
        assertEquals(2, FileSigner(cert, rsa.private).protocolMajor())
        assertEquals(3, FileSigner(cert, ec.private).protocolMajor())
    }

    @Test
    fun `the certificate comes back unchanged and a caller cannot change the stored copy`() {
        val signer = FileSigner(cert, ec.private)
        assertArrayEquals(cert, signer.certificate())
        signer.certificate()[0] = 0
        assertArrayEquals(cert, signer.certificate())
    }

    @Test
    fun `a challenge of the wrong size is refused`() {
        for (size in listOf(0, 19, 21, 32)) {
            assertTrue(refused { FileSigner(cert, rsa.private).sign(ByteArray(size)) })
        }
        for (size in listOf(0, 20, 31, 33)) {
            assertTrue(refused { FileSigner(cert, ec.private).sign(ByteArray(size)) })
        }
    }

    private fun refused(block: () -> Unit) = try { block(); false } catch (e: IllegalArgumentException) { true }
}
