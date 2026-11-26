package com.andrerinas.openheadunit.connection.carplaynative.crypto

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SRP-6a server over the RFC 5054 3072-bit group and SHA-512, the AirPlay pair-setup variant.
 * Ported from xcertplay (https://github.com/shilapi/xcertplay) and LIVI (https://github.com/f-io/LIVI), crate livi-cp.
 */
class Srp6aServer private constructor(
    val salt: ByteArray,
    private val identifier: ByteArray,
    private val verifier: BigInteger,
    private val privateExponent: BigInteger,
) {
    class Verified(val sessionKey: ByteArray, val serverProof: ByteArray)

    private val publicB: BigInteger = (MULTIPLIER * verifier + G.modPow(privateExponent, N)).mod(N)

    /** B, padded to 384 bytes. */
    val publicKey: ByteArray get() = pad(publicB)

    /** Returns null for a zero A or a client proof that does not match. */
    fun verify(publicKeyA: ByteArray, clientProof: ByteArray): Verified? {
        val a = BigInteger(1, publicKeyA)
        if (a.mod(N).signum() == 0) return null
        val u = BigInteger(1, CarPlayCrypto.sha512(pad(a), pad(publicB)))
        val s = (a * verifier.modPow(u, N)).mod(N).modPow(privateExponent, N)
        val sessionKey = CarPlayCrypto.sha512(toBytes(s))
        val hashN = CarPlayCrypto.sha512(toBytes(N))
        val hashG = CarPlayCrypto.sha512(toBytes(G))
        val xor = ByteArray(hashN.size) { (hashN[it].toInt() xor hashG[it].toInt()).toByte() }
        val expected = CarPlayCrypto.sha512(
            xor, CarPlayCrypto.sha512(identifier), salt, pad(a), pad(publicB), sessionKey,
        )
        if (!MessageDigest.isEqual(expected, clientProof)) return null
        return Verified(sessionKey, CarPlayCrypto.sha512(pad(a), clientProof, sessionKey))
    }

    companion object {
        private const val SALT_BYTES = 16
        private const val PRIVATE_BYTES = 32
        private const val N_BYTES = 384
        private val random = SecureRandom()
        private val G = BigInteger.valueOf(5)
        private val COLON = byteArrayOf(':'.code.toByte())
        private val N = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74" +
            "020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F1437" +
            "4FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
            "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF05" +
            "98DA48361C55D39A69163FA8FD24CF5F83655D23DCA3AD961C62F356208552BB" +
            "9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
            "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183" +
            "995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A" +
            "85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7AB" +
            "F5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D8" +
            "7602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208" +
            "E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF",
            16,
        )
        private val MULTIPLIER = BigInteger(1, CarPlayCrypto.sha512(toBytes(N), pad(G)))

        fun start(username: String, password: String): Srp6aServer = withSecrets(
            username, password,
            ByteArray(SALT_BYTES).also(random::nextBytes),
            ByteArray(PRIVATE_BYTES).also(random::nextBytes),
        )

        /** For tests with fixed secrets. */
        internal fun withSecrets(username: String, password: String, salt: ByteArray, privateBytes: ByteArray): Srp6aServer {
            val identifier = username.toByteArray(Charsets.UTF_8)
            val inner = CarPlayCrypto.sha512(identifier, COLON, password.toByteArray(Charsets.UTF_8))
            val x = BigInteger(1, CarPlayCrypto.sha512(salt, inner))
            return Srp6aServer(salt, identifier, G.modPow(x, N), BigInteger(1, privateBytes))
        }

        internal fun toBytes(value: BigInteger): ByteArray {
            if (value.signum() == 0) return byteArrayOf(0)
            val encoded = value.toByteArray()
            return if (encoded.size > 1 && encoded[0] == 0.toByte()) encoded.copyOfRange(1, encoded.size) else encoded
        }

        internal fun pad(value: BigInteger): ByteArray {
            val bytes = toBytes(value)
            if (bytes.size >= N_BYTES) return bytes
            return ByteArray(N_BYTES).also { bytes.copyInto(it, N_BYTES - bytes.size) }
        }

        internal fun modulus(): BigInteger = N
        internal fun generator(): BigInteger = G
    }
}
