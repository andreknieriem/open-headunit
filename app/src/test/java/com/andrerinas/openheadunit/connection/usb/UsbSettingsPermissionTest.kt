package com.andrerinas.openheadunit.connection.usb

import android.hardware.usb.UsbDevice
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.ConnectionArbiter
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class UsbSettingsPermissionTest {
    @Test fun `late permission distinguishes cancellation from expired or overtaken Save`() {
        mockStatic(android.os.SystemClock::class.java).use {
        mockStatic(androidx.appcompat.app.AppCompatDelegate::class.java).use {
            for (outcome in listOf("cancelled", "expired", "overtaken")) {
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
            `when`(service.applicationContext).thenReturn(app)
            App::class.java.getDeclaredField("component\$delegate").apply { isAccessible = true }.set(app, lazyOf(component))
            `when`(component.commManager).thenReturn(comm)
            val manager = spy(UsbLauncherManager(service))
            doNothing().`when`(manager).checkAlreadyConnected(true, false, null)
            val usb = mock(android.hardware.usb.UsbManager::class.java)
            `when`(service.getSystemService(android.content.Context.USB_SERVICE)).thenReturn(usb)
            `when`(service.getSharedPreferences(anyString(), anyInt()))
                .thenReturn(mock(android.content.SharedPreferences::class.java))
            val device = mock(UsbDevice::class.java)
            `when`(device.deviceName).thenReturn("/dev/bus/usb/001/002")
            `when`(device.vendorId).thenReturn(0x18d1)
            `when`(device.productId).thenReturn(0x2d00)
            // A system dialog can outlive Save or an intervening connection. Only explicit
            // cancellation must discard the grant; the other cases use ordinary admission.
            @Suppress("UNCHECKED_CAST")
            val owners = UsbLauncherManager::class.java.getDeclaredField("permissionOwners")
                .apply { isAccessible = true }.get(manager) as MutableMap<String, CommManager.ConnectionState.Disconnected>
            owners[device.deviceName] = saved
            if (outcome == "cancelled") comm.cancelPendingSettingsRestart()
            ConnectionArbiter.reset()
            try {
                UsbLauncherListener(manager).onUsbPermission(true, true, device)
                verify(manager, times(if (outcome == "cancelled") 0 else 1))
                    .checkAlreadyConnected(true, false, null)
                assertFalse(manager.isSwitchingToProjection())
                assertSame(current, states.value)
                assertTrue(owners.isEmpty())
            } finally { ConnectionArbiter.reset() }
            }
        }
        }
    }
}
