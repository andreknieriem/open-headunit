package com.andrerinas.openheadunit.connection.wifi.scan

import android.os.IBinder
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

/** Exercise the daemon ownership logic with a controlled setting, without Android Wi-Fi APIs. */
class WifiScanUserServiceTest {
    private fun binder(alive: Boolean): IBinder = mock(IBinder::class.java).also {
        `when`(it.isBinderAlive).thenReturn(alive)
    }
    private fun set(service: WifiScanUserService, name: String, value: Any) {
        WifiScanUserService::class.java.getDeclaredField(name).also {
            it.isAccessible = true
            it.set(service, value)
        }
    }
    private fun service(owner: IBinder, lease: ScanControlLease, mode: Int = ScanControlPolicy.AUTOJOIN,
                        original: Int = 1): WifiScanUserService {
        val service = mock(WifiScanUserService::class.java, CALLS_REAL_METHODS)
        set(service, "released", ReleasedScanLeases())
        set(service, "lease", lease)
        set(service, "owner", owner)
        set(service, "death", IBinder.DeathRecipient {})
        val held = WifiScanUserService::class.java.declaredClasses.single { it.simpleName == "Held" }
            .getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .also { it.isAccessible = true }
            .newInstance("old", mode, original)
        set(service, "held", held)
        return service
    }

    @Test fun `system restoration after owner death allows a new lease on the surviving daemon`() {
        var setting = 1 // Android's restoration dialog already restored the old value.
        val service = service(binder(false), ScanControlLease({ setting }, { _, value -> setting = value }))
        assertTrue(service.apply(binder(true), "new", ScanControlPolicy.AUTOJOIN, 1))
        assertEquals(0, setting)
        // A late app journal cannot restore over the new owner's pause.
        assertTrue(service.restore("old", ScanControlPolicy.AUTOJOIN, 1))
        assertEquals(0, setting)
        assertTrue(service.restore("new", ScanControlPolicy.AUTOJOIN, 1))
        assertEquals(1, setting)
    }

    @Test fun `an unreadable old lease remains recoverable and is not replaced`() {
        var setting = -1
        val service = service(binder(false), ScanControlLease({ setting }, { _, value -> setting = value }))
        assertThrows(IllegalStateException::class.java) {
            service.apply(binder(true), "new", ScanControlPolicy.AUTOJOIN, 1)
        }
        assertTrue(service.restore("new", ScanControlPolicy.AUTOJOIN, 1))
        assertEquals(-1, setting) // The rejected id must not restore the old owner's setting.
        setting = 0
        assertTrue(service.restore("old", ScanControlPolicy.AUTOJOIN, 1))
        assertEquals(1, setting)
    }

    @Test fun `a duplicate held id must not be marked released when apply is refused`() {
        var setting = 0
        val service = service(binder(true), ScanControlLease({ setting }, { _, value -> setting = value }))
        assertThrows(IllegalStateException::class.java) {
            service.apply(binder(true), "old", ScanControlPolicy.AUTOJOIN, 0)
        }
        assertTrue(service.restore("old", ScanControlPolicy.AUTOJOIN, 1))
        assertEquals(1, setting)
    }

    @Test fun `a live owner cannot be displaced by another caller`() {
        var setting = 0
        val service = service(binder(true), ScanControlLease({ setting }, { _, value -> setting = value }))
        assertThrows(IllegalStateException::class.java) {
            service.apply(binder(true), "new", ScanControlPolicy.AUTOJOIN, 0)
        }
        assertEquals(0, setting)
        assertTrue(service.restore("old", ScanControlPolicy.AUTOJOIN, 1))
        assertEquals(1, setting)
    }
    @Test fun `duplicate legacy apply does not repeat Wi-Fi writes and release happens only once`() {
        val writes = mutableListOf<Int>()
        val service = service(binder(true), ScanControlLease({ -1 }, { _, value -> writes += value }),
            ScanControlPolicy.LEGACY_CONNECTIVITY, ScanControlPolicy.UNKNOWN)
        repeat(3) {
            assertThrows(IllegalStateException::class.java) {
                service.apply(binder(true), "old", ScanControlPolicy.LEGACY_CONNECTIVITY, -1)
            }
        }
        assertTrue(writes.isEmpty())
        assertTrue(service.restore("old", ScanControlPolicy.LEGACY_CONNECTIVITY, -1))
        assertTrue(service.restore("old", ScanControlPolicy.LEGACY_CONNECTIVITY, -1))
        assertEquals(listOf(1), writes)
    }

    @Test fun `legacy takeover releases a dead owners pause before acquiring the new one`() {
        val writes = mutableListOf<Int>()
        val service = service(binder(false), ScanControlLease({ -1 }, { _, value -> writes += value }),
            ScanControlPolicy.LEGACY_AUTOJOIN, ScanControlPolicy.UNKNOWN)
        assertTrue(service.apply(binder(true), "new", ScanControlPolicy.LEGACY_AUTOJOIN, -1))
        assertEquals(listOf(1, 0), writes)
        assertTrue(service.restore("old", ScanControlPolicy.LEGACY_AUTOJOIN, -1))
        assertEquals(listOf(1, 0), writes) // Old journal cannot undo the replacement lease.
        assertTrue(service.restore("new", ScanControlPolicy.LEGACY_AUTOJOIN, -1))
        assertEquals(listOf(1, 0, 1), writes)
    }

    @Test fun `renewal requires live matching legacy owner and cannot act after release`() {
        val writes = mutableListOf<Int>()
        val owner = binder(true)
        val service = service(owner, ScanControlLease({ -1 }, { _, value -> writes += value }),
            ScanControlPolicy.LEGACY_CONNECTIVITY, -1)
        assertFalse(service.renew("stale"))
        assertTrue(service.renew("old"))
        assertEquals(listOf(0), writes)
        `when`(owner.isBinderAlive).thenReturn(false)
        assertFalse(service.renew("old"))
        assertTrue(service.restore("old", ScanControlPolicy.LEGACY_CONNECTIVITY, -1))
        assertFalse(service.renew("old"))
        assertEquals(listOf(0, 1), writes)
    }

    @Test fun `renewal never writes modern autojoin legacy external gate or hotspot settings`() {
        for (mode in listOf(ScanControlPolicy.AUTOJOIN, ScanControlPolicy.LEGACY_AUTOJOIN,
                ScanControlPolicy.HOTSPOT_SCAN)) {
            val service = service(binder(true), ScanControlLease({ 0 }, { _, _ -> fail("No renewal") }),
                mode, if (mode == ScanControlPolicy.LEGACY_AUTOJOIN) -1 else 1)
            assertFalse(service.renew("old"))
        }
    }

}
