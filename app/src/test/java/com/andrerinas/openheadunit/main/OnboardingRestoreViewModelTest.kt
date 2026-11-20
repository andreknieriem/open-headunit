package com.andrerinas.openheadunit.main

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.andrerinas.openheadunit.connection.CommManager
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class OnboardingRestoreViewModelTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val store = ViewModelStore()
    private val model get() = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())
        .get(OnboardingRestoreViewModel::class.java)
    private val files = mutableListOf<File>()
    private val ticket = 42L
    private val connection = mock<CommManager>()

    @Before fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        whenever(connection.appConnectionTicket()).thenReturn(ticket)
        whenever(connection.acceptsAppConnectionTicket(ticket)).thenReturn(true)
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
                model.state.first { it != OnboardingRestoreViewModel.State.Importing }
            }
        }

    @Test fun `restore survives activity view model reattachment and ignores duplicate requests`() = runTest {
        val original = model
        val good = backup(SettingsBackupManager.exportToJson(mapOf("show-home-clock" to true), "test", 1))
        original.restore(context, connection, file = good)
        assertEquals(OnboardingRestoreViewModel.State.Importing, original.state.value)
        original.restore(context, connection, file = backup("broken"))
        assertSame(original, model)
        val result = awaitResult()
        assertTrue(result is OnboardingRestoreViewModel.State.Restored)
        val restored = result as OnboardingRestoreViewModel.State.Restored
        assertEquals(restored.before.wireless, SettingsRestoreHandler.capture(context, Settings(context), ticket).wireless)
        assertTrue(Settings(context).showHomeClock)
        // Only the activity may complete onboarding, after a successful import and consent.
        assertFalse(Settings(context).hasCompletedSetupWizard)
        assertEquals(0, Settings(context).onboardingVersion)
    }

    @Test fun `invalid file leaves onboarding incomplete and a retry can succeed`() = runTest {
        model.restore(context, connection, file = backup("not JSON"))
        assertTrue(awaitResult() is OnboardingRestoreViewModel.State.Failed)
        assertFalse(Settings(context).hasCompletedSetupWizard)
        assertFalse(Settings(context).showHomeClock)
        model.acknowledge()
        assertEquals(OnboardingRestoreViewModel.State.Idle, model.state.value)
        model.restore(context, connection, file = backup(SettingsBackupManager.exportToJson(
            mapOf("show-home-clock" to true), "test", 1)))
        assertTrue(awaitResult() is OnboardingRestoreViewModel.State.Restored)
    }

    @Test fun `restored wireless options request a launcher rearm`() = runTest {
        model.restore(context, connection, file = backup(SettingsBackupManager.exportToJson(
            mapOf("external-bt-zbt-transport" to true, "connection-modes" to setOf("wifi")), "test", 1)))
        val result = awaitResult()
        assertTrue(result is OnboardingRestoreViewModel.State.Restored)
        val restored = result as OnboardingRestoreViewModel.State.Restored
        assertNotEquals(restored.before.wireless, SettingsRestoreHandler.capture(context, Settings(context), ticket).wireless)
    }

    @Test fun `Stop accepted during backup read leaves Idle and writes no preferences`() = runTest {
        val uri = Uri.parse("content://onboarding-test/backup.json")
        val json = SettingsBackupManager.exportToJson(mapOf("show-home-clock" to true), "test", 1)
        val prefs = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        val beforeStop = AtomicReference<Map<String, *>>()
        whenever(connection.acceptsAppConnectionTicket(ticket)).thenAnswer { beforeStop.get() == null }
        shadowOf(context.contentResolver).registerInputStream(uri, object : ByteArrayInputStream(json.toByteArray()) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                // Capture after the Settings snapshot has run its ordinary preference migrations.
                // Stop revokes the ticket at the first backup read, before any import can apply.
                if (beforeStop.get() == null) beforeStop.set(prefs.all)
                return super.read(bytes, offset, length)
            }
        })

        model.restore(context, connection, uri = uri)

        assertEquals(OnboardingRestoreViewModel.State.Importing, model.state.value)
        assertEquals(OnboardingRestoreViewModel.State.Idle, awaitResult())
        verify(connection).acceptsAppConnectionTicket(ticket)
        assertEquals(checkNotNull(beforeStop.get()), prefs.all)
    }

    @Test fun `empty or entirely unknown backups cannot complete the wizard`() = runTest {
        for (settings in listOf("{}", """{"unrecognized":true}""")) {
            model.restore(context, connection, file = backup(
                """{"format":"open-headunit-settings","version":1,"settings":$settings}"""))
            assertTrue(awaitResult() is OnboardingRestoreViewModel.State.Failed)
            assertFalse(Settings(context).hasCompletedSetupWizard)
            model.acknowledge()
        }
    }

    @Test fun `no selected file leaves state untouched`() {
        assertEquals(OnboardingRestoreViewModel.State.Idle, model.state.value)
        assertFalse(Settings(context).hasCompletedSetupWizard)
        assertThrows(IllegalArgumentException::class.java) { model.restore(context, connection) }
        assertEquals(OnboardingRestoreViewModel.State.Idle, model.state.value)
    }
}
