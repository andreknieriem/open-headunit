package com.andrerinas.openheadunit.thirdparty

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThirdPartyForwardPolicyTest {

    private fun field(number: Int, value: ByteArray): ByteArray =
        byteArrayOf(((number shl 3) or 2).toByte(), value.size.toByte()) + value

    private fun varintField(number: Int, value: Int): ByteArray = byteArrayOf((number shl 3).toByte(), value.toByte())

    @Test
    fun albumArtIsRemovedFromMediaMetadataUnlessAllowed() {
        val song = field(1, "Song".toByteArray())
        val artist = field(2, "Artist".toByteArray())
        val art = field(4, ByteArray(40) { 7 })
        val duration = varintField(6, 200 and 0x7F)
        val payload = song + artist + art + duration

        val stripped = ThirdPartyForwardPolicy.payloadFor("media", 32771, payload, includeAlbumArt = false)
        assertArrayEquals(song + artist + duration, stripped)

        val kept = ThirdPartyForwardPolicy.payloadFor("media", 32771, payload, includeAlbumArt = true)
        assertArrayEquals(payload, kept)
    }

    @Test
    fun otherMessagesAreNeverTouched() {
        val payload = field(4, ByteArray(10) { 1 })   // field 4 means something else in a navigation message
        assertArrayEquals(payload, ThirdPartyForwardPolicy.payloadFor("nav", 32774, payload, includeAlbumArt = false))
        assertArrayEquals(payload, ThirdPartyForwardPolicy.payloadFor("media", 32769, payload, includeAlbumArt = false))
    }

    @Test
    fun aMetadataPayloadThatDoesNotParseIsNotForwardedAtAll() {
        val broken = field(1, "Song".toByteArray()) + byteArrayOf(0x22, 0x7F, 1, 2)   // field 4 claims 127 bytes, has 2
        assertNull(ThirdPartyForwardPolicy.payloadFor("media", 32771, broken, includeAlbumArt = false))
        // with album art allowed the payload is passed on as received: nothing is being hidden
        assertArrayEquals(broken, ThirdPartyForwardPolicy.payloadFor("media", 32771, broken, includeAlbumArt = true))
    }

    @Test
    fun onlyTheMessagesOfTheContractAreForwarded() {
        for (m in listOf(32769, 32770, 32771, 32772, 32773, 32774, 32775)) assertTrue("nav $m", ThirdPartyForwardPolicy.isForwarded("nav", m))
        for (m in listOf(0, 5, 32776)) assertFalse("nav $m", ThirdPartyForwardPolicy.isForwarded("nav", m))
        assertTrue(ThirdPartyForwardPolicy.isForwarded("media", 32771))
        assertTrue(ThirdPartyForwardPolicy.isForwarded("media", 32769))
        assertFalse(ThirdPartyForwardPolicy.isForwarded("media", 32770))   // playback input
        assertFalse(ThirdPartyForwardPolicy.isForwarded("audio", 32774))
    }

    @Test
    fun theTurnImageOfTheLegacyNextTurnMessageIsDropped() {
        val keep = field(1, "Main St".toByteArray())
        val image = byteArrayOf(0x22, 0x03, 1, 2, 3)   // field 4: the icon
        assertArrayEquals(keep, ThirdPartyForwardPolicy.payloadFor("nav", 32772, keep + image, includeAlbumArt = true))
        assertArrayEquals(keep + image, ThirdPartyForwardPolicy.payloadFor("nav", 32773, keep + image, includeAlbumArt = false))
    }

    @Test
    fun anAppIsOnlyAReceiverWhileItsSigningCertificateIsTheOnePinned() {
        val digest = ThirdPartyForwardPolicy.digestOf(listOf(byteArrayOf(1, 2, 3)))
        val other = ThirdPartyForwardPolicy.digestOf(listOf(byteArrayOf(9, 9, 9)))
        assertNotEquals(digest, other)

        val entries = listOf(
            ThirdPartyForwardPolicy.Entry("de.wfabia", digest),
            ThirdPartyForwardPolicy.Entry("com.replaced", digest),
            ThirdPartyForwardPolicy.Entry("com.removed", digest),
        )
        val installed = mapOf("de.wfabia" to digest, "com.replaced" to other)   // com.removed is not installed any more
        assertEquals(listOf("de.wfabia"), ThirdPartyForwardPolicy.recipients(entries) { installed[it] })
        assertEquals(emptyList<String>(), ThirdPartyForwardPolicy.recipients(emptyList()) { installed[it] })   // empty whitelist: nobody
    }

    @Test
    fun theCertificateDigestDoesNotDependOnTheOrderOfTheSigners() {
        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5, 6)
        assertEquals(ThirdPartyForwardPolicy.digestOf(listOf(a, b)), ThirdPartyForwardPolicy.digestOf(listOf(b, a)))
        assertNotEquals(ThirdPartyForwardPolicy.digestOf(listOf(a)), ThirdPartyForwardPolicy.digestOf(listOf(a, b)))
        assertEquals(64, ThirdPartyForwardPolicy.digestOf(listOf(a)).length)   // hex SHA-256
    }
}
