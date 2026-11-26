package com.andrerinas.openheadunit.connection.self

import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

class SelfLauncherActiveFlagTest {
    private fun manager() = SelfLauncherManager(mock(AapService::class.java), mock(WifiLauncherManager::class.java))

    @Test fun changesFireOnce() {
        val m = manager()
        var calls = 0
        m.onActiveChanged = { calls++ }
        m.isActive = true
        assertEquals(1, calls)
        m.isActive = false
        assertEquals(2, calls)
    }

    @Test fun theSameValueDoesNotFire() {
        val m = manager()
        var calls = 0
        m.onActiveChanged = { calls++ }
        m.isActive = false
        m.isActive = true
        m.isActive = true
        assertEquals(1, calls)
    }

    @Test fun theCallbackReadsTheNewValue() {
        val m = manager()
        val seen = mutableListOf<Boolean>()
        m.onActiveChanged = { seen += m.isActive }
        m.isActive = true
        m.isActive = false
        assertEquals(listOf(true, false), seen)
    }

    @Test fun noCallbackDoesNotThrow() {
        val m = manager()
        m.isActive = true
        assertTrue(m.isActive)
        m.isActive = false
        assertFalse(m.isActive)
    }
}
