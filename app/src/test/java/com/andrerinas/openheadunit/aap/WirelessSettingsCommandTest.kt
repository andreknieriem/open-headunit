package com.andrerinas.openheadunit.aap

import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.connection.ConnectionOperation
import com.andrerinas.openheadunit.connection.AppExitPolicy
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class WirelessSettingsCommandTest {
    private fun serviceWithoutHctOwner(): AapService {
        val service = mock(AapService::class.java, CALLS_REAL_METHODS)
        // Mockito bypasses the Service constructor. Preserve the real lazy-owner state so
        // settings cleanup exercises releaseParkedHct without creating an OEM connection.
        AapService::class.java.getDeclaredField("hctWppOwnerDelegate").apply { isAccessible = true }
            .set(service, lazy<com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.hct.HctWppOwner> {
                error("Settings cleanup must not initialize an unused HCT owner")
            })
        return service
    }

    @Test fun `queued wireless refresh preserves the same Save but explicit stop revokes it`() {
        mockStatic(SystemClock::class.java).use {
        mockStatic(AppCompatDelegate::class.java).use {
            for (fromSave in listOf(true, false)) {
                val service = serviceWithoutHctOwner()
                val app = mock(App::class.java)
                val component = mock(AppComponent::class.java)
                doReturn(app).`when`(service).applicationContext
                App::class.java.getDeclaredField("component\$delegate").apply { isAccessible = true }
                    .set(app, lazyOf(component))
                val comm = mock(CommManager::class.java, CALLS_REAL_METHODS)
                `when`(component.commManager).thenReturn(comm)
                val saved = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART)
                val state = MutableStateFlow<CommManager.ConnectionState>(saved)
                for ((name, value) in listOf("_connectionState" to state, "transportLifecycleLock" to Any(), "appExitPolicy" to AppExitPolicy())) {
                    CommManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(comm, value)
                }
                val retry = Job()
                saved.trackSettingsLaunch(retry)
                val wireless = mock(WifiLauncherManager::class.java)
                AapService::class.java.getDeclaredField("wifiLauncherManager").apply { isAccessible = true }
                    .set(service, wireless)
                service.stopWirelessForCommand(fromSettings = fromSave, ticket = comm.appConnectionTicket())
                assertSame(saved, state.value)
                assertEquals(fromSave, saved.acceptsSettingsRestart(state.value))
                assertEquals(!fromSave, retry.isCancelled)
                verify(wireless).detachForOperation(org.mockito.kotlin.any())
                retry.cancel()
            }
        }
        }
    }
    @Test fun `wireless cleanup finishes before Save can publish a replacement Self listener`() {
        mockStatic(SystemClock::class.java).use {
        mockStatic(AppCompatDelegate::class.java).use {
            val service = serviceWithoutHctOwner()
            val app = mock(App::class.java)
            val component = mock(AppComponent::class.java)
            doReturn(app).`when`(service).applicationContext
            App::class.java.getDeclaredField("component\$delegate").apply { isAccessible = true }
                .set(app, lazyOf(component))
            val comm = mock(CommManager::class.java, CALLS_REAL_METHODS)
            `when`(component.commManager).thenReturn(comm)
            val saved = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART)
            val state = MutableStateFlow<CommManager.ConnectionState>(saved)
            for ((name, value) in listOf("_connectionState" to state, "transportLifecycleLock" to Any(), "appExitPolicy" to AppExitPolicy())) {
                CommManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(comm, value)
            }
            val manager = mock(WifiLauncherManager::class.java, CALLS_REAL_METHODS)
            WifiLauncherManager::class.java.getDeclaredField("service").apply { isAccessible = true }.set(manager, service)
            val shared = com.andrerinas.openheadunit.connection.wifi.WifiLauncherSharedServices(service)
            WifiLauncherManager::class.java.getDeclaredField("sharedServices").apply { isAccessible = true }.set(manager, shared)
            AapService::class.java.getDeclaredField("wifiLauncherManager").apply { isAccessible = true }.set(service, manager)
            val instance = AapService::class.java.getDeclaredField("instance").apply { isAccessible = true }
            val previous = instance.get(null)
            val serverField = shared.javaClass.getDeclaredField("wirelessServer").apply { isAccessible = true }
            val oldServer = mock(com.andrerinas.openheadunit.connection.wifi.server.WirelessServer::class.java)
            val replacement = mock(com.andrerinas.openheadunit.connection.wifi.server.WirelessServer::class.java)
            serverField.set(shared, oldServer)
            val context = mock(android.content.Context::class.java)
            try {
                instance.set(null, service)
                AapService.applyWirelessSettings(service, startWireless = false, ticket = comm.appConnectionTicket())
                verify(oldServer).stopServer()
                assertNull(shared.wirelessServer)
                verify(service, never()).startService(org.mockito.kotlin.any())
                // This is the listener publication performed by the later Self retry. There is
                // no queued service command left to tear it down after this boundary.
                serverField.set(shared, replacement)
                assertTrue(saved.acceptsSettingsRestart(state.value))
                verifyNoInteractions(replacement)
                service.stopWirelessForCommand(fromSettings = false, ticket = comm.appConnectionTicket())
                verify(replacement).stopServer()
                assertNull(shared.wirelessServer)
                assertFalse(saved.acceptsSettingsRestart(state.value))
            } finally {
                instance.set(null, previous)
                com.andrerinas.openheadunit.connection.ConnectionStageTracker.clear()
            }
        }
        }
    }

}
