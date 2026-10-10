package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionRaiseDeadlinePolicyTest {

    @Test
    fun `a raise that was attempted arms the deadline`() {
        assertTrue(ProjectionRaiseDeadlinePolicy.arms(raiseAttempted = true))
    }

    /** PiP, a no_ui command and the settings screen each raise the projection themselves later. */
    @Test
    fun `a raise skipped on purpose arms nothing`() {
        assertFalse(ProjectionRaiseDeadlinePolicy.arms(raiseAttempted = false))
    }

    @Test
    fun `the first expiry tries the raise again`() {
        assertEquals(
            ProjectionRaiseDeadlinePolicy.Action.RETRY_RAISE,
            ProjectionRaiseDeadlinePolicy.actionFor(raisesMade = 1, unprojectedEndsInARow = 0)
        )
    }

    @Test
    fun `the first unraised session recovers`() {
        assertEquals(
            ProjectionRaiseDeadlinePolicy.Action.END_AND_RECOVER,
            ProjectionRaiseDeadlinePolicy.actionFor(raisesMade = 2, unprojectedEndsInARow = 0)
        )
    }

    @Test
    fun `the second unraised session in a row is held`() {
        assertEquals(
            ProjectionRaiseDeadlinePolicy.Action.END_AND_HOLD,
            ProjectionRaiseDeadlinePolicy.actionFor(raisesMade = 2, unprojectedEndsInARow = 1)
        )
    }

    @Test
    fun `a raise that never succeeds ends at most the bound plus one sessions`() {
        var sessionsStarted = 0
        var endsInARow = 0
        var next = true
        repeat(20) {
            if (!next) return@repeat
            sessionsStarted++
            var raises = 1
            while (ProjectionRaiseDeadlinePolicy.actionFor(raises, endsInARow) ==
                ProjectionRaiseDeadlinePolicy.Action.RETRY_RAISE) raises++
            val action = ProjectionRaiseDeadlinePolicy.actionFor(raises, endsInARow)
            endsInARow++
            next = action == ProjectionRaiseDeadlinePolicy.Action.END_AND_RECOVER
        }
        assertTrue(sessionsStarted <= ProjectionRaiseDeadlinePolicy.MAX_RECOVERED_ENDS + 1)
    }

    @Test
    fun `a projected session resets the count`() {
        assertEquals(0, ProjectionRaiseDeadlinePolicy.endsInARowAfter(1, projected = true, userRequested = false))
        assertEquals(1, ProjectionRaiseDeadlinePolicy.endsInARowAfter(1, projected = false, userRequested = false))
    }

    @Test
    fun `a user's connect resets the count`() {
        assertEquals(0, ProjectionRaiseDeadlinePolicy.endsInARowAfter(1, projected = false, userRequested = true))
    }

    @Test
    fun `the deadline beats the phone's own patience`() {
        assertTrue(
            ProjectionRaiseDeadlinePolicy.DEADLINE_MS * ProjectionRaiseDeadlinePolicy.MAX_RAISES
                < 35_000L
        )
    }
}
