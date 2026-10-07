package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.connection.CommManager
import org.mockito.kotlin.anyOrNull
import org.junit.Test
import org.mockito.Mockito.*

class UsbSettingsRestartTest {
    @org.junit.After fun resetArbiter() {
        com.andrerinas.openheadunit.connection.ConnectionArbiter.reset()
    }

    @Test fun `Save waiting for retirement cannot lift a later USB cancellation`() {
        val manager = mock(UsbLauncherManager::class.java, CALLS_REAL_METHODS)
        UsbLauncherManager::class.java.getDeclaredField("cancelledByUser").apply { isAccessible = true }.set(manager, true)
        val saved = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART)
        manager.restartForSettings(saved)
        verify(manager, never()).checkAlreadyConnected(anyBoolean(), anyBoolean(), anyOrNull())
    }

    @Test fun `uncancelled Save still grants one explicit device check`() {
        val manager = mock(UsbLauncherManager::class.java, CALLS_REAL_METHODS)
        val saved = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART)
        doNothing().`when`(manager).checkAlreadyConnected(true, false, saved)
        manager.restartForSettings(saved)
        verify(manager).checkAlreadyConnected(force = true, userRequested = false, settingsRestart = saved)
    }

    @Test fun `revoking Save cancels queued and permission-waiting USB work without changing AAP state`() {
        mockStatic(android.os.SystemClock::class.java).use {
        mockStatic(androidx.appcompat.app.AppCompatDelegate::class.java).use {
            for (enterPermissionWait in listOf(false, true)) for (cancelPath in listOf("save", "user", "preempt", "replacement")) {
                com.andrerinas.openheadunit.connection.ConnectionArbiter.reset()
                val tasks = java.util.ArrayDeque<Runnable>()
                val queued = object : kotlinx.coroutines.CoroutineDispatcher() {
                    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
                }
                val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + queued)
                val service = mock(com.andrerinas.openheadunit.aap.AapService::class.java)
                val app = mock(com.andrerinas.openheadunit.App::class.java)
                val component = mock(com.andrerinas.openheadunit.AppComponent::class.java)
                val comm = mock(CommManager::class.java, CALLS_REAL_METHODS)
                val saved = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART)
                val states = kotlinx.coroutines.flow.MutableStateFlow<CommManager.ConnectionState>(saved)
                fun field(name: String, value: Any) {
                    CommManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(comm, value)
                }
                field("_connectionState", states)
                field("connectionState", states)
                field("transportLifecycleLock", Any())
                `when`(service.serviceScope).thenReturn(scope)
                `when`(service.applicationContext).thenReturn(app)
                com.andrerinas.openheadunit.App::class.java.getDeclaredField("component\$delegate")
                    .apply { isAccessible = true }.set(app, lazyOf(component))
                `when`(component.commManager).thenReturn(comm)
                val launcher = UsbLauncherManager(service)
                org.junit.Assert.assertTrue(launcher.beginAttempt(
                    com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Tier.USB, "Save retry", saved))
                org.junit.Assert.assertTrue(launcher.isSwitchingToProjection())
                val claimField = UsbLauncherManager::class.java.getDeclaredField("attemptClaim").apply { isAccessible = true }
                val oldClaim = claimField.get(launcher) as com.andrerinas.openheadunit.connection.ConnectionArbiter.Claim
                org.junit.Assert.assertTrue(com.andrerinas.openheadunit.connection.ConnectionArbiter.holds(oldClaim))
                val permission = kotlinx.coroutines.CompletableDeferred<Unit>()
                var entered = false
                var opened = false
                val block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit = {
                    entered = true
                    permission.await()
                    opened = true
                }
                val launch = UsbLauncherManager::class.java.getDeclaredMethod("launchAttempt",
                    CommManager.ConnectionState.Disconnected::class.java,
                    kotlin.coroutines.CoroutineContext::class.java,
                    kotlin.jvm.functions.Function2::class.java).apply { isAccessible = true }
                launch.invoke(launcher, saved, kotlin.coroutines.EmptyCoroutineContext, block)
                if (enterPermissionWait) while (tasks.isNotEmpty()) tasks.removeFirst().run()
                org.junit.Assert.assertEquals(enterPermissionWait, entered)
                comm.cancelPendingSettingsRestart()
                when (cancelPath) {
                    "user" -> launcher.stopForUser()
                    "preempt", "replacement" -> launcher.preemptAttempt()
                }
                var replacementClaim: com.andrerinas.openheadunit.connection.ConnectionArbiter.Claim? = null
                if (cancelPath == "replacement") {
                    org.junit.Assert.assertTrue(launcher.beginAttempt(
                        com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Tier.USB, "new USB request"))
                    replacementClaim = claimField.get(launcher) as com.andrerinas.openheadunit.connection.ConnectionArbiter.Claim
                    val replacement: suspend kotlinx.coroutines.CoroutineScope.() -> Unit = { kotlinx.coroutines.awaitCancellation() }
                    launch.invoke(launcher, null, kotlin.coroutines.EmptyCoroutineContext, replacement)
                }
                permission.complete(Unit)
                while (tasks.isNotEmpty()) tasks.removeFirst().run()
                org.junit.Assert.assertFalse(opened)
                org.junit.Assert.assertSame(saved, states.value)
                org.junit.Assert.assertFalse(com.andrerinas.openheadunit.connection.ConnectionArbiter.holds(oldClaim))
                if (replacementClaim != null) {
                    org.junit.Assert.assertTrue(launcher.isSwitchingToProjection())
                    org.junit.Assert.assertTrue(com.andrerinas.openheadunit.connection.ConnectionArbiter.holds(replacementClaim))
                } else {
                    org.junit.Assert.assertFalse(launcher.isSwitchingToProjection())
                    org.junit.Assert.assertNull(claimField.get(launcher))
                    launcher.liftUserCancel("next explicit USB request")
                    org.junit.Assert.assertTrue(launcher.beginAttempt(
                        com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Tier.USB, "next USB request"))
                    launcher.setSwitchingToProjection(false)
                }
                scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
                while (tasks.isNotEmpty()) tasks.removeFirst().run()
                com.andrerinas.openheadunit.connection.ConnectionArbiter.reset()
            }
        }
        }
    }
}
