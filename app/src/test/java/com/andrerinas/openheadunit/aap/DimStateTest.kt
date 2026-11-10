package com.andrerinas.openheadunit.aap

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DimStateTest {
    private val state = DimState()

    @Test fun everyWriterHoldsTheLockBecauseTheSessionResetRunsOffTheMainThread() {
        for (name in listOf("toggle", "suspendForOverlay", "resumeAfterOverlay", "resetForNewSession")) {
            assertTrue(name, java.lang.reflect.Modifier.isSynchronized(DimState::class.java.getMethod(name).modifiers))
        }
    }

    @Test fun aNewSessionStartsOff() {
        state.toggle()
        assertTrue(state.on.value)
        state.resetForNewSession()
        assertFalse(state.on.value)
    }

    @Test fun aRecreatedActivitySeesTheHeldState() = runBlocking {
        val firstActivity = async(start = CoroutineStart.UNDISPATCHED) { state.on.take(2).toList() }
        state.toggle()
        assertEquals(listOf(false, true), firstActivity.await())
        // A collector that starts after the first one ended gets the held value, not the default.
        assertTrue(state.on.first())
    }

    @Test fun theOverlayPausesAndRestoresTheDimmer() {
        state.toggle()
        state.suspendForOverlay()
        assertFalse(state.on.value)
        state.resumeAfterOverlay()
        assertTrue(state.on.value)
    }

    @Test fun resumeWithoutASuspendChangesNothing() {
        state.resumeAfterOverlay()
        assertFalse(state.on.value)
        state.toggle()
        state.resumeAfterOverlay()
        assertTrue(state.on.value)
    }

    @Test fun aResetBetweenSuspendAndResumeLeavesItOff() {
        state.toggle()
        state.suspendForOverlay()
        state.resetForNewSession()
        state.resumeAfterOverlay()
        assertFalse(state.on.value)
    }

    @Test fun theDriversOwnChoiceBeatsAPendingResume() {
        state.toggle()
        state.suspendForOverlay()
        state.toggle()
        assertTrue(state.on.value)
        state.toggle()
        state.resumeAfterOverlay()
        assertFalse(state.on.value)
    }

    @Test fun aRecreatedActivityResumesAHeldDimmerOnce() {
        state.toggle()
        state.suspendForOverlay()
        state.resumeAfterOverlay()
        assertTrue(state.on.value)
        state.toggle()
        state.resumeAfterOverlay()
        assertFalse(state.on.value)
    }
}
