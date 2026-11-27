package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.CarPlaySigner
import com.andrerinas.openheadunit.connection.carplaynative.crypto.CarPlayCrypto

/**
 * The MFi proof of /auth-setup, sent over the encrypted control channel. The request is a version
 * byte and the phone's X25519 key. The reply is our key, the signer's certificate and its signature
 * over both keys, encrypted with AES-128-CTR. Blocking: a signer may talk to a chip.
 * Follows xcertplay airplay/MfiSapAuthSetup.kt (https://github.com/shilapi/xcertplay) and LIVI (https://github.com/f-io/LIVI) auth_setup.rs.
 */
object AuthSetup {
    private const val VERSION = 1
    private const val KEY_SIZE = 32
    private const val AES_SIZE = 16

    /** Returns null for a malformed request or a low-order key. A [CarPlaySigner] failure propagates. */
    fun handle(body: ByteArray, signer: CarPlaySigner): ByteArray? {
        if (body.size != 1 + KEY_SIZE || body[0].toInt() != VERSION) return null
        val peer = body.copyOfRange(1, body.size)
        val pair = CarPlayCrypto.x25519Generate()
        val shared = try {
            CarPlayCrypto.x25519Shared(pair.privateKey, peer)
        } catch (_: RuntimeException) {
            return null
        }
        if (shared.all { it.toInt() == 0 }) return null

        val aesKey = CarPlayCrypto.sha1(ascii("AES-KEY"), shared).copyOf(AES_SIZE)
        val aesIv = CarPlayCrypto.sha1(ascii("AES-IV"), shared).copyOf(AES_SIZE)
        val certificate = signer.certificate()
        val digest = if (signer.protocolMajor() == 2) {
            CarPlayCrypto.sha1(pair.publicKey, peer)
        } else {
            CarPlayCrypto.sha256(pair.publicKey, peer)
        }
        val signature = CarPlayCrypto.aesCtr128(aesKey, aesIv, signer.sign(digest))
        return pair.publicKey + u32(certificate.size) + certificate + u32(signature.size) + signature
    }

    private fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)

    private fun u32(value: Int): ByteArray =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
}
