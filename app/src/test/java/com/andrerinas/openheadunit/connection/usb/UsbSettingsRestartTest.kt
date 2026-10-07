package com.andrerinas.openheadunit.connection.usb

import org.junit.Test
import org.mockito.Mockito.*

class UsbSettingsRestartTest {
    @Test fun `Save waiting for retirement cannot lift a later USB cancellation`() {
        val manager = mock(UsbLauncherManager::class.java, CALLS_REAL_METHODS)
        UsbLauncherManager::class.java.getDeclaredField("cancelledByUser").apply { isAccessible = true }.set(manager, true)
        manager.restartForSettings()
        verify(manager, never()).checkAlreadyConnected(anyBoolean(), anyBoolean())
    }

    @Test fun `uncancelled Save still grants one explicit device check`() {
        val manager = mock(UsbLauncherManager::class.java, CALLS_REAL_METHODS)
        doNothing().`when`(manager).checkAlreadyConnected(true, true)
        manager.restartForSettings()
        verify(manager).checkAlreadyConnected(force = true, userRequested = true)
    }
}
