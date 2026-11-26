package com.andrerinas.openheadunit.connection.carplaynative

import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.ECPrivateKey

/**
 * Signs in software with the imported key, as the chip would: a P-256 key signs a 32-byte
 * digest as raw r||s, an RSA key signs a 20-byte SHA-1 digest with PKCS#1 v1.5.
 * Ported from LIVI (https://github.com/f-io/LIVI), crate iap2-mfi.
 */
class FileSigner(private val certificate: ByteArray, private val key: PrivateKey) : CarPlaySigner {

    private val isEc: Boolean get() = key is ECPrivateKey

    override fun protocolMajor(): Int = if (isEc) 3 else 2

    override fun certificate(): ByteArray = certificate.copyOf()

    override fun sign(challenge: ByteArray): ByteArray = if (isEc) signEc(challenge) else signRsa(challenge)

    private fun signEc(digest: ByteArray): ByteArray {
        require(digest.size == EC_DIGEST_LEN) { "a P-256 challenge is $EC_DIGEST_LEN bytes, got ${digest.size}" }
        // NONE means the caller's digest is signed as given.
        val signature = Signature.getInstance("NONEwithECDSA")
        signature.initSign(key)
        signature.update(digest)
        return EcdsaSignature.derToRaw(signature.sign())
    }

    private fun signRsa(digest: ByteArray): ByteArray {
        require(digest.size == SHA1_LEN) { "an RSA challenge is $SHA1_LEN bytes, got ${digest.size}" }
        val signature = Signature.getInstance("NONEwithRSA")
        signature.initSign(key)
        signature.update(SHA1_DIGEST_INFO + digest)
        return signature.sign()
    }

    companion object {
        const val EC_DIGEST_LEN = 32
        const val SHA1_LEN = 20

        /** The DER DigestInfo header for SHA-1, ahead of the 20 digest bytes. */
        val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )
    }
}
