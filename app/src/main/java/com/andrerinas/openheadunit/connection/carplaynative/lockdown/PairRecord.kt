package com.andrerinas.openheadunit.connection.carplaynative.lockdown

import com.andrerinas.openheadunit.connection.carplaynative.Der
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlainBase64
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue
import java.math.BigInteger
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * The pairing material lockdown keeps for one iPhone: the host id, the PEM keys and certificates.
 * It holds secrets, so [toString] hides everything and no log line may carry a field.
 */
class PairRecord(
    val hostId: String,
    val systemBuid: String,
    val wifiAddress: String,
    val devicePublicKeyPem: ByteArray,
    val deviceCertificatePem: ByteArray,
    val hostPrivateKeyPem: ByteArray,
    val hostCertificatePem: ByteArray,
    val rootPrivateKeyPem: ByteArray,
    val rootCertificatePem: ByteArray,
) {
    /** The nested `PairRecord` value of the Pair request. */
    fun toPairRequestDictionary(): PlistValue.Dict = PlistValue.dict(
        "DeviceCertificate" to PlistValue.Data(deviceCertificatePem),
        "HostCertificate" to PlistValue.Data(hostCertificatePem),
        "HostID" to PlistValue.Text(hostId),
        "RootCertificate" to PlistValue.Data(rootCertificatePem),
        "SystemBUID" to PlistValue.Text(systemBuid),
    )

    override fun toString(): String = "PairRecord(redacted)"
}

/**
 * Builds a [PairRecord] with no I/O: two RSA 2048 keys, and a root, a host and a device certificate of hand-built DER.
 * Ported from xcertplay (https://github.com/shilapi/xcertplay) `transport/LockdownPairRecord.kt`.
 */
object PairRecordFactory {
    private const val RSA_KEY_BITS = 2048
    private const val TEN_YEARS_MILLIS = 10L * 365 * 24 * 60 * 60 * 1000
    private const val UTC_TIME_LAST_YEAR = 2049
    private const val OID_SHA256_RSA = "1.2.840.113549.1.1.11"
    private const val OID_RSA = "1.2.840.113549.1.1.1"
    private const val OID_BASIC_CONSTRAINTS = "2.5.29.19"
    private const val OID_SUBJECT_KEY_ID = "2.5.29.14"
    private const val OID_KEY_USAGE = "2.5.29.15"
    private const val PEM_PUBLIC_BEGIN = "-----BEGIN RSA PUBLIC KEY-----"
    private const val PEM_PUBLIC_END = "-----END RSA PUBLIC KEY-----"

    /** A new RSA 2048 key pair. The caller makes two, one for the root and one for the host. */
    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(RSA_KEY_BITS) }.generateKeyPair()

    @Throws(GeneralSecurityException::class)
    fun generate(
        devicePublicKeyPem: ByteArray,
        wifiAddress: String,
        hostId: String,
        systemBuid: String,
        nowMillis: Long,
        rootKeys: KeyPair = newKeyPair(),
        hostKeys: KeyPair = newKeyPair(),
    ): PairRecord {
        require(devicePublicKeyPem.isNotEmpty() && wifiAddress.isNotBlank() && hostId.isNotBlank() && systemBuid.isNotBlank()) { "pair inputs must not be empty" }
        val devicePublic = parsePkcs1PublicKey(devicePublicKeyPem)
        val rootPublic = rootKeys.public as? RSAPublicKey ?: throw GeneralSecurityException("root key is not RSA")
        val hostPublic = hostKeys.public as? RSAPublicKey ?: throw GeneralSecurityException("host key is not RSA")
        fun issue(subject: RSAPublicKey, extensions: ByteArray) = certificate(subject, rootKeys.private, rootPublic, nowMillis, extensions)
        val rootDer = issue(rootPublic, rootExtensions())
        val hostDer = issue(hostPublic, leafExtensions(null))
        val deviceDer = issue(devicePublic, leafExtensions(devicePublic))
        return PairRecord(
            hostId, systemBuid, wifiAddress, devicePublicKeyPem.copyOf(),
            pem("CERTIFICATE", deviceDer), pem("PRIVATE KEY", hostKeys.private.encoded), pem("CERTIFICATE", hostDer),
            pem("PRIVATE KEY", rootKeys.private.encoded), pem("CERTIFICATE", rootDer),
        )
    }

    /** The key of the iPhone's `DevicePublicKey` value: a PKCS#1 `RSA PUBLIC KEY` PEM. */
    fun parsePkcs1PublicKey(pem: ByteArray): RSAPublicKey {
        val text = String(pem, Charsets.US_ASCII).trim()
        require(text.startsWith(PEM_PUBLIC_BEGIN) && text.endsWith(PEM_PUBLIC_END)) { "expected a PKCS#1 RSA public key" }
        val der = PlainBase64.decode(text.substring(PEM_PUBLIC_BEGIN.length, text.length - PEM_PUBLIC_END.length).filterNot(Char::isWhitespace))
        val outer = Der.header(der, 0)?.takeIf { it.tag == Der.SEQUENCE && it.end == der.size } ?: throw IllegalArgumentException("not a DER sequence")
        val modulus = Der.header(der, outer.contentStart)?.takeIf { it.tag == Der.INTEGER } ?: throw IllegalArgumentException("no modulus")
        val exponent = Der.header(der, modulus.end)?.takeIf { it.tag == Der.INTEGER && it.end == outer.end } ?: throw IllegalArgumentException("no exponent")
        val n = BigInteger(1, der.copyOfRange(modulus.contentStart, modulus.end))
        val e = BigInteger(1, der.copyOfRange(exponent.contentStart, exponent.end))
        return KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e)) as RSAPublicKey
    }

    private fun certificate(subject: RSAPublicKey, signingKey: PrivateKey, signingPublic: RSAPublicKey, nowMillis: Long, extensions: ByteArray): ByteArray {
        val emptyName = Der.sequence()
        val signatureAlgorithm = Der.sequence(Der.oid(OID_SHA256_RSA), Der.nullValue())
        val tbs = Der.sequence(
            Der.explicit(0, Der.integer(BigInteger.valueOf(2))),
            Der.integer(BigInteger.ZERO),
            signatureAlgorithm,
            emptyName,
            Der.sequence(time(nowMillis), time(nowMillis + TEN_YEARS_MILLIS)),
            emptyName,
            Der.sequence(Der.sequence(Der.oid(OID_RSA), Der.nullValue()), Der.bitString(pkcs1(subject))),
            Der.explicit(3, extensions),
        )
        val signature = Signature.getInstance("SHA256withRSA").apply { initSign(signingKey); update(tbs) }.sign()
        val verifier = Signature.getInstance("SHA256withRSA").apply { initVerify(signingPublic); update(tbs) }
        if (!verifier.verify(signature)) throw GeneralSecurityException("generated certificate does not verify")
        return Der.sequence(tbs, signatureAlgorithm, Der.bitString(signature))
    }

    /** A leaf: basic constraints (not a CA), the subject key id for the device only, key usage digitalSignature and keyEncipherment. */
    private fun leafExtensions(subjectKeyIdOf: RSAPublicKey?): ByteArray {
        val list = ArrayList<ByteArray>()
        list.add(extension(OID_BASIC_CONSTRAINTS, critical = true, value = Der.sequence()))
        if (subjectKeyIdOf != null) {
            list.add(extension(OID_SUBJECT_KEY_ID, critical = false, value = Der.octetString(MessageDigest.getInstance("SHA-1").digest(pkcs1(subjectKeyIdOf)))))
        }
        list.add(extension(OID_KEY_USAGE, critical = true, value = Der.bitString(byteArrayOf(0xa0.toByte()), unusedBits = 5)))
        return Der.sequence(*list.toTypedArray())
    }

    /** The root: basic constraints, critical, CA true. */
    private fun rootExtensions(): ByteArray = Der.sequence(extension(OID_BASIC_CONSTRAINTS, critical = true, value = Der.sequence(Der.boolean(true))))

    private fun extension(oid: String, critical: Boolean, value: ByteArray): ByteArray =
        if (critical) Der.sequence(Der.oid(oid), Der.boolean(true), Der.octetString(value)) else Der.sequence(Der.oid(oid), Der.octetString(value))

    private fun pkcs1(key: RSAPublicKey): ByteArray = Der.sequence(Der.integer(key.modulus), Der.integer(key.publicExponent))

    /** UTCTime up to 2049, GeneralizedTime after. */
    private fun time(millis: Long): ByteArray {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.US).apply { timeInMillis = millis }
        val year = c.get(Calendar.YEAR)
        val utc = year <= UTC_TIME_LAST_YEAR
        val text = String.format(
            Locale.US, if (utc) "%02d%02d%02d%02d%02d%02dZ" else "%04d%02d%02d%02d%02d%02dZ",
            if (utc) year % 100 else year, c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
            c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), c.get(Calendar.SECOND),
        )
        return Der.tlv(if (utc) 0x17 else 0x18, text.toByteArray(Charsets.US_ASCII))
    }

    private fun pem(label: String, der: ByteArray): ByteArray {
        val lines = PlainBase64.encode(der).chunked(64).joinToString("\n")
        return "-----BEGIN $label-----\n$lines\n-----END $label-----\n".toByteArray(Charsets.US_ASCII)
    }
}
