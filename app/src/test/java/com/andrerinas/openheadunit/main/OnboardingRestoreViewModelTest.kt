package com.andrerinas.openheadunit.main

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SettingsBackupManager
import com.andrerinas.openheadunit.utils.SettingsRestoreHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class OnboardingRestoreViewModelTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val store = ViewModelStore()
    private val model get() = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())
        .get(OnboardingRestoreViewModel::class.java)
    private val files = mutableListOf<File>()

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun cleanup() {
        store.clear()
        Dispatchers.resetMain()
        files.forEach { it.delete() }
    }

    private fun backup(json: String): File =
        File.createTempFile("onboarding-restore-", ".json", context.cacheDir).also {
            it.writeText(json)
            files.add(it)
        }

    private suspend fun awaitResult(): OnboardingRestoreViewModel.State =
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                model.state.first {
                    it is OnboardingRestoreViewModel.State.Restored || it is OnboardingRestoreViewModel.State.Failed
                }
            }
        }

    @Test fun `restore survives activity view model reattachment and ignores duplicate requests`() = runTest {
        val original = model
        val good = backup(SettingsBackupManager.exportToJson(mapOf("show-home-clock" to true), "test", 1))
        original.restore(context, file = good)
        assertEquals(OnboardingRestoreViewModel.State.Importing, original.state.value)
        original.restore(context, file = backup("broken"))
        assertSame(original, model)
        val result = awaitResult()
        assertTrue(result is OnboardingRestoreViewModel.State.Restored)
        val restored = result as OnboardingRestoreViewModel.State.Restored
        assertEquals(restored.before.wireless, SettingsRestoreHandler.capture(context, Settings(context)).wireless)
        assertTrue(Settings(context).showHomeClock)
        // Only the activity may complete onboarding, after a successful import and consent.
        assertFalse(Settings(context).hasCompletedSetupWizard)
        assertEquals(0, Settings(context).onboardingVersion)
    }

    @Test fun `invalid file leaves onboarding incomplete and a retry can succeed`() = runTest {
        model.restore(context, file = backup("not JSON"))
        assertTrue(awaitResult() is OnboardingRestoreViewModel.State.Failed)
        assertFalse(Settings(context).hasCompletedSetupWizard)
        assertFalse(Settings(context).showHomeClock)
        model.acknowledge()
        assertEquals(OnboardingRestoreViewModel.State.Idle, model.state.value)
        model.restore(context, file = backup(SettingsBackupManager.exportToJson(
            mapOf("show-home-clock" to true), "test", 1)))
        assertTrue(awaitResult() is OnboardingRestoreViewModel.State.Restored)
    }

    @Test fun `restored wireless options request a launcher rearm`() = runTest {
        model.restore(context, file = backup(SettingsBackupManager.exportToJson(
            mapOf("external-bt-zbt-transport" to true, "connection-modes" to setOf("wifi")), "test", 1)))
        val result = awaitResult()
        assertTrue(result is OnboardingRestoreViewModel.State.Restored)
        val restored = result as OnboardingRestoreViewModel.State.Restored
        assertNotEquals(restored.before.wireless, SettingsRestoreHandler.capture(context, Settings(context)).wireless)
    }

    @Test fun `empty or entirely unknown backups cannot complete the wizard`() = runTest {
        for (settings in listOf("{}", """{"unrecognized":true}""")) {
            model.restore(context, file = backup(
                """{"format":"open-headunit-settings","version":1,"settings":$settings}"""))
            assertTrue(awaitResult() is OnboardingRestoreViewModel.State.Failed)
            assertFalse(Settings(context).hasCompletedSetupWizard)
            model.acknowledge()
        }
    }

    @Test fun `no selected file leaves state untouched`() {
        assertEquals(OnboardingRestoreViewModel.State.Idle, model.state.value)
        assertFalse(Settings(context).hasCompletedSetupWizard)
        assertThrows(IllegalArgumentException::class.java) { model.restore(context) }
        assertEquals(OnboardingRestoreViewModel.State.Idle, model.state.value)
    }
}
