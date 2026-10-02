package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ModuleRearmPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

class ModuleRearmPolicyTest {

    @Test
    fun `a stopped launcher is rebuilt whatever its handshake says`() {
        assertEquals(Action.REBUILD_LAUNCHER, ModuleRearmPolicy.action(false, false, true))
        assertEquals(Action.REBUILD_LAUNCHER, ModuleRearmPolicy.action(false, false, false))
    }

    @Test
    fun `a running launcher with a stopped handshake starts only the handshake`() {
        assertEquals(Action.START_HANDSHAKE, ModuleRearmPolicy.action(true, false, false))
    }

    @Test
    fun `a fully armed stack is woken rather than rebuilt under a bring-up in flight`() {
        assertEquals(Action.WAKE_PHONE, ModuleRearmPolicy.action(true, false, true))
    }

    @Test
    fun `a press while the daemon is still being asked wakes rather than starting a second ask`() {
        assertEquals(Action.WAKE_PHONE, ModuleRearmPolicy.action(true, false, false, measuringDaemon = true))
        assertEquals(Action.REBUILD_LAUNCHER, ModuleRearmPolicy.action(false, false, false, measuringDaemon = true))
    }

    @Test
    fun `a launcher that skipped its network for a refusal is rebuilt whatever its handshake says`() {
        assertEquals(Action.REBUILD_LAUNCHER, ModuleRearmPolicy.action(true, true, false))
        assertEquals(Action.REBUILD_LAUNCHER, ModuleRearmPolicy.action(true, true, true))
    }
}
