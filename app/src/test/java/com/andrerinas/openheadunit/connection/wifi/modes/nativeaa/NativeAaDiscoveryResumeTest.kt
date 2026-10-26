package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.ZbtDiscovery
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.ZbtDiscoveryPolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.ZbtDaemonReachability
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever

class NativeAaDiscoveryResumeTest {
    private lateinit var clock: org.mockito.MockedStatic<android.os.SystemClock>
    private lateinit var logger: AppLog.Logger

    @Before fun setup() {
        clock = org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java)
        ZbtDaemonReachability.forget()
        ZbtDiscovery.beginArming()
        logger = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) = Unit
        }
    }

    @After fun restore() { ZbtDiscovery.beginArming(); AppLog.LOGGER = logger; clock.close() }

    private fun set(manager: NativeAaHandshakeManager, field: String, value: Any?) {
        NativeAaHandshakeManager::class.java.getDeclaredField(field).apply { isAccessible = true }.set(manager, value)
    }

    @Test fun pickedPhoneSurvivesDiscoveryAndWakesAfterAndroidFallback() {
        val manager = mock(NativeAaHandshakeManager::class.java, CALLS_REAL_METHODS)
        set(manager, "measuringDaemon", true)
        set(manager, "measureGeneration", 4)
        val wakes = mutableListOf<String>()
        doAnswer {
            // Use the real deferral while discovery owns the route, and replace only the
            // physical Bluetooth work once the Android listener has been armed.
            if (manager.isMeasuringDaemon()) it.callRealMethod()
            else wakes.add(it.getArgument(0))
            Unit
        }.whenever(manager).manualPoke(any())
        manager.selectDriver("11:22:33:44:55:66", explicit = true)
        assertTrue(wakes.isEmpty())
        val decision = ZbtDiscoveryPolicy.Decision(ZbtDiscoveryPolicy.Mode.IDENTITY, "QF")
        manager.completeDaemonMeasurement(4, decision, ZbtDiscovery.Measurement.Completion.CARRIER_BUSY) {
            assertFalse(manager.isMeasuringDaemon())
            val snapshot = ZbtDiscovery.measurement(decision).snapshot()
            assertEquals(ExternalBtTransportPolicy.Route.NORMAL,
                ExternalBtTransportPolicy.route(snapshot, false, false))
            assertFalse(ExternalBtTransportPolicy.needsDaemonMeasurement(snapshot, false, false))
            assertNull(ZbtDaemonReachability.cached(requireIdentity = true))
            assertEquals("11:22:33:44:55:66", manager.pendingSelectionTargetMac)
            set(manager, "isRunning", true)
        }
        assertEquals(listOf("11:22:33:44:55:66"), wakes)
        assertEquals("11:22:33:44:55:66", manager.pendingSelectionTargetMac)
    }

    @Test fun staleTimeoutCannotDeferOrResumeReplacementArming() {
        val manager = mock(NativeAaHandshakeManager::class.java, CALLS_REAL_METHODS)
        set(manager, "measureGeneration", 5)
        set(manager, "measuringDaemon", true)
        val decision = ZbtDiscoveryPolicy.Decision(ZbtDiscoveryPolicy.Mode.IDENTITY, "QF")
        manager.completeDaemonMeasurement(4, decision, ZbtDiscovery.Measurement.Completion.CARRIER_BUSY) {
            fail("old timeout armed listeners")
        }
        assertTrue(manager.isMeasuringDaemon())
        assertNull(ZbtDiscovery.measurement(decision).snapshot().reachable)
    }

    @Test fun stoppedGenerationCannotArmOrWakeItsOldPick() {
        val manager = mock(NativeAaHandshakeManager::class.java, CALLS_REAL_METHODS)
        set(manager, "measureGeneration", 5)
        manager.resumeAfterDaemonMeasurement(4) { fail("stale completion armed listeners") }
        assertNull(manager.pendingSelectionTargetMac)
    }
    @Test fun busyLegacyRouteStopsWithoutFalseCacheFallbackOrImmediateRearm() {
        val manager = mock(NativeAaHandshakeManager::class.java, CALLS_REAL_METHODS)
        set(manager, "measureGeneration", 4)
        set(manager, "measuringDaemon", true)
        set(manager, "wakeAwaitingModule", true)
        val decision = ZbtDiscoveryPolicy.Decision(ZbtDiscoveryPolicy.Mode.REACHABILITY, "external")
        manager.completeDaemonMeasurement(4, decision, ZbtDiscovery.Measurement.Completion.CARRIER_BUSY) {
            fail("busy legacy completion immediately rearmed")
        }
        assertFalse(manager.isMeasuringDaemon())
        assertTrue(manager.notStartedReason()!!.contains("still in use"))
        val measurement = ZbtDiscovery.measurement(decision)
        assertNull(measurement.snapshot().reachable)
        assertNull(measurement.cached())
        assertEquals(ExternalBtTransportPolicy.WifiButton.MODULE,
            ExternalBtTransportPolicy.wifiButton(measurement.snapshot(), false, false))
        assertEquals(ModuleRearmPolicy.Action.START_HANDSHAKE, ModuleRearmPolicy.action(
            nativeLauncherStarted = true, launcherRefusedModule = false,
            handshakeStarted = manager.isStarted(), measuringDaemon = manager.isMeasuringDaemon()))

        // The next explicit button press can measure again. A real answer then resumes once;
        // the busy result did not install a refusal or force the Android fallback snapshot.
        set(manager, "measuringDaemon", true)
        ZbtDaemonReachability.record(true)
        var arms = 0
        manager.completeDaemonMeasurement(4, decision, ZbtDiscovery.Measurement.Completion.SETTLED) {
            arms++
            assertFalse(ExternalBtTransportPolicy.needsDaemonMeasurement(
                measurement.snapshot(), false, false))
            assertEquals(ExternalBtTransportPolicy.Route.ZBT,
                ExternalBtTransportPolicy.route(measurement.snapshot(), false, false))
        }
        assertEquals(1, arms)
    }

}
