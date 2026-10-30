package com.andrerinas.openheadunit.connection.self

import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.utils.DummyVpnPolicy
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class SelfManualDeadlineTest {
    @Test fun `manual launch deadline retires only on an arrival since its acceptance`() {
        // App's companion reads both on first initialization; another test must not be relied on to do it.
        mockStatic(android.os.SystemClock::class.java).use { _ -> mockStatic(androidx.appcompat.app.AppCompatDelegate::class.java).use {
            for (arrived in listOf(false, true)) for (live in listOf(false, true)) {
                val service = mock(AapService::class.java)
                val app = mock(App::class.java)
                val component = mock(AppComponent::class.java)
                val comm = mock(CommManager::class.java)
                `when`(service.applicationContext).thenReturn(app)
                App::class.java.getDeclaredField("component\$delegate").apply { isAccessible = true }.set(app, lazyOf(component))
                `when`(component.commManager).thenReturn(comm)
                val mark = CommManager.LaunchMark(0L, CommManager.ConnectionState.Disconnected(), 0L)
                `when`(comm.arrivedSince(mark)).thenReturn(arrived)
                `when`(comm.isLiveLoopbackSession).thenReturn(live)
                val manager = mock(SelfLauncherManager::class.java, CALLS_REAL_METHODS)
                fun field(name: String) = SelfLauncherManager::class.java.getDeclaredField(name).apply { isAccessible = true }
                fun set(name: String, value: Any?) = field(name).set(manager, value)
                set("service", service)
                val launch = Job()
                val deadline = Job()
                set("launchJob", launch)
                set("launchPending", true)
                set("launchMark", mark)
                set("launchTimeoutJob", deadline)
                manager.onConnectionEstablished()
                // A late live state from a session older than this launch does not answer it.
                assertEquals("arrived=$arrived live=$live", arrived, deadline.isCancelled)
                assertEquals(!arrived, field("launchPending").get(manager))
                // The launch's tun is released as projection would on going live; a live loopback
                // session keeps the launch it answered, and an arrival already over retires it.
                verify(service, if (arrived) times(1) else never()).stopDummyVpn(DummyVpnPolicy.Reason.SELF_MODE_SESSION_LIVE)
                assertEquals(arrived && !live, launch.isCancelled)
                assertSame(if (arrived && !live) null else launch, manager.currentLaunch())
            }
        } }
    }
}
