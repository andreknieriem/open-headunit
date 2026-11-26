package com.andrerinas.openheadunit.connection.carplaynative.lockdown

import com.andrerinas.openheadunit.connection.carplaynative.Der
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlainBase64
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Certificate
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

class PairRecordTest {

    private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private fun pkcs1Pem(key: RSAPublicKey): ByteArray {
        val der = Der.sequence(Der.integer(key.modulus), Der.integer(key.publicExponent))
        return ("-----BEGIN RSA PUBLIC KEY-----\n" + PlainBase64.encode(der).chunked(64).joinToString("\n") + "\n-----END RSA PUBLIC KEY-----\n").toByteArray()
    }

    /**
     * The JVM CertificateFactory rejects the empty issuer name that lockdown certificates use,
     * so a BouncyCastle ASN.1 parser reads them and the signature is checked over the raw TBS bytes.
     */
    private fun parse(pem: ByteArray): Certificate {
        val text = String(pem, Charsets.US_ASCII).lines().filter { !it.startsWith("-----") }.joinToString("")
        return Certificate.getInstance(PlainBase64.decode(text))
    }

    private fun verifies(cert: Certificate, key: java.security.PublicKey): Boolean =
        Signature.getInstance("SHA256withRSA").apply { initVerify(key); update(cert.tbsCertificate.getEncoded(ASN1Encoding.DER)) }.verify(cert.signature.bytes)

    private fun publicKeyOf(cert: Certificate): java.security.PublicKey =
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(cert.subjectPublicKeyInfo.getEncoded(ASN1Encoding.DER)))

    @Test
    fun `the certificates parse and the host and device certificates verify with the root key`() {
        val record = PairRecordFactory.generate(pkcs1Pem(device.public as RSAPublicKey), "aa:bb:cc:dd:ee:ff", "HOST", "BUID", NOW, root, host)
        val rootCert = parse(record.rootCertificatePem)
        assertTrue(verifies(rootCert, root.public))
        assertTrue(verifies(parse(record.hostCertificatePem), root.public))
        val deviceCert = parse(record.deviceCertificatePem)
        assertTrue(verifies(deviceCert, root.public))
        assertEquals(device.public, publicKeyOf(deviceCert))
        assertEquals(host.public, publicKeyOf(parse(record.hostCertificatePem)))
        assertEquals(root.public, publicKeyOf(rootCert))
    }

    @Test
    fun `the certificates are v3 with the SHA-256 RSA algorithm and empty names`() {
        val cert = parse(PairRecordFactory.generate(pkcs1Pem(device.public as RSAPublicKey), "w", "H", "B", NOW, root, host).hostCertificatePem)
        assertEquals(3, cert.versionNumber)
        assertEquals("1.2.840.113549.1.1.11", cert.signatureAlgorithm.algorithm.id)
        assertEquals(0, cert.issuer.rdNs.size)
        assertEquals(0, cert.subject.rdNs.size)
    }

    @Test
    fun `the root is a CA and the leaves are not, and the validity is ten years from now`() {
        val record = PairRecordFactory.generate(pkcs1Pem(rsa().public as RSAPublicKey), "w", "H", "B", NOW, root, host)
        fun isCa(pem: ByteArray) = BasicConstraints.fromExtensions(parse(pem).tbsCertificate.extensions).isCA
        assertTrue(isCa(record.rootCertificatePem))
        assertFalse(isCa(record.hostCertificatePem))
        assertFalse(isCa(record.deviceCertificatePem))
        val c = parse(record.hostCertificatePem).tbsCertificate
        assertEquals(NOW / 1000, c.startDate.date.time / 1000)
        assertEquals((NOW + 10L * 365 * 24 * 3600 * 1000) / 1000, c.endDate.date.time / 1000)
    }

    @Test
    fun `the key usage is digital signature and key encipherment on a leaf, and the device alone has a key id`() {
        val record = PairRecordFactory.generate(pkcs1Pem(rsa().public as RSAPublicKey), "w", "H", "B", NOW, root, host)
        val usage = KeyUsage.fromExtensions(parse(record.hostCertificatePem).tbsCertificate.extensions)
        assertTrue(usage.hasUsages(KeyUsage.digitalSignature))
        assertTrue(usage.hasUsages(KeyUsage.keyEncipherment))
        assertFalse(usage.hasUsages(KeyUsage.keyCertSign))
        assertNull(parse(record.hostCertificatePem).tbsCertificate.extensions.getExtension(Extension.subjectKeyIdentifier))
        assertNotNull(parse(record.deviceCertificatePem).tbsCertificate.extensions.getExtension(Extension.subjectKeyIdentifier))
    }

    @Test
    fun `a certificate signed by another key does not verify`() {
        val record = PairRecordFactory.generate(pkcs1Pem(rsa().public as RSAPublicKey), "w", "H", "B", NOW, root, host)
        assertFalse(verifies(parse(record.hostCertificatePem), host.public))
    }

    @Test
    fun `the PEM blocks have their labels and 64-character lines`() {
        val record = PairRecordFactory.generate(pkcs1Pem(rsa().public as RSAPublicKey), "w", "H", "B", NOW, root, host)
        val cert = String(record.hostCertificatePem)
        assertTrue(cert.startsWith("-----BEGIN CERTIFICATE-----\n") && cert.endsWith("-----END CERTIFICATE-----\n"))
        assertTrue(cert.lines().all { it.length <= 64 })
        assertTrue(String(record.hostPrivateKeyPem).startsWith("-----BEGIN PRIVATE KEY-----\n"))
        assertTrue(String(record.rootPrivateKeyPem).startsWith("-----BEGIN PRIVATE KEY-----\n"))
    }

    @Test
    fun `the Pair request record holds the five keys and nothing private`() {
        val record = PairRecordFactory.generate(pkcs1Pem(rsa().public as RSAPublicKey), "w", "HOST-1", "BUID-1", NOW, root, host)
        val dict = record.toPairRequestDictionary()
        assertEquals(listOf("DeviceCertificate", "HostCertificate", "HostID", "RootCertificate", "SystemBUID"), dict.entries.map { it.first })
        assertEquals(PlistValue.Text("HOST-1"), dict["HostID"])
        assertEquals(PlistValue.Text("BUID-1"), dict["SystemBUID"])
        assertArrayEquals(record.rootCertificatePem, dict["RootCertificate"]!!.asBytes)
    }

    @Test
    fun `toString hides the record`() {
        val record = PairRecordFactory.generate(pkcs1Pem(rsa().public as RSAPublicKey), "w", "HOST-1", "BUID-1", NOW, root, host)
        assertEquals("PairRecord(redacted)", record.toString())
    }

    @Test
    fun `a device key that is no PKCS1 RSA PEM is refused`() {
        listOf("junk", "-----BEGIN PUBLIC KEY-----\nAAAA\n-----END PUBLIC KEY-----").forEach {
            try {
                PairRecordFactory.generate(it.toByteArray(), "w", "H", "B", NOW, root, host)
                throw AssertionError("accepted $it")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `a PKCS1 key with trailing bytes is refused`() {
        val key = device.public as RSAPublicKey
        val der = Der.sequence(Der.integer(key.modulus), Der.integer(key.publicExponent), Der.integer(BigInteger.ONE))
        val pem = "-----BEGIN RSA PUBLIC KEY-----\n${PlainBase64.encode(der)}\n-----END RSA PUBLIC KEY-----\n"
        try {
            PairRecordFactory.parsePkcs1PublicKey(pem.toByteArray())
            throw AssertionError("accepted trailing data")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `the device key read back equals the key the PEM was made from`() {
        assertEquals(device.public, PairRecordFactory.parsePkcs1PublicKey(pkcs1Pem(device.public as RSAPublicKey)))
    }

    private companion object {
        // 2026-11-26 00:00:00 UTC.
        const val NOW = 1_795_651_200_000L
        lateinit var root: KeyPair
        lateinit var host: KeyPair
        lateinit var device: KeyPair

        @JvmStatic
        @BeforeClass
        fun keys() {
            val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            root = generator.generateKeyPair()
            host = generator.generateKeyPair()
            device = generator.generateKeyPair()
        }
    }
}
