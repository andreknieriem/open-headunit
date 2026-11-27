package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.crypto.CarPlayCrypto
import java.util.UUID

/**
 * The long-term Ed25519 identity of this head unit. The caller stores [encode] in private storage.
 * Follows xcertplay airplay/AirPlayIdentity.kt (https://github.com/shilapi/xcertplay).
 */
class AirPlayIdentity(val seed: ByteArray, val pairingId: String) {
    val publicKey: ByteArray = CarPlayCrypto.ed25519Public(seed)

    /** The 32-byte seed, then the pairing id as UTF-8. */
    fun encode(): ByteArray = seed + pairingId.toByteArray(Charsets.UTF_8)

    override fun toString(): String = "AirPlayIdentity($pairingId)"

    companion object {
        private const val SEED_SIZE = 32

        fun generate(): AirPlayIdentity = AirPlayIdentity(CarPlayCrypto.ed25519Generate().privateKey, UUID.randomUUID().toString())

        /** Returns null for bytes that [encode] did not make. */
        fun decode(bytes: ByteArray): AirPlayIdentity? {
            if (bytes.size <= SEED_SIZE) return null
            val id = String(bytes, SEED_SIZE, bytes.size - SEED_SIZE, Charsets.UTF_8)
            return AirPlayIdentity(bytes.copyOf(SEED_SIZE), id)
        }

        /** Reads the stored identity, or makes one and writes it. An unreadable record is replaced. */
        fun loadOrCreate(read: () -> ByteArray?, write: (ByteArray) -> Unit): AirPlayIdentity {
            read()?.let { decode(it) }?.let { return it }
            return generate().also { write(it.encode()) }
        }
    }
}
