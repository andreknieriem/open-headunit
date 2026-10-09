package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class UsbSettingsRetryLoopTest {
    @Test fun `four physical open failures retain Save but an unrelated terminal stops the loop`() = runBlocking {
        mockStatic(android.os.SystemClock::class.java).use {
        mockStatic(androidx.appcompat.app.AppCompatDelegate::class.java).use {
            for (scenario in listOf("own", "unrelated", "gone", "reenumerated", "different-device", "normal-mode", "blacklisted", "expired")) {
                val service = mock(AapService::class.java)
                val app = mock(App::class.java)
                val component = mock(AppComponent::class.java)
                val comm = mock(CommManager::class.java)
                `when`(service.applicationContext).thenReturn(app)
                App::class.java.getDeclaredField("component\$delegate").apply { isAccessible = true }.set(app, lazyOf(component))
                `when`(component.commManager).thenReturn(comm)
                val saved = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART, settingsRestartUntilMs = if (scenario == "expired") 0 else Long.MAX_VALUE)
                val states = MutableStateFlow<CommManager.ConnectionState>(saved)
                `when`(comm.connectionState).thenReturn(states)
                val device = mock(android.hardware.usb.UsbDevice::class.java)
                `when`(device.deviceName).thenReturn("usb-1")
                `when`(device.vendorId).thenReturn(0x18d1)
                `when`(device.productId).thenReturn(0x2d00)
                val prefs = mock(android.content.SharedPreferences::class.java)
                `when`(service.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
                val usb = mock(android.hardware.usb.UsbManager::class.java)
                `when`(service.getSystemService(android.content.Context.USB_SERVICE)).thenReturn(usb)
                `when`(usb.deviceList).thenReturn(hashMapOf("usb-1" to device))
                `when`(usb.hasPermission(device)).thenReturn(true)
                val fresh = mock(android.hardware.usb.UsbDevice::class.java)
                `when`(fresh.deviceName).thenReturn(if (scenario == "different-device") "usb-1" else "usb-2")
                if (scenario == "different-device") `when`(fresh.vendorId).thenReturn(1)
                `when`(fresh.vendorId).thenReturn(if (scenario == "different-device") 1 else 0x18d1)
                `when`(fresh.productId).thenReturn(if (scenario == "normal-mode") 0x4ee7 else 0x2d00)
                `when`(usb.hasPermission(fresh)).thenReturn(true)
                val opened = mutableListOf<android.hardware.usb.UsbDevice>()
                for (candidate in listOf(device, fresh)) {
                    doAnswer {
                        states.value = CommManager.ConnectionState.Connecting
                        opened += candidate
                        if (scenario == "gone") `when`(usb.deviceList).thenReturn(hashMapOf())
                        if (scenario in listOf("reenumerated", "different-device", "normal-mode", "blacklisted")) {
                            `when`(usb.deviceList).thenReturn(hashMapOf("usb-2" to fresh))
                            `when`(usb.hasPermission(device)).thenReturn(false)
                            if (scenario == "blacklisted") `when`(prefs.getStringSet(anyString(), isNull()))
                                .thenReturn(setOf("vidpid:18d1:2d00"))
                        }
                        states.value = CommManager.ConnectionState.Disconnected(
                            settingsRetryOwner = if (scenario == "unrelated") null else saved)
                        Unit
                    }.`when`(comm).connect(candidate, ConnectionPriorityPolicy.Tier.USB, saved)
                }
                UsbLauncherManager(service).connectWithRetry(device, settingsRestart = saved)
                val expected = when (scenario) { "expired" -> 0; "gone", "unrelated", "different-device", "normal-mode", "blacklisted" -> 1; else -> 4 }
                assertEquals(scenario, expected, opened.size)
                if (scenario == "reenumerated") assertTrue(opened.drop(1).all { it === fresh })
            }
        }
        }
    }
}
