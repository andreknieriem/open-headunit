package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.airplay.PairingTlv.ascii
import com.andrerinas.openheadunit.connection.carplaynative.codec.Tlv8
import com.andrerinas.openheadunit.connection.carplaynative.crypto.CarPlayCrypto
import com.andrerinas.openheadunit.connection.carplaynative.crypto.Nonces

/**
 * The accessory side of AirPlay pair-verify: an ephemeral X25519 exchange, Ed25519 proofs against
 * the stored controller key, then the control channel keys.
 * Follows xcertplay airplay/PairVerify.kt (https://github.com/shilapi/xcertplay).
 */
class PairVerify(
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
) {
    class ControlKeys(val readKey: ByteArray, val writeKey: ByteArray)

    private var ephemeralPublicKey: ByteArray? = null
    private var clientEphemeralPublicKey: ByteArray? = null
    private var encryptionKey: ByteArray? = null

    /** The X25519 secret. The stream and event keys derive from it. */
    var sharedSecret: ByteArray? = null
        private set
    var controlKeys: ControlKeys? = null
        private set
    var verifiedControllerId: String? = null
        private set

    val isVerified: Boolean get() = controlKeys != null

    /** One TLV8 message in, one out. A bad message gets an error reply, never an exception. */
    fun handle(body: ByteArray): ByteArray {
        val tlv = Tlv8.decode(body)
        val state = PairingTlv.state(tlv)
        return try {
            when (state) {
                1 -> m2(tlv)
                3 -> m4(tlv)
                else -> null
            } ?: PairingTlv.error(state)
        } catch (_: RuntimeException) {
            PairingTlv.error(state)
        }
    }

    private fun m2(tlv: Map<Int, ByteArray>): ByteArray? {
        controlKeys = null
        verifiedControllerId = null
        val client = tlv[PairingTlv.PUBLIC_KEY] ?: return null
        if (client.size != KEY_SIZE) return null
        val pair = CarPlayCrypto.x25519Generate()
        val shared = CarPlayCrypto.x25519Shared(pair.privateKey, client)
        val key = CarPlayCrypto.hkdfSha512(shared, ascii(ENCRYPT_SALT), ascii(ENCRYPT_INFO))
        ephemeralPublicKey = pair.publicKey
        clientEphemeralPublicKey = client
        sharedSecret = shared
        encryptionKey = key

        val accessoryId = identity.pairingId.toByteArray(Charsets.UTF_8)
        val signature = CarPlayCrypto.ed25519Sign(identity.seed, pair.publicKey + accessoryId + client)
        val sub = Tlv8.encode(
            listOf(
                Tlv8.Item(PairingTlv.IDENTIFIER, accessoryId),
                Tlv8.Item(PairingTlv.SIGNATURE, signature),
            ),
        )
        return Tlv8.encode(
            listOf(
                Tlv8.Item(PairingTlv.STATE, byteArrayOf(2)),
                Tlv8.Item(PairingTlv.PUBLIC_KEY, pair.publicKey),
                Tlv8.Item(PairingTlv.ENCRYPTED_DATA, CarPlayCrypto.chachaSeal(key, Nonces.label(MSG02), sub)),
            ),
        )
    }

    private fun m4(tlv: Map<Int, ByteArray>): ByteArray? {
        val encrypted = tlv[PairingTlv.ENCRYPTED_DATA]
        val key = encryptionKey
        val shared = sharedSecret
        val own = ephemeralPublicKey
        val client = clientEphemeralPublicKey
        if (encrypted == null || key == null || shared == null || own == null || client == null) return null

        val plain = CarPlayCrypto.chachaOpen(key, Nonces.label(MSG03), encrypted) ?: return null
        val sub = Tlv8.decode(plain)
        val controllerId = sub[PairingTlv.IDENTIFIER] ?: return null
        val signature = sub[PairingTlv.SIGNATURE] ?: return null
        val id = String(controllerId, Charsets.UTF_8)
        val controllerKey = pairings.get(id) ?: return null
        if (!CarPlayCrypto.ed25519Verify(controllerKey, client + controllerId + own, signature)) return null

        // The phone reads with our write key, so the two labels cross.
        controlKeys = ControlKeys(
            readKey = CarPlayCrypto.hkdfSha512(shared, ascii(CONTROL_SALT), ascii(CONTROL_WRITE_KEY_INFO)),
            writeKey = CarPlayCrypto.hkdfSha512(shared, ascii(CONTROL_SALT), ascii(CONTROL_READ_KEY_INFO)),
        )
        verifiedControllerId = id
        return Tlv8.encode(listOf(Tlv8.Item(PairingTlv.STATE, byteArrayOf(4))))
    }

    private companion object {
        const val KEY_SIZE = 32
        const val ENCRYPT_SALT = "Pair-Verify-Encrypt-Salt"
        const val ENCRYPT_INFO = "Pair-Verify-Encrypt-Info"
        const val CONTROL_SALT = "Control-Salt"
        const val CONTROL_READ_KEY_INFO = "Control-Read-Encryption-Key"
        const val CONTROL_WRITE_KEY_INFO = "Control-Write-Encryption-Key"
        const val MSG02 = "PV-Msg02"
        const val MSG03 = "PV-Msg03"
    }
}
