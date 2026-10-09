package com.andrerinas.openheadunit.connection.self

import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.CommManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class SelfManualDeadlineTest {
    @Test fun `manual launch deadline retires on live state or conflated Save terminal`() {
        mockStatic(androidx.appcompat.app.AppCompatDelegate::class.java).use {
            for (terminalOnly in listOf(false, true)) {
                val service = mock(AapService::class.java)
                val app = mock(App::class.java)
                val component = mock(AppComponent::class.java)
                val comm = mock(CommManager::class.java)
                `when`(service.applicationContext).thenReturn(app)
                App::class.java.getDeclaredField("component\$delegate").apply { isAccessible = true }.set(app, lazyOf(component))
                `when`(component.commManager).thenReturn(comm)
                val saved = CommManager.ConnectionState.Disconnected(
                    reason = CommManager.DisconnectReason.SETTINGS_RESTART, wasLoopbackSession = true,
                )
                `when`(comm.connectionState).thenReturn(MutableStateFlow(saved))
                `when`(comm.isConnected).thenReturn(!terminalOnly)
                `when`(comm.isLoopbackSession).thenReturn(!terminalOnly)
                val manager = mock(SelfLauncherManager::class.java, CALLS_REAL_METHODS)
                fun set(name: String, value: Any?) = SelfLauncherManager::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }.set(manager, value)
                set("service", service)
                val deadline = Job()
                set("launchTimeoutJob", deadline)
                set("settingsLaunchOwner", null)
                if (terminalOnly) manager.onConnectionEnded(saved) else manager.onConnectionEstablished()
                assertTrue(deadline.isCancelled)
                assertEquals(true, SelfLauncherManager::class.java.getDeclaredField("launchConnected")
                    .apply { isAccessible = true }.get(manager))
            }
        }
    }
}
