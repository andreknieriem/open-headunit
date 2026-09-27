package com.andrerinas.openheadunit.automation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationOutputPolicyTest {

    private val roots = listOf("/storage/emulated/0/Android/data/pkg/files", "/storage/emulated/0/Download")

    @Test
    fun `a file inside an allowed root may be written`() {
        assertTrue(AutomationOutputPolicy.mayWriteTo("/storage/emulated/0/Download/x.json", roots))
        assertTrue(AutomationOutputPolicy.mayWriteTo("/storage/emulated/0/Download/OpenHeadunitLogs/a.txt", roots))
    }

    /**
     * The write runs with the app's own privileges, so an unconstrained path lets a caller put
     * chosen bytes into the app's private storage. settings.xml is the one that would matter.
     */
    @Test
    fun `app private storage is refused`() {
        assertFalse(
            AutomationOutputPolicy.mayWriteTo(
                "/data/data/com.andrerinas.headunitrevived/shared_prefs/settings.xml",
                roots
            )
        )
        assertFalse(AutomationOutputPolicy.mayWriteTo("/data/local/tmp/x.json", roots))
        assertFalse(AutomationOutputPolicy.mayWriteTo("/sdcard/elsewhere/x.json", roots))
    }

    /** Starting inside an allowed root is not enough if the path then climbs out of it. */
    @Test
    fun `traversal out of an allowed root is refused`() {
        assertFalse(
            AutomationOutputPolicy.mayWriteTo(
                "/storage/emulated/0/Download/../../../data/data/pkg/shared_prefs/settings.xml",
                roots
            )
        )
        assertFalse(AutomationOutputPolicy.mayWriteTo("/storage/emulated/0/Download/..", roots))
    }

    @Test
    fun `a relative or empty path is refused rather than resolved`() {
        assertFalse(AutomationOutputPolicy.mayWriteTo("x.json", roots))
        assertFalse(AutomationOutputPolicy.mayWriteTo("", roots))
        assertFalse(AutomationOutputPolicy.mayWriteTo("   ", roots))
    }

    /** The root itself is a directory, not a file to overwrite. */
    @Test
    fun `the root itself is not a writable target`() {
        assertFalse(AutomationOutputPolicy.mayWriteTo("/storage/emulated/0/Download", roots))
    }

    @Test
    fun `a duplicated separator does not slip past the prefix check`() {
        assertTrue(AutomationOutputPolicy.mayWriteTo("/storage/emulated/0//Download//x.json", roots))
    }

    @Test
    fun `with no roots nothing is writable`() {
        assertFalse(AutomationOutputPolicy.mayWriteTo("/storage/emulated/0/Download/x.json", emptyList()))
        assertFalse(AutomationOutputPolicy.mayWriteTo("/storage/emulated/0/Download/x.json", listOf("", "  ")))
    }

    /**
     * The passphrase is the head unit's own AP credential in clear text, and an export goes either
     * to a caller we cannot identify or to a path that caller chose.
     */
    @Test
    fun `the credential bearing settings are withheld from an export`() {
        assertTrue("hotspot-password" in AutomationOutputPolicy.WITHHELD_KEYS)
        assertTrue("wifi-direct-group-passphrase" in AutomationOutputPolicy.WITHHELD_KEYS)
        assertTrue("wifi-direct-readback-passphrase" in AutomationOutputPolicy.WITHHELD_KEYS)
        // The pair and its provenance travel together or not at all.
        assertTrue("wifi-direct-identity-user-set" in AutomationOutputPolicy.WITHHELD_KEYS)
        assertTrue("auto-start-bt-macs" in AutomationOutputPolicy.WITHHELD_KEYS)
        assertTrue("auto-disconnect-bt-macs" in AutomationOutputPolicy.WITHHELD_KEYS)
        assertTrue("native-poke-bt-macs" in AutomationOutputPolicy.WITHHELD_KEYS)
        assertTrue("static-bssid" in AutomationOutputPolicy.WITHHELD_KEYS)
    }

    @Test
    fun `a primary external storage symlink resolves onto the real root`() {
        val roots = listOf("/storage/emulated/0/Download")
        val real = "/storage/emulated/0"
        assertTrue(AutomationOutputPolicy.mayWriteTo("/sdcard/Download/x.json", roots, real))
        assertTrue(AutomationOutputPolicy.mayWriteTo("/mnt/sdcard/Download/x.json", roots, real))
        assertTrue(AutomationOutputPolicy.mayWriteTo("/storage/self/primary/Download/x.json", roots, real))
        assertTrue(AutomationOutputPolicy.mayWriteTo("/storage/emulated/0/Download/x.json", roots, real))
    }

    @Test
    fun `an alias cannot reach anywhere the real path could not`() {
        val roots = listOf("/storage/emulated/0/Download")
        val real = "/storage/emulated/0"
        assertFalse(AutomationOutputPolicy.mayWriteTo("/sdcard/Android/data/x", roots, real))
        assertFalse(AutomationOutputPolicy.mayWriteTo("/sdcard/Download/../../../data/data/x", roots, real))
        // A name that merely starts with an alias is not the alias.
        assertFalse(AutomationOutputPolicy.mayWriteTo("/sdcardish/Download/x", roots, real))
    }

    @Test
    fun `with no external root known the alias is refused rather than guessed`() {
        assertFalse(
            AutomationOutputPolicy.mayWriteTo("/sdcard/Download/x.json", listOf("/storage/emulated/0/Download"), null)
        )
    }
}
