package com.andrerinas.openheadunit.app

import com.andrerinas.openheadunit.app.MakerWhitelistPolicy.REGLINK
import com.andrerinas.openheadunit.app.MakerWhitelistPolicy.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MakerWhitelistPolicyTest {

    private val pkg = "com.andrerinas.headunitrevived"

    @Test
    fun `reglink is detected only with all three lists`() {
        assertEquals(REGLINK, MakerWhitelistPolicy.detect(REGLINK.files + "/system/etc/hosts"))
        assertNull(MakerWhitelistPolicy.detect(REGLINK.files.take(2)))
        assertNull(MakerWhitelistPolicy.detect(emptyList()))
    }

    @Test
    fun `status counts the lists that already have the app`() {
        assertEquals(Status.NOT_APPLIED, MakerWhitelistPolicy.status(REGLINK, emptyList()))
        assertEquals(Status.PARTLY_APPLIED, MakerWhitelistPolicy.status(REGLINK, REGLINK.files.take(1)))
        assertEquals(Status.APPLIED, MakerWhitelistPolicy.status(REGLINK, REGLINK.files))
    }

    @Test
    fun `only enforcing verity blocks the write`() {
        assertTrue(MakerWhitelistPolicy.verityBlocksWrites("enforcing\n"))
        assertTrue(MakerWhitelistPolicy.verityBlocksWrites("EIO"))
        assertFalse(MakerWhitelistPolicy.verityBlocksWrites(""))
        assertFalse(MakerWhitelistPolicy.verityBlocksWrites("disabled"))
        assertFalse(MakerWhitelistPolicy.verityBlocksWrites("logging"))
    }

    @Test
    fun `the app is added as its own last line`() {
        assertEquals("com.reglink.*\ncom.baony.avm360\n$pkg\n", MakerWhitelistPolicy.withApp("com.reglink.*\ncom.baony.avm360\n", pkg))
        // A list without a trailing newline must not glue the package onto its last entry.
        assertEquals("com.baony.avm360\n$pkg\n", MakerWhitelistPolicy.withApp("com.baony.avm360", pkg))
        assertEquals("$pkg\n", MakerWhitelistPolicy.withApp("", pkg))
    }

    @Test
    fun `nothing is added twice`() {
        assertNull(MakerWhitelistPolicy.withApp("com.baony.avm360\n $pkg \n", pkg))
        // A longer name that starts with ours is a different package.
        assertTrue(MakerWhitelistPolicy.withApp("$pkg.debug\n", pkg) != null)
    }

    @Test
    fun `removing keeps every other line`() {
        assertEquals("com.reglink.*\ncom.baony.avm360\n", MakerWhitelistPolicy.withoutApp("com.reglink.*\n$pkg\ncom.baony.avm360\n", pkg))
        assertEquals("", MakerWhitelistPolicy.withoutApp("$pkg\n", pkg))
        assertNull(MakerWhitelistPolicy.withoutApp("com.baony.avm360\n", pkg))
    }

    @Test
    fun `adding and removing gives the list back`() {
        val original = "com.reglink.*\ncom.baony.avm360\n"
        val added = MakerWhitelistPolicy.withApp(original, pkg)!!
        assertEquals(original, MakerWhitelistPolicy.withoutApp(added, pkg))
    }

    @Test
    fun `the write is a quoted heredoc`() {
        assertEquals(
            "cat > '/system/etc/x.txt' <<'OHU_EOF'\na\n$pkg\nOHU_EOF",
            MakerWhitelistPolicy.writeCommand("/system/etc/x.txt", "a\n$pkg"),
        )
        assertNull(MakerWhitelistPolicy.writeCommand("/system/etc/x.txt", "a\nOHU_EOF\nb\n"))
    }

    @Test
    fun `a list is only read when cat finished`() {
        assertEquals("com.baony.avm360\ncom.reglink.*\n", MakerWhitelistPolicy.listContent("com.baony.avm360\ncom.reglink.*\n\nOHU_READ_OK"))
        // No trailing newline in the file: the echo before the marker keeps them apart.
        assertEquals("com.baony.avm360\n", MakerWhitelistPolicy.listContent("com.baony.avm360\nOHU_READ_OK\n"))
        assertEquals("", MakerWhitelistPolicy.listContent("\nOHU_READ_OK"))
        assertNull(MakerWhitelistPolicy.listContent("cat: /system/etc/x.txt: Permission denied"))
        assertNull(MakerWhitelistPolicy.listContent(""))
    }

    @Test
    fun `remount commands`() {
        assertEquals("mount -o rw,remount /system", MakerWhitelistPolicy.remountCommand("/system", true))
        assertEquals("mount -o ro,remount /", MakerWhitelistPolicy.remountCommand("/", false))
    }
}
