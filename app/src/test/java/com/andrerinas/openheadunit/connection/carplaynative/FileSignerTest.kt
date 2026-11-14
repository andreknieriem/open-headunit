package com.andrerinas.openheadunit.connection.carplaynative

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSignerTest {

    private val challenge = ByteArray(32) { it.toByte() }

    @Test
    fun `an RSA signature verifies with the public key`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val sig = FileSigner(pair.private).sign(challenge)
        val v = Signature.getInstance("SHA256withRSA").apply { initVerify(pair.public); update(challenge) }
        assertTrue(v.verify(sig))
    }

    @Test
    fun `an EC signature verifies with the public key`() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val sig = FileSigner(pair.private).sign(challenge)
        val v = Signature.getInstance("SHA256withECDSA").apply { initVerify(pair.public); update(challenge) }
        assertTrue(v.verify(sig))
    }

    @Test
    fun `the algorithm follows the key type`() {
        assertEquals("SHA256withECDSA", FileSigner.algorithmFor("EC"))
        assertEquals("SHA256withRSA", FileSigner.algorithmFor("RSA"))
    }
}
