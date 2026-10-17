package com.andrerinas.openheadunit.main

import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mock

/** Exercise the activity's real handoff methods without opening framework permission windows. */
class SettingsExternalSetupTest {
    private fun activity() = mock(SettingsActivity::class.java, CALLS_REAL_METHODS)
    private fun pending(activity: SettingsActivity): Boolean =
        SettingsActivity::class.java.getDeclaredField("externalSetupPending").let {
            it.isAccessible = true
            it.getBoolean(activity)
        }

    @Test fun `permission result releases a hold even when no window ever paused the activity`() {
        val activity = activity()
        lateinit var reply: () -> Unit
        assertTrue(activity.openSetup { reply = activity.setupRequestCompletion(); true })
        assertTrue(pending(activity))
        reply()
        assertFalse(pending(activity))
    }

    @Test fun `a failed activity launch does not leave auto-connect held`() {
        val activity = activity()
        assertThrows(IllegalStateException::class.java) {
            activity.openSetup { throw IllegalStateException("No activity handles setup") }
        }
        assertFalse(pending(activity))
        assertFalse(activity.openSetup { false })
        assertFalse(pending(activity))
    }

    @Test fun `late permission result cannot release a newer developer settings visit`() {
        val activity = activity()
        lateinit var oldReply: () -> Unit
        activity.openSetup { oldReply = activity.setupRequestCompletion(); true }
        activity.openSetup { true }
        oldReply()
        assertTrue(pending(activity))
    }

    @Test fun `refused duplicate request retains the original requests completion`() {
        val activity = activity()
        lateinit var reply: () -> Unit
        activity.openSetup { reply = activity.setupRequestCompletion(); true }
        assertFalse(activity.openSetup { false })
        assertTrue(pending(activity))
        reply()
        assertFalse(pending(activity))
    }

    @Test fun `reply queued by a failed launch cannot match the next visits generation`() {
        val activity = activity()
        lateinit var failedReply: () -> Unit
        activity.openSetup { failedReply = activity.setupRequestCompletion(); false }
        activity.openSetup { true }
        failedReply()
        assertTrue(pending(activity))
    }

    @Test fun `failed launch from another settings instance preserves the pending requests owner`() {
        val first = activity()
        val second = activity()
        lateinit var reply: () -> Unit
        first.openSetup { reply = first.setupRequestCompletion(); true }
        second.openSetup { false }
        reply()
        assertFalse(pending(first))
        assertFalse(pending(second))
    }

    @Test fun `another settings instance cannot reuse the previous requests generation`() {
        val first = activity()
        val second = activity()
        lateinit var oldReply: () -> Unit
        first.openSetup { oldReply = first.setupRequestCompletion(); true }
        second.openSetup { true }
        oldReply()
        assertTrue(pending(second))
    }

    @Test fun `throwing second launch restores the previous setup ownership`() {
        val activity = activity()
        lateinit var reply: () -> Unit
        activity.openSetup { reply = activity.setupRequestCompletion(); true }
        assertThrows(IllegalStateException::class.java) {
            activity.openSetup { throw IllegalStateException("Launch refused") }
        }
        assertTrue(pending(activity))
        reply()
        assertFalse(pending(activity))
    }
    private fun visible(activity: SettingsActivity) {
        val token = Any()
        for ((name, value) in listOf("visibilityOwner" to token, "currentVisibilityOwner" to token)) {
            SettingsActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                .set(if (name == "visibilityOwner") activity else null, value)
        }
        SettingsActivity.isForeground = true
        SettingsActivity.isVisible = true
    }

    @Test fun `Home from external settings releases both connection holds`() {
        val activity = activity()
        visible(activity)
        activity.openSetup { true }
        activity.onExternalSetupExit(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS, "homekey")
        assertFalse(pending(activity))
        assertFalse(SettingsActivity.isForeground)
        assertFalse(SettingsActivity.isVisible)
    }

    @Test fun `screen off after a permission reply also releases retained visibility`() {
        val activity = activity()
        visible(activity)
        activity.openSetup { true }
        activity.setupRequestCompletion()()
        activity.onExternalSetupExit(android.content.Intent.ACTION_SCREEN_OFF, null)
        assertFalse(SettingsActivity.isVisible)
        assertFalse(SettingsActivity.isForeground)
    }

    @Test fun `notification shade does not abandon setup and old activity cannot release new owner`() {
        val first = activity()
        visible(first)
        first.openSetup { true }
        first.onExternalSetupExit(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS, "assist")
        assertTrue(pending(first))
        assertTrue(SettingsActivity.isVisible)
        val second = activity()
        visible(second)
        second.openSetup { true }
        first.onExternalSetupExit(android.content.Intent.ACTION_SCREEN_OFF, null)
        assertTrue(pending(second))
        assertTrue(SettingsActivity.isVisible)
        second.onExternalSetupExit(android.content.Intent.ACTION_SCREEN_OFF, null)
    }

    @Test fun `switching apps from external setup releases its hold`() {
        val activity = activity()
        visible(activity)
        activity.openSetup { true }
        activity.onExternalSetupExit(android.content.Intent.ACTION_CLOSE_SYSTEM_DIALOGS, "recentapps")
        assertFalse(pending(activity))
        assertFalse(SettingsActivity.isForeground)
        assertFalse(SettingsActivity.isVisible)
    }

}
