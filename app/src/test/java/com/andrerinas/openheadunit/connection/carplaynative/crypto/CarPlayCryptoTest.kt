package com.andrerinas.openheadunit.connection.carplaynative.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vectors come from RFC 7748, RFC 8032, RFC 8439, SP 800-38A and the LIVI `ts_vectors.json` file. */
class CarPlayCryptoTest {

    @Test
    fun x25519MatchesRfc7748Section5() {
        assertEquals(
            "c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552",
            CarPlayCrypto.x25519Shared(
                hex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                hex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c"),
            ).toHex(),
        )
        assertEquals(
            "95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957",
            CarPlayCrypto.x25519Shared(
                hex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"),
                hex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493"),
            ).toHex(),
        )
    }

    @Test
    fun x25519MatchesRfc7748Section6() {
        val alice = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val bob = hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb")
        val alicePublic = hex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")
        val bobPublic = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        val shared = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"
        assertEquals(shared, CarPlayCrypto.x25519Shared(alice, bobPublic).toHex())
        assertEquals(shared, CarPlayCrypto.x25519Shared(bob, alicePublic).toHex())
    }

    @Test
    fun x25519RefusesALowOrderPeerAndAgreesOnGeneratedKeys() {
        assertThrows(RuntimeException::class.java) {
            CarPlayCrypto.x25519Shared(ByteArray(32) { 7 }, ByteArray(32))
        }
        val a = CarPlayCrypto.x25519Generate()
        val b = CarPlayCrypto.x25519Generate()
        assertArrayEquals(
            CarPlayCrypto.x25519Shared(a.privateKey, b.publicKey),
            CarPlayCrypto.x25519Shared(b.privateKey, a.publicKey),
        )
    }

    @Test
    fun ed25519MatchesRfc8032Tests1To3() {
        val cases = listOf(
            Triple(
                "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
                "",
                "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a" to
                    "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                    "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
            ),
            Triple(
                "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
                "72",
                "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c" to
                    "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da" +
                    "085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
            ),
            Triple(
                "c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
                "af82",
                "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025" to
                    "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac" +
                    "18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a",
            ),
        )
        for ((seed, message, expected) in cases) {
            assertEquals(expected.first, CarPlayCrypto.ed25519Public(hex(seed)).toHex())
            val signature = CarPlayCrypto.ed25519Sign(hex(seed), hex(message))
            assertEquals(expected.second, signature.toHex())
            assertTrue(CarPlayCrypto.ed25519Verify(hex(expected.first), hex(message), signature))
        }
    }

    @Test
    fun ed25519RefusesATamperedMessageAndAShortKey() {
        val key = CarPlayCrypto.ed25519Generate()
        val signature = CarPlayCrypto.ed25519Sign(key.privateKey, "sign me".toByteArray())
        assertFalse(CarPlayCrypto.ed25519Verify(key.publicKey, "sign you".toByteArray(), signature))
        assertFalse(CarPlayCrypto.ed25519Verify(ByteArray(5), "sign me".toByteArray(), signature))
    }

    @Test
    fun chachaMatchesRfc8439Section282() {
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("070000004041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val plain = ("Ladies and Gentlemen of the class of '99: If I could offer you " +
            "only one tip for the future, sunscreen would be it.").toByteArray()
        val sealed = hex(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
                "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b61161ae10b594f09e26a7e902ecbd060" +
                "0691",
        )
        assertArrayEquals(sealed, CarPlayCrypto.chachaSeal(key, nonce, plain, aad))
        assertArrayEquals(plain, CarPlayCrypto.chachaOpen(key, nonce, sealed, aad))
    }

    @Test
    fun aTamperedTagWrongAadOrShortInputFailsAndTheOpenerRecovers() {
        val key = ByteArray(32) { (it * 7 + 3).toByte() }
        val nonce = Nonces.counter(9)
        val aad = byteArrayOf(9, 9, 9, 9)
        val plain = ByteArray(64) { it.toByte() }
        val sealed = CarPlayCrypto.chachaSeal(key, nonce, plain, aad)
        val tampered = sealed.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertNull(CarPlayCrypto.chachaOpen(key, nonce, tampered, aad))
        assertNull(CarPlayCrypto.chachaOpen(key, nonce, sealed, byteArrayOf(1)))
        assertNull(CarPlayCrypto.chachaOpen(key, nonce, ByteArray(15), aad))

        val opener = CarPlayCrypto.ChaChaOpener(key)
        val out = ByteArray(plain.size)
        assertNull(opener.open(9L, tampered, tampered.size, aad, out))
        assertEquals(plain.size, opener.open(9L, sealed, sealed.size, aad, out))
        assertArrayEquals(plain, out)
        assertNull(opener.open(9L, sealed, sealed.size, aad, ByteArray(plain.size - 1)))
    }

    @Test
    fun hkdfMatchesTheLiviVector() {
        assertEquals(
            "1685445548b7b7ca1bbf7f87f12bbe0565947c3b71d23fd1210346031274f100",
            CarPlayCrypto.hkdfSha512(
                ByteArray(32) { 9 }, "Control-Salt".toByteArray(), "Control-Read-Encryption-Key".toByteArray(),
            ).toHex(),
        )
    }

    @Test
    fun ed25519MatchesTheLiviVector() {
        assertEquals(
            "c30aa1a26a5de91b79931f02fdf8e338690c52abbd2cef801983ca6beb9141816638ae6776ffbf74b234515e2cdd385b75858a36da963948cfd505e1c3b34707",
            CarPlayCrypto.ed25519Sign(ByteArray(32) { 5 }, "sign me".toByteArray()).toHex(),
        )
    }

    @Test
    fun theLiviChachaLabelAndNonceVectorsMatch() {
        assertEquals(
            "f550e0b9ef38a5d0558f3b5daa13b289de319324a5a10d",
            CarPlayCrypto.chachaSeal(
                ByteArray(32) { 4 }, Nonces.label("PV-Msg02"), "sub tlv".toByteArray(),
            ).toHex(),
        )
        assertEquals("000000000504030201000000", Nonces.counter(0x01_0203_0405).toHex())
    }

    @Test
    fun aNonceLabelLongerThanEightBytesIsCut() {
        assertEquals("000000004142434445464748", Nonces.label("ABCDEFGHIJ").toHex())
    }

    @Test
    fun aesCtrAndTheDigestsMatchTheirVectors() {
        assertEquals(
            "76f6679a1ec754e4029b30579257e2794ce0e84294f7f092a0b12643958912",
            CarPlayCrypto.aesCtr128(
                ByteArray(16) { 1 }, ByteArray(16) { 2 }, "a signature of some length here".toByteArray(),
            ).toHex(),
        )
        assertEquals(
            "874d6191b620e3261bef6864990db6ce9806f66b7970fdff8617187bb9fffdff",
            CarPlayCrypto.aesCtr128(
                hex("2b7e151628aed2a6abf7158809cf4f3c"),
                hex("f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"),
                hex("6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51"),
            ).toHex(),
        )
        assertEquals(
            "0cdba6f65e812a105fae619e868fca876317521e",
            CarPlayCrypto.sha1("AES-KEY".toByteArray(), ByteArray(32) { 3 }).toHex(),
        )
        assertEquals(
            "fb8e20fc2e4c3f248c60c39bd652f3c1347298bb977b8b4d5903b85055620603",
            CarPlayCrypto.sha256("a".toByteArray(), "b".toByteArray()).toHex(),
        )
    }

    /** The AirPlay control record: a 2-byte little-endian length as AAD, then the sealed body. */
    private fun controlRecords(key: ByteArray, plain: ByteArray): ByteArray {
        var out = ByteArray(0)
        var counter = 0L
        var offset = 0
        do {
            val chunk = plain.copyOfRange(offset, minOf(offset + 0x4000, plain.size))
            val header = byteArrayOf(chunk.size.toByte(), (chunk.size shr 8).toByte())
            out += header + CarPlayCrypto.chachaSeal(key, Nonces.counter(counter++), chunk, header)
            offset += 0x4000
        } while (offset < plain.size)
        return out
    }

    @Test
    fun theLiviControlCipherVectorsMatch() {
        val key = ByteArray(32) { 2 }
        val first = controlRecords(key, ByteArray(16384) { 0x41 } + "tail".toByteArray())
        assertEquals(16424, first.size)
        assertEquals(
            "d9a80f2f38ddd9c2af5f3ac536cd481e4dcfa9dd38c87b6d400289ccfc8cabd2",
            CarPlayCrypto.sha256(first).toHex(),
        )
    }

    @Test
    fun theLiviSecondControlRecordVectorMatches() {
        val key = ByteArray(32) { 2 }
        val header = byteArrayOf(6, 0)
        val sealed = CarPlayCrypto.chachaSeal(key, Nonces.counter(2), "second".toByteArray(), header)
        assertEquals("0600f46717c443a865be9990d158e4679a8fd58f23a1a863", (header + sealed).toHex())
    }
}
