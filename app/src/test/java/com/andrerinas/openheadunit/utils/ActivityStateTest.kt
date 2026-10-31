package com.andrerinas.openheadunit.utils

import androidx.lifecycle.Lifecycle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActivityStateTest {

    private val missing: () -> Boolean = { throw NoSuchMethodError("android.app.Activity.isDestroyed") }

    @Before
    @After
    fun reset() {
        ActivityState.hasIsDestroyed = true
    }

    @Test
    fun `a finishing activity is gone without asking the framework`() {
        assertTrue(ActivityState.isGone(true, 19, missing) { Lifecycle.State.RESUMED })
        assertTrue(ActivityState.hasIsDestroyed)
    }

    @Test
    fun `isDestroyed answers where the framework has it`() {
        assertTrue(ActivityState.isGone(false, 19, { true }) { Lifecycle.State.RESUMED })
        assertFalse(ActivityState.isGone(false, 19, { false }) { Lifecycle.State.DESTROYED })
    }

    @Test
    fun `a ROM without isDestroyed falls back to the lifecycle instead of crashing`() {
        assertFalse(ActivityState.isGone(false, 19, missing) { Lifecycle.State.RESUMED })
        assertFalse(ActivityState.hasIsDestroyed)
        assertTrue(ActivityState.isGone(false, 19, missing) { Lifecycle.State.DESTROYED })
    }

    @Test
    fun `the method is not tried again after it was found missing`() {
        var calls = 0
        val counting: () -> Boolean = { calls++; throw NoSuchMethodError() }
        repeat(3) { ActivityState.isGone(false, 19, counting) { Lifecycle.State.STARTED } }
        assertEquals(1, calls)
    }

    @Test
    fun `below API 17 the lifecycle answers and the method is never called`() {
        assertTrue(ActivityState.isGone(false, 16, missing) { Lifecycle.State.DESTROYED })
        assertFalse(ActivityState.isGone(false, 16, missing) { null })
        assertTrue(ActivityState.hasIsDestroyed)
    }

    @Test
    fun `a Glide call that hits the missing method takes the fallback scope`() {
        assertEquals("app", ActivityState.orFallback({ throw NoSuchMethodError() }) { "app" })
        assertEquals("app", ActivityState.orFallback({ "activity" }) { "app" })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `other failures are not swallowed`() {
        ActivityState.orFallback<String>({ throw IllegalArgumentException() }) { "app" }
    }
}
