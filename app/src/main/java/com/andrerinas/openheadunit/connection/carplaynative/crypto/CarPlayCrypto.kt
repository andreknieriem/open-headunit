package com.andrerinas.openheadunit.connection.carplaynative.crypto

import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * BouncyCastle lightweight primitives for CarPlay pairing and the AirPlay channels.
 * The code never registers a JCA provider, so R8 needs no keep rule.
 * Ported from xcertplay (https://github.com/shilapi/xcertplay).
 */
object CarPlayCrypto {
    private const val MAC_BITS = 128
    const val TAG_SIZE = 16
    private val random = SecureRandom()

    class KeyPair(val privateKey: ByteArray, val publicKey: ByteArray)

    fun x25519Generate(): KeyPair {
        val key = X25519PrivateKeyParameters(random)
        return KeyPair(key.encoded, key.generatePublicKey().encoded)
    }

    /** Throws when the peer key is a low-order point (an all-zero secret). */
    fun x25519Shared(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        val shared = ByteArray(X25519PrivateKeyParameters.SECRET_SIZE)
        X25519PrivateKeyParameters(privateKey, 0)
            .generateSecret(X25519PublicKeyParameters(peerPublicKey, 0), shared, 0)
        return shared
    }

    fun ed25519Generate(): KeyPair {
        val key = Ed25519PrivateKeyParameters(random)
        return KeyPair(key.encoded, key.generatePublicKey().encoded)
    }

    fun ed25519Public(seed: ByteArray): ByteArray =
        Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    fun ed25519Sign(seed: ByteArray, data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(publicKey: ByteArray, data: ByteArray, signature: ByteArray): Boolean = try {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        signer.update(data, 0, data.size)
        signer.verifySignature(signature)
    } catch (_: RuntimeException) {
        false
    }

    fun hkdfSha512(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val generator = HKDFBytesGenerator(SHA512Digest())
        generator.init(HKDFParameters(ikm, salt, info))
        return ByteArray(length).also { generator.generateBytes(it, 0, length) }
    }

    fun sha1(vararg parts: ByteArray): ByteArray = digest("SHA-1", parts)
    fun sha256(vararg parts: ByteArray): ByteArray = digest("SHA-256", parts)
    fun sha512(vararg parts: ByteArray): ByteArray = digest("SHA-512", parts)

    /** AES-128-CTR; the same call encrypts and decrypts. */
    fun aesCtr128(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** Returns ciphertext followed by the 16-byte tag. */
    fun chachaSeal(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), MAC_BITS, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(plain.size))
        val n = cipher.processBytes(plain, 0, plain.size, out, 0)
        cipher.doFinal(out, n)
        return out
    }

    /** Returns null when the tag does not verify, so a bad frame is a value, not an exception. */
    fun chachaOpen(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray = ByteArray(0)): ByteArray? {
        if (sealed.size < TAG_SIZE) return null
        val out = ByteArray(sealed.size - TAG_SIZE)
        val len = ChaChaOpener(key).open(nonce, sealed, sealed.size, aad, out) ?: return null
        return if (len == out.size) out else out.copyOf(len)
    }

    /** Opens many frames under one key and writes into a caller buffer, so a stream allocates once. */
    class ChaChaOpener(key: ByteArray) {
        private val cipher = ChaCha20Poly1305()
        private val keyParameter = KeyParameter(key.copyOf())
        private val nonce = ByteArray(Nonces.SIZE)

        /** Opens with the counter nonce of [Nonces.counter]. Returns the plain length or null. */
        fun open(counter: Long, sealed: ByteArray, sealedLength: Int, aad: ByteArray, out: ByteArray): Int? {
            Nonces.fillCounter(nonce, counter)
            return open(nonce, sealed, sealedLength, aad, out)
        }

        /** After a null, [out] may hold unchecked bytes: do not read it. */
        fun open(nonce: ByteArray, sealed: ByteArray, sealedLength: Int, aad: ByteArray, out: ByteArray): Int? {
            if (sealedLength < TAG_SIZE || sealedLength > sealed.size || out.size < sealedLength - TAG_SIZE) return null
            return try {
                cipher.init(false, AEADParameters(keyParameter, MAC_BITS, nonce, aad))
                val n = cipher.processBytes(sealed, 0, sealedLength, out, 0)
                n + cipher.doFinal(out, n)
            } catch (_: org.bouncycastle.crypto.InvalidCipherTextException) {
                null
            }
        }
    }

    private fun digest(name: String, parts: Array<out ByteArray>): ByteArray {
        val d = MessageDigest.getInstance(name)
        for (part in parts) d.update(part)
        return d.digest()
    }
}
