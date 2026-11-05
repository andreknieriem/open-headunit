package com.andrerinas.openheadunit.connection.usb.hci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileNotFoundException

class HciFirmwareRootsTest {
    @get:Rule val folder = TemporaryFolder()

    private val lines = mutableListOf<String>()

    private fun root(name: String, config: Boolean = true) = folder.newFolder(name).also {
        File(it, "rtl_bt").mkdirs()
        File(it, "rtl_bt/rtl8761bu_fw.bin").writeBytes(name.toByteArray())
        if (config) File(it, "rtl_bt/rtl8761bu_config.bin").writeBytes(byteArrayOf(1, 2))
    }

    private fun load(vararg roots: Pair<String, File?>, read: (File) -> ByteArray = File::readBytes) =
        HciFirmwareRoots.load(roots.toList(), { lines.add(it) }, read)

    private fun denied(path: String): (File) -> ByteArray = { f ->
        if (f.path.contains(path)) throw FileNotFoundException("EACCES") else f.readBytes()
    }

    @Test fun firstRootWins() {
        val files = load("external" to root("a"), "internal" to root("b"))!!
        assertEquals("external", files.dir)
        assertArrayEquals("a".toByteArray(), files.fw)
        assertArrayEquals(byteArrayOf(1, 2), files.config)
    }

    @Test fun anAbsentConfigIsNoFailure() {
        assertNull(load("external" to root("a", config = false))!!.config)
    }

    @Test fun aNullOrEmptyRootIsPassed() {
        assertEquals("internal", load("external" to null, "empty" to folder.newFolder("e"), "internal" to root("b"))!!.dir)
    }

    @Test fun anUnreadableFirmwareFallsToTheNextRoot() {
        val ext = root("ext")
        val files = load("external" to ext, "internal" to root("int"), read = denied(ext.path))!!
        assertEquals("internal", files.dir)
        assertEquals(listOf("HCI: firmware skipped dir=external reason=FileNotFoundException"), lines)
    }

    @Test fun anUnreadableConfigFallsToTheNextRoot() {
        val ext = root("ext")
        val files = load("external" to ext, "internal" to root("int"), read = denied("ext/rtl_bt/rtl8761bu_config"))!!
        assertEquals("internal", files.dir)
    }

    @Test fun aSecurityExceptionFallsToTheNextRoot() {
        val ext = root("ext")
        val read: (File) -> ByteArray = { f -> if (f.path.contains(ext.path)) throw SecurityException("no") else f.readBytes() }
        assertEquals("internal", load("external" to ext, "internal" to root("int"), read = read)!!.dir)
    }

    @Test fun noReadableRootGivesNullSoTheStepIsFirmwareFile() {
        assertNull(load("external" to root("a"), "internal" to root("b"), read = { throw FileNotFoundException("EACCES") }))
        assertEquals(2, lines.size)
    }
}
