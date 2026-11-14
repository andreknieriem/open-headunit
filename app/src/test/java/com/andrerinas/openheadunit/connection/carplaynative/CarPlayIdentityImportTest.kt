package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.CarPlayIdentityImport.Result
import com.andrerinas.openheadunit.connection.carplaynative.CarPlayIdentityFixtures as F
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayIdentityImportTest {

    private val b64: (String) -> ByteArray = { java.util.Base64.getMimeDecoder().decode(it) }

    private fun parse(text: String) = CarPlayIdentityImport.parse(text.toByteArray(), b64)

    @Test
    fun `an RSA certificate with its key is accepted`() {
        val r = parse(F.RSA_CERT + F.RSA_KEY)
        assertTrue(r is Result.Ok)
        assertEquals("RSA", (r as Result.Ok).key.algorithm)
    }

    @Test
    fun `an EC P-256 certificate with its key is accepted`() {
        val r = parse(F.EC_KEY + F.EC_CERT)
        assertTrue(r is Result.Ok)
        assertEquals("EC", (r as Result.Ok).key.algorithm)
    }

    @Test
    fun `a PKCS7 certificate block is accepted`() {
        assertTrue(parse(F.RSA_PKCS7 + F.RSA_KEY) is Result.Ok)
    }

    @Test
    fun `a key from another certificate is the wrong key`() {
        assertTrue(parse(F.RSA_CERT + F.OTHER_RSA_KEY) is Result.WrongKey)
        assertTrue(parse(F.EC_CERT + F.RSA_KEY) is Result.WrongKey)
    }

    @Test
    fun `a cut file is corrupt`() {
        val cut = (F.RSA_CERT + F.RSA_KEY).dropLast(120)
        assertTrue(parse(cut) is Result.Corrupt)
    }

    @Test
    fun `garbage inside a block is corrupt`() {
        val bad = F.RSA_CERT + "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n"
        assertTrue(parse(bad) is Result.Corrupt)
    }

    @Test
    fun `a DER file or a missing half is the wrong format`() {
        val der = java.util.Base64.getMimeDecoder().decode(
            F.RSA_CERT.lines().filter { !it.startsWith("-----") }.joinToString(""),
        )
        assertTrue(CarPlayIdentityImport.parse(der, b64) is Result.WrongFormat)
        assertTrue(parse(F.RSA_CERT) is Result.WrongFormat)
        assertTrue(parse(F.RSA_KEY) is Result.WrongFormat)
        assertTrue(parse("") is Result.WrongFormat)
    }

    @Test
    fun `an encrypted key is the wrong format`() {
        val enc = F.RSA_CERT + "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----\n"
        assertTrue(parse(enc) is Result.WrongFormat)
    }
}
