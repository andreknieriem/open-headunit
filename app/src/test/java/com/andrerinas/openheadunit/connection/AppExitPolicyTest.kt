package com.andrerinas.openheadunit.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppExitPolicyTest {
    @Test
    fun `reopening idle home revokes both finish delivery and delayed exit`() {
        val policy = AppExitPolicy()
        val exit = checkNotNull(policy.request(killProcess = true, userStop = false))
        policy.supersede()
        repeat(3) {
            assertFalse(policy.owns(exit.id, killOnDisconnect = true))
            assertFalse(policy.commit(exit, killOnDisconnect = true))
        }
        assertTrue(policy.acceptConnection())
    }

    @Test
    fun `accepted Connecting attempt supersedes old exit before socket open`() {
        val policy = AppExitPolicy()
        val exit = checkNotNull(policy.request(killProcess = true, userStop = false))
        assertTrue(policy.acceptConnection())
        assertFalse(policy.commit(exit, killOnDisconnect = true))
        assertFalse(policy.owns(exit.id, killOnDisconnect = true))
    }

    @Test
    fun `eligible exit commits once and holds admission through process exit`() {
        val policy = AppExitPolicy()
        val exit = checkNotNull(policy.request(killProcess = true, userStop = true))
        assertTrue(policy.commit(exit, killOnDisconnect = true))
        assertFalse(policy.commit(exit, killOnDisconnect = true))
        assertFalse(policy.acceptConnection())
        assertEquals(AppExitPolicy.Completion.KILL_PROCESS, policy.destroyed(exit, killOnDisconnect = true))
        assertFalse(policy.acceptConnection())
        assertEquals(AppExitPolicy.Completion.NONE, policy.destroyed(exit, killOnDisconnect = true))
    }

    @Test
    fun `reopen after stop was queued cannot publish until cleanup and restarts only once`() {
        val policy = AppExitPolicy()
        val exit = checkNotNull(policy.request(killProcess = true, userStop = true))
        assertTrue(policy.commit(exit, killOnDisconnect = true))
        policy.supersede()
        policy.supersede()
        assertFalse(policy.owns(exit.id, killOnDisconnect = true))
        assertFalse(policy.acceptConnection())
        assertEquals(AppExitPolicy.Completion.RESTART_SERVICE, policy.destroyed(exit, killOnDisconnect = true))
        assertTrue(policy.acceptConnection())
        assertEquals(AppExitPolicy.Completion.NONE, policy.destroyed(exit, killOnDisconnect = true))
        assertFalse(policy.commit(exit, killOnDisconnect = true))
    }

    @Test
    fun `settings opt out prevents pending close and process exit already queued`() {
        val policy = AppExitPolicy()
        val pending = checkNotNull(policy.request(killProcess = true, userStop = false))
        assertFalse(policy.owns(pending.id, killOnDisconnect = false))
        assertFalse(policy.commit(pending, killOnDisconnect = false))
        assertTrue(policy.acceptConnection())
        val queued = checkNotNull(policy.request(killProcess = true, userStop = false))
        assertTrue(policy.commit(queued, killOnDisconnect = true))
        assertEquals(AppExitPolicy.Completion.STOPPED, policy.destroyed(queued, killOnDisconnect = false))
    }

    @Test
    fun `explicit stop still closes with setting disabled and retains user stop reason`() {
        val policy = AppExitPolicy()
        val exit = checkNotNull(policy.request(killProcess = false, userStop = true, requiresKillOnDisconnect = false))
        assertTrue(exit.userStop)
        assertTrue(policy.owns(exit.id, killOnDisconnect = false))
        assertTrue(policy.commit(exit, killOnDisconnect = false))
        assertTrue(policy.userStopAtDestroy(exit))
        assertEquals(AppExitPolicy.Completion.STOPPED, policy.destroyed(exit, killOnDisconnect = false))
        assertFalse(policy.isStopping)
    }

    @Test
    fun `reentry revokes the old user stop without passing it to replacement destruction`() {
        val policy = AppExitPolicy()
        val old = checkNotNull(policy.request(killProcess = true, userStop = true))
        assertTrue(policy.commit(old, killOnDisconnect = true))
        assertTrue(policy.userStopAtDestroy(old))
        policy.supersede()
        assertFalse(policy.userStopAtDestroy(old))
        assertEquals(AppExitPolicy.Completion.RESTART_SERVICE, policy.destroyed(old, killOnDisconnect = true))
        val replacement = checkNotNull(policy.request(killProcess = false, userStop = false, requiresKillOnDisconnect = false))
        assertTrue(policy.commit(replacement, killOnDisconnect = false))
        assertEquals(AppExitPolicy.Completion.NONE, policy.destroyed(old, killOnDisconnect = true))
        assertTrue(policy.isStopping)
        assertFalse(policy.userStopAtDestroy(replacement))
        assertEquals(AppExitPolicy.Completion.STOPPED, policy.destroyed(replacement, killOnDisconnect = false))
    }

    @Test
    fun `queued stop after reopen cannot resurrect committed exit authority`() {
        val policy = AppExitPolicy()
        val old = checkNotNull(policy.request(killProcess = true, userStop = false))
        assertTrue(policy.commit(old, killOnDisconnect = true))
        policy.supersede()
        repeat(3) {
            assertNull(policy.request(killProcess = false, userStop = true, requiresKillOnDisconnect = false))
            assertFalse(policy.commit(old, killOnDisconnect = true))
            assertFalse(policy.owns(old.id, killOnDisconnect = true))
            assertFalse(policy.userStopAtDestroy(old))
        }
        assertEquals(AppExitPolicy.Completion.RESTART_SERVICE, policy.destroyed(old, killOnDisconnect = true))
    }

    @Test
    fun `completed stop retains queued finish permission but never recommits`() {
        val policy = AppExitPolicy()
        val exit = checkNotNull(policy.request(killProcess = false, userStop = true, requiresKillOnDisconnect = false))
        assertTrue(policy.commit(exit, killOnDisconnect = false))
        assertEquals(AppExitPolicy.Completion.STOPPED, policy.destroyed(exit, killOnDisconnect = false))
        assertTrue(policy.owns(exit.id, killOnDisconnect = false))
        assertFalse(policy.commit(exit, killOnDisconnect = false))
        policy.supersede()
        assertFalse(policy.owns(exit.id, killOnDisconnect = false))
    }

    @Test
    fun `exit only intents cannot turn a committed stop into a restart`() {
        val policy = AppExitPolicy()
        val exit = checkNotNull(policy.request(killProcess = false, userStop = true, requiresKillOnDisconnect = false))
        assertTrue(policy.commit(exit, killOnDisconnect = false))
        for ((action, scheme, host) in listOf(
            Triple("com.andrerinas.openheadunit.ACTION_EXIT", null, null),
            Triple("android.intent.action.VIEW", "headunit", "exit"),
        )) {
            assertTrue(AppExitPolicy.isExitCommand(action, scheme, host))
            if (!AppExitPolicy.isExitCommand(action, scheme, host)) policy.supersede()
            assertNull(policy.request(killProcess = false, userStop = true, requiresKillOnDisconnect = false))
        }
        assertEquals(AppExitPolicy.Completion.STOPPED, policy.destroyed(exit, killOnDisconnect = false))
        assertFalse(AppExitPolicy.isExitCommand("android.intent.action.MAIN", null, null))
        assertFalse(AppExitPolicy.isExitCommand("android.intent.action.VIEW", "headunit", "connect"))
    }
}
