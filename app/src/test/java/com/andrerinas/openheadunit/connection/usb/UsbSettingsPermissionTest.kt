package com.andrerinas.openheadunit.connection.usb

import android.hardware.usb.UsbDevice
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.withUsbInputPreferencesForTest
import com.andrerinas.openheadunit.connection.ConnectionArbiter
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any as anyValue
import org.mockito.kotlin.eq
import org.mockito.kotlin.same

class UsbSettingsPermissionTest {
    @Test fun `late permission distinguishes cancellation from expired or overtaken Save`() {
        for (outcome in listOf("cancelled", "expired", "overtaken")) checkPermission(outcome)
    }

    @Test fun `denial then grant preserves the original scan and consumes its launcher token once`() {
        checkPermission("expired", rebuiltDialog = true)
    }

    private fun checkPermission(outcome: String, rebuiltDialog: Boolean = false) {
        ConnectionArbiter.reset()
        try {
            mockStatic(android.os.SystemClock::class.java).use {
            mockStatic(androidx.appcompat.app.AppCompatDelegate::class.java).use {
            val service = mock(AapService::class.java)
            val app = mock(App::class.java)
            val component = mock(AppComponent::class.java)
            val comm = mock(CommManager::class.java, CALLS_REAL_METHODS)
            val saved = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART, settingsRestartUntilMs = if (outcome == "expired") 0 else Long.MAX_VALUE)
            val current = if (outcome == "overtaken") CommManager.ConnectionState.Disconnected() else saved
            val states = MutableStateFlow<CommManager.ConnectionState>(current)
            fun set(name: String, value: Any) = CommManager::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.set(comm, value)
            set("_connectionState", states)
            set("connectionState", states)
            set("transportLifecycleLock", Any())
            set("appExitPolicy", com.andrerinas.openheadunit.connection.AppExitPolicy())
            `when`(service.applicationContext).thenReturn(app)
            App::class.java.getDeclaredField("component\$delegate").apply { isAccessible = true }.set(app, lazyOf(component))
            `when`(component.commManager).thenReturn(comm)
            `when`(component.settings).thenReturn(mock(com.andrerinas.openheadunit.utils.Settings::class.java))
            `when`(service.getString(anyInt())).thenReturn("")
            val manager = spy(UsbLauncherManager(service))
            doNothing().`when`(manager).checkAlreadyConnected(anyBoolean(), anyBoolean(), isNull(), anyBoolean(), anyValue(), anyValue())
            val usb = mock(android.hardware.usb.UsbManager::class.java)
            `when`(service.getSystemService(android.content.Context.USB_SERVICE)).thenReturn(usb)
            `when`(service.getSharedPreferences(anyString(), anyInt()))
                .thenReturn(mock(android.content.SharedPreferences::class.java))
            service.withUsbInputPreferencesForTest()
            val device = mock(UsbDevice::class.java)
            `when`(device.deviceName).thenReturn("/dev/bus/usb/001/002")
            `when`(device.vendorId).thenReturn(0x18d1)
            `when`(device.productId).thenReturn(0x2d00)
            // A system dialog can outlive Save or an intervening connection. Only explicit
            // cancellation must discard the grant; the other cases use ordinary admission.
            @Suppress("UNCHECKED_CAST")
            val owners = UsbLauncherManager::class.java.getDeclaredField("permissionOwners")
                .apply { isAccessible = true }.get(manager) as MutableMap<String, UsbLauncherManager.PermissionAttempt>
            val scan = comm.usbRecheck(comm.appConnectionTicket())
            val token = UsbReceiver.newLauncherToken()
            owners[token] = UsbLauncherManager.PermissionAttempt(device.deviceName, saved, scan)
            if (outcome == "cancelled") comm.cancelPendingSettingsRestart()
            val listener = UsbLauncherListener(manager)
            val replyScan = checkNotNull(UsbReceiver.decodeRecheck(UsbReceiver.encodeRecheck(scan)))
            if (rebuiltDialog) {
                listener.onUsbPermission(false, true, device, replyScan, token)
                assertTrue(owners.containsKey(token))
                verify(manager, never()).checkAlreadyConnected(anyBoolean(), anyBoolean(), isNull(), anyBoolean(), anyValue(), anyValue())
            }
            listener.onUsbPermission(true, true, device, replyScan, token)
            assertTrue(owners.isEmpty())
            // An expired Save's grant still continues the original scan before any duplicate arrives.
            // Ordinary admission continues the dialog's own owed scan, not the decoded reply.
            verify(manager, times(if (outcome == "cancelled") 0 else 1))
                .checkAlreadyConnected(eq(true), eq(false), isNull(), eq(false), eq(UsbPlugInPolicy.Trigger.APP_START), same(scan))
            if (rebuiltDialog) {
                listener.onUsbPermission(true, true, device, replyScan, token)
                verify(manager).checkAlreadyConnected(anyBoolean(), anyBoolean(), isNull(), anyBoolean(), anyValue(), anyValue())
            }
            assertFalse(manager.isSwitchingToProjection())
            assertSame(current, states.value)
            }
            }
        } finally { ConnectionArbiter.reset() }
    }
}
