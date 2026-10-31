package com.andrerinas.openheadunit.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.main.FloatingButtonManager
import com.andrerinas.openheadunit.main.FloatingButtonService
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.ToastUtils
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.*
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import java.util.ArrayDeque
import android.provider.Settings as AndroidSettings

/** Real App callbacks and floating-button decisions; only the Android boundary is replaced. */
class AppDirectBootLifecycleTest {
    private class Fixture : AutoCloseable {
        private val resources = mutableListOf<AutoCloseable>()
        private val pending = ArrayDeque<Runnable>()
        var unlocked = false
        var componentInitializations = 0
        var credentialReads = 0
        var floatingSettingReads = 0
        var toastSettingReads = 0
        val serviceStarts = mutableListOf<String?>()
        lateinit var app: App
            private set
        lateinit var activity: UsbAttachedActivity
            private set

        init {
            try {
                replaceStatic(Build.VERSION::class.java, "SDK_INT", 35)
                resources += Mockito.mockStatic(SystemClock::class.java)
                resources += Mockito.mockStatic(AppCompatDelegate::class.java)
                val loopers = Mockito.mockStatic(Looper::class.java).also(resources::add)
                val main = mock<Looper>()
                loopers.`when`<Looper> { Looper.getMainLooper() }.thenReturn(main)
                loopers.`when`<Looper> { Looper.myLooper() }.thenReturn(main)
                // Covers first initialization of the manager in any test order. Replace its
                // process-wide handler separately so an earlier test's handler is also safe.
                resources += Mockito.mockConstruction(Handler::class.java)
                val handler = mock<Handler>()
                whenever(handler.post(any())).thenAnswer {
                    pending.addLast(it.getArgument<Runnable>(0))
                    true
                }
                replaceStatic(FloatingButtonManager::class.java, "mainHandler", handler)
                replaceStatic(FloatingButtonManager::class.java, "isAppForeground", false)
                replaceStatic(FloatingButtonService::class.java, "activeOverlayView", null)
                replaceStatic(App::class.java, "hasStartedActivity", false)
                replaceStatic(AppUiEntry::class.java, "policy", AppForegroundPolicy())

                resources += Mockito.mockConstruction(Intent::class.java,
                    Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { intent, _ ->
                    var action: String? = null
                    whenever(intent.setAction(anyOrNull())).thenAnswer {
                        action = it.getArgument(0)
                        intent
                    }
                    whenever(intent.action).thenAnswer { action }
                }
                val overlays = Mockito.mockStatic(AndroidSettings::class.java).also(resources::add)
                overlays.`when`<Boolean> { AndroidSettings.canDrawOverlays(any()) }.thenReturn(true)

                app = mock()
                whenever(app.applicationContext).thenReturn(app)
                val userManager = mock<UserManager>()
                whenever(userManager.isUserUnlocked).thenAnswer { unlocked }
                whenever(app.getSystemService(Context.USER_SERVICE)).thenReturn(userManager)
                doCallRealMethod().whenever(app).onActivityStarted(any())
                doCallRealMethod().whenever(app).onActivityStopped(any())

                val prefs = mock<SharedPreferences>()
                whenever(prefs.getBoolean(eq("enable-floating-button"), any())).thenAnswer {
                    floatingSettingReads++
                    true
                }
                whenever(prefs.getBoolean(eq("show-toast-messages"), any())).thenAnswer {
                    toastSettingReads++
                    false
                }
                whenever(app.getSharedPreferences(any(), any())).thenAnswer {
                    credentialReads++
                    check(unlocked) { "Credential storage is locked" }
                    prefs
                }
                whenever(app.startService(any())).thenAnswer {
                    serviceStarts += it.getArgument<Intent>(0).action
                    null
                }

                val unlockedComponent = mock<AppComponent>()
                whenever(unlockedComponent.settings).thenReturn(Settings(app))
                val component = lazy {
                    componentInitializations++
                    // On the old source this traverses the real AppComponent/VideoDecoder/
                    // Settings initialization and throws Settings' actual CE-unavailable error.
                    if (!unlocked) AppComponent(app) else unlockedComponent
                }
                App::class.java.getDeclaredField("component\$delegate").apply { isAccessible = true }
                    .set(app, component)

                activity = mock()
                whenever(activity.applicationContext).thenReturn(app)
                whenever(activity.getSystemService(Context.USER_SERVICE)).thenReturn(userManager)
            } catch (failure: Throwable) {
                try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        fun drain() {
            // Execute on the test thread: Mockito static mocks are thread-local.
            while (pending.isNotEmpty()) pending.removeFirst().run()
        }

        fun assertNoCredentialAccess() {
            assertEquals("AppComponent must stay uninitialized before first unlock", 0, componentInitializations)
            assertEquals("Credential preferences must stay unread before first unlock", 0, credentialReads)
            assertEquals(0, floatingSettingReads)
            assertEquals(0, toastSettingReads)
            assertTrue("Locked callbacks must not start the overlay service", serviceStarts.isEmpty())
        }

        private fun replaceStatic(type: Class<*>, name: String, value: Any?) {
            val field = type.getDeclaredField(name).apply { isAccessible = true }
            val oldValue = field.get(null)
            resources += AutoCloseable { writeStatic(field, oldValue) }
            writeStatic(field, value)
        }

        private fun writeStatic(field: Field, value: Any?) {
            if (!Modifier.isFinal(field.modifiers)) {
                field.set(null, value)
                return
            }
            // The host Android SDK_INT and Kotlin object val fields are static final.
            // As in the existing codec/service tests, restore every original value in close().
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val base = unsafeClass.getMethod("staticFieldBase", Field::class.java).invoke(unsafe, field)
            val offset = unsafeClass.getMethod("staticFieldOffset", Field::class.java).invoke(unsafe, field)
            val primitive = field.type == Int::class.javaPrimitiveType
            unsafeClass.getMethod(if (primitive) "putIntVolatile" else "putObjectVolatile",
                Any::class.java, Long::class.javaPrimitiveType,
                if (primitive) Int::class.javaPrimitiveType else Any::class.java)
                .invoke(unsafe, base, offset, value)
        }

        override fun close() {
            pending.clear()
            var failure: Throwable? = null
            resources.asReversed().forEach {
                try { it.close() } catch (cleanup: Throwable) {
                    if (failure == null) failure = cleanup else failure!!.addSuppressed(cleanup)
                }
            }
            resources.clear()
            failure?.let { throw it }
        }
    }

    @Test fun `locked USB start callback cannot initialize component or read credential settings`() {
        Fixture().use { f ->
            f.app.onActivityStarted(f.activity)
            f.drain()
            assertTrue(App.hasStartedActivity)
            assertTrue(FloatingButtonManager.isAppForeground)
            f.assertNoCredentialAccess()
        }
    }

    @Test fun `locked USB stop callback cannot initialize component or read credential settings`() {
        Fixture().use { f ->
            // The USB trampoline can finish before its queued foreground update is delivered.
            f.app.onActivityStarted(f.activity)
            f.app.onActivityStopped(f.activity)
            f.drain()
            assertFalse(App.hasStartedActivity)
            assertFalse(FloatingButtonManager.isAppForeground)
            f.assertNoCredentialAccess()
        }
    }

    @Test fun `direct floating update while locked cannot initialize component or read credential settings`() {
        Fixture().use { f ->
            FloatingButtonManager.update(f.app)
            f.assertNoCredentialAccess()
        }
    }

    @Test fun `locked floating start cannot create the service`() {
        Fixture().use { f ->
            FloatingButtonService.start(f.app)
            f.assertNoCredentialAccess()
        }
    }

    @Test fun `locked floating stop does not start a service to deliver its stop command`() {
        Fixture().use { f ->
            FloatingButtonService.stop(f.app)
            f.assertNoCredentialAccess()
            verify(f.app).stopService(any())
        }
    }

    @Test fun `first start after unlock updates floating button in the same process`() {
        Fixture().use { f ->
            f.app.onActivityStarted(f.activity)
            f.drain()
            f.app.onActivityStopped(f.activity)
            f.drain()
            f.assertNoCredentialAccess()

            f.unlocked = true
            f.app.onActivityStarted(f.activity)
            f.drain()
            assertEquals(1, f.componentInitializations)
            assertTrue(f.credentialReads > 0)
            assertTrue(f.floatingSettingReads > 0)
            assertTrue(FloatingButtonManager.isAppForeground)

            f.serviceStarts.clear()
            f.app.onActivityStopped(f.activity)
            f.drain()
            assertEquals(listOf<String?>(null), f.serviceStarts)
            assertFalse(FloatingButtonManager.isAppForeground)
        }
    }

    @Test fun `unlocked start and stop still update the floating button`() {
        Fixture().use { f ->
            f.unlocked = true
            f.app.onActivityStarted(f.activity)
            f.drain()
            assertEquals(1, f.componentInitializations)
            assertTrue(f.credentialReads > 0)
            assertTrue(f.floatingSettingReads > 0)
            assertTrue("Entering the foreground must not create an overlay service to stop it", f.serviceStarts.isEmpty())
            verify(f.app).stopService(any())
            assertTrue(FloatingButtonManager.isAppForeground)

            f.serviceStarts.clear()
            f.app.onActivityStopped(f.activity)
            f.drain()
            assertEquals(1, f.componentInitializations)
            assertEquals(listOf<String?>(null), f.serviceStarts)
            assertFalse(FloatingButtonManager.isAppForeground)
        }
    }

    @Test fun `overlapping unlocked activities keep the overlay stopped until the last activity stops`() {
        Fixture().use { f ->
            val secondActivity = mock<Activity>()
            f.unlocked = true
            f.app.onActivityStarted(f.activity)
            f.drain()
            assertTrue(App.hasStartedActivity)
            assertTrue(FloatingButtonManager.isAppForeground)
            assertTrue(f.serviceStarts.isEmpty())
            verify(f.app).stopService(any())

            f.app.onActivityStarted(secondActivity)
            f.drain()
            assertTrue(App.hasStartedActivity)
            assertTrue(FloatingButtonManager.isAppForeground)
            assertTrue(f.serviceStarts.isEmpty())

            f.app.onActivityStopped(f.activity)
            f.drain()
            assertTrue(App.hasStartedActivity)
            assertTrue(FloatingButtonManager.isAppForeground)
            assertTrue(f.serviceStarts.isEmpty())

            f.app.onActivityStopped(secondActivity)
            f.drain()
            assertFalse(App.hasStartedActivity)
            assertFalse(FloatingButtonManager.isAppForeground)
            assertEquals(listOf<String?>(null), f.serviceStarts)
        }
    }

    @Test fun `first start after unlock updates while the locked USB activity is still started`() {
        Fixture().use { f ->
            f.app.onActivityStarted(f.activity)
            f.drain()
            f.assertNoCredentialAccess()

            f.unlocked = true
            val unlockedActivity = mock<Activity>()
            f.app.onActivityStarted(unlockedActivity)
            f.drain()
            assertEquals(1, f.componentInitializations)
            assertTrue(f.credentialReads > 0)
            assertTrue(f.floatingSettingReads > 0)
            assertTrue(FloatingButtonManager.isAppForeground)
            assertTrue(f.serviceStarts.isEmpty())
            verify(f.app).stopService(any())

            f.app.onActivityStopped(f.activity)
            f.drain()
            assertTrue(App.hasStartedActivity)
            assertTrue(FloatingButtonManager.isAppForeground)
            assertTrue(f.serviceStarts.isEmpty())

            f.app.onActivityStopped(unlockedActivity)
            f.drain()
            assertFalse(App.hasStartedActivity)
            assertFalse(FloatingButtonManager.isAppForeground)
            assertEquals(listOf<String?>(null), f.serviceStarts)
        }
    }

    @Test fun `locked USB status toasts do not attempt component initialization or credential reads`() {
        Fixture().use { f ->
            Mockito.mockStatic(Toast::class.java).use { toasts ->
                val toast = mock<Toast>()
                toasts.`when`<Toast> { Toast.makeText(same(f.app), any<CharSequence>(), any()) }.thenReturn(toast)
                toasts.`when`<Toast> { Toast.makeText(same(f.app), any<Int>(), any()) }.thenReturn(toast)
                ToastUtils.showToast(f.app, "Switching USB device to accessory mode")
                ToastUtils.showToast(f.app, 1)
                f.assertNoCredentialAccess()
                verify(toast, times(2)).show()
            }
        }
    }

    @Test fun `unlocked status toasts still honor the setting and forced toasts still show`() {
        Fixture().use { f ->
            f.unlocked = true
            Mockito.mockStatic(Toast::class.java).use { toasts ->
                val toast = mock<Toast>()
                toasts.`when`<Toast> { Toast.makeText(same(f.app), any<CharSequence>(), any()) }.thenReturn(toast)
                toasts.`when`<Toast> { Toast.makeText(same(f.app), any<Int>(), any()) }.thenReturn(toast)
                ToastUtils.showToast(f.app, "USB status")
                ToastUtils.showToast(f.app, 1)
                assertEquals(1, f.componentInitializations)
                assertTrue(f.credentialReads > 0)
                assertTrue(f.toastSettingReads > 0)
                verify(toast, never()).show()

                ToastUtils.showToast(f.app, "USB refusal", force = true)
                verify(toast).show()
            }
        }
    }
}
