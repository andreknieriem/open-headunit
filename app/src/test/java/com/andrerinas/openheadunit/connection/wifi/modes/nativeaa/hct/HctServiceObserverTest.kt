package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct

import android.content.*
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.connection.ConnectionAdmission
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.*
import java.io.IOException

/** Exercises real start/close with framework calls replaced; no OEM service is started. */
class HctServiceObserverTest {
    private class Fixture : AutoCloseable {
        val context = mock<Context>()
        val pm = mock<PackageManager>()
        val main = mock<Looper>()
        val resources = mutableListOf<AutoCloseable>()
        val loopers = Mockito.mockStatic(Looper::class.java).also(resources::add)
        val compat = Mockito.mockStatic(ContextCompat::class.java).also(resources::add)
        val handlers = Mockito.mockConstruction(Handler::class.java).also(resources::add)
        var registrationFailure: RuntimeException? = null
        var bindingFailure: RuntimeException? = null
        var accepted = true
        var available = true
        var authorityCurrent = true
        var invalidations = 0
        var connection: ServiceConnection? = null
        var receiver: BroadcastReceiver? = null
        val observer: HctServiceObserver

        init {
            resources.add(Mockito.mockConstruction(ComponentName::class.java))
            resources.add(Mockito.mockConstruction(IntentFilter::class.java))
            resources.add(Mockito.mockConstruction(Intent::class.java, Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)))
            loopers.`when`<Looper> { Looper.getMainLooper() }.thenReturn(main)
            loopers.`when`<Looper> { Looper.myLooper() }.thenReturn(main)
            whenever(context.applicationContext).thenReturn(context)
            whenever(context.packageManager).thenReturn(pm)
            compat.`when`<Intent?> {
                ContextCompat.registerReceiver(any(), any(), any(), any<String>(), isNull(), any())
            }.thenAnswer {
                receiver = it.arguments[1] as BroadcastReceiver
                registrationFailure?.let { throw it }
                null
            }
            whenever(context.bindService(any(), any<ServiceConnection>(), eq(0))).thenAnswer {
                connection = it.arguments[1] as ServiceConnection
                bindingFailure?.let { failure -> throw failure }
                accepted
            }
            observer = HctServiceObserver(context, {}, { invalidations++ },
                ConnectionAdmission { action -> if (!authorityCurrent) false else { action(); true } }, { available })
        }

        override fun close() {
            observer.close()
            resources.asReversed().forEach { it.close() }
        }
    }

    @Test fun `withdrawn connection authority cannot bind manufacturer service`(): Unit = Fixture().use { f ->
        f.authorityCurrent = false
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { f.observer.start() }
        assertNull(f.receiver)
        verify(f.context, never()).bindService(any<Intent>(), any<ServiceConnection>(), any<Int>())
    }

    @Test fun `close winning before start is cancellation without framework registration`() = Fixture().use { f ->
        f.observer.close()
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { f.observer.start() }
        assertNull(f.receiver)
        verify(f.context, never()).bindService(any<Intent>(), any<ServiceConnection>(), any<Int>())
        verify(f.context, never()).unbindService(any())
        assertEquals(1, f.invalidations)
    }

    @Test fun `accepted binding is released exactly once with its original connection`() = Fixture().use { f ->
        f.observer.start()
        verify(f.context, never()).unbindService(any())
        f.observer.close()
        f.observer.close()
        verify(f.context).unbindService(same(f.connection!!))
        verify(f.context).unregisterReceiver(same(f.receiver!!))
        assertEquals(1, f.invalidations)
    }

    @Test fun `false binding result releases framework tracking and receiver`() = Fixture().use { f ->
        f.accepted = false
        assertThrows(IOException::class.java) { f.observer.start() }
        f.observer.close()
        verify(f.context).unbindService(same(f.connection!!))
        verify(f.context).unregisterReceiver(same(f.receiver!!))
    }

    @Test fun `bind exception is preserved even if unbind reports no registration`() = Fixture().use { f ->
        val failure = SecurityException("OEM permission changed")
        f.bindingFailure = failure
        doThrow(IllegalArgumentException("not registered")).whenever(f.context).unbindService(any())
        assertSame(failure, assertThrows(SecurityException::class.java) { f.observer.start() })
        f.observer.close()
        verify(f.context).unbindService(same(f.connection!!))
        verify(f.context).unregisterReceiver(same(f.receiver!!))
    }

    @Test fun `registration failure does not release a binding that was never attempted`() = Fixture().use { f ->
        f.registrationFailure = SecurityException("receiver denied")
        assertSame(f.registrationFailure, assertThrows(SecurityException::class.java) { f.observer.start() })
        verify(f.context, never()).bindService(any<Intent>(), any<ServiceConnection>(), any<Int>())
        verify(f.context, never()).unbindService(any())
        verify(f.context).unregisterReceiver(same(f.receiver!!))
    }

    @Test fun `failed registration cleanup preserves original exception and is not repeated`() = Fixture().use { f ->
        val failure = SecurityException("receiver registration denied")
        f.registrationFailure = failure
        doThrow(IllegalArgumentException("not registered")).whenever(f.context).unregisterReceiver(any())
        assertSame(failure, assertThrows(SecurityException::class.java) { f.observer.start() })
        f.observer.close()
        verify(f.context).unregisterReceiver(same(f.receiver!!))
        verify(f.context, never()).unbindService(any())
    }

    @Test fun `unavailable interface does not register or bind`() = Fixture().use { f ->
        f.available = false
        assertThrows(IOException::class.java) { f.observer.start() }
        assertNull(f.receiver)
        verify(f.context, never()).bindService(any<Intent>(), any<ServiceConnection>(), any<Int>())
        verify(f.context, never()).unbindService(any())
    }

    @Test fun `late service callbacks after failed bind cannot resurrect admission`() = Fixture().use { f ->
        f.accepted = false
        assertThrows(IOException::class.java) { f.observer.start() }
        val callbacks = f.connection!!
        callbacks.onServiceConnected(mock(), mock<IBinder>())
        callbacks.onServiceDisconnected(mock())
        callbacks.onBindingDied(mock())
        callbacks.onNullBinding(mock())
        assertEquals(1, f.invalidations)
        assertTrue(f.observer.arrivals.tryReceive().isClosed)
        verify(f.context).unbindService(same(callbacks))
    }

    @Test fun `off-main close schedules one cleanup on main`() = Fixture().use { f ->
        f.observer.start()
        f.loopers.`when`<Looper?> { Looper.myLooper() }.thenReturn(null)
        f.observer.close()
        f.observer.close()
        val task = argumentCaptor<Runnable>()
        verify(f.handlers.constructed().single()).post(task.capture())
        verify(f.context, never()).unbindService(any())
        f.loopers.`when`<Looper> { Looper.myLooper() }.thenReturn(f.main)
        task.firstValue.run()
        verify(f.context).unbindService(same(f.connection!!))
    }

    @Test fun `service bind permission belongs to OHU separately from OEM sender permission`() = Fixture().use { f ->
        val service = ServiceInfo().apply {
            enabled = true
            exported = true
            applicationInfo = ApplicationInfo().apply { enabled = true; flags = ApplicationInfo.FLAG_SYSTEM }
        }
        whenever(f.pm.checkPermission("android.permission.SERIAL_PORT", "android.microntek.mtcser"))
            .thenReturn(PackageManager.PERMISSION_GRANTED)
        assertTrue(HctServiceObserver.canBind(f.context, service))
        service.permission = "vendor.permission.BIND_BT"
        Mockito.mockStatic(android.os.Process::class.java).use {
            whenever(f.context.checkPermission(eq(service.permission), any(), any())).thenReturn(PackageManager.PERMISSION_DENIED)
            assertFalse(HctServiceObserver.canBind(f.context, service))
            whenever(f.context.checkPermission(eq(service.permission), any(), any())).thenReturn(PackageManager.PERMISSION_GRANTED)
            assertTrue(HctServiceObserver.canBind(f.context, service))
            service.exported = false
            assertFalse(HctServiceObserver.canBind(f.context, service))
            service.exported = true
            whenever(f.pm.checkPermission(any(), any())).thenReturn(PackageManager.PERMISSION_DENIED)
            assertFalse(HctServiceObserver.canBind(f.context, service))
        }
    }
}
