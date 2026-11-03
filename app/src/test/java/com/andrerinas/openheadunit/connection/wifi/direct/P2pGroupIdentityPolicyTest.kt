package com.andrerinas.openheadunit.connection.wifi.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class P2pGroupIdentityPolicyTest {

    private val kept = StoredP2pIdentity("DIRECT-K7-HeadUnit", "abcDEF123456")

    // --- decide ---

    @Test
    fun `keeping with a stored pair asks for exactly that pair, persistent, and stores nothing`() {
        val choice = P2pGroupIdentityPolicy.decide(keepIdentity = true, stored = kept, deviceName = "Car")
        assertEquals(P2pGroupIdentity.Named("DIRECT-K7-HeadUnit", "abcDEF123456", persistent = true), choice.identity)
        assertNull(choice.toStore)
    }

    @Test
    fun `keeping with nothing stored draws a pair, asks for it persistent, and says to store it`() {
        val choice = P2pGroupIdentityPolicy.decide(keepIdentity = true, stored = null, deviceName = "Car", random = Random(1))
        assertTrue(choice.identity.persistent)
        assertEquals(StoredP2pIdentity(choice.identity.networkName, choice.identity.passphrase), choice.toStore)
        assertTrue(P2pGroupIdentityPolicy.isValid(choice.toStore!!))
    }

    @Test
    fun `keeping with an unusable stored pair replaces both halves together`() {
        val bad = StoredP2pIdentity("DIRECT-K7-HeadUnit", "short")
        val choice = P2pGroupIdentityPolicy.decide(keepIdentity = true, stored = bad, deviceName = "Car", random = Random(2))
        val stored = choice.toStore!!
        assertNotEquals(bad.networkName, stored.networkName)
        assertNotEquals(bad.passphrase, stored.passphrase)
        assertTrue(choice.reason.contains("not usable"))
    }

    @Test
    fun `not keeping draws a fresh temporary pair every time and never stores`() {
        val a = P2pGroupIdentityPolicy.decide(keepIdentity = false, stored = kept, deviceName = "Car", random = Random(3))
        val b = P2pGroupIdentityPolicy.decide(keepIdentity = false, stored = kept, deviceName = "Car", random = Random(4))
        assertFalse(a.identity.persistent)
        assertNull(a.toStore)
        assertNotEquals(kept.networkName, a.identity.networkName)
        assertNotEquals(a.identity.passphrase, b.identity.passphrase)
    }

    @Test
    fun `the same seed draws the same pair, so a draw is a pure function of its inputs`() {
        val a = P2pGroupIdentityPolicy.mint("Car", Random(9))
        val b = P2pGroupIdentityPolicy.mint("Car", Random(9))
        assertEquals(a, b)
    }

    @Test
    fun `every reason is a full sentence naming the network`() {
        for (choice in listOf(
            P2pGroupIdentityPolicy.decide(true, kept, "Car"),
            P2pGroupIdentityPolicy.decide(true, null, "Car", Random(5)),
            P2pGroupIdentityPolicy.decide(false, kept, "Car", Random(6)),
        )) {
            assertTrue(choice.reason, choice.reason.startsWith("group identity:"))
            assertTrue(choice.reason, choice.reason.contains(choice.identity.networkName))
        }
    }

    // --- the platform's shape rules ---

    @Test
    fun `a name is DIRECT-, two characters, a dash and the unit's name`() {
        assertEquals("DIRECT-AB-MyCar", P2pGroupIdentityPolicy.networkName("AB", "My Car"))
    }

    @Test
    fun `a missing or unprintable unit name falls back to HeadUnit`() {
        assertEquals("DIRECT-AB-HeadUnit", P2pGroupIdentityPolicy.networkName("AB", null))
        assertEquals("DIRECT-AB-HeadUnit", P2pGroupIdentityPolicy.networkName("AB", "  "))
        assertEquals("DIRECT-AB-HeadUnit", P2pGroupIdentityPolicy.networkName("AB", "日本"))
        // Non-ASCII letters are dropped, not transliterated: the name is one byte a character.
        assertEquals("DIRECT-AB-ber", P2pGroupIdentityPolicy.networkName("AB", "Über"))
    }

    @Test
    fun `a long unit name is cut so the whole name stays within 32 bytes`() {
        val name = P2pGroupIdentityPolicy.networkName("AB", "A".repeat(80))
        assertTrue(name.toByteArray().size <= P2pGroupIdentityPolicy.MAX_NAME_BYTES)
        assertTrue(P2pGroupIdentityPolicy.isValidName(name))
    }

    @Test
    fun `a minted pair always satisfies the platform's validators`() {
        for (seed in 0 until 200) {
            val pair = P2pGroupIdentityPolicy.mint("Some Head Unit 2000", Random(seed))
            assertTrue(pair.networkName, P2pGroupIdentityPolicy.isValidName(pair.networkName))
            assertTrue(pair.passphrase, P2pGroupIdentityPolicy.isValidPassphrase(pair.passphrase))
            assertEquals(P2pGroupIdentityPolicy.PASSPHRASE_LENGTH, pair.passphrase.length)
        }
    }

    @Test
    fun `names that the builder would reject are not valid here either`() {
        assertFalse(P2pGroupIdentityPolicy.isValidName("HeadUnit"))
        assertFalse(P2pGroupIdentityPolicy.isValidName("DIRECT-"))
        assertFalse(P2pGroupIdentityPolicy.isValidName("DIRECT-A-HeadUnit"))
        assertFalse(P2pGroupIdentityPolicy.isValidName("DIRECT-AB-" + "x".repeat(40)))
        assertTrue(P2pGroupIdentityPolicy.isValidName("DIRECT-ab"))
    }

    @Test
    fun `passphrases are 8 to 63 printable ASCII characters`() {
        assertFalse(P2pGroupIdentityPolicy.isValidPassphrase("1234567"))
        assertTrue(P2pGroupIdentityPolicy.isValidPassphrase("12345678"))
        assertTrue(P2pGroupIdentityPolicy.isValidPassphrase("x".repeat(63)))
        assertFalse(P2pGroupIdentityPolicy.isValidPassphrase("x".repeat(64)))
        assertFalse(P2pGroupIdentityPolicy.isValidPassphrase("pässword1"))
    }

    // --- persistent profile classification ---

    @Test
    fun `a stored network id means the profile is persistent and a negative one means temporary`() {
        assertTrue(P2pGroupIdentityPolicy.isPersistentNetworkId(0))
        assertTrue(P2pGroupIdentityPolicy.isPersistentNetworkId(7))
        assertFalse(P2pGroupIdentityPolicy.isPersistentNetworkId(-1))
        assertFalse(P2pGroupIdentityPolicy.isPersistentNetworkId(-2))
    }

    @Test
    fun `an unavailable network id cannot identify a stored profile`() {
        assertFalse(P2pGroupIdentityPolicy.isPersistentNetworkId(null))
    }

}
