package com.andrerinas.openheadunit.connection.usb

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings.Global
import android.provider.Settings.SettingNotFoundException
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.app.AppUiEntry
import com.andrerinas.openheadunit.app.AppForegroundPolicy
import com.andrerinas.openheadunit.connection.AppExitPolicy
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.ConnectionArbiter
import com.andrerinas.openheadunit.connection.ConnectionOperation
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Tier
import com.andrerinas.openheadunit.connection.UsbRecheck
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.main.SettingsActivity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowPausedSystemClock
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import kotlin.coroutines.CoroutineContext

/** Real Stop/Exit inputs, receiver persistence and launcher entries; Android is a recording edge. */
@RunWith(RobolectricTestRunner::class)
// Select N+ before production classes load; a later write to final SDK_INT is not JIT-safe.
@Config(application = Application::class, sdk = [35])
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class UsbUserStopHoldTest {
    private object Queued : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }

    private class Fixture : AutoCloseable {
        private val cleanup = mutableListOf<AutoCloseable>()
        private val disk = mutableMapOf<String, MutableMap<String, Any>>()
        private val oldLogger = AppLog.LOGGER
        private val oldArbiterClock = ConnectionArbiter.clock
        private val oldArbiterActions = ConnectionArbiter.actions
        private val scopes = mutableListOf<CoroutineScope>()
        private val contentResolver = RuntimeEnvironment.getApplication().contentResolver
        var now: Long
            get() = SystemClock.elapsedRealtime()
            set(value) {
                // PAUSED time only advances. Reset its epoch for the simulated reboot/rollback;
                // committed USB preferences deliberately survive this clock-only reset.
                if (value < SystemClock.elapsedRealtime()) ShadowPausedSystemClock.reset()
                check(SystemClock.setCurrentTimeMillis(value)) { "Could not set the fixture elapsed clock" }
                check(SystemClock.elapsedRealtime() == value) { "Fixture elapsed clock did not reach $value" }
            }
        var boot: Int
            get() = Global.getInt(contentResolver, Global.BOOT_COUNT, -1)
            set(value) {
                check(Global.putInt(contentResolver, Global.BOOT_COUNT, value)) { "Could not set the fixture boot count" }
            }
        var unlocked = true
        var credentialReads = 0
        var admissions = 0
        var raises = 0
        val usb = mock<UsbManager>()
        var bus = hashMapOf<String, UsbDevice>()
        lateinit var service: AapService
        lateinit var comm: CommManager
        lateinit var launcher: UsbLauncherManager
        lateinit var settings: Settings
        lateinit var states: MutableStateFlow<CommManager.ConnectionState>
        lateinit var receiver: UsbReceiver

        init {
            try {
                val previousTime = now
                // ShadowGlobal keeps integer and string values distinct; preserve either form.
                val previousBootText = Global.getString(contentResolver, Global.BOOT_COUNT)
                val previousBoot = try {
                    Global.getInt(contentResolver, Global.BOOT_COUNT)
                } catch (_: SettingNotFoundException) {
                    null
                }
                cleanup += AutoCloseable {
                    if (previousBoot != null) Global.putInt(contentResolver, Global.BOOT_COUNT, previousBoot)
                    else Global.putString(contentResolver, Global.BOOT_COUNT, previousBootText)
                    now = previousTime
                }
                now = 1000L
                boot = 7
                check(Looper.myLooper() === Looper.getMainLooper()) { "USB fixture must run on the main looper" }
                val oldSettingsVisible = SettingsActivity.isVisible
                SettingsActivity.isVisible = false
                cleanup += AutoCloseable { SettingsActivity.isVisible = oldSettingsVisible }
                val policyField = AppUiEntry::class.java.getDeclaredField("policy").apply { isAccessible = true }
                val oldPolicy = policyField.get(null)
                writeStaticObject(policyField, AppForegroundPolicy())
                cleanup += AutoCloseable { writeStaticObject(policyField, oldPolicy) }
                // Diagnostics retains bus observations process-wide; restore them so these
                // synthetic device snapshots cannot change another suite's bus-hint result.
                @Suppress("UNCHECKED_CAST")
                val signatures = UsbDeviceDiagnostics::class.java.getDeclaredField("lastSignatures")
                    .apply { isAccessible = true }.get(null) as MutableMap<String, String>
                val oldSignatures = signatures.toMap()
                cleanup += AutoCloseable { signatures.clear(); signatures.putAll(oldSignatures) }
                @Suppress("UNCHECKED_CAST")
                val scans = UsbDeviceDiagnostics::class.java.getDeclaredField("seenScans")
                    .apply { isAccessible = true }.get(null) as MutableList<Pair<Long, Set<String>>>
                val oldScans = synchronized(scans) { scans.toList() }
                cleanup += AutoCloseable { synchronized(scans) { scans.clear(); scans.addAll(oldScans) } }
                val hostReported = UsbDeviceDiagnostics::class.java.getDeclaredField("hostSupportReported")
                    .apply { isAccessible = true }.get(null) as java.util.concurrent.atomic.AtomicBoolean
                val oldHostReported = hostReported.get()
                cleanup += AutoCloseable { hostReported.set(oldHostReported) }
                AppLog.LOGGER = object : AppLog.Logger {
                    override fun println(priority: Int, tag: String, msg: String) = Unit
                }
                ConnectionArbiter.reset()
                ConnectionArbiter.actions = null
                ConnectionArbiter.clock = { now }
                whenever(usb.deviceList).thenAnswer { HashMap(bus) }
                whenever(usb.hasPermission(any<UsbDevice>())).thenReturn(true)
                newProcess()
            } catch (failure: Throwable) {
                try { close() } catch (suppressed: Throwable) { failure.addSuppressed(suppressed) }
                throw failure
            }
        }

        /** Recreate all process/service objects while retaining only the committed preference data. */
        fun newProcess() {
            val app = mock<App>()
            val component = mock<AppComponent>()
            settings = mock()
            val deviceContext = mock<Context>()
            val user = mock<UserManager>()
            val scope = CoroutineScope(SupervisorJob() + Queued).also(scopes::add)
            service = mock()
            comm = mock(defaultAnswer = Mockito.CALLS_REAL_METHODS)
            states = MutableStateFlow(CommManager.ConnectionState.Disconnected())
            whenever(user.isUserUnlocked).thenAnswer { unlocked }
            whenever(app.applicationContext).thenReturn(app)
            whenever(service.applicationContext).thenReturn(app)
            whenever(app.contentResolver).thenReturn(contentResolver)
            whenever(service.contentResolver).thenReturn(contentResolver)
            whenever(deviceContext.contentResolver).thenReturn(contentResolver)
            whenever(service.getSystemService(Context.USER_SERVICE)).thenReturn(user)
            whenever(app.getSystemService(Context.USER_SERVICE)).thenReturn(user)
            whenever(service.getSystemService(Context.USB_SERVICE)).thenReturn(usb)
            whenever(service.packageManager).thenReturn(mock<android.content.pm.PackageManager>())
            whenever(app.getSystemService(Context.USB_SERVICE)).thenReturn(usb)
            whenever(service.createDeviceProtectedStorageContext()).thenReturn(deviceContext)
            whenever(app.createDeviceProtectedStorageContext()).thenReturn(deviceContext)
            whenever(deviceContext.getSharedPreferences(any(), any())).thenAnswer { call ->
                preferences(disk.getOrPut(call.getArgument(0)) { mutableMapOf() })
            }
            whenever(service.getSharedPreferences(any(), any())).thenAnswer {
                credentialReads++
                error("Credential preferences must not be read by USB hold observation")
            }
            whenever(app.getSharedPreferences(any(), any())).thenAnswer {
                credentialReads++
                error("Credential preferences must not be read by USB hold observation")
            }
            whenever(service.serviceScope).thenReturn(scope)
            whenever(settings.allowedDevices).thenReturn(emptySet())
            whenever(settings.autoStartOnUsb).thenReturn(true)
            whenever(settings.hasAcceptedDisclaimer).thenReturn(true)
            whenever(component.commManager).thenReturn(comm)
            whenever(component.settings).thenReturn(settings)
            App::class.java.getDeclaredField("component\$delegate").apply { isAccessible = true }
                .set(app, lazy {
                    check(unlocked) { "AppComponent must remain uninitialized before first unlock" }
                    component
                })
            field("context", service)
            field("settings", settings)
            field("transportLifecycleLock", Any())
            field("appExitPolicy", AppExitPolicy())
            field("appExitHandler", mock<Handler>())
            field("_connectionState", states)
            field("connectionState", states)
            field("usbRecoveryOwner", Any())
            field("_scope", scope)
            field("appEntryScope", scope)
            launcher = spy(UsbLauncherManager(service))
            // Admission is the first connection effect of the real selection path. Do not start
            // IO workers: this fixture records admission synchronously on the main test thread.
            doAnswer { admissions++; false }.whenever(launcher).beginAttempt(any(), any(), anyOrNull(), any())
            doAnswer { raises++; Unit }.whenever(service).launchMainActivityIfNeeded(any())
            receiver = UsbReceiver(UsbLauncherListener(launcher))
        }

        fun phone(accessory: Boolean = true): UsbDevice = mock<UsbDevice>().also { device ->
            whenever(device.deviceName).thenReturn(if (accessory) "/dev/bus/usb/001/003" else "/dev/bus/usb/001/006")
            whenever(device.vendorId).thenReturn(0x18d1)
            whenever(device.productId).thenReturn(if (accessory) 0x2d00 else 0x4ee7)
            if (!accessory) {
                val adb = mock<UsbInterface>()
                whenever(device.interfaceCount).thenReturn(1)
                whenever(device.getInterface(0)).thenReturn(adb)
                whenever(adb.interfaceClass).thenReturn(0xff)
                whenever(adb.interfaceSubclass).thenReturn(0x42)
                whenever(adb.interfaceProtocol).thenReturn(1)
            }
        }

        fun plug(device: UsbDevice = phone()) { bus["phone"] = device }
        fun detach() {
            val device = bus["phone"] ?: phone()
            bus.clear()
            deliver(UsbManager.ACTION_USB_DEVICE_DETACHED, device)
        }
        fun deliver(action: String, device: UsbDevice = bus["phone"] ?: phone()) {
            val intent = mock<Intent>()
            whenever(intent.action).thenReturn(action)
            whenever(intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)).thenReturn(device)
            @Suppress("DEPRECATION")
            whenever(intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)).thenReturn(device)
            receiver.onReceive(service, intent)
        }
        fun scan(manual: Boolean = false, recheck: UsbRecheck = comm.usbRecheckNow()) {
            launcher.checkAlreadyConnected(force = true, userRequested = manual, recheck = recheck)
        }
        fun stop() { assertNotNull(comm.requestExplicitAppExit(comm.appConnectionTicket(), false)) }
        fun settingsStop(ticket: Long = comm.appConnectionTicket()): AppExitPolicy.Request? {
            // Execute the accepted configuration Stop on both API shapes. Baseline has no USB
            // hold parameter; the new optional false preserves that no-hold behavior explicitly.
            val parameters = arrayOf<Class<*>>(Long::class.java, Boolean::class.java, Boolean::class.java)
            val method = CommManager::class.java.declaredMethods.single {
                // Internal names have a module suffix; generated default/lambda helpers are static.
                !Modifier.isStatic(it.modifiers) &&
                    it.name.substringBefore('$') == "requestExplicitAppExit" &&
                    it.returnType == AppExitPolicy.Request::class.java &&
                    (it.parameterTypes.contentEquals(parameters) ||
                        it.parameterTypes.contentEquals(parameters.copyOf(2)))
            }.apply { isAccessible = true }
            val args = if (method.parameterCount == 2) arrayOf<Any>(ticket, false) else arrayOf<Any>(ticket, false, false)
            return method.invoke(comm, *args) as? AppExitPolicy.Request
        }
        fun exit() {
            comm.disconnect(ConnectionOperation(comm.appConnectionTicket(), null), sendByeBye = false,
                isUserExit = true, honorKillOnDisconnect = false)
        }
        fun open() {
            val application = service.applicationContext
            val deviceContext = service.createDeviceProtectedStorageContext()
            val activity = mock<Activity>()
            whenever(activity.applicationContext).thenReturn(application)
            whenever(activity.createDeviceProtectedStorageContext()).thenReturn(deviceContext)
            val intent = mock<Intent>()
            whenever(intent.action).thenReturn(Intent.ACTION_MAIN)
            AppUiEntry.delivered(activity, intent)
            val parameters = arrayOf<Class<*>>(Long::class.java, UsbRecheck::class.java)
            val restore = Mockito.mockingDetails(comm).invocations.last {
                val method = it.method
                !Modifier.isStatic(method.modifiers) &&
                    method.name.substringBefore('$') == "restoreAppForEntry" &&
                    method.returnType == Void.TYPE &&
                    (method.parameterTypes.contentEquals(parameters) ||
                        method.parameterTypes.contentEquals(parameters.copyOf(1)))
            }
            // The old overload has only owner; the new one also carries the scan captured at Open.
            // Reflect the invocation so this regression compiles unchanged against the old tree.
            val captured = restore.arguments.getOrNull(1) as? UsbRecheck
            assertNotNull("A genuine Open must carry the held USB scan even with an existing service", captured)
            scan(manual = true, recheck = checkNotNull(captured))
        }
        private fun field(name: String, value: Any) = CommManager::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(comm, value)

        private fun preferences(data: MutableMap<String, Any>): SharedPreferences {
            val prefs = mock<SharedPreferences>()
            whenever(prefs.contains(any())).thenAnswer { data.containsKey(it.getArgument<String>(0)) }
            whenever(prefs.getBoolean(any(), any())).thenAnswer { data[it.getArgument<String>(0)] ?: it.getArgument<Boolean>(1) }
            whenever(prefs.getLong(any(), any())).thenAnswer { data[it.getArgument<String>(0)] ?: it.getArgument<Long>(1) }
            whenever(prefs.getInt(any(), any())).thenAnswer { data[it.getArgument<String>(0)] ?: it.getArgument<Int>(1) }
            whenever(prefs.getStringSet(any(), anyOrNull())).thenReturn(emptySet())
            whenever(prefs.edit()).thenAnswer {
                val pending = mutableMapOf<String, Any>()
                val removed = mutableSetOf<String>()
                var clear = false
                val editor = mock<SharedPreferences.Editor>(defaultAnswer = Mockito.RETURNS_SELF)
                fun put(key: String, value: Any) { removed.remove(key); pending[key] = value }
                doAnswer { put(it.getArgument(0), it.getArgument<Boolean>(1)); editor }.whenever(editor).putBoolean(any(), any())
                doAnswer { put(it.getArgument(0), it.getArgument<Long>(1)); editor }.whenever(editor).putLong(any(), any())
                doAnswer { put(it.getArgument(0), it.getArgument<Int>(1)); editor }.whenever(editor).putInt(any(), any())
                doAnswer {
                    val key = it.getArgument<String>(0)
                    pending.remove(key); removed.add(key); editor
                }.whenever(editor).remove(any())
                doAnswer { clear = true; editor }.whenever(editor).clear()
                doAnswer {
                    if (clear) data.clear()
                    removed.forEach(data::remove)
                    data.putAll(pending)
                    true
                }.whenever(editor).commit()
                // apply deliberately does not persist: kill-on-disconnect requires commit first.
                editor
            }
            return prefs
        }

        override fun close() {
            scopes.forEach { it.cancel() }
            ConnectionArbiter.reset()
            ConnectionArbiter.clock = oldArbiterClock
            ConnectionArbiter.actions = oldArbiterActions
            AppLog.LOGGER = oldLogger
            cleanup.asReversed().forEach { it.close() }
            cleanup.clear()
        }

        private fun writeStaticObject(field: Field, value: Any?) {
            val type = Class.forName("sun.misc.Unsafe")
            val unsafe = type.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
            val base = type.getMethod("staticFieldBase", Field::class.java).invoke(unsafe, field)
            val offset = type.getMethod("staticFieldOffset", Field::class.java).invoke(unsafe, field)
            type.getMethod("putObjectVolatile", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
                .invoke(unsafe, base, offset, value)
        }
    }

    @Test fun stopRejectsFreshAutomaticAccessoryAttachAfterReenumeration() = Fixture().use { f ->
        f.plug(); f.stop(); f.detach(); f.now += 2700; f.plug()
        f.deliver(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        assertEquals("Stop must prevent a re-enumerated accessory from admitting a session", 0, f.admissions)
        assertEquals("A held accessory attach must not raise the app", 0, f.raises)
    }

    @Test fun exitRejectsFreshNormalModeAttachAndForcedScan() = Fixture().use { f ->
        f.plug(); f.exit(); f.detach(); f.now += 2200; f.plug(f.phone(false))
        f.deliver(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        f.scan()
        assertEquals("Exit must prevent a normal-mode re-enumeration from admitting an AOA switch", 0, f.admissions)
        assertEquals("A held normal attach must not raise the app", 0, f.raises)
    }

    @Test fun stopDuringTheAoAAbsenceStillHoldsTheReturningAccessory() = Fixture().use { f ->
        f.stop(); f.now += 2700; f.plug(); f.scan()
        assertEquals("Stop during ACC START re-enumeration must hold the returning accessory", 0, f.admissions)
    }

    @Test fun committedHoldSurvivesServiceAndProcessReplacement() = Fixture().use { f ->
        f.plug(); f.stop(); f.newProcess(); f.scan()
        assertEquals("A new process must honor the committed USB Stop", 0, f.admissions)
        assertEquals(0, f.credentialReads)
    }

    @Test fun continuouslyAttachedPhoneDoesNotExpireWithStopAge() = Fixture().use { f ->
        f.plug(); f.exit(); f.now += 120_000; f.scan()
        assertEquals("Time since Exit is not evidence of USB absence", 0, f.admissions)
    }

    @Test fun nineSecondGapIsStillReenumeration() = Fixture().use { f ->
        f.plug(); f.stop(); f.detach(); f.now += 9999; f.plug(); f.scan()
        assertEquals("A USB absence below 10 seconds must keep the Stop", 0, f.admissions)
    }

    @Test fun genuineTenSecondAbsenceAdmitsTheNextPlug() = Fixture().use { f ->
        f.plug(); f.stop(); f.detach(); f.newProcess(); f.now += 10_000; f.plug(); f.scan()
        assertEquals("A genuine unplug must release the hold for the next plug", 1, f.admissions)
    }

    @Test fun manualUsbInputReleasesTheHold() = Fixture().use { f ->
        f.plug(); f.stop(); f.scan(manual = true)
        assertEquals("An explicit USB input must resume the attached phone", 1, f.admissions)
    }

    @Test fun explicitUsbInputConnectsANormalPhoneWithAutomaticSelectionDisabled() = Fixture().use { f ->
        val phone = f.phone(false)
        whenever(phone.vendorId).thenReturn(0x04e8)
        f.plug(phone)
        whenever(f.settings.autoStartOnUsb).thenReturn(false)
        whenever(f.settings.autoConnectSingleUsbDevice).thenReturn(false)
        whenever(f.settings.autoConnectLastSession).thenReturn(false)
        whenever(f.settings.allowedDevices).thenReturn(setOf("a different phone"))
        f.exit(); f.scan(manual = true)
        assertEquals("An explicit USB input must connect the chosen normal phone despite automatic selection settings", 1, f.admissions)
    }

    @Test fun automaticUsbInputStillHonorsTheConfiguredSelection() = Fixture().use { f ->
        val phone = f.phone(false)
        whenever(phone.vendorId).thenReturn(0x04e8)
        f.plug(phone)
        whenever(f.settings.autoStartOnUsb).thenReturn(false)
        whenever(f.settings.autoConnectSingleUsbDevice).thenReturn(false)
        whenever(f.settings.autoConnectLastSession).thenReturn(false)
        whenever(f.settings.allowedDevices).thenReturn(setOf("a different phone"))
        f.scan()
        assertEquals("An automatic input must still honor the configured phone selection", 0, f.admissions)
    }

    @Test fun genuineReplugAlsoReleasesTheOldStatusPillCancellation() = Fixture().use { f ->
        f.plug(); f.launcher.stopForUser(); f.exit(); f.detach(); f.now += 10_000; f.plug(); f.scan()
        assertFalse("A genuine replug must release the earlier status-pill cancellation", f.launcher.cancelledByUser)
        assertEquals(1, f.admissions)
    }

    @Test fun genuineOpenCarriesAForcedUsbInputForTheAttachedPhone() = Fixture().use { f ->
        f.plug(); f.exit(); f.open()
        assertEquals(1, f.admissions)
    }

    @Test fun aStaleManualRelayCannotReleaseANewerStop() = Fixture().use { f ->
        f.plug(); val old = f.comm.usbRecheckNow(); f.stop(); f.scan(manual = true, recheck = old); f.scan()
        assertEquals("A stale manual relay must leave the newer USB Stop intact", 0, f.admissions)
    }

    @Test fun exitAfterTheInitialAutomaticGateStillPreventsFreshUsbAdmission() = Fixture().use { f ->
        f.plug(f.phone(false)); f.scan()
        assertEquals("An ordinary initial USB scan must reach admission", 1, f.admissions)
        // The attach activity admits directly, after its initial gate. An IO peer Exit in
        // between must win even when the activity then reads the newer scan epoch.
        f.exit()
        assertNull("Exit between the activity gate and admission must prevent its AOA switch",
            f.comm.admitUsbRequest(Tier.USB, "activity USB switch", null, f.comm.usbRecheckNow()))
    }

    @Test fun usbAdmissionWithoutStopAndAfterExplicitInputStillWorks() = Fixture().use { f ->
        f.plug(f.phone(false))
        val automatic = f.comm.admitUsbRequest(Tier.USB, "ordinary USB switch", null, f.comm.usbRecheckNow())
        assertNotNull("An automatic input without Stop must still admit its USB request", automatic)
        f.comm.endRequest(checkNotNull(automatic))
        f.stop(); f.scan(manual = true)
        val explicit = f.comm.admitUsbRequest(Tier.USER, "explicit USB switch", null, f.comm.usbRecheckNow())
        assertNotNull("Explicit USB input must release the admission hold", explicit)
        f.comm.endRequest(checkNotNull(explicit))
    }

    @Test fun rebootPreservesStopAndDiscardsThePreviousBootsAbsence() = Fixture().use { f ->
        f.plug(); f.stop(); f.detach(); f.boot++; f.now = 20_000; f.newProcess(); f.plug(); f.scan()
        assertEquals("USB absence measured in another boot cannot release the Stop", 0, f.admissions)
    }

    @Test fun elapsedClockRollbackPreservesStopWhenBootCountIsUnavailable() = Fixture().use { f ->
        f.boot = -1; f.plug(); f.stop(); f.now = 100; f.newProcess(); f.scan()
        assertEquals("An elapsed-clock rollback must preserve the user's USB Stop", 0, f.admissions)
    }

    @Test fun rebootWithoutStopStillConnects() = Fixture().use { f ->
        f.plug(); f.boot++; f.now = 100; f.newProcess(); f.scan()
        assertEquals("A reboot without a preceding Stop must still connect the attached phone", 1, f.admissions)
    }

    @Test fun settingsServiceRestartDoesNotCreateAUsbHold() = Fixture().use { f ->
        f.plug(); assertNotNull(f.settingsStop()); f.scan()
        assertEquals("A Settings service restart must not create a user USB Stop", 1, f.admissions)
    }

    @Test fun settingsServiceRestartPreservesAnEarlierUserStop() = Fixture().use { f ->
        f.plug(); f.stop(); assertNotNull(f.settingsStop()); f.scan()
        assertEquals("Settings Save must preserve an earlier user USB Stop", 0, f.admissions)
    }

    @Test fun staleSettingsServiceRestartCannotReplaceAUserStop() = Fixture().use { f ->
        f.plug(); val ticket = f.comm.appConnectionTicket(); f.stop()
        assertNull("A stale Settings Save must not dispatch a new service Stop", f.settingsStop(ticket))
        f.scan()
        assertEquals("A stale Settings Save must leave the newer user USB Stop intact", 0, f.admissions)
    }

    @Test fun newerOpenScanSurvivesTheOlderPhysicalStop() = Fixture().use { f ->
        f.plug()
        val stop = checkNotNull(f.comm.requestExplicitAppExit(f.comm.appConnectionTicket(), false))
        val physical = checkNotNull(f.comm.commitAppExit(stop))
        f.comm.onAppRelaunched()
        val openScan = f.comm.usbRecheckNow()
        f.comm.disconnectForAppExit(physical)
        f.comm.completeAppExit(physical, allowProcessExit = false)
        f.scan(manual = true, recheck = openScan)
        assertEquals("A newer Open's USB input must survive older physical Stop cleanup", 1, f.admissions)
    }

    @Test fun lockedObserverReadsOnlyCommittedDeviceStorage() = Fixture().use { f ->
        f.plug(); f.stop(); f.newProcess(); f.unlocked = false
        // A manifest receiver has no service listener; use reflection so baseline compilation
        // does not depend on the added no-argument constructor.
        val noArg = UsbReceiver::class.java.constructors.firstOrNull { it.parameterCount == 0 }
        val observer = noArg?.newInstance() as? UsbReceiver ?: UsbReceiver(mock())
        f.bus.clear()
        val detached = mock<Intent>()
        whenever(detached.action).thenReturn(UsbManager.ACTION_USB_DEVICE_DETACHED)
        observer.onReceive(f.service, detached)
        f.now += 2000; f.plug()
        val attached = mock<Intent>()
        whenever(attached.action).thenReturn(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        observer.onReceive(f.service, attached)
        assertEquals("Locked observation must not read credential storage", 0, f.credentialReads)
        f.unlocked = true; f.scan()
        assertEquals("A locked process replacement must retain the USB Stop", 0, f.admissions)
    }

    @Test fun ordinarySwitchLinkLossAndSettingsRestartDoNotCreateAHold() {
        for (reason in listOf("ordinary-switch", "link-loss", "settings-restart")) Fixture().use { f ->
            f.plug(f.phone(false))
            if (reason != "ordinary-switch") f.comm.disconnect(
                ConnectionOperation(f.comm.appConnectionTicket(), null), sendByeBye = false,
                isUserExit = false, honorKillOnDisconnect = false,
                reason = if (reason == "settings-restart") CommManager.DisconnectReason.SETTINGS_RESTART
                    else CommManager.DisconnectReason.CONNECTION_ENDED)
            f.detach(); f.now += 2200; f.plug(); f.scan()
            assertEquals("$reason must allow the phone's ordinary re-enumeration", 1, f.admissions)
        }
    }
}
