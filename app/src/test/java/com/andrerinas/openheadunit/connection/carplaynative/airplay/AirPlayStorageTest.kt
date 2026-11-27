package com.andrerinas.openheadunit.connection.carplaynative.airplay

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AirPlayStorageTest {
    @get:Rule val folder = TemporaryFolder()

    private fun key(n: Int) = ByteArray(32) { n.toByte() }
    private fun storage() = AirPlayStorage(File(folder.root, "airplay"))

    @Test fun theIdentityIsMadeOnceAndReadBackTheSecondTime() {
        val first = storage().identity()
        val second = storage().identity()
        assertEquals(first.pairingId, second.pairingId)
        assertArrayEquals(first.publicKey, second.publicKey)
    }

    @Test fun anUnreadableIdentityFileIsReplaced() {
        val dir = File(folder.root, "airplay").also { it.mkdirs() }
        File(dir, "identity.bin").writeBytes(ByteArray(5))
        val made = storage().identity()
        assertEquals(made.pairingId, storage().identity().pairingId)
    }

    @Test fun aSavedControllerComesBackInANewStore() {
        val store = storage().pairings()
        store.save("phone-a", key(1))
        val again = storage().pairings()
        assertArrayEquals(key(1), again.get("phone-a"))
        assertNull(again.get("phone-b"))
    }

    @Test fun theCapOfSixteenReachesTheFileAndTheDroppedControllerIsGone() {
        val store = storage().pairings()
        for (n in 0..PairingStore.MAX_ENTRIES) store.save("phone-$n", key(n))
        assertNull(store.get("phone-0"))
        val again = storage().pairings()
        assertNull(again.get("phone-0"))
        for (n in 1..PairingStore.MAX_ENTRIES) assertArrayEquals(key(n), again.get("phone-$n"))
        assertEquals(PairingStore.MAX_ENTRIES, AirPlayPairingFile.decode(File(folder.root, "airplay/pairings.bin").readBytes()).size)
    }

    @Test fun savingAnIdAgainKeepsOneEntryWithTheNewKey() {
        val store = storage().pairings()
        store.save("phone-a", key(1))
        store.save("phone-a", key(2))
        val decoded = AirPlayPairingFile.decode(File(folder.root, "airplay/pairings.bin").readBytes())
        assertEquals(1, decoded.size)
        assertArrayEquals(key(2), decoded["phone-a"])
    }

    @Test fun aStoreThatLoadsRewritesNothing() {
        storage().pairings().save("phone-a", key(1))
        val file = File(folder.root, "airplay/pairings.bin")
        val before = file.lastModified()
        file.setLastModified(before - 10_000)
        storage().pairings()
        assertEquals(before - 10_000, file.lastModified())
    }

    @Test fun theFileRoundTripsAndACutFileKeepsTheEntriesBeforeTheCut() {
        val entries = linkedMapOf("a" to key(1), "bb" to key(2), "ünï" to key(3))
        val bytes = AirPlayPairingFile.encode(entries)
        val decoded = AirPlayPairingFile.decode(bytes)
        assertEquals(entries.keys.toList(), decoded.keys.toList())
        assertArrayEquals(key(3), decoded["ünï"])
        assertEquals(2, AirPlayPairingFile.decode(bytes.copyOf(bytes.size - 1)).size)
        assertTrue(AirPlayPairingFile.decode(ByteArray(0)).isEmpty())
    }

    @Test fun aKeyOfTheWrongSizeIsNotWritten() {
        val bytes = AirPlayPairingFile.encode(mapOf("a" to ByteArray(5), "b" to key(2)))
        assertEquals(listOf("b"), AirPlayPairingFile.decode(bytes).keys.toList())
    }

    @Test fun noTemporaryFileStaysBehind() {
        val store = storage().pairings()
        store.save("phone-a", key(1))
        storage().identity()
        assertFalse(File(folder.root, "airplay").listFiles()!!.any { it.name.endsWith(".tmp") })
        assertNotEquals(0, File(folder.root, "airplay").listFiles()!!.size)
    }
}
