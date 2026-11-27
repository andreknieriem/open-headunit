package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.codec.Tlv8
import com.andrerinas.openheadunit.connection.carplaynative.crypto.CarPlayCrypto
import com.andrerinas.openheadunit.connection.carplaynative.crypto.Nonces
import com.andrerinas.openheadunit.connection.carplaynative.crypto.Srp6aServer
import java.math.BigInteger

/** The controller (phone) side of pair-setup and pair-verify, written from the HomeKit message layout. */
internal class PairingClient(val id: String = "phone-1") {
    val seed: ByteArray = CarPlayCrypto.ed25519Generate().privateKey
    val publicKey: ByteArray = CarPlayCrypto.ed25519Public(seed)
    private val idBytes = id.toByteArray()

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)
    private fun tlv(vararg items: Pair<Int, ByteArray>) = Tlv8.encode(items.map { Tlv8.Item(it.first, it.second) })

    class Setup(val reply: Map<Int, ByteArray>, val sessionKey: ByteArray?)

    /** Runs M1 to M6. Returns the M6 reply and the accessory proof check. */
    fun pairSetup(server: PairSetup, code: String = "3939", announcedKey: ByteArray = publicKey): Setup =
        pairSetup(server::handle, code, announcedKey)

    /** The same exchange over any channel that carries one TLV8 body and returns the reply. */
    fun pairSetup(handle: (ByteArray) -> ByteArray, code: String = "3939", announcedKey: ByteArray = publicKey): Setup {
        val m2 = Tlv8.decode(handle(tlv(PairingTlv.STATE to byteArrayOf(1))))
        val serverKey = BigInteger(1, m2.getValue(PairingTlv.PUBLIC_KEY))
        val salt = m2.getValue(PairingTlv.SALT)
        val n = Srp6aServer.modulus()
        val g = Srp6aServer.generator()
        val k = BigInteger(1, CarPlayCrypto.sha512(Srp6aServer.toBytes(n), Srp6aServer.pad(g)))
        val secret = BigInteger(1, ByteArray(32) { 0x42 })
        val a = g.modPow(secret, n)
        val u = BigInteger(1, CarPlayCrypto.sha512(Srp6aServer.pad(a), Srp6aServer.pad(serverKey)))
        val x = BigInteger(1, CarPlayCrypto.sha512(salt, CarPlayCrypto.sha512(ascii("Pair-Setup"), ascii(":"), ascii(code))))
        val base = (serverKey + n * k - (k * g.modPow(x, n)).mod(n)).mod(n)
        val key = CarPlayCrypto.sha512(Srp6aServer.toBytes(base.modPow(secret + u * x, n)))
        val hn = CarPlayCrypto.sha512(Srp6aServer.toBytes(n))
        val hg = CarPlayCrypto.sha512(Srp6aServer.toBytes(g))
        val xor = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        val m1 = CarPlayCrypto.sha512(xor, CarPlayCrypto.sha512(ascii("Pair-Setup")), salt, Srp6aServer.pad(a), Srp6aServer.pad(serverKey), key)

        val m4 = Tlv8.decode(
            handle(tlv(PairingTlv.STATE to byteArrayOf(3), PairingTlv.PUBLIC_KEY to Srp6aServer.pad(a), PairingTlv.PROOF to m1)),
        )
        if (m4[PairingTlv.ERROR] != null) return Setup(m4, null)
        val encKey = CarPlayCrypto.hkdfSha512(key, ascii("Pair-Setup-Encrypt-Salt"), ascii("Pair-Setup-Encrypt-Info"))
        val signKey = CarPlayCrypto.hkdfSha512(key, ascii("Pair-Setup-Controller-Sign-Salt"), ascii("Pair-Setup-Controller-Sign-Info"))
        val sub = tlv(
            PairingTlv.IDENTIFIER to idBytes,
            PairingTlv.PUBLIC_KEY to announcedKey,
            PairingTlv.SIGNATURE to CarPlayCrypto.ed25519Sign(seed, signKey + idBytes + announcedKey),
        )
        val sealed = CarPlayCrypto.chachaSeal(encKey, Nonces.label("PS-Msg05"), sub)
        val m6 = Tlv8.decode(handle(tlv(PairingTlv.STATE to byteArrayOf(5), PairingTlv.ENCRYPTED_DATA to sealed)))
        return Setup(m6, key)
    }

    /** The accessory's identity and signature in an M6 reply, checked against its public key. */
    fun accessoryProofOk(setup: Setup, accessoryPublicKey: ByteArray, accessoryId: String): Boolean {
        val key = setup.sessionKey ?: return false
        val encKey = CarPlayCrypto.hkdfSha512(key, ascii("Pair-Setup-Encrypt-Salt"), ascii("Pair-Setup-Encrypt-Info"))
        val plain = CarPlayCrypto.chachaOpen(encKey, Nonces.label("PS-Msg06"), setup.reply.getValue(PairingTlv.ENCRYPTED_DATA)) ?: return false
        val sub = Tlv8.decode(plain)
        val signKey = CarPlayCrypto.hkdfSha512(key, ascii("Pair-Setup-Accessory-Sign-Salt"), ascii("Pair-Setup-Accessory-Sign-Info"))
        val signed = signKey + sub.getValue(PairingTlv.IDENTIFIER) + sub.getValue(PairingTlv.PUBLIC_KEY)
        return String(sub.getValue(PairingTlv.IDENTIFIER)) == accessoryId &&
            sub.getValue(PairingTlv.PUBLIC_KEY).contentEquals(accessoryPublicKey) &&
            CarPlayCrypto.ed25519Verify(accessoryPublicKey, signed, sub.getValue(PairingTlv.SIGNATURE))
    }

    class Verified(val shared: ByteArray, val m4: Map<Int, ByteArray>, val m2Ok: Boolean)

    /** Runs pair-verify M1 to M4 against [server], which knows [accessoryPublicKey] for the M2 check. */
    fun pairVerify(server: PairVerify, accessoryPublicKey: ByteArray, accessoryId: String): Verified =
        pairVerify(server::handle, accessoryPublicKey, accessoryId)

    fun pairVerify(handle: (ByteArray) -> ByteArray, accessoryPublicKey: ByteArray, accessoryId: String): Verified {
        val eph = CarPlayCrypto.x25519Generate()
        val m2 = Tlv8.decode(handle(tlv(PairingTlv.STATE to byteArrayOf(1), PairingTlv.PUBLIC_KEY to eph.publicKey)))
        val serverEph = m2.getValue(PairingTlv.PUBLIC_KEY)
        val shared = CarPlayCrypto.x25519Shared(eph.privateKey, serverEph)
        val key = CarPlayCrypto.hkdfSha512(shared, ascii("Pair-Verify-Encrypt-Salt"), ascii("Pair-Verify-Encrypt-Info"))
        val plain = CarPlayCrypto.chachaOpen(key, Nonces.label("PV-Msg02"), m2.getValue(PairingTlv.ENCRYPTED_DATA))!!
        val sub = Tlv8.decode(plain)
        val m2Ok = String(sub.getValue(PairingTlv.IDENTIFIER)) == accessoryId &&
            CarPlayCrypto.ed25519Verify(
                accessoryPublicKey, serverEph + accessoryId.toByteArray() + eph.publicKey, sub.getValue(PairingTlv.SIGNATURE),
            )
        val mine = tlv(
            PairingTlv.IDENTIFIER to idBytes,
            PairingTlv.SIGNATURE to CarPlayCrypto.ed25519Sign(seed, eph.publicKey + idBytes + serverEph),
        )
        val m3 = tlv(
            PairingTlv.STATE to byteArrayOf(3),
            PairingTlv.ENCRYPTED_DATA to CarPlayCrypto.chachaSeal(key, Nonces.label("PV-Msg03"), mine),
        )
        return Verified(shared, Tlv8.decode(handle(m3)), m2Ok)
    }
}
