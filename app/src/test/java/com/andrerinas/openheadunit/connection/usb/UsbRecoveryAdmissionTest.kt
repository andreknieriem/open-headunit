package com.andrerinas.openheadunit.connection.usb

import android.content.Context
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbConstants
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.AppExitPolicy
import com.andrerinas.openheadunit.connection.ConnectionArbiter
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Tier
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Owner
import com.andrerinas.openheadunit.connection.withAppExitDispatcherForTest
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.mockito.Mockito.*
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UsbRecoveryAdmissionTest {
    private val clock = ConnectionArbiter.clock
    private val logger = AppLog.LOGGER
    @Before fun prepare() {
        ConnectionArbiter.reset()
        ConnectionArbiter.clock = { 1000L }
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) = Unit
        }
    }
    @After fun restore() {
        ConnectionArbiter.reset()
        ConnectionArbiter.clock = clock
        AppLog.LOGGER = logger
    }
    private class Fixture {
        val manager = mock(CommManager::class.java, CALLS_REAL_METHODS).withAppExitDispatcherForTest()
        val context = mock(Context::class.java)
        val usb = mock(UsbManager::class.java)
        val device = mock(UsbDevice::class.java)
        val settings = mock(Settings::class.java)
        val owner = Any()
        val claim = checkNotNull(ConnectionArbiter.claim(Tier.USB, Owner.USB, "recovery"))
        val states = MutableStateFlow<CommManager.ConnectionState>(CommManager.ConnectionState.Disconnected())
        init {
            set("transportLifecycleLock", Any())
            set("appExitPolicy", AppExitPolicy())
            set("usbRecoveryOwner", owner)
            set("_connectionState", states)
            set("context", context)
            set("settings", settings)
            `when`(device.deviceName).thenReturn("usb-1")
            `when`(device.vendorId).thenReturn(0x18d1)
            `when`(device.productId).thenReturn(0x2d00)
            `when`(context.getSystemService(Context.USB_SERVICE)).thenReturn(usb)
            val prefs = mock(SharedPreferences::class.java)
            `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
            `when`(usb.deviceList).thenReturn(hashMapOf("usb-1" to device))
        }
        fun prepareOpen(): UsbDeviceConnection {
            val handle = mock(UsbDeviceConnection::class.java)
            val iface = mock(UsbInterface::class.java)
            val input = mock(UsbEndpoint::class.java)
            val output = mock(UsbEndpoint::class.java)
            `when`(usb.hasPermission(device)).thenReturn(true)
            `when`(usb.openDevice(device)).thenReturn(handle)
            `when`(device.interfaceCount).thenReturn(1)
            `when`(device.getInterface(0)).thenReturn(iface)
            `when`(handle.claimInterface(iface, true)).thenReturn(true)
            `when`(iface.endpointCount).thenReturn(2)
            `when`(iface.getEndpoint(0)).thenReturn(input)
            `when`(iface.getEndpoint(1)).thenReturn(output)
            `when`(input.direction).thenReturn(UsbConstants.USB_DIR_IN)
            `when`(output.direction).thenReturn(UsbConstants.USB_DIR_OUT)
            `when`(input.type).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK)
            `when`(output.type).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK)
            // Leave close verification to connectUsb's real finally block. The unrelated
            // decoder/service teardown is not part of this publication-boundary test.
            doAnswer {
                set("disconnectRequested", true)
                states.value = CommManager.ConnectionState.Disconnected()
                null
            }.`when`(manager).disconnect(any(), any(), any(), any(), any(), anyOrNull())
            return handle
        }
        fun set(name: String, value: Any?) = CommManager::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(manager, value)
    }


    @Test fun companionDetachDeliveredToAnOldObserverRejectsCurrentPublication(): Unit = runBlocking {
        val f = Fixture()
        val handle = f.prepareOpen()
        val original = UsbRecoveryDevice("usb-1", 0x18d1, 0x2d00, "Maker", "Box", "target")
        val companion = original.copy(path = "usb-2", serial = "other")
        val target = UsbRecoveryTarget(original, listOf(original, companion))
        val episode = StaleAccessoryRecoveryPolicy.BusObservation()
        val candidate = StaleAccessoryRecoveryPolicy.BusObservation()
        doAnswer {
            // The callback captured the episode observer before the candidate replaced it.
            // Resume it after open, but before Connected publication.
            target.noteDetach(companion.path, companion.vendor, companion.product)
            episode.noteDetach()
            null
        }.`when`(f.settings).saveLastConnection(eq(Settings.CONNECTION_TYPE_USB), anyOrNull(), anyOrNull())
        withContext(Dispatchers.IO) {
            mockStatic(android.os.SystemClock::class.java).use {
                f.manager.reconnectUsb(f.device, f.owner, f.claim, target.guard(candidate))
            }
        }
        verify(f.settings).saveLastConnection(any(), any(), any())
        assertFalse(candidate.hasDetached)
        assertTrue(f.states.value is CommManager.ConnectionState.Disconnected)
        verify(handle).close()
    }

    @Test fun detachBeforeRetryDispatchCannotAcquireAClaim(): Unit = runBlocking {
        val f = Fixture()
        val observation = StaleAccessoryRecoveryPolicy.BusObservation()
        observation.noteDetach()
        f.manager.reconnectUsb(f.device, f.owner, f.claim, observation)
        assertTrue(ConnectionArbiter.holds(f.claim))
        verifyNoInteractions(f.usb)
    }

    @Test fun detachDuringTeardownCannotOpenSamePathReplacement(): Unit = runBlocking {
        val f = Fixture()
        f.prepareOpen()
        val observation = StaleAccessoryRecoveryPolicy.BusObservation()
        val teardown = Job()
        f.set("_disconnectJob", teardown)
        val permission = CountDownLatch(1)
        `when`(f.usb.hasPermission(f.device)).thenAnswer { permission.countDown(); true }
        val retry = async(Dispatchers.IO) { f.manager.reconnectUsb(f.device, f.owner, f.claim, observation) }
        try {
            assertTrue(permission.await(5, TimeUnit.SECONDS))
            observation.noteDetach()
        } finally { teardown.complete() }
        withTimeout(5_000) { retry.await() }
        assertTrue(f.states.value is CommManager.ConnectionState.Disconnected)
        verify(f.usb, never()).openDevice(anyOrNull())
    }

    @Test fun detachAfterOpenCannotPublishConnectedOrAnOldFailure(): Unit = runBlocking {
        for (throwAfterDetach in listOf(false, true)) {
            ConnectionArbiter.reset()
            val f = Fixture()
            val handle = f.prepareOpen()
            val observation = StaleAccessoryRecoveryPolicy.BusObservation()
            val failures = mutableListOf<String>()
            f.manager.onSessionFailure = { failures.add(it) }
            doAnswer {
                observation.noteDetach()
                if (throwAfterDetach) throw IllegalStateException("detached during persistence")
                null
            }.`when`(f.settings).saveLastConnection(any(), any(), any())
            withContext(Dispatchers.IO) {
                mockStatic(android.os.SystemClock::class.java).use {
                    f.manager.reconnectUsb(f.device, f.owner, f.claim, observation)
                }
            }
            assertTrue(f.states.value is CommManager.ConnectionState.Disconnected)
            assertTrue(failures.isEmpty())
            verify(handle, atLeastOnce()).close()
        }
    }

    @Test fun targetDisappearingDuringOpenClosesItsHandleWithoutPublishingSuccess(): Unit = runBlocking {
        val f = Fixture()
        val handle = f.prepareOpen()
        val bus = StaleAccessoryRecoveryPolicy.BusObservation()
        var reads = 0
        val admission = object : com.andrerinas.openheadunit.connection.UsbRecoveryAdmission {
            override fun resolve(manager: UsbManager): UsbDevice? {
                if (++reads == 1) return f.device
                bus.noteDetach()
                return null
            }
            override fun run(action: () -> Unit) = bus.run(action)
        }
        withContext(Dispatchers.IO) {
            mockStatic(android.os.SystemClock::class.java).use {
                f.manager.reconnectUsb(f.device, f.owner, f.claim, admission)
            }
        }
        assertEquals(2, reads)
        assertTrue(f.states.value is CommManager.ConnectionState.Disconnected)
        verify(handle, atLeastOnce()).close()
        verify(f.settings, never()).saveLastConnection(any(), any(), any())
    }

    @Test fun revokedOwnerCannotClaimAfterDispatcherHandoff(): Unit = runBlocking {
        val f = Fixture()
        f.set("usbRecoveryOwner", Any())
        f.manager.reconnectUsb(f.device, f.owner, f.claim)
        assertTrue(ConnectionArbiter.holds(f.claim))
        verifyNoInteractions(f.usb)
    }

    @Test fun revocationAfterClaimCannotPublishAConnection(): Unit = runBlocking {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        `when`(f.usb.hasPermission(f.device)).thenAnswer {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            true
        }
        val retry = async(Dispatchers.IO) { f.manager.reconnectUsb(f.device, f.owner, f.claim) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.set("usbRecoveryOwner", Any())
        } finally { release.countDown() }
        withTimeout(5_000) { retry.await() }
        assertTrue(f.states.value is CommManager.ConnectionState.Disconnected)
        verify(f.usb, never()).openDevice(anyOrNull())
        assertFalse(ConnectionArbiter.holds(f.claim))
    }

    @Test fun anotherClaimInTheSameRecoveryIntervalCannotBeStolen(): Unit = runBlocking {
        val f = Fixture()
        val newer = checkNotNull(ConnectionArbiter.claim(Tier.USB, Owner.USB, "new attachment"))
        f.manager.reconnectUsb(f.device, f.owner, f.claim)
        assertTrue(ConnectionArbiter.holds(newer))
        verifyNoInteractions(f.usb)
    }

    @Test fun claimReplacementDuringPersistenceCannotPublishConnected(): Unit = runBlocking {
        checkReplacementBeforePublication(throwAfterReplacement = false)
    }

    @Test fun claimReplacementBeforeExceptionCannotPublishOldFailure(): Unit = runBlocking {
        checkReplacementBeforePublication(throwAfterReplacement = true)
    }

    private suspend fun checkReplacementBeforePublication(throwAfterReplacement: Boolean) = coroutineScope {
        val f = Fixture()
        val handle = f.prepareOpen()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failures = mutableListOf<String>()
        f.manager.onSessionFailure = { failures.add(it) }
        doAnswer {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            if (throwAfterReplacement) throw IllegalStateException("persistence failed")
            null
        }.`when`(f.settings).saveLastConnection(any(), any(), any())
        val retry = async(Dispatchers.IO) {
            // StandardUsb's diagnostic clock is initialized on the connection thread.
            mockStatic(android.os.SystemClock::class.java).use {
                f.manager.reconnectUsb(f.device, f.owner, f.claim)
            }
        }
        var newer: ConnectionArbiter.Claim? = null
        try {
            val reached = entered.await(5, TimeUnit.SECONDS)
            assertTrue("USB open did not reach settings persistence: ${f.states.value}", reached)
            // Same USB owner: no preempt callback, so candidate identity alone cannot
            // detect this replacement. Persistence must not hold the arbiter monitor.
            newer = checkNotNull(ConnectionArbiter.claim(Tier.USB, Owner.USB, "new attachment"))
        } finally { release.countDown() }
        withTimeout(5_000) { retry.await() }
        assertTrue(f.states.value is CommManager.ConnectionState.Disconnected)
        assertTrue(ConnectionArbiter.holds(checkNotNull(newer)))
        assertTrue(failures.isEmpty())
        verify(handle, atLeastOnce()).close()
    }
}
