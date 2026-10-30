package com.andrerinas.openheadunit.connection.usb

import android.content.Context
import android.hardware.usb.*
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.ConnectionArbiter
import com.andrerinas.openheadunit.connection.withAppExitDispatcherForTest
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

/** Real scan -> permission wait -> retry -> public connect -> StandardUsbProjectionConnection. */
class UsbLogicalAttemptTest {
    @OptIn(InternalCoroutinesApi::class)
    private class Queue(private val wake: java.util.concurrent.Semaphore) : CoroutineDispatcher(), Delay {
        private val ready = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        private val timers = mutableListOf<Pair<Long, () -> Unit>>()
        private var now = 0L
        override fun dispatch(context: CoroutineContext, block: Runnable) { ready.add(block); wake.release() }
        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            timers += (now + timeMillis) to { if (continuation.isActive) continuation.resume(Unit) }
            wake.release()
        }
        fun drain() { while (true) (ready.poll() ?: return).run() }
        fun hasTimer() = timers.isNotEmpty()
        fun advance(ms: Long) {
            now += ms
            val due = timers.filter { it.first <= now }
            timers.removeAll(due.toSet())
            due.forEach { it.second() }
            drain()
        }
    }

    private class Fixture {
        private val wake = java.util.concurrent.Semaphore(0)
        val queue = Queue(wake)
        val io = Queue(wake)
        val scope = CoroutineScope(SupervisorJob() + queue)
        val service = mock(AapService::class.java)
        val comm = mock(CommManager::class.java, CALLS_REAL_METHODS).withAppExitDispatcherForTest()
        val states = MutableStateFlow<CommManager.ConnectionState>(CommManager.ConnectionState.Disconnected())
        val usb = mock(UsbManager::class.java)
        val device = mock(UsbDevice::class.java)
        val settings = mock(Settings::class.java)
        val handle = mock(UsbDeviceConnection::class.java)
        val launcher: UsbLauncherManager
        var opens = 0
        var failFirst = false
        init {
            val app = mock(App::class.java)
            val component = mock(AppComponent::class.java)
            `when`(service.applicationContext).thenReturn(app)
            `when`(service.serviceScope).thenReturn(scope)
            `when`(service.getSystemService(Context.USB_SERVICE)).thenReturn(usb)
            `when`(service.packageManager).thenReturn(mock(android.content.pm.PackageManager::class.java))
            `when`(service.getSharedPreferences(anyString(), anyInt()))
                .thenReturn(mock(android.content.SharedPreferences::class.java))
            App::class.java.getDeclaredField("component\$delegate").apply { isAccessible = true }.set(app, lazyOf(component))
            `when`(component.commManager).thenReturn(comm)
            `when`(component.settings).thenReturn(settings)
            set("_connectionState", states)
            set("connectionState", states)
            set("context", service)
            set("settings", settings)
            set("usbRecoveryOwner", Any())
            set("connectionIoDispatcher", io)
            `when`(settings.allowedDevices).thenReturn(emptySet())
            val devicePath = "/dev/bus/usb/001/002"
            `when`(device.deviceName).thenReturn(devicePath)
            `when`(device.vendorId).thenReturn(0x18d1)
            `when`(device.productId).thenReturn(0x2d00)
            `when`(usb.deviceList).thenReturn(hashMapOf(devicePath to device))
            `when`(usb.hasPermission(device)).thenReturn(true)
            `when`(usb.openDevice(device)).thenAnswer { opens++; handle }
            val iface = mock(UsbInterface::class.java)
            val input = mock(UsbEndpoint::class.java)
            val output = mock(UsbEndpoint::class.java)
            `when`(device.interfaceCount).thenReturn(1)
            `when`(device.getInterface(0)).thenReturn(iface)
            `when`(iface.endpointCount).thenReturn(2)
            `when`(iface.getEndpoint(0)).thenReturn(input)
            `when`(iface.getEndpoint(1)).thenReturn(output)
            `when`(input.direction).thenReturn(UsbConstants.USB_DIR_IN)
            `when`(output.direction).thenReturn(UsbConstants.USB_DIR_OUT)
            `when`(input.type).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK)
            `when`(output.type).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK)
            // Fail one actual candidate after openDevice, rather than usbOpen's own
            // null-handle retry loop (which would sleep and retry inside the same connect).
            `when`(handle.claimInterface(iface, true)).thenAnswer { !(failFirst && opens == 1) }
            // Only transport retirement is doubled. Admission, candidate construction, open,
            // physical success/failure and publication remain the real production consumers.
            doAnswer { call ->
                set("disconnectRequested", true)
                states.value = CommManager.ConnectionState.Disconnected(settingsRetryOwner = call.getArgument(5))
                null
            }.`when`(comm).disconnect(any(), any(), any(), any(), any(), anyOrNull())
            launcher = UsbLauncherManager(service)
        }
        fun set(name: String, value: Any?) = CommManager::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(comm, value)
        fun scan() { launcher.checkAlreadyConnected(force = true); queue.drain() }
        fun connect() {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (true) {
                io.drain(); queue.drain()
                val attempt = launcher.attemptJob
                if (attempt == null || attempt.isCompleted || queue.hasTimer()) return
                val remaining = deadline - System.nanoTime()
                check(remaining > 0 && wake.tryAcquire(remaining, java.util.concurrent.TimeUnit.NANOSECONDS)) {
                    "Physical USB IO did not return to its owning attempt"
                }
            }
        }
        fun stopOpen(): Long { comm.requestExplicitAppExit(killProcess = false); return comm.onAppRelaunched() }
        fun permissionReply(ticket: Long?) {
            val intent = mock(android.content.Intent::class.java)
            `when`(intent.action).thenReturn(UsbReceiver.ACTION_USB_DEVICE_PERMISSION)
            if (ticket != null) {
                val data = mock(android.net.Uri::class.java)
                `when`(intent.data).thenReturn(data)
                `when`(data.scheme).thenReturn("ohu-usb-attempt")
                `when`(data.schemeSpecificPart).thenReturn(ticket.toString())
            }
            @Suppress("DEPRECATION")
            `when`(intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)).thenReturn(device)
            `when`(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)).thenReturn(true)
            UsbReceiver(UsbLauncherListener(launcher)).onReceive(service, intent)
            queue.drain()
        }
        fun assertIdle() {
            assertFalse(launcher.isSwitchingToProjection())
            assertNull(launcher.attemptJob)
            assertNull(UsbLauncherManager::class.java.getDeclaredField("attemptClaim").apply { isAccessible = true }.get(launcher))
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        val logger = AppLog.LOGGER
        val messages = StringBuilder()
        ConnectionArbiter.reset()
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) { messages.appendLine(msg) }
        }
        mockStatic(android.os.SystemClock::class.java).use {
        mockStatic(android.os.Looper::class.java).use {
        mockStatic(androidx.appcompat.app.AppCompatDelegate::class.java).use {
            val f = Fixture()
            try { block(f) } catch (failure: Throwable) {
                throw AssertionError(messages.toString(), failure)
            } finally {
                f.scope.cancel(); f.io.drain(); f.queue.drain()
                ConnectionArbiter.reset(); AppLog.LOGGER = logger
            }
        }
        }
        }
    }

    @Test fun stopOpenDuringTheFirstPublicIoWaitCannotReviveTheLogicalAttempt() = fixture { f ->
        f.scan()
        assertTrue(f.launcher.isSwitchingToProjection())
        val c = f.stopOpen()
        f.connect()
        f.queue.advance(1500); f.connect()
        assertEquals(0, f.opens)
        assertTrue(f.comm.isAppRunOwner(c))
        assertTrue(f.states.value is CommManager.ConnectionState.Disconnected)
        f.assertIdle()
    }

    @Test fun stopOpenDuringRetryDelayAfterAnActualOpenFailureCannotOpenAgain() = fixture { f ->
        f.failFirst = true
        f.scan(); f.connect()
        assertEquals(1, f.opens)
        assertTrue(f.states.value is CommManager.ConnectionState.Disconnected)
        val c = f.stopOpen()
        f.queue.advance(1500); f.connect()
        assertEquals(1, f.opens)
        assertTrue(f.comm.isAppRunOwner(c))
        f.assertIdle()
    }

    @Test fun stopOpenDuringPermissionPollingCannotOpenOrRetainTheBusySlot() = fixture { f ->
        `when`(f.usb.hasPermission(f.device)).thenReturn(false)
        f.scan()
        assertTrue(f.launcher.isSwitchingToProjection())
        val c = f.stopOpen()
        `when`(f.usb.hasPermission(f.device)).thenReturn(true)
        f.queue.advance(UsbAccessoryHandoffPolicy.PERMISSION_POLL_INTERVAL_MS); f.connect()
        assertEquals(0, f.opens)
        assertTrue(f.comm.isAppRunOwner(c))
        f.assertIdle()
        f.scan(); f.connect()
        assertEquals(1, f.opens)
        assertTrue(f.states.value is CommManager.ConnectionState.Connected)
    }

    @Test fun compatibleOpenWithoutStopRetainsTheRetryAfterARealFailure() = fixture { f ->
        f.failFirst = true
        f.scan(); f.connect()
        assertEquals(1, f.opens)
        f.comm.onAppRelaunched()
        f.queue.advance(1500); f.connect()
        assertEquals(2, f.opens)
        assertTrue(f.states.value is CommManager.ConnectionState.Connected)
        f.assertIdle()
    }

    @Test fun aFreshScanCanTakeOverBeforeTheCancelledOldIoDispatchReturns() = fixture { f ->
        f.scan()
        val oldJob = checkNotNull(f.launcher.attemptJob)
        f.stopOpen()
        f.scan()
        val freshJob = checkNotNull(f.launcher.attemptJob)
        assertNotSame(oldJob, freshJob)
        f.connect()
        assertEquals(1, f.opens)
        assertTrue(oldJob.isCancelled)
        assertTrue(freshJob.isCompleted)
        assertTrue(f.states.value is CommManager.ConnectionState.Connected)
        verify(f.handle, never()).close()
        f.assertIdle()
    }

    @Test fun anOldPermissionReplyCannotReplaceOrReleaseTheFreshAttemptsClaim() = fixture { f ->
        f.stopOpen()
        f.scan()
        val fresh = checkNotNull(f.launcher.attemptJob)
        f.permissionReply(0L)
        assertSame(fresh, f.launcher.attemptJob)
        assertEquals(0, f.opens)
        f.connect()
        assertEquals(1, f.opens)
        assertTrue(f.states.value is CommManager.ConnectionState.Connected)
        verify(f.handle, never()).close()
        f.assertIdle()
    }

    @Test fun permissionGrantedForANewInputAfterStopIsAccepted() = fixture { f ->
        f.stopOpen()
        f.permissionReply(1L)
        f.connect()
        assertEquals(1, f.opens)
        assertTrue(f.states.value is CommManager.ConnectionState.Connected)
        f.assertIdle()
    }

    // A request made before first unlock carries no ticket: the grant persists, but its reply
    // cannot start a session that needs credential-protected storage.
    @Test fun aGrantOnlyPermissionReplyCannotOpenUsb() = fixture { f ->
        f.permissionReply(null)
        f.connect()
        assertEquals(0, f.opens)
        f.assertIdle()
        f.scan(); f.connect()
        assertEquals(1, f.opens)
        assertTrue(f.states.value is CommManager.ConnectionState.Connected)
    }

    @Test fun cancelledSettingsRestartCannotBorrowTheTicketOfTheNextSave() = fixture { f ->
        fun saved() = CommManager.ConnectionState.Disconnected(
            reason = CommManager.DisconnectReason.SETTINGS_RESTART,
            settingsRestartUntilMs = Long.MAX_VALUE,
        )
        val first = saved()
        f.states.value = first
        f.failFirst = true
        f.launcher.restartForSettings(first); f.queue.drain(); f.connect()
        assertEquals(1, f.opens)
        val c = f.stopOpen()
        f.queue.advance(1500); f.connect()
        assertEquals(1, f.opens)
        assertTrue(f.comm.isAppRunOwner(c))
        assertTrue(first.isSettingsRestartCancelled)
        f.assertIdle()
        val next = saved()
        f.states.value = next
        f.launcher.restartForSettings(next); f.queue.drain(); f.connect()
        assertEquals(2, f.opens)
        assertTrue(f.states.value is CommManager.ConnectionState.Connected)
        f.assertIdle()
    }

    @Test fun aNewManualUsbInputAfterStopCanOriginateANewRun() = fixture { f ->
        f.comm.requestExplicitAppExit(killProcess = false)
        f.launcher.checkOnRequest(userRequested = true)
        f.queue.drain(); f.connect()
        assertEquals(1, f.opens)
        assertTrue(f.states.value is CommManager.ConnectionState.Connected)
        f.assertIdle()
    }
}
