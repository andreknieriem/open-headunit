package com.andrerinas.openheadunit.connection.wifi.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class P2pIdentityEditPolicyTest {

    private val current = StoredP2pIdentity("DIRECT-K7-HeadUnit", "abcDEF123456")

    private fun edit(
        name: String = "",
        passphrase: String = "",
        from: StoredP2pIdentity? = current,
        deviceName: String? = "Car",
        random: Random = Random(7),
    ) = P2pIdentityEditPolicy.edit(from, name, passphrase, deviceName, random)

    private fun accepted(outcome: P2pIdentityEdit) = outcome as P2pIdentityEdit.Accepted

    @Test
    fun `a changed password on the same name redraws the name's code`() {
        val result = accepted(edit(name = current.networkName, passphrase = "newPassword1"))
        assertTrue(result.recoded)
        assertEquals("newPassword1", result.identity.passphrase)
        assertNotEquals(current.networkName, result.identity.networkName)
        assertTrue(P2pGroupIdentityPolicy.isValidName(result.identity.networkName))
    }

    @Test
    fun `the redrawn name keeps everything after the code`() {
        val result = accepted(edit(passphrase = "newPassword1"))
        assertTrue(result.identity.networkName.endsWith("-HeadUnit"))
        assertEquals(current.networkName.length, result.identity.networkName.length)
    }

    @Test
    fun `a changed name keeps the password exactly`() {
        val result = accepted(edit(name = "DIRECT-K7-Garage"))
        assertEquals(current.passphrase, result.identity.passphrase)
        assertEquals("DIRECT-K7-Garage", result.identity.networkName)
        assertFalse(result.recoded)
    }

    @Test
    fun `both changed are taken verbatim, with no gratuitous redraw`() {
        val result = accepted(edit(name = "DIRECT-K7-Garage", passphrase = "newPassword1"))
        assertEquals(StoredP2pIdentity("DIRECT-K7-Garage", "newPassword1"), result.identity)
        assertFalse(result.recoded)
    }

    @Test
    fun `typing back what is stored changes nothing`() {
        assertEquals(
            P2pIdentityEdit.Unchanged,
            edit(name = current.networkName, passphrase = current.passphrase),
        )
    }

    @Test
    fun `both fields empty asks for a pair the app draws`() {
        assertEquals(P2pIdentityEdit.Cleared, edit())
    }

    @Test
    fun `a bare suffix is built into the shape the platform demands`() {
        val result = accepted(edit(name = "MyCar"))
        assertEquals("DIRECT-K7-MyCar", result.identity.networkName)
        assertTrue(P2pGroupIdentityPolicy.isValidName(result.identity.networkName))
    }

    @Test
    fun `a suffix of punctuation and non-ascii still yields a valid name`() {
        val result = accepted(edit(name = "Åsa's Car!", from = null))
        assertTrue(P2pGroupIdentityPolicy.isValidName(result.identity.networkName))
        assertTrue(result.identity.networkName.toByteArray(Charsets.UTF_8).size <= P2pGroupIdentityPolicy.MAX_NAME_BYTES)
    }

    @Test
    fun `a password below the minimum is rejected for its length`() {
        assertEquals(
            P2pIdentityEdit.Rejected(P2pIdentityRejection.PASSPHRASE_LENGTH),
            edit(passphrase = "1234567"),
        )
    }

    @Test
    fun `a password past the maximum is rejected for its length`() {
        assertEquals(
            P2pIdentityEdit.Rejected(P2pIdentityRejection.PASSPHRASE_LENGTH),
            edit(passphrase = "a".repeat(P2pGroupIdentityPolicy.MAX_PASSPHRASE_LENGTH + 1)),
        )
    }

    @Test
    fun `a password outside printable ascii is rejected for its charset`() {
        assertEquals(
            P2pIdentityEdit.Rejected(P2pIdentityRejection.PASSPHRASE_CHARSET),
            edit(passphrase = "pass\tword"),
        )
    }

    @Test
    fun `a typed DIRECT- name that is not the shape is rejected`() {
        assertEquals(
            P2pIdentityEdit.Rejected(P2pIdentityRejection.NAME_SHAPE),
            edit(name = "DIRECT-!!-Car"),
        )
    }

    @Test
    fun `a typed name past 32 bytes is rejected`() {
        assertEquals(
            P2pIdentityEdit.Rejected(P2pIdentityRejection.NAME_SHAPE),
            edit(name = "DIRECT-K7-" + "A".repeat(40)),
        )
    }

    @Test
    fun `an edit with nothing kept yet yields a valid pair`() {
        val result = accepted(edit(name = "MyCar", passphrase = "newPassword1", from = null))
        assertTrue(P2pGroupIdentityPolicy.isValid(result.identity))
    }

    @Test
    fun `a redraw is deterministic for a given seed`() {
        val a = accepted(edit(passphrase = "newPassword1", random = Random(3)))
        val b = accepted(edit(passphrase = "newPassword1", random = Random(3)))
        assertEquals(a.identity, b.identity)
    }

    @Test
    fun `every reason is a sentence`() {
        listOf(
            accepted(edit(passphrase = "newPassword1")),
            accepted(edit(name = "DIRECT-K7-Garage")),
        ).forEach { assertTrue(it.reason, it.reason.endsWith(".")) }
    }
}
