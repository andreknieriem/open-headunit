package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.CarPlayIdentityImport.Result
import com.andrerinas.openheadunit.connection.carplaynative.CarPlayIdentityFixtures as F
import java.security.MessageDigest
import java.security.Signature
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayIdentityImportTest {

    private val b64: (String) -> ByteArray = { java.util.Base64.getMimeDecoder().decode(it) }

    private fun parse(cert: String, key: String) = CarPlayIdentityImport.parse(cert.toByteArray(), key.toByteArray(), b64)

    private fun combined(text: String) = CarPlayIdentityImport.parseFiles(listOf(text.toByteArray()), b64)

    @Test
    fun `an RSA PKCS7 with its key is accepted as protocol 2`() {
        val r = parse(F.RSA_PKCS7, F.RSA_KEY)
        assertTrue(r is Result.Ok)
        r as Result.Ok
        assertEquals("RSA", r.key.algorithm)
        assertEquals(2, r.protocolMajor)
        assertArrayEquals(F.der(F.RSA_PKCS7), r.certificate)
    }

    @Test
    fun `an EC P-256 PKCS7 with its key is accepted as protocol 3`() {
        val r = parse(F.EC_PKCS7, F.EC_KEY)
        assertTrue(r is Result.Ok)
        r as Result.Ok
        assertEquals("EC", r.key.algorithm)
        assertEquals(3, r.protocolMajor)
    }

    @Test
    fun `DER files are accepted like PEM`() {
        val r = CarPlayIdentityImport.parse(F.der(F.EC_PKCS7), F.der(F.EC_KEY), b64)
        assertTrue(r is Result.Ok)
        assertArrayEquals(F.der(F.EC_PKCS7), (r as Result.Ok).certificate)
    }

    @Test
    fun `an accepted key signs a digest the certificate verifies`() {
        val ok = parse(F.RSA_PKCS7, F.RSA_KEY) as Result.Ok
        val message = "hello".toByteArray()
        val sig = FileSigner(ok.certificate, ok.key).sign(MessageDigest.getInstance("SHA-1").digest(message))
        val leaf = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificates(java.io.ByteArrayInputStream(ok.certificate)).first()
        val v = Signature.getInstance("SHA1withRSA").apply { initVerify(leaf.publicKey); update(message) }
        assertTrue(v.verify(sig))
    }

    @Test
    fun `one PEM file with both blocks is accepted in either order`() {
        assertTrue(combined(F.RSA_PKCS7 + F.RSA_KEY) is Result.Ok)
        assertTrue(combined(F.EC_KEY + F.EC_PKCS7) is Result.Ok)
    }

    @Test
    fun `two picked files are sorted by content in either order`() {
        val cert = F.EC_PKCS7.toByteArray()
        val key = F.EC_KEY.toByteArray()
        assertTrue(CarPlayIdentityImport.parseFiles(listOf(cert, key), b64) is Result.Ok)
        assertTrue(CarPlayIdentityImport.parseFiles(listOf(key, cert), b64) is Result.Ok)
        assertTrue(CarPlayIdentityImport.parseFiles(listOf(F.der(F.EC_KEY), F.der(F.EC_PKCS7)), b64) is Result.Ok)
        // A file name plays no part: only the bytes decide.
        assertTrue(CarPlayIdentityImport.parseFiles(listOf(F.der(F.RSA_PKCS7), F.RSA_KEY.toByteArray()), b64) is Result.Ok)
    }

    @Test
    fun `two certificates, two keys or three files are the wrong format`() {
        val cert = F.EC_PKCS7.toByteArray()
        val key = F.EC_KEY.toByteArray()
        assertTrue(CarPlayIdentityImport.parseFiles(listOf(cert, cert), b64) is Result.WrongFormat)
        assertTrue(CarPlayIdentityImport.parseFiles(listOf(key, key), b64) is Result.WrongFormat)
        assertTrue(CarPlayIdentityImport.parseFiles(listOf(cert, key, key), b64) is Result.WrongFormat)
        assertTrue(CarPlayIdentityImport.parseFiles(emptyList(), b64) is Result.WrongFormat)
    }

    @Test
    fun `a plain X509 certificate is not a PKCS7`() {
        assertTrue(parse(F.RSA_CERT, F.RSA_KEY) is Result.WrongFormat)
        assertTrue(CarPlayIdentityImport.parse(F.der(F.EC_CERT), F.der(F.EC_KEY), b64) is Result.WrongFormat)
    }

    @Test
    fun `a key from another certificate is the wrong key`() {
        assertTrue(parse(F.RSA_PKCS7, F.OTHER_RSA_KEY) is Result.WrongKey)
        assertTrue(parse(F.EC_PKCS7, F.RSA_KEY) is Result.WrongKey)
        assertTrue(parse(F.RSA_PKCS7, F.EC_KEY) is Result.WrongKey)
    }

    @Test
    fun `an EC key that is not P-256 is the wrong format`() {
        assertTrue(parse(F.EC_PKCS7, F.EC_P384_KEY) is Result.WrongFormat)
    }

    @Test
    fun `an encrypted key is the wrong format`() {
        val pem = "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----\n"
        assertTrue(parse(F.RSA_PKCS7, pem) is Result.WrongFormat)
        assertTrue(combined(F.RSA_PKCS7 + pem) is Result.WrongFormat)
        // DER: SEQUENCE { SEQUENCE { OID }, OCTET STRING }
        val der = byteArrayOf(0x30, 0x09, 0x30, 0x03, 0x06, 0x01, 0x2a, 0x04, 0x02, 0x01, 0x02)
        assertTrue(CarPlayIdentityImport.parse(F.der(F.RSA_PKCS7), der, b64) is Result.WrongFormat)
    }

    @Test
    fun `a cut file is corrupt`() {
        val cut = (F.RSA_PKCS7 + F.RSA_KEY).dropLast(120)
        assertTrue(combined(cut) is Result.Corrupt)
    }

    @Test
    fun `garbage inside a block is corrupt`() {
        val bad = "-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n"
        assertTrue(parse(F.RSA_PKCS7, bad) is Result.Corrupt)
        assertTrue(combined(F.RSA_PKCS7 + bad) is Result.Corrupt)
    }

    @Test
    fun `a DER file or a missing half is the wrong format`() {
        assertTrue(CarPlayIdentityImport.parseFiles(listOf(F.der(F.RSA_PKCS7)), b64) is Result.WrongFormat)
        assertTrue(combined(F.RSA_PKCS7) is Result.WrongFormat)
        assertTrue(combined(F.RSA_KEY) is Result.WrongFormat)
        assertTrue(combined("") is Result.WrongFormat)
    }
}
