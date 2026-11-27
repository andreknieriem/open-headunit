package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.airplay.PairingTlv.ascii
import com.andrerinas.openheadunit.connection.carplaynative.codec.Tlv8
import com.andrerinas.openheadunit.connection.carplaynative.crypto.CarPlayCrypto
import com.andrerinas.openheadunit.connection.carplaynative.crypto.Nonces
import com.andrerinas.openheadunit.connection.carplaynative.crypto.Srp6aServer

/**
 * The accessory side of AirPlay pair-setup: SRP-6a with the fixed code 3939, then the exchange of
 * long-term Ed25519 keys. Follows xcertplay airplay/PairSetup.kt (https://github.com/shilapi/xcertplay).
 */
class PairSetup(
    private val identity: AirPlayIdentity,
    private val pairings: PairingStore,
) {
    private var srp: Srp6aServer? = null
    private var sessionKey: ByteArray? = null

    var complete: Boolean = false
        private set

    /** One TLV8 message in, one out. A bad message gets an error reply, never an exception. */
    fun handle(body: ByteArray): ByteArray {
        val tlv = Tlv8.decode(body)
        val state = PairingTlv.state(tlv)
        return try {
            when (state) {
                1 -> m2()
                3 -> m4(tlv)
                5 -> m6(tlv)
                else -> PairingTlv.error(state)
            } ?: PairingTlv.error(state)
        } catch (_: RuntimeException) {
            PairingTlv.error(state)
        }
    }

    private fun m2(): ByteArray {
        sessionKey = null
        complete = false
        val session = Srp6aServer.start(SETUP_USERNAME, SETUP_CODE)
        srp = session
        return Tlv8.encode(
            listOf(
                Tlv8.Item(PairingTlv.STATE, byteArrayOf(2)),
                Tlv8.Item(PairingTlv.PUBLIC_KEY, session.publicKey),
                Tlv8.Item(PairingTlv.SALT, session.salt),
            ),
        )
    }

    private fun m4(tlv: Map<Int, ByteArray>): ByteArray? {
        val publicKeyA = tlv[PairingTlv.PUBLIC_KEY]
        val proof = tlv[PairingTlv.PROOF]
        val session = srp
        if (session == null || publicKeyA == null || proof == null) return null
        srp = null
        val verified = session.verify(publicKeyA, proof) ?: return null
        sessionKey = verified.sessionKey
        return Tlv8.encode(
            listOf(
                Tlv8.Item(PairingTlv.STATE, byteArrayOf(4)),
                Tlv8.Item(PairingTlv.PROOF, verified.serverProof),
            ),
        )
    }

    private fun m6(tlv: Map<Int, ByteArray>): ByteArray? {
        val encrypted = tlv[PairingTlv.ENCRYPTED_DATA]
        val key = sessionKey
        if (key == null || encrypted == null) return null
        sessionKey = null

        val encryptionKey = CarPlayCrypto.hkdfSha512(key, ascii(ENCRYPT_SALT), ascii(ENCRYPT_INFO))
        val plain = CarPlayCrypto.chachaOpen(encryptionKey, Nonces.label(MSG05), encrypted) ?: return null
        val sub = Tlv8.decode(plain)
        val controllerId = sub[PairingTlv.IDENTIFIER]
        val controllerKey = sub[PairingTlv.PUBLIC_KEY]
        val controllerSignature = sub[PairingTlv.SIGNATURE]
        if (controllerId == null || controllerKey == null || controllerSignature == null) return null
        if (controllerKey.size != KEY_SIZE) return null

        val controllerSignKey = CarPlayCrypto.hkdfSha512(key, ascii(CONTROLLER_SIGN_SALT), ascii(CONTROLLER_SIGN_INFO))
        val signed = controllerSignKey + controllerId + controllerKey
        if (!CarPlayCrypto.ed25519Verify(controllerKey, signed, controllerSignature)) return null
        pairings.save(String(controllerId, Charsets.UTF_8), controllerKey)

        val accessoryId = identity.pairingId.toByteArray(Charsets.UTF_8)
        val accessorySignKey = CarPlayCrypto.hkdfSha512(key, ascii(ACCESSORY_SIGN_SALT), ascii(ACCESSORY_SIGN_INFO))
        val accessorySignature = CarPlayCrypto.ed25519Sign(identity.seed, accessorySignKey + accessoryId + identity.publicKey)
        val reply = Tlv8.encode(
            listOf(
                Tlv8.Item(PairingTlv.IDENTIFIER, accessoryId),
                Tlv8.Item(PairingTlv.PUBLIC_KEY, identity.publicKey),
                Tlv8.Item(PairingTlv.SIGNATURE, accessorySignature),
            ),
        )
        complete = true
        return Tlv8.encode(
            listOf(
                Tlv8.Item(PairingTlv.STATE, byteArrayOf(6)),
                Tlv8.Item(PairingTlv.ENCRYPTED_DATA, CarPlayCrypto.chachaSeal(encryptionKey, Nonces.label(MSG06), reply)),
            ),
        )
    }

    private companion object {
        const val SETUP_USERNAME = "Pair-Setup"
        const val SETUP_CODE = "3939"
        const val KEY_SIZE = 32
        const val ENCRYPT_SALT = "Pair-Setup-Encrypt-Salt"
        const val ENCRYPT_INFO = "Pair-Setup-Encrypt-Info"
        const val CONTROLLER_SIGN_SALT = "Pair-Setup-Controller-Sign-Salt"
        const val CONTROLLER_SIGN_INFO = "Pair-Setup-Controller-Sign-Info"
        const val ACCESSORY_SIGN_SALT = "Pair-Setup-Accessory-Sign-Salt"
        const val ACCESSORY_SIGN_INFO = "Pair-Setup-Accessory-Sign-Info"
        const val MSG05 = "PS-Msg05"
        const val MSG06 = "PS-Msg06"
    }
}
