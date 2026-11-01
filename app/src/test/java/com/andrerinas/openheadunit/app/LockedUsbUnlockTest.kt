package com.andrerinas.openheadunit.app

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.UsbRecheck
import com.andrerinas.openheadunit.connection.usb.UsbDeviceCompat
import com.andrerinas.openheadunit.connection.usb.UsbReceiver
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.*
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import android.provider.Settings as AndroidSettings

/**
 * Real App initializer, preference mirroring, BootCompleteReceiver and USB relay factory. The
 * persisted pending input is seeded as a preceding locked attach; the host companion also runs
 * the actual attach entry and App's dynamic unlock receiver. No service, radio or theme job runs.
 */
class LockedUsbUnlockTest {
    private class MemoryPrefs {
        val values = mutableMapOf<String, Any?>()
        val prefs = mock<SharedPreferences>()
        private val editor = mock<SharedPreferences.Editor>()

        init {
            whenever(prefs.contains(any())).thenAnswer { values.containsKey(it.getArgument(0)) }
            whenever(prefs.getBoolean(any(), any())).thenAnswer {
                values[it.getArgument<String>(0)] as? Boolean ?: it.getArgument<Boolean>(1)
            }
            whenever(prefs.getInt(any(), any())).thenAnswer {
                values[it.getArgument<String>(0)] as? Int ?: it.getArgument<Int>(1)
            }
            whenever(prefs.getLong(any(), any())).thenAnswer {
                values[it.getArgument<String>(0)] as? Long ?: it.getArgument<Long>(1)
            }
            whenever(prefs.getString(any(), anyOrNull())).thenAnswer {
                values[it.getArgument<String>(0)] as? String ?: it.getArgument<String?>(1)
            }
            whenever(prefs.getStringSet(any(), anyOrNull())).thenAnswer {
                @Suppress("UNCHECKED_CAST")
                (values[it.getArgument<String>(0)] as? Set<String>) ?: it.getArgument<Set<String>?>(1)
            }
            whenever(prefs.edit()).thenReturn(editor)
            whenever(editor.putBoolean(any(), any())).thenAnswer { values[it.getArgument(0)] = it.getArgument<Boolean>(1); editor }
            whenever(editor.putInt(any(), any())).thenAnswer { values[it.getArgument(0)] = it.getArgument<Int>(1); editor }
            whenever(editor.putLong(any(), any())).thenAnswer { values[it.getArgument(0)] = it.getArgument<Long>(1); editor }
            whenever(editor.putString(any(), anyOrNull())).thenAnswer { values[it.getArgument(0)] = it.getArgument<String?>(1); editor }
            whenever(editor.putStringSet(any(), anyOrNull())).thenAnswer { values[it.getArgument(0)] = it.getArgument<Set<String>?>(1); editor }
            whenever(editor.clear()).thenAnswer { values.clear(); editor }
            whenever(editor.remove(any())).thenAnswer { values.remove(it.getArgument<String>(0)); editor }
            whenever(editor.commit()).thenReturn(true)
        }
    }

    private class Fixture : AutoCloseable {
        private val resources = mutableListOf<AutoCloseable>()
        private val devicePrefs = mutableMapOf<String, MemoryPrefs>()
        private val credentials = MemoryPrefs()
        val starts = mutableListOf<Intent>()
        val scans = mutableListOf<UsbRecheck>()
        val devices = hashMapOf<String, UsbDevice>()
        var unlocked = false
        var boot = 101
        var ticket = 11L
        var componentReads = 0
        var credentialReads = 0
        var rejectedStarts = 0
        private var foregroundStartAllowed = false
        lateinit var app: App
            private set

        init {
            try {
                // API 24 is Direct Boot capable and avoids unrelated Shizuku initialization.
                replaceStatic(Build.VERSION::class.java, "SDK_INT", 24)
                resources += Mockito.mockStatic(SystemClock::class.java)
                resources += Mockito.mockStatic(AppCompatDelegate::class.java)
                resources += Mockito.mockStatic(Log::class.java)
                val loopers = Mockito.mockStatic(Looper::class.java).also(resources::add)
                val main = mock<Looper>()
                loopers.`when`<Looper> { Looper.getMainLooper() }.thenReturn(main)
                // Theme application posts and stays queued: connection tests do not run UI jobs.
                loopers.`when`<Looper?> { Looper.myLooper() }.thenReturn(null)
                resources += Mockito.mockConstruction(Handler::class.java)
                for (name in listOf("LOGGER", "cachedLogLevel", "currentLogSource", "lastAppLogFile", "appLogFileLogger")) {
                    val field = AppLog::class.java.getDeclaredField(name).apply { isAccessible = true }
                    val previous = field.get(null)
                    resources += AutoCloseable { field.set(null, previous) }
                    if (name == "appLogFileLogger") field.set(null, null)
                }
                val system = Mockito.mockStatic(AndroidSettings.Global::class.java).also(resources::add)
                system.`when`<Int> { AndroidSettings.Global.getInt(any(), eq(AndroidSettings.Global.BOOT_COUNT), any()) }
                    .thenAnswer { boot }
                resources += Mockito.mockConstruction(Intent::class.java,
                    Mockito.withSettings().defaultAnswer(Mockito.RETURNS_SELF)) { intent, construction ->
                    var action = construction.arguments().singleOrNull() as? String
                    val extras = mutableMapOf<String, Any?>()
                    whenever(intent.action).thenAnswer { action }
                    whenever(intent.setAction(anyOrNull())).thenAnswer { action = it.getArgument(0); intent }
                    whenever(intent.putExtra(any<String>(), any<Long>())).thenAnswer { extras[it.getArgument(0)] = it.getArgument<Long>(1); intent }
                    whenever(intent.putExtra(any<String>(), any<String>())).thenAnswer { extras[it.getArgument(0)] = it.getArgument<String>(1); intent }
                    whenever(intent.putExtra(any<String>(), any<Boolean>())).thenAnswer { extras[it.getArgument(0)] = it.getArgument<Boolean>(1); intent }
                    whenever(intent.getLongExtra(any(), any())).thenAnswer { extras[it.getArgument<String>(0)] as? Long ?: it.getArgument<Long>(1) }
                    whenever(intent.getBooleanExtra(any(), any())).thenAnswer { extras[it.getArgument<String>(0)] as? Boolean ?: it.getArgument<Boolean>(1) }
                    whenever(intent.getStringExtra(any())).thenAnswer { extras[it.getArgument<String>(0)] as? String }
                }
                val compat = Mockito.mockStatic(ContextCompat::class.java).also(resources::add)
                compat.`when`<android.content.ComponentName?> { ContextCompat.startForegroundService(any(), any()) }
                    .thenAnswer {
                        if (!foregroundStartAllowed) {
                            rejectedStarts++
                            throw IllegalStateException("USER_UNLOCKED has no foreground-service exemption")
                        }
                        starts += it.getArgument<Intent>(1)
                        null
                    }
                newProcess()
                val device = mock<UsbDevice>()
                whenever(device.vendorId).thenReturn(0x18d1)
                whenever(device.productId).thenReturn(0x2d00)
                whenever(device.deviceName).thenReturn("/dev/bus/usb/001/002")
                devices[device.deviceName] = device
                setUsbEnabled(true)
            } catch (failure: Throwable) {
                try { close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }

        fun newProcess() {
            app = mock()
            whenever(app.applicationContext).thenReturn(app)
            whenever(app.contentResolver).thenReturn(mock<ContentResolver>())
            val user = mock<UserManager>()
            whenever(user.isUserUnlocked).thenAnswer { unlocked }
            whenever(app.getSystemService(Context.USER_SERVICE)).thenReturn(user)
            val usb = mock<UsbManager>()
            whenever(usb.deviceList).thenReturn(devices)
            whenever(app.getSystemService(Context.USB_SERVICE)).thenReturn(usb)
            val deviceContext = mock<Context>()
            whenever(app.createDeviceProtectedStorageContext()).thenReturn(deviceContext)
            whenever(deviceContext.getSharedPreferences(any(), any())).thenAnswer {
                devicePrefs.getOrPut(it.getArgument(0)) { MemoryPrefs() }.prefs
            }
            whenever(app.getSharedPreferences(any(), any())).thenAnswer {
                credentialReads++
                check(unlocked) { "Credential storage is locked" }
                credentials.prefs
            }
            val manager = mock<CommManager>()
            whenever(manager.usbRecheckNow()).thenAnswer { UsbRecheck(ticket, 0).also(scans::add) }
            val component = mock<AppComponent>()
            whenever(component.commManager).thenReturn(manager)
            whenever(component.suExecutor).thenReturn(mock())
            App::class.java.getDeclaredField("component\$delegate").apply { isAccessible = true }
                .set(app, lazy {
                    check(unlocked) { "App component initialized before unlock" }
                    componentReads++
                    component
                })
        }

        fun armLockedAttach() {
            check(!unlocked)
            devicePrefs.getOrPut("usb-unlock") { MemoryPrefs() }.values["pending-boot"] = boot
            assertTrue(starts.isEmpty())
            assertTrue(scans.isEmpty())
            assertEquals(0, componentReads)
            assertEquals(0, credentialReads)
        }

        fun setUsbEnabled(enabled: Boolean) {
            devicePrefs.getOrPut("settings_device_protected") { MemoryPrefs() }.values["auto-start-on-usb"] = enabled
        }

        fun enableBoot() {
            devicePrefs.getOrPut("settings_device_protected") { MemoryPrefs() }.values["auto-start-on-boot"] = true
            credentials.values["auto-start-on-boot"] = true
        }

        fun blacklistPhone() {
            val blacklist = setOf(UsbDeviceCompat.blacklistKey(devices.values.single()))
            devicePrefs.getOrPut("settings_device_protected") { MemoryPrefs() }.values[Settings.KEY_USB_BLACKLIST] = blacklist
            credentials.values[Settings.KEY_USB_BLACKLIST] = blacklist
        }

        fun unlock(replaceProcess: Boolean = false) {
            unlocked = true
            ticket = 47L
            if (replaceProcess) newProcess()
            App::class.java.getDeclaredMethod("initUnlockedOnce").apply { isAccessible = true }.invoke(app)
        }

        fun bootCompleted() {
            val receiver = mock<BootCompleteReceiver>()
            doCallRealMethod().whenever(receiver).onReceive(any(), any())
            foregroundStartAllowed = true
            try { receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED)) }
            finally { foregroundStartAllowed = false }
        }

        fun assertOneScan(message: String, expectedTicket: Long = 47L) {
            assertEquals(message, 1, starts.size)
            assertEquals("unlock must mint exactly one owed scan", 1, scans.size)
            assertEquals("USER_UNLOCKED must not attempt a non-exempt service start", 0, rejectedStarts)
            assertEquals("unlock completion must use its current input authority", expectedTicket, scans.single().ticket)
        }

        private fun replaceStatic(type: Class<*>, name: String, value: Any?) {
            val field = type.getDeclaredField(name).apply { isAccessible = true }
            val previous = field.get(null)
            resources += AutoCloseable { writeStatic(field, previous) }
            writeStatic(field, value)
        }

        private fun writeStatic(field: Field, value: Any?) {
            if (!Modifier.isFinal(field.modifiers)) { field.set(null, value); return }
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val base = unsafeClass.getMethod("staticFieldBase", Field::class.java).invoke(unsafe, field)
            val offset = unsafeClass.getMethod("staticFieldOffset", Field::class.java).invoke(unsafe, field)
            unsafeClass.getMethod("putIntVolatile", Any::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(unsafe, base, offset, value)
        }

        override fun close() {
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

    @Test fun `locked accessory resumes at first unlock in the same process`() {
        Fixture().use { f ->
            f.armLockedAttach()
            f.unlock()
            assertTrue("USER_UNLOCKED prepares the pending input without foreground startup", f.starts.isEmpty())
            assertTrue(f.scans.isEmpty())
            f.bootCompleted()
            f.assertOneScan("first unlock must enqueue one USB scan")
            f.bootCompleted()
            f.unlock()
            assertEquals("repeated unlock must keep the one accepted startup", 1, f.starts.size)
            assertEquals("repeated unlock must not mint another owed scan", 1, f.scans.size)
            f.bootCompleted()
            f.assertOneScan("BOOT_COMPLETED and repeated unlock must not duplicate USB")
            assertEquals(AapService.ACTION_CHECK_USB, f.starts.single().action)
        }
    }

    @Test fun `locked accessory resumes when the process was replaced before unlock`() {
        Fixture().use { f ->
            f.armLockedAttach()
            f.unlock(replaceProcess = true)
            assertTrue(f.starts.isEmpty())
            assertTrue(f.scans.isEmpty())
            f.bootCompleted()
            f.assertOneScan("replacement process must resume the persisted locked USB input")
            f.newProcess()
            f.bootCompleted()
            f.assertOneScan("a later process must not replay a consumed USB input")
        }
    }

    @Test fun `unlock USB and boot auto-start use one scan and one boot strike`() {
        Fixture().use { f ->
            f.enableBoot()
            f.armLockedAttach()
            f.unlock()
            assertTrue("USER_UNLOCKED prepares the pending input without foreground startup", f.starts.isEmpty())
            assertTrue(f.scans.isEmpty())
            f.bootCompleted()
            f.assertOneScan("unlock must share the boot auto-start scan")
            assertTrue(f.starts.single().getBooleanExtra(BootCompleteReceiver.EXTRA_BOOT_START, false))
            f.bootCompleted()
            f.newProcess()
            f.bootCompleted()
            f.assertOneScan("boot broadcast must not enqueue a second unlock scan")
            assertEquals(1, Settings.getBootLoopStrikes(f.app))
        }
    }

    @Test fun `prepared unlock survives replacement before boot and captures current completion authority`() {
        Fixture().use { f ->
            f.armLockedAttach()
            f.unlock()
            assertTrue(f.starts.isEmpty())
            assertTrue(f.scans.isEmpty())
            assertEquals(0, f.rejectedStarts)
            f.unlock(replaceProcess = true)
            f.ticket = 71L
            f.bootCompleted()
            f.assertOneScan("prepared unlock must survive a second process", expectedTicket = 71L)
        }
    }

    @Test fun `disabling USB before unlock consumes without starting`() {
        Fixture().use { f ->
            f.armLockedAttach()
            f.setUsbEnabled(false)
            f.unlock()
            f.bootCompleted()
            assertTrue(f.starts.isEmpty())
            assertTrue(f.scans.isEmpty())
        }
    }

    @Test fun `absent or blacklisted phone is not resumed at unlock`() {
        for (blacklisted in listOf(false, true)) Fixture().use { f ->
            f.armLockedAttach()
            if (blacklisted) f.blacklistPhone() else f.devices.clear()
            f.unlock()
            f.bootCompleted()
            assertTrue("no connectable pending phone must mean no startup", f.starts.isEmpty())
            assertTrue(f.scans.isEmpty())
        }
    }

    @Test fun `pending unlock preserves a persisted Stop in surviving and replacement processes`() {
        for (replaceProcess in listOf(false, true)) Fixture().use { f ->
            f.armLockedAttach()
            assertTrue(UsbReceiver.allowsAutomaticInput(f.app))
            UsbReceiver.holdAfterUserStop(f.app, UsbReceiver.bootCount(f.app))
            f.unlock(replaceProcess)
            f.bootCompleted()
            assertTrue("unlock must not bypass a persisted USB Stop", f.starts.isEmpty())
            assertTrue(f.scans.isEmpty())
            assertTrue("unlock must not release the user's USB Stop", UsbReceiver.isUserStopped(f.app))
        }
    }

    @Test fun `an earlier boot pending input is not resumed`() {
        Fixture().use { f ->
            f.armLockedAttach()
            f.boot++
            f.unlock(replaceProcess = true)
            f.bootCompleted()
            assertTrue(f.starts.isEmpty())
            assertTrue(f.scans.isEmpty())
        }
    }
}
