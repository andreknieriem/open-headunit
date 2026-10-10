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
    private fun service(owner: IBinder, lease: ScanControlLease): WifiScanUserService {
        val service = mock(WifiScanUserService::class.java, CALLS_REAL_METHODS)
        set(service, "released", ReleasedScanLeases())
        set(service, "lease", lease)
        set(service, "owner", owner)
        set(service, "death", IBinder.DeathRecipient {})
        val held = WifiScanUserService::class.java.declaredClasses.single { it.simpleName == "Held" }
            .getDeclaredConstructor(String::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .also { it.isAccessible = true }
            .newInstance("old", ScanControlPolicy.AUTOJOIN, 1)
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
}
