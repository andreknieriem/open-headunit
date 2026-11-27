package com.andrerinas.openheadunit.connection.carplaynative.lockdown

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PairRecordStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun record(id: String = "HOST-1") = PairRecord(
        id, "BUID", "aa:bb:cc:dd:ee:ff", "pub".toByteArray(), "devcert".toByteArray(), "hostkey".toByteArray(),
        "hostcert".toByteArray(), "rootkey".toByteArray(), "rootcert".toByteArray(),
    )

    @Test fun aRecordRoundTripsWithAllNineFields() {
        val store = PairRecordStore(File(folder.root, "pair"))
        assertTrue(store.save("SER1", record()))
        val back = store.load("SER1")!!
        assertEquals("HOST-1", back.hostId); assertEquals("BUID", back.systemBuid); assertEquals("aa:bb:cc:dd:ee:ff", back.wifiAddress)
        assertArrayEquals("pub".toByteArray(), back.devicePublicKeyPem); assertArrayEquals("devcert".toByteArray(), back.deviceCertificatePem)
        assertArrayEquals("hostkey".toByteArray(), back.hostPrivateKeyPem); assertArrayEquals("hostcert".toByteArray(), back.hostCertificatePem)
        assertArrayEquals("rootkey".toByteArray(), back.rootPrivateKeyPem); assertArrayEquals("rootcert".toByteArray(), back.rootCertificatePem)
    }

    @Test fun eachSerialHasItsOwnFileAndAnUnknownOneHasNone() {
        val store = PairRecordStore(folder.root)
        store.save("A", record("A")); store.save("B", record("B"))
        assertEquals("A", store.load("A")!!.hostId)
        assertEquals("B", store.load("B")!!.hostId)
        assertNull(store.load("C"))
    }

    @Test fun savingAgainReplacesTheRecord() {
        val store = PairRecordStore(folder.root)
        store.save("A", record("old")); store.save("A", record("new"))
        assertEquals("new", store.load("A")!!.hostId)
        assertEquals(listOf("A.plist"), folder.root.list()!!.toList())
    }

    @Test fun clearRemovesTheRecord() {
        val store = PairRecordStore(folder.root)
        store.save("A", record()); store.clear("A")
        assertNull(store.load("A"))
        store.clear("A")
    }

    @Test fun aDamagedFileOrOneWithAMissingFieldIsIgnored() {
        val store = PairRecordStore(folder.root)
        File(folder.root, "A.plist").writeText("not a plist")
        assertNull(store.load("A"))
        store.save("B", record())
        val text = File(folder.root, "B.plist").readText().replace("<key>RootCertificate</key>", "<key>Other</key>")
        File(folder.root, "B.plist").writeText(text)
        assertNull(store.load("B"))
    }

    @Test fun aSerialCannotLeaveTheDirectory() {
        assertFalse(PairRecordStore.safeName("../x").contains("/"))
        assertEquals("unknown", PairRecordStore.safeName(""))
        assertEquals("unknown", PairRecordStore.safeName(".."))
        val store = PairRecordStore(File(folder.root, "d"))
        store.save("../../escape", record())
        assertTrue(File(folder.root, "d").list()!!.all { it.endsWith(".plist") })
        assertFalse(File(folder.root.parentFile, "escape.plist").exists())
    }

    @Test fun theRecordNeverPrintsItsFields() {
        assertEquals("PairRecord(redacted)", record().toString())
        assertNotNull(record())
    }
}
