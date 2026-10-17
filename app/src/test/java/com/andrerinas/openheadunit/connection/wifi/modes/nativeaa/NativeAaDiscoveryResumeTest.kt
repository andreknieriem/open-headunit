package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.utils.AppLog
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
        logger = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) = Unit
        }
    }

    @After fun restore() { AppLog.LOGGER = logger; clock.close() }

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
        manager.resumeAfterDaemonMeasurement(4) {
            assertEquals("11:22:33:44:55:66", manager.pendingSelectionTargetMac)
            set(manager, "isRunning", true)
        }
        assertEquals(listOf("11:22:33:44:55:66"), wakes)
        assertEquals("11:22:33:44:55:66", manager.pendingSelectionTargetMac)
    }

    @Test fun stoppedGenerationCannotArmOrWakeItsOldPick() {
        val manager = mock(NativeAaHandshakeManager::class.java, CALLS_REAL_METHODS)
        set(manager, "measureGeneration", 5)
        manager.resumeAfterDaemonMeasurement(4) { fail("stale completion armed listeners") }
        assertNull(manager.pendingSelectionTargetMac)
    }
}
