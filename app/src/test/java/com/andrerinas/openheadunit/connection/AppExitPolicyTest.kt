package com.andrerinas.openheadunit.connection

import org.junit.Assert.*
import org.junit.Test

class AppExitPolicyTest {
    private fun AppExitPolicy.stop(kill: Boolean = false) =
        request(kill, userStop = true, requiresKillOnDisconnect = false)

    @Test fun `automatic terminal cannot weaken uncommitted or committed explicit Stop`() {
        for (committed in listOf(false, true)) {
            val policy = AppExitPolicy()
            val a = policy.stop(true)
            if (committed) assertSame(a, policy.commit(a, true))
            val revision = policy.revision
            val epoch = policy.stopEpoch
            repeat(3) { assertNull(policy.requestAutomatic(userStop = false)) }
            assertEquals(revision, policy.revision)
            assertEquals(epoch, policy.stopEpoch)
            assertSame(a, policy.requestFor(a.id))
            assertTrue(policy.owns(a.id, false))
            if (!committed) assertSame(a, policy.commit(a, false))
            assertTrue(policy.userStopAtDestroy(a))
            assertEquals(AppExitPolicy.Effect.STOPPED, policy.destroyed(a, false).effect)
        }
    }

    @Test fun `fresh explicit Stop supersedes earlier automatic terminal`() {
        val policy = AppExitPolicy()
        val automatic = checkNotNull(policy.requestAutomatic(userStop = false))
        val a = policy.stop()
        assertNull(policy.commit(automatic, false))
        assertSame(a, policy.requestFor(a.id))
        assertSame(a, policy.commit(a, false))
        assertTrue(policy.userStopAtDestroy(a))
        assertEquals(a.id, policy.destroyed(a, false).owner)
    }

    @Test fun `reopen invalidates old relay without changing newer pending Stop`() {
        val policy = AppExitPolicy()
        val a = policy.stop(true)
        val r = policy.supersede()
        assertTrue(policy.isRunOwner(r))
        assertNull(policy.requestFor(a.id))
        val b = policy.stop()
        val epoch = policy.stopEpoch
        val revision = policy.revision
        repeat(3) { assertNull(policy.commit(a, true)); assertNull(policy.requestFor(a.id)) }
        assertEquals(epoch, policy.stopEpoch)
        assertEquals(revision, policy.revision)
        assertSame(b, policy.commit(b, true))
    }

    @Test fun `A R freshB joins physicalA and completes with B flags`() {
        val policy = AppExitPolicy()
        val a = policy.stop(true)
        assertSame(a, policy.commit(a, true))
        policy.supersede()
        val b = policy.stop(false)
        assertSame(a, policy.commit(b, true))
        assertTrue(policy.userStopAtDestroy(a))
        assertFalse(policy.userStopAtDestroy(b))
        assertFalse(policy.owns(a.id, true))
        assertTrue(policy.owns(b.id, true))
        val revision = policy.revision
        val completed = policy.destroyed(a, true)
        assertEquals(AppExitPolicy.Effect.STOPPED, completed.effect)
        assertEquals(b.id, completed.owner)
        assertSame(b, completed.request)
        assertEquals(revision, policy.revision)
        assertFalse(policy.isStopping)
        assertNull(policy.commit(b, true))
        assertEquals(AppExitPolicy.Effect.NONE, policy.destroyed(a, true).effect)
    }

    @Test fun `B accepted before relay still owns completed A and is never recommitted`() {
        val policy = AppExitPolicy()
        val a = policy.stop(true)
        policy.commit(a, true)
        policy.supersede()
        val b = policy.stop()
        val completed = policy.destroyed(a, true)
        assertEquals(b.id, completed.owner)
        assertEquals(AppExitPolicy.Effect.STOPPED, completed.effect)
        assertNull(policy.commit(b, true))
        assertTrue(policy.owns(b.id, true))
    }

    @Test fun `A R oldrelay completes for R once and B C completes for C`() {
        for (freshB in listOf(false, true)) {
            val policy = AppExitPolicy()
            val a = policy.stop(true)
            policy.commit(a, true)
            policy.supersede()
            val b = if (freshB) policy.stop() else a
            if (freshB) assertSame(a, policy.commit(b, true))
            val c = policy.supersede()
            assertNull(policy.commit(a, true))
            assertNull(policy.commit(b, true))
            assertFalse(policy.userStopAtDestroy(a))
            val completion = policy.destroyed(a, true)
            assertEquals(AppExitPolicy.Effect.RESTART_SERVICE, completion.effect)
            assertEquals(c, completion.owner)
            assertTrue(policy.isRunOwner(c))
            assertEquals(AppExitPolicy.Effect.NONE, policy.destroyed(a, true).effect)
        }
    }

    @Test fun `physical progress does not invalidate observed resume revision`() {
        val policy = AppExitPolicy()
        val a = policy.stop()
        val observed = policy.revision
        policy.commit(a, true)
        policy.destroyed(a, true)
        val resumed = checkNotNull(policy.acceptResume(observed))
        assertTrue(policy.isRunOwner(resumed))
        assertFalse(policy.owns(a.id, true))
        assertNull(policy.acceptResume(observed))
        val candidate = policy.revision
        val b = policy.stop()
        assertNull(policy.acceptResume(candidate))
        assertTrue(policy.owns(b.id, true))
    }

    @Test fun `Stop cancellation epoch survives Open but compatible Open permits waiting U`() {
        for (stop in listOf(false, true)) {
            val policy = AppExitPolicy()
            val ticket = policy.stopEpoch
            policy.supersede() // accepted U
            if (stop) policy.stop()
            policy.supersede() // compatible UI Open cannot revive cancelled U
            assertEquals(!stop, policy.acceptsConnection(ticket))
        }
    }

    @Test fun `conditional automatic exit respects opt out and explicit Stop remains explicit`() {
        val policy = AppExitPolicy()
        val automatic = policy.request(true, userStop = false)
        assertFalse(policy.owns(automatic.id, false))
        assertNull(policy.commit(automatic, false))
        val explicit = policy.stop(true)
        assertSame(explicit, policy.commit(explicit, false))
        assertTrue(policy.owns(explicit.id, false))
        assertTrue(policy.userStopAtDestroy(explicit))
        assertEquals(AppExitPolicy.Effect.STOPPED, policy.destroyed(explicit, false).effect)
        assertTrue(policy.owns(explicit.id, false))
        assertNull(policy.commit(explicit, true))
    }

    @Test fun `latest B kill permission is independent of A and cannot survive C`() {
        val policy = AppExitPolicy()
        val a = policy.stop(false)
        policy.commit(a, true)
        policy.supersede()
        val b = policy.stop(true)
        policy.commit(b, true)
        val completion = policy.destroyed(a, true)
        assertEquals(AppExitPolicy.Effect.KILL_PROCESS, completion.effect)
        assertEquals(b.id, completion.owner)
        policy.supersede()
        assertFalse(policy.owns(completion.owner, true))
    }

    @Test fun `genuine exit commands are classified separately from normal launch`() {
        assertTrue(AppExitPolicy.isExitCommand("com.andrerinas.openheadunit.ACTION_EXIT", null, null))
        assertTrue(AppExitPolicy.isExitCommand("android.intent.action.VIEW", "headunit", "exit"))
        assertFalse(AppExitPolicy.isExitCommand("android.intent.action.MAIN", null, null))
        assertFalse(AppExitPolicy.isExitCommand("android.intent.action.VIEW", "headunit", "connect"))
    }
}
