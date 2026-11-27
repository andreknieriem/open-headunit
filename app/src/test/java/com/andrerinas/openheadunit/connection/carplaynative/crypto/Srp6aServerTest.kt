package com.andrerinas.openheadunit.connection.carplaynative.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigInteger

class Srp6aServerTest {

    private val n = Srp6aServer.modulus()
    private val g = Srp6aServer.generator()

    private fun bigInt(bytes: ByteArray) = BigInteger(1, bytes)
    private fun pad(v: BigInteger) = Srp6aServer.pad(v)
    private fun toBytes(v: BigInteger) = Srp6aServer.toBytes(v)

    /** An in-test client: x, S and M1 per RFC 5054 with the AirPlay proof layout. */
    private class ClientRun(val a: ByteArray, val m1: ByteArray, val key: ByteArray)

    private fun client(server: Srp6aServer, user: String, password: String): ClientRun {
        val k = bigInt(CarPlayCrypto.sha512(toBytes(n), pad(g)))
        val secret = BigInteger(1, ByteArray(32) { 0x42 })
        val bigA = g.modPow(secret, n)
        val bigB = bigInt(server.publicKey)
        val u = bigInt(CarPlayCrypto.sha512(pad(bigA), pad(bigB)))
        val x = bigInt(
            CarPlayCrypto.sha512(
                server.salt,
                CarPlayCrypto.sha512(user.toByteArray(), ":".toByteArray(), password.toByteArray()),
            ),
        )
        val base = (bigB + n * k - (k * g.modPow(x, n)).mod(n)).mod(n)
        val s = base.modPow(secret + u * x, n)
        val key = CarPlayCrypto.sha512(toBytes(s))
        val hn = CarPlayCrypto.sha512(toBytes(n))
        val hg = CarPlayCrypto.sha512(toBytes(g))
        val xor = ByteArray(hn.size) { (hn[it].toInt() xor hg[it].toInt()).toByte() }
        val m1 = CarPlayCrypto.sha512(
            xor, CarPlayCrypto.sha512(user.toByteArray()), server.salt, pad(bigA), pad(bigB), key,
        )
        return ClientRun(pad(bigA), m1, key)
    }

    @Test
    fun theSaltAndPublicKeyMatchTheLiviVector() {
        val server = Srp6aServer.withSecrets("Pair-Setup", "3939", ByteArray(16) { 0x11 }, ByteArray(32) { 0x22 })
        assertEquals("11111111111111111111111111111111", server.salt.toHex())
        assertEquals(384, server.publicKey.size)
        assertEquals(
            "9f1e3e2f30504e46d1af262800b47ca35075602be0bf000102d449b4d8d9c9ce",
            CarPlayCrypto.sha256(server.publicKey).toHex(),
        )
    }

    @Test
    fun aClientWithTheCodeVerifies() {
        val server = Srp6aServer.start("Pair-Setup", "3939")
        val run = client(server, "Pair-Setup", "3939")
        val verified = server.verify(run.a, run.m1)
        assertNotNull(verified)
        assertArrayEquals(run.key, verified!!.sessionKey)
        assertArrayEquals(CarPlayCrypto.sha512(run.a, run.m1, run.key), verified.serverProof)
    }

    @Test
    fun aWrongCodeOrAZeroKeyFails() {
        val server = Srp6aServer.start("Pair-Setup", "3939")
        val wrong = client(server, "Pair-Setup", "0000")
        assertNull(server.verify(wrong.a, wrong.m1))
        assertNull(server.verify(toBytes(n), wrong.m1))
        assertNull(server.verify(byteArrayOf(0), wrong.m1))
    }

    @Test
    fun smallValuesPadAndZeroIsOneByte() {
        assertEquals(384, pad(BigInteger.valueOf(5)).size)
        assertArrayEquals(byteArrayOf(0), toBytes(BigInteger.ZERO))
    }
}
