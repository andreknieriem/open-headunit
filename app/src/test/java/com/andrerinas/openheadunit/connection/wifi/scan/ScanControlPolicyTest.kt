package com.andrerinas.openheadunit.connection.wifi.scan

import org.junit.Assert.*
import org.junit.Test

class ScanControlPolicyTest {
    @Test fun `USB self mode and disconnected transports never acquire a scan lease`() {
        assertFalse(ScanControlPolicy.wirelessTransport(live = true, wireless = false, loopback = false)) // USB
        assertFalse(ScanControlPolicy.wirelessTransport(live = true, wireless = true, loopback = true)) // self
        assertFalse(ScanControlPolicy.wirelessTransport(live = false, wireless = true, loopback = false)) // ended
        assertTrue(ScanControlPolicy.wirelessTransport(live = true, wireless = true, loopback = false))
    }

    @Test fun `reboot resets autojoin but does not discard persistent scan-always restoration`() {
        assertTrue(ScanControlPolicy.discardAfterBoot(1, 5, 6))
        assertFalse(ScanControlPolicy.discardAfterBoot(1, 5, 5))
        assertFalse(ScanControlPolicy.discardAfterBoot(2, 5, 6))
        assertThrows(IllegalArgumentException::class.java) { ScanControlPolicy.discardAfterBoot(1, 5, -1) }
    }
    @Test fun `legacy Direct uses its version-specific gate rather than scan-always`() {
        for (sdk in 16..25) for (hotspot in listOf(false, true))
            assertEquals(0, ScanControlPolicy.mode(sdk, hotspot, true))
        for (sdk in 26..32) {
            assertEquals(if (sdk < 30) 4 else 3, ScanControlPolicy.mode(sdk, false, true))
            assertEquals(0, ScanControlPolicy.mode(sdk, true, false))
            assertEquals(2, ScanControlPolicy.mode(sdk, true, true))
        }
    }
    @Test fun `modern external gate supports Native AP and GO regardless of station state`() {
        for (sdk in 33..36) for (hotspot in listOf(false, true)) for (off in listOf(false, true))
            assertEquals(1, ScanControlPolicy.mode(sdk, hotspot, off))
    }
    @Test fun `client STA modes keep automatic reconnection on every version`() {
        for (sdk in 26..36) for (hotspot in listOf(false, true))
            assertEquals(0, ScanControlPolicy.mode(sdk, hotspot, true, nativeHost = false))
    }
    @Test fun `a released lease is remembered independently of later user changes`() {
        val receipts = ReleasedScanLeases()
        assertFalse(receipts.contains("lease"))
        receipts.record("lease")
        assertTrue(receipts.contains("lease"))
        assertFalse(receipts.contains("new-lease"))
    }
    @Test fun `a preexisting disabled setting stays disabled after session end`() {
        var value = 0
        var writes = 0
        val lease = ScanControlLease({ value }, { _, v -> writes++; value = v })
        assertTrue(lease.apply(1, 0))
        assertTrue(lease.restore(1, 0))
        assertEquals(0, writes)
        assertEquals(0, value)
    }
    @Test fun `enabled setting is restored after ordinary end or uncertain completion`() {
        var value = 1
        val lease = ScanControlLease({ value }, { _, v -> value = v })
        assertTrue(lease.apply(1, 1))
        assertEquals(0, value)
        assertTrue(lease.restore(1, 1))
        assertEquals(1, value)
        assertTrue(lease.restore(1, 1)) // app journal after helper death rollback
    }
    @Test fun `setting change before apply is declined without taking ownership or undoing it`() {
        var acquired = false
        val lease = ScanControlLease({ 0 }, { _, _ -> fail("must not write") })
        assertFalse(lease.apply(1, 1) { acquired = true })
        assertFalse(acquired)
    }
    @Test fun `ownership is recorded before an asynchronous setter can fail`() {
        var acquired = false
        var value = 1
        val lease = ScanControlLease({ value }, { _, v ->
            assertTrue(acquired)
            value = v
            if (v == 0) throw IllegalStateException("Reply lost after write")
        })
        assertThrows(IllegalStateException::class.java) { lease.apply(1, 1) { acquired = true } }
        assertEquals(0, value)
        assertTrue(lease.restore(1, 1))
        assertEquals(1, value)
    }
    @Test fun `silent refusal does not count as a successful stop`() {
        val lease = ScanControlLease({ 1 }, { _, _ -> })
        assertFalse(lease.apply(1, 1))
    }
    @Test fun `user reenabled searches are respected even when original was disabled`() {
        val lease = ScanControlLease({ 1 }, { _, _ -> fail("must not overwrite user") })
        assertTrue(lease.restore(1, 0))
    }
    @Test fun `unreadable state cannot lead to an assumed restore`() {
        val lease = ScanControlLease({ -1 }, { _, _ -> fail("must not write") })
        assertFalse(lease.restore(1, 1))
        assertFalse(lease.apply(1, 1))
        assertThrows(IllegalArgumentException::class.java) { lease.apply(1, -1) }
    }
    @Test fun `legacy release enables searches without pretending to know their previous state`() {
        for (mode in listOf(ScanControlPolicy.LEGACY_CONNECTIVITY, ScanControlPolicy.LEGACY_AUTOJOIN)) {
            val writes = mutableListOf<Int>()
            var recorded = false
            val lease = ScanControlLease({ fail("There is no getter"); 0 }, { _, value ->
                assertTrue(recorded)
                writes += value
            })
            assertTrue(lease.apply(mode, ScanControlPolicy.UNKNOWN) { recorded = true })
            assertTrue(lease.restore(mode, ScanControlPolicy.UNKNOWN))
            assertEquals(listOf(0, 1), writes)
            assertTrue(ScanControlPolicy.discardAfterBoot(mode, 5, 6))
            assertFalse(ScanControlPolicy.discardAfterBoot(mode, 5, 5))
            assertFalse(ScanControlPolicy.validSnapshot(mode, 1))
        }
    }

    @Test fun `legacy setter failure is not reported as a successful pause or release`() {
        val lease = ScanControlLease({ ScanControlPolicy.UNKNOWN }, { _, _ -> throw SecurityException("refused") })
        assertThrows(SecurityException::class.java) { lease.apply(3, -1) }
        assertThrows(SecurityException::class.java) { lease.restore(4, -1) }
    }

}
