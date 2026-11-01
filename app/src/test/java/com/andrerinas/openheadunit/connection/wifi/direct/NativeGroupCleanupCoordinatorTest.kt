package com.andrerinas.openheadunit.connection.wifi.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the production coordinator with a recording, manually completed P2P boundary. */
class NativeGroupCleanupCoordinatorTest {
    private class RecordingP2p(
        private val events: MutableList<String>,
        private val transportIsClosed: (() -> Boolean)? = null,
    ) : NativeGroupCleanupCoordinator.Removal {
        private val answers = ArrayDeque<(Int?) -> Unit>()
        var submissions = 0
            private set

        override fun remove(onComplete: (Int?) -> Unit) {
            submissions++
            events += "remove"
            transportIsClosed?.let { events += "transportClosed:${it()}" }
            answers.addLast(onComplete)
        }

        fun answer(failure: Int? = null) {
            events += if (failure == null) "removed" else "failed:$failure"
            answers.removeFirst()(failure)
        }
    }

    private fun enter(
        cleanup: NativeGroupCleanupCoordinator,
        owner: Any,
        events: MutableList<String>,
        operation: String,
    ) {
        if (cleanup.deferUntilReady(owner) { enter(cleanup, owner, events, operation) }) return
        events += operation
    }

    @Test
    fun `a fresh Stop removes the survivor after the service owner is gone`() {
        val cleanup = NativeGroupCleanupCoordinator()
        val events = mutableListOf<String>()
        val p2p = RecordingP2p(events)
        val oldServiceOwner = Any()
        enter(cleanup, oldServiceOwner, events, "adopt")
        cleanup.retain(oldServiceOwner, p2p)
        cleanup.cancelWaiting(oldServiceOwner)

        cleanup.requestStop { true }

        assertEquals(listOf("adopt", "remove"), events)
        p2p.answer()
        cleanup.requestStop { true }
        assertEquals(1, p2p.submissions)
    }

    @Test
    fun `a superseded Stop cannot submit removal of the newer Run group`() {
        val cleanup = NativeGroupCleanupCoordinator()
        val events = mutableListOf<String>()
        val p2p = RecordingP2p(events)
        val oldOwner = Any()
        enter(cleanup, oldOwner, events, "old adopt")
        cleanup.retain(oldOwner, p2p)
        enter(cleanup, Any(), events, "new adopt")

        cleanup.requestStop { false }
        assertEquals(0, p2p.submissions)
        assertFalse(cleanup.remove(oldOwner, p2p) { error("stale callback") })
        assertEquals(listOf("old adopt", "new adopt"), events)
    }

    @Test
    fun `Stop authority is rechecked immediately before submission`() {
        val cleanup = NativeGroupCleanupCoordinator()
        val events = mutableListOf<String>()
        val p2p = RecordingP2p(events)
        val owner = Any()
        enter(cleanup, owner, events, "adopt")
        cleanup.retain(owner, p2p)
        var checks = 0

        cleanup.requestStop { ++checks == 1 }

        assertEquals(2, checks)
        assertEquals(0, p2p.submissions)
    }

    @Test
    fun `Run adoption and creation wait for a submitted retained removal`() {
        val cleanup = NativeGroupCleanupCoordinator()
        val events = mutableListOf<String>()
        val p2p = RecordingP2p(events)
        val oldOwner = Any()
        enter(cleanup, oldOwner, events, "old adopt")
        cleanup.retain(oldOwner, p2p)
        cleanup.requestStop { true }
        val newOwner = Any()
        enter(cleanup, newOwner, events, "new adopt or create")

        assertEquals(listOf("old adopt", "remove"), events)
        assertFalse(cleanup.isOwnerReady(oldOwner))
        assertFalse(cleanup.isOwnerReady(newOwner))
        p2p.answer()
        assertEquals(listOf("old adopt", "remove", "removed", "new adopt or create"), events)
        assertTrue(cleanup.isOwnerReady(newOwner))
    }

    @Test
    fun `ordinary recreate removal remains a barrier across service replacement`() {
        val cleanup = NativeGroupCleanupCoordinator()
        val events = mutableListOf<String>()
        val p2p = RecordingP2p(events)
        val oldOwner = Any()
        enter(cleanup, oldOwner, events, "old create")
        var oldStopped = false
        assertTrue(cleanup.remove(oldOwner, p2p) { if (!oldStopped) events += "old recreate" })
        oldStopped = true
        cleanup.cancelWaiting(oldOwner)
        enter(cleanup, Any(), events, "new create")

        assertEquals(listOf("old create", "remove"), events)
        p2p.answer()
        assertEquals(listOf("old create", "remove", "removed", "new create"), events)
    }

    @Test
    fun `a failed removal releases the barrier without claiming OS removal`() {
        val cleanup = NativeGroupCleanupCoordinator()
        val events = mutableListOf<String>()
        val p2p = RecordingP2p(events)
        val owner = Any()
        enter(cleanup, owner, events, "adopt")
        cleanup.retain(owner, p2p)
        cleanup.requestStop { true }

        p2p.answer(failure = 2)
        assertTrue(cleanup.isOwnerReady(owner))
        assertEquals(1, p2p.submissions) // No retry, timer or assumed successful removal.
        cleanup.requestStop { true }
        assertEquals(2, p2p.submissions) // A distinct fresh Stop can still retire the survivor.
    }

    @Test
    fun `new Run inherits cleanup responsibility when its bring-up cannot adopt or create`() {
        val cleanup = NativeGroupCleanupCoordinator()
        val events = mutableListOf<String>()
        val p2p = RecordingP2p(events)
        val oldOwner = Any()
        enter(cleanup, oldOwner, events, "old adopt")
        cleanup.retain(oldOwner, p2p)
        enter(cleanup, Any(), events, "new bring-up refused by radio")

        cleanup.requestStop { true }
        assertEquals(1, p2p.submissions)
        p2p.answer(failure = 1) // Unsupported OS does not become a fictitious success.
        assertEquals(listOf("old adopt", "new bring-up refused by radio", "remove", "failed:1"), events)
    }

    @Test
    fun `Stop joining pending removal does not submit duplicate cleanup`() {
        val cleanup = NativeGroupCleanupCoordinator()
        val events = mutableListOf<String>()
        val p2p = RecordingP2p(events)
        val owner = Any()
        enter(cleanup, owner, events, "adopt")
        cleanup.retain(owner, p2p)
        cleanup.requestStop { true }
        cleanup.requestStop { true }

        assertEquals(1, p2p.submissions)
        p2p.answer(failure = 2)
        assertEquals(1, p2p.submissions)
    }

    @Test
    fun `stopping a waiting service releases its continuation instead of resurrecting it`() {
        val cleanup = NativeGroupCleanupCoordinator()
        val events = mutableListOf<String>()
        val p2p = RecordingP2p(events)
        val oldOwner = Any()
        enter(cleanup, oldOwner, events, "old adopt")
        cleanup.retain(oldOwner, p2p)
        cleanup.requestStop { true }
        val waitingOwner = Any()
        enter(cleanup, waitingOwner, events, "must not run")
        cleanup.cancelWaiting(waitingOwner)

        p2p.answer()
        assertEquals(listOf("old adopt", "remove", "removed"), events)
    }

    @Test
    fun `all outstanding ordinary removals must answer before newer create`() {
        val cleanup = NativeGroupCleanupCoordinator()
        val events = mutableListOf<String>()
        val p2p = RecordingP2p(events)
        val owner = Any()
        enter(cleanup, owner, events, "adopt")
        cleanup.remove(owner, p2p) {}
        cleanup.remove(owner, p2p) {}
        enter(cleanup, Any(), events, "new create")

        p2p.answer()
        assertFalse(events.contains("new create"))
        p2p.answer(failure = 2)
        assertEquals("new create", events.last())
    }

    @Test
    fun `a refresh cannot replace the bring-up already queued behind removal`() {
        val cleanup = NativeGroupCleanupCoordinator()
        val events = mutableListOf<String>()
        val p2p = RecordingP2p(events)
        val owner = Any()
        enter(cleanup, owner, events, "old adopt")
        cleanup.remove(owner, p2p) {}
        val newOwner = Any()
        enter(cleanup, newOwner, events, "new bring-up")
        enter(cleanup, newOwner, events, "refresh")

        p2p.answer()
        assertEquals(listOf("old adopt", "remove", "removed", "new bring-up"), events)
    }

    @Test
    fun `actual survivor adoption hands Stop cleanup back to transport retirement`() {
        val cleanup = NativeGroupCleanupCoordinator()
        val events = mutableListOf<String>()
        var transportClosed = false
        val p2p = RecordingP2p(events) { transportClosed }
        val oldOwner = Any()
        enter(cleanup, oldOwner, events, "old adopt")
        assertTrue(cleanup.activate(oldOwner))
        cleanup.retain(oldOwner, p2p) // Old service keeps its real group at physical destruction.
        val activeOwner = Any()
        enter(cleanup, activeOwner, events, "new bring-up")
        assertTrue(cleanup.activate(activeOwner)) // Actual accepted survivor, not just bring-up.
        events += "transport active"

        // Mirrors stopIntent()/notification ingress: acceptance happens before service relay.
        cleanup.requestStop { true }
        events += "Stop accepted"
        assertEquals(0, p2p.submissions)
        assertEquals(
            listOf("old adopt", "new bring-up", "transport active", "Stop accepted"),
            events,
        )

        events += "service relay"
        transportClosed = true
        events += "transport retired"
        // The normal service teardown may detach/remove only after ByeBye and socket closure.
        cleanup.retain(activeOwner, p2p)
        assertTrue(cleanup.remove(activeOwner, p2p) {})
        assertEquals("transportClosed:true", events.last())
        assertFalse(events.contains("transportClosed:false"))
        p2p.answer()
        assertEquals(1, p2p.submissions)
    }

    @Test
    fun `activation cannot withdraw a removal already submitted for a detached survivor`() {
        val cleanup = NativeGroupCleanupCoordinator()
        val events = mutableListOf<String>()
        val p2p = RecordingP2p(events)
        val owner = Any()
        enter(cleanup, owner, events, "adopt")
        cleanup.retain(owner, p2p)
        cleanup.requestStop { true }

        assertFalse(cleanup.activate(owner))
        assertFalse(cleanup.activate(Any()))
        p2p.answer(failure = 2)
        cleanup.requestStop { true }
        assertEquals(2, p2p.submissions)
    }

    @Test
    fun `late abandoned create retains failed cleanup for the next distinct Stop`() {
        val cleanup = NativeGroupCleanupCoordinator()
        val events = mutableListOf<String>()
        val p2p = RecordingP2p(events)
        val owner = Any()
        enter(cleanup, owner, events, "create pending")
        cleanup.cancelWaiting(owner)
        events += "manager stopped without known group"
        cleanup.requestStop { true }
        assertEquals(0, p2p.submissions)

        // The actual late success now proves a group exists. Production retains this exact
        // framework boundary before its abandoned-create removal, rather than at create request.
        events += "late create success"
        assertTrue(cleanup.isOwner(owner))
        cleanup.retain(owner, p2p)
        assertTrue(cleanup.remove(owner, p2p) {})
        p2p.answer(failure = 2)
        assertEquals(1, p2p.submissions) // Failure itself must not retry.

        cleanup.requestStop { true }
        assertEquals(2, p2p.submissions)
        p2p.answer()
        cleanup.requestStop { true }
        assertEquals(2, p2p.submissions) // Successful cleanup really released responsibility.
    }

    @Test
    fun `late create from an old owner cannot retain or remove a newer active group`() {
        val cleanup = NativeGroupCleanupCoordinator()
        val events = mutableListOf<String>()
        val oldP2p = RecordingP2p(events)
        val newP2p = RecordingP2p(events)
        val oldOwner = Any()
        enter(cleanup, oldOwner, events, "old create pending")
        cleanup.cancelWaiting(oldOwner)
        val activeOwner = Any()
        enter(cleanup, activeOwner, events, "new adopt")
        assertTrue(cleanup.activate(activeOwner))

        assertFalse(cleanup.isOwner(oldOwner)) // Production abandoned-create takeover guard.
        cleanup.retain(oldOwner, oldP2p) // Even a stale boundary cannot steal responsibility.
        assertFalse(cleanup.remove(oldOwner, oldP2p) { error("stale removal callback") })
        assertFalse(cleanup.activate(oldOwner))
        cleanup.requestStop { true }
        assertEquals(0, oldP2p.submissions)
        assertEquals(0, newP2p.submissions)

        cleanup.retain(activeOwner, newP2p) // Actual physical retirement of the newer manager.
        cleanup.requestStop { true }
        assertEquals(0, oldP2p.submissions)
        assertEquals(1, newP2p.submissions)
    }
}
