package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyLearnSessionPolicyTest {
    private fun plan(
        sdk: Int,
        learning: Boolean = true,
        was: Boolean = false,
        dialog: Boolean = false,
        self: Boolean = false,
        phone: Boolean = false,
    ) = KeyLearnSessionPolicy.decide(sdk, learning, was, dialog, self, phone)

    @Test fun belowApi26LearningActivatesTheSession() {
        for (sdk in listOf(19, 25)) {
            val p = plan(sdk, dialog = true)
            assertTrue(p.activate)
            assertFalse(p.silentTrack)
        }
    }

    @Test fun fromApi26OnlyAnOpenDialogPlaysTheTrack() {
        for (sdk in listOf(26, 34)) {
            assertTrue(plan(sdk, dialog = true).silentTrack)
            assertFalse(plan(sdk, dialog = false).silentTrack)
            assertFalse(plan(sdk, dialog = true).activate)
        }
    }

    @Test fun selfModeForcesEveryFieldFalse() {
        for (sdk in listOf(19, 34)) {
            val p = plan(sdk, dialog = true, self = true)
            assertEquals(KeyLearnSessionPolicy.Plan(false, false, false), p)
            assertFalse(plan(sdk, learning = false, was = true, self = true).deactivate)
        }
    }

    @Test fun belowApi26LeavingWithNoPhoneDeactivates() {
        assertTrue(plan(19, learning = false, was = true, phone = false).deactivate)
        assertFalse(plan(19, learning = false, was = true, phone = true).deactivate)
    }

    @Test fun fromApi26LeavingNeverDeactivates() {
        assertFalse(plan(26, learning = false, was = true).deactivate)
        assertFalse(plan(34, learning = false, was = true).deactivate)
    }

    @Test fun noSecondDeactivateWhenNotLearningBefore() {
        assertFalse(plan(19, learning = false, was = false).deactivate)
    }

    @Test fun notLearningPlaysNothingAndActivatesNothing() {
        val p = plan(34, learning = false, dialog = true)
        assertFalse(p.activate)
        assertFalse(p.silentTrack)
    }

    @Test fun clearingTheSelfFlagLetsTheSessionRouteAct() {
        assertEquals(KeyLearnSessionPolicy.Plan(false, false, false), plan(34, dialog = true, self = true))
        assertTrue(plan(34, dialog = true, self = false).silentTrack)
        assertTrue(plan(19, dialog = true, self = false).activate)
    }
}
