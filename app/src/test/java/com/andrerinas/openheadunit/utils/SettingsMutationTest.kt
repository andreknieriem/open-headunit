package com.andrerinas.openheadunit.utils

import android.app.Application
import android.content.Context
import com.andrerinas.openheadunit.connection.AppExitPolicy
import com.andrerinas.openheadunit.main.settings.SettingsMutation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.Reader
import java.io.StringReader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsMutationTest {
    private lateinit var context: Context

    @Before fun clearPreferences() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("settings_device_protected", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** A real Reader whose blocking read cannot be interrupted by ordinary coroutine cancellation. */
    private class BlockingInput(json: String) : Reader() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        private val source = StringReader(json)

        override fun read(buffer: CharArray, offset: Int, length: Int): Int {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "Input was not released" }
            return source.read(buffer, offset, length)
        }

        override fun close() = source.close()
        fun awaitRead() = assertTrue("Import never reached its blocking input", entered.await(5, TimeUnit.SECONDS))
    }

    /** Every apply runs on the test's Main thread; IO still runs on a genuine worker. */
    private class MainQueue : CoroutineDispatcher() {
        private val tasks = LinkedBlockingQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun drain() { while (true) (tasks.poll() ?: return).run() }
        fun finish(job: Job) {
            while (!job.isCompleted) {
                val next = tasks.poll(5, TimeUnit.SECONDS)
                if (next == null) assertTrue("Mutation did not finish", job.isCompleted) else next.run()
            }
            drain()
        }
    }

    private inner class Harness : AutoCloseable {
        val main = MainQueue()
        val root = SupervisorJob()
        val scope = CoroutineScope(root + main)
        val mutations = SettingsMutation(scope)
        val appExit = AppExitPolicy()
        val blocked = mutableListOf<BlockingInput>()
        var stops = 0
        var failure: Exception? = null

        fun completed(changedKeys: Set<String>) {
            if (SettingsBackupManager.requiresProjectionRestart(changedKeys)) {
                appExit.request(killProcess = false, userStop = true, requiresKillOnDisconnect = false)
                stops++
            }
        }

        fun import(input: BlockingInput): Job {
            blocked.add(input)
            return mutations.start(
                prepare = { withContext(Dispatchers.IO) { input.use(SettingsBackupManager::prepareImport) } },
                apply = { completed(SettingsBackupManager.applyImport(context, it).changedKeys) },
                onFailure = { failure = it },
            )
        }

        fun reset(): Job {
            return mutations.start(
                prepare = { Unit },
                apply = { completed(SettingsBackupManager.resetFromContext(context).changedKeys) },
                onFailure = { failure = it },
            )
        }

        override fun close() {
            root.cancel()
            blocked.forEach { it.release.countDown() }
            main.finish(root)
        }
    }

    private fun backup(fps: Int, autoStart: Boolean): String = SettingsBackupManager.exportToJson(
        mapOf(
            "fps-limit" to fps,
            "auto-start-on-boot" to autoStart,
            "auto-start-on-wifi" to autoStart,
            "auto-start-wifi-ssid" to if (autoStart) "old-import" else "",
            "auto-start-bt-macs" to if (autoStart) setOf("AA:BB:CC:DD:EE:FF") else emptySet<String>(),
            "usb-blacklist" to if (autoStart) setOf("1234:5678") else emptySet<String>(),
        ), "test", 1,
    )

    private fun seed(fps: Int, autoStart: Boolean) {
        val prepared = SettingsBackupManager.prepareImport(StringReader(backup(fps, autoStart)))
        SettingsBackupManager.applyImport(context, prepared)
    }

    private fun assertMirrors(autoStart: Boolean) {
        assertEquals(autoStart, Settings.isAutoStartOnBootEnabled(context))
        assertEquals(autoStart, Settings.isAutoStartOnWifiEnabled(context))
        assertEquals(if (autoStart) "old-import" else "", Settings.getAutoStartWifiSsid(context))
        assertEquals(if (autoStart) setOf("AA:BB:CC:DD:EE:FF") else emptySet<String>(), Settings.getAutoStartBtMacs(context))
        val mirrors = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("settings_device_protected", Context.MODE_PRIVATE)
        assertEquals(if (autoStart) setOf("1234:5678") else emptySet<String>(), mirrors.getStringSet(Settings.KEY_USB_BLACKLIST, null))
    }

    @Test fun `blocked import A cannot overwrite Save B preferences or mirrors and cannot Stop`() {
        seed(fps = 24, autoStart = false)
        Harness().use { h ->
            val input = BlockingInput(backup(fps = 30, autoStart = true))
            val old = h.import(input)
            h.main.drain()
            input.awaitRead()

            // Save invalidates the operation first, then executes its synchronous Settings writes.
            h.mutations.cancel()
            val settings = Settings(context)
            settings.fpsLimit = 60
            settings.commit()
            input.release.countDown()
            h.main.finish(old)

            assertEquals(60, Settings(context).fpsLimit)
            assertFalse(Settings(context).autoStartOnBoot)
            assertFalse(Settings(context).autoStartOnWifi)
            assertMirrors(autoStart = false)
            assertEquals(0, h.stops)
            assertNull(h.failure)
        }
    }

    @Test fun `A blocked prepare then waiting B then reset C cannot leave an older writer`() {
        seed(fps = 24, autoStart = true)
        val prefs = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString("unrelated-private-state", "kept").commit()
        Harness().use { h ->
            val inputA = BlockingInput(backup(fps = 30, autoStart = true))
            val a = h.import(inputA)
            h.main.drain()
            inputA.awaitRead()

            val inputB = BlockingInput(backup(fps = 45, autoStart = true))
            val b = h.import(inputB)
            h.main.drain()
            inputB.awaitRead()
            val c = h.reset()
            h.main.finish(c)
            assertEquals("Reset must not wait for a cancelled blocking read", 1, h.stops)
            assertFalse(prefs.contains("fps-limit"))
            assertMirrors(autoStart = false)

            inputB.release.countDown()
            h.main.finish(b)
            inputA.release.countDown()
            h.main.finish(a)
            assertFalse(prefs.contains("fps-limit"))
            assertFalse(Settings(context).autoStartOnBoot)
            assertMirrors(autoStart = false)
            assertEquals("kept", prefs.getString("unrelated-private-state", null))
            assertEquals(1, h.stops)
            assertNull(h.failure)
        }
    }

    @Test fun `current import survives UI Open and applies real data mirrors and restart decision`() {
        seed(fps = 60, autoStart = false)
        Harness().use { h ->
            val input = BlockingInput(backup(fps = 30, autoStart = true))
            val current = h.import(input)
            h.main.drain()
            input.awaitRead()
            h.appExit.supersede() // A UI Open is not a settings operation replacement.
            input.release.countDown()
            h.main.finish(current)

            assertEquals(30, Settings(context).fpsLimit)
            assertTrue(Settings(context).autoStartOnBoot)
            assertTrue(Settings(context).autoStartOnWifi)
            assertMirrors(autoStart = true)
            assertEquals(1, h.stops)
            assertTrue(h.appExit.owns(h.appExit.revision, killOnDisconnect = false))
            assertNull(h.failure)
        }
    }

    @Test fun `view lifecycle cancellation prevents all settings and Stop completion effects`() {
        seed(fps = 60, autoStart = false)
        Harness().use { h ->
            val input = BlockingInput(backup(fps = 30, autoStart = true))
            val current = h.import(input)
            h.main.drain()
            input.awaitRead()
            h.root.cancel()
            input.release.countDown()
            h.main.finish(current)
            assertEquals(60, Settings(context).fpsLimit)
            assertMirrors(autoStart = false)
            assertEquals(0, h.stops)
            assertNull(h.failure)
        }
    }

    @Test fun `invalid prepared input cannot apply settings or request Stop`() {
        seed(fps = 60, autoStart = false)
        Harness().use { h ->
            val input = BlockingInput("""{"format":"not-a-backup","settings":{"fps-limit":30}}""")
            val current = h.import(input)
            h.main.drain()
            input.awaitRead()
            input.release.countDown()
            h.main.finish(current)
            assertEquals(60, Settings(context).fpsLimit)
            assertMirrors(autoStart = false)
            assertEquals(0, h.stops)
            assertNotNull(h.failure)
        }
    }
}
