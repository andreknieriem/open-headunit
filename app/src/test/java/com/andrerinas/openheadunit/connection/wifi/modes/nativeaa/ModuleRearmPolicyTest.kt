package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ModuleRearmPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleRearmPolicyTest {

    @Test
    fun `a stopped launcher is rebuilt whatever its handshake says`() {
        assertEquals(Action.REBUILD_LAUNCHER, ModuleRearmPolicy.action(false, true))
        assertEquals(Action.REBUILD_LAUNCHER, ModuleRearmPolicy.action(false, false))
    }

    @Test
    fun `a running launcher with a stopped handshake starts only the handshake`() {
        assertEquals(Action.START_HANDSHAKE, ModuleRearmPolicy.action(true, false))
    }

    @Test
    fun `a fully armed stack is woken rather than rebuilt under a bring-up in flight`() {
        assertEquals(Action.WAKE_PHONE, ModuleRearmPolicy.action(true, true))
    }
}
