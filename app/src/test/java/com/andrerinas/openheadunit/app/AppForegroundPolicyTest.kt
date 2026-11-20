package com.andrerinas.openheadunit.app

import com.andrerinas.openheadunit.connection.AppExitPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppForegroundPolicyTest {
    private val open = AppForegroundPolicy.Kind.OPEN
    private val exit = AppForegroundPolicy.Kind.EXIT
    private val internal = AppForegroundPolicy.Kind.INTERNAL_CONTINUATION

    private fun stop(app: AppExitPolicy) = app.request(
        killProcess = false, userStop = true, requiresKillOnDisconnect = false,
    )

    @Test
    fun `same Main returns without delivery after service stopped and revokes old finish`() {
        val ui = AppForegroundPolicy()
        val app = AppExitPolicy()
        val main = AppForegroundPolicy.LogicalActivity()
        assertEquals(open, ui.created(main, open))
        app.supersede()
        ui.foregroundStarted(app.revision, entryActivity = true)
        assertNull(ui.resumeRevision())
        ui.fullyStopped(changingConfigurations = false)
        val old = stop(app)
        assertNotNull(app.commit(old, killOnDisconnect = false))
        assertEquals(AppExitPolicy.Effect.STOPPED, app.destroyed(old, false).effect)

        // No onCreate/onNewIntent occurs, and the old Activity still stores its original Intent.
        ui.foregroundStarted(app.revision, entryActivity = true)
        val owner = checkNotNull(app.acceptResume(checkNotNull(ui.resumeRevision())))
        assertTrue(app.isRunOwner(owner)) // This is the token passed to restoreAppForEntry.
        assertFalse(app.owns(old.id, killOnDisconnect = false))
        assertNull(ui.resumeRevision())
    }

    @Test
    fun `cold Exit delivery before start cannot cancel itself`() {
        val ui = AppForegroundPolicy()
        val app = AppExitPolicy()
        assertEquals(exit, ui.created(AppForegroundPolicy.LogicalActivity(), exit))
        val stopped = stop(app)
        ui.foregroundStarted(app.revision, entryActivity = true)
        assertNull(ui.resumeRevision())
        assertTrue(app.owns(stopped.id, false))
    }

    @Test
    fun `warm Exit delivered after candidate start consumes before resume`() {
        val ui = AppForegroundPolicy()
        val app = AppExitPolicy()
        ui.fullyStopped(changingConfigurations = false)
        ui.foregroundStarted(app.revision, entryActivity = true)
        ui.delivered(exit)
        val stopped = stop(app)
        assertNull(ui.resumeRevision())
        assertTrue(app.owns(stopped.id, false))
    }

    @Test
    fun `previous Exit delivery is not a sticky veto on a later real task return`() {
        val ui = AppForegroundPolicy()
        val app = AppExitPolicy()
        ui.created(AppForegroundPolicy.LogicalActivity(), exit)
        val stopped = stop(app)
        ui.foregroundStarted(app.revision, entryActivity = true)
        assertNull(ui.resumeRevision())
        ui.fullyStopped(changingConfigurations = false)
        ui.foregroundStarted(app.revision, entryActivity = true)
        assertNotNull(app.acceptResume(checkNotNull(ui.resumeRevision())))
        assertFalse(app.owns(stopped.id, false))
    }

    @Test
    fun `actual new Open differs from stored Exit and owns its delivery immediately`() {
        val ui = AppForegroundPolicy()
        val app = AppExitPolicy()
        ui.created(AppForegroundPolicy.LogicalActivity(), exit)
        stop(app)
        ui.foregroundStarted(app.revision, entryActivity = true)
        val actual = ui.delivered(open)
        val owner = app.supersede()
        assertEquals(open, actual)
        assertTrue(app.isRunOwner(owner))
        assertNull(ui.resumeRevision())
    }

    @Test
    fun `Projection and Settings spend the episode once before Main uncovers`() {
        // Each is an eligible target even when Main has not resumed in this episode.
        for (first in listOf("Projection", "Settings")) {
            val ui = AppForegroundPolicy()
            val app = AppExitPolicy()
            ui.fullyStopped(changingConfigurations = false)
            ui.foregroundStarted(app.revision, entryActivity = true)
            assertNotNull(first, app.acceptResume(checkNotNull(ui.resumeRevision())))
            val newerStop = stop(app)
            assertNull("Main must not spend $first's episode again", ui.resumeRevision())
            assertTrue(app.owns(newerStop.id, false))
        }
    }

    @Test
    fun `Main Open forwarded before its resume shares handling with Projection`() {
        val ui = AppForegroundPolicy()
        val app = AppExitPolicy()
        ui.fullyStopped(changingConfigurations = false)
        val main = checkNotNull(ui.created(AppForegroundPolicy.LogicalActivity(), open))
        app.supersede()
        val projection = checkNotNull(ui.created(AppForegroundPolicy.LogicalActivity(), internal))
        assertEquals(open, main)
        assertEquals(internal, projection)
        ui.foregroundStarted(app.revision, entryActivity = true)
        val newerStop = stop(app)
        assertNull(ui.resumeRevision()) // Projection's first resume.
        assertNull(ui.resumeRevision()) // Main's eventual first resume.
        assertTrue(app.owns(newerStop.id, false))
    }

    @Test
    fun `automatic no-session Leave Main delivery preserves Stop instead of reopening`() {
        val ui = AppForegroundPolicy()
        val app = AppExitPolicy()
        ui.fullyStopped(changingConfigurations = false)
        ui.foregroundStarted(app.revision, entryActivity = true)
        val newerStop = stop(app)
        val home = checkNotNull(ui.created(AppForegroundPolicy.LogicalActivity(), internal))
        assertEquals(internal, home)
        assertNull(ui.resumeRevision())
        assertTrue(app.owns(newerStop.id, false))
    }

    @Test
    fun `internal uncover without fully stopped transition cannot create Open`() {
        val ui = AppForegroundPolicy()
        val app = AppExitPolicy()
        ui.created(AppForegroundPolicy.LogicalActivity(), open)
        app.supersede()
        ui.foregroundStarted(app.revision, entryActivity = true)
        val newerStop = stop(app)
        // Settings/Projection stop overlaps Main start: the aggregate started count never hits 0.
        assertNull(ui.resumeRevision())
        assertTrue(app.owns(newerStop.id, false))
    }

    @Test
    fun `late episode candidate cannot supersede newer Stop even in another Activity`() {
        val ui = AppForegroundPolicy()
        val app = AppExitPolicy()
        ui.fullyStopped(changingConfigurations = false)
        ui.foregroundStarted(app.revision, entryActivity = true)
        val newerStop = stop(app)
        assertNull(app.acceptResume(checkNotNull(ui.resumeRevision())))
        assertNull(ui.resumeRevision())
        assertTrue(app.owns(newerStop.id, false))
    }

    @Test
    fun `same physical exit commit and destruction do not invalidate observed return`() {
        val ui = AppForegroundPolicy()
        val app = AppExitPolicy()
        val old = stop(app)
        ui.fullyStopped(changingConfigurations = false)
        ui.foregroundStarted(app.revision, entryActivity = true)
        val observedRevision = app.revision
        assertNotNull(app.commit(old, false))
        assertEquals(AppExitPolicy.Effect.STOPPED, app.destroyed(old, false).effect)
        assertEquals(observedRevision, app.revision)
        val owner = checkNotNull(app.acceptResume(checkNotNull(ui.resumeRevision())))
        assertTrue(app.isRunOwner(owner))
        assertFalse(app.owns(old.id, false))
    }

    @Test
    fun `retained logical config replacement does not replay stored Open or Exit`() {
        for (stored in listOf(open, exit)) {
            val ui = AppForegroundPolicy()
            val app = AppExitPolicy()
            val logical = AppForegroundPolicy.LogicalActivity()
            assertNotNull(ui.created(logical, stored))
            ui.foregroundStarted(app.revision, entryActivity = true)
            val newerStop = stop(app)
            ui.fullyStopped(changingConfigurations = true)
            assertNull(ui.created(logical, stored))
            ui.foregroundStarted(app.revision, entryActivity = true)
            assertNull(ui.resumeRevision())
            assertTrue(app.owns(newerStop.id, false))
        }
    }

    @Test
    fun `new logical identity is a delivery even when caller restored saved state`() {
        val ui = AppForegroundPolicy()
        val old = AppForegroundPolicy.LogicalActivity()
        ui.created(old, open)
        ui.fullyStopped(changingConfigurations = true)
        // A Bundle alone is intentionally not an input to this identity decision.
        assertNotNull(ui.created(AppForegroundPolicy.LogicalActivity(), open))
    }

    @Test
    fun `trampoline episode is ineligible and leaves no delivery veto on later UI`() {
        val ui = AppForegroundPolicy()
        ui.fullyStopped(changingConfigurations = false)
        ui.foregroundStarted(revision = 1L, entryActivity = false)
        assertNull(ui.resumeRevision())
        ui.fullyStopped(changingConfigurations = false)
        ui.foregroundStarted(revision = 2L, entryActivity = true)
        assertEquals(2L, ui.resumeRevision())
        assertNull(ui.resumeRevision())
    }
}
