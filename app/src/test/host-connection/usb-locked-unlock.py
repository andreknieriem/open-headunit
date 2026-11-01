#!/usr/bin/env python3
"""Run the real locked attach prefix, App unlocked initializer and boot receiver on the JVM.

The attach extraction stops immediately before the AOA-switch body (a platform effect). The
accessory permission/locked-return branches and all pending-input/boot decisions are unchanged.
Pass an unchanged production tree to obtain the failing-before assertions; no old-source adapters
or replacement guards are used. Android storage, USB and service starts are recording boundaries.
"""
from pathlib import Path
import hashlib, json, os, re, subprocess, sys

root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[4]
base = root / 'app/src/main/java/com/andrerinas/openheadunit'
out = root / 'build/host-connection/usb-locked-unlock'
out.mkdir(parents=True, exist_ok=True)
for previous in out.glob('*.kt'):
    previous.unlink()
hashes = {}


def member(source, name):
    start = source.index(name)
    opening = source.index('{', start)
    depth = 0
    for end in range(opening, len(source)):
        if source[end] == '{': depth += 1
        elif source[end] == '}':
            depth -= 1
            if depth == 0:
                result = source[start:end + 1]
                hashes[name] = hashlib.sha256(result.encode()).hexdigest()
                return result
    raise ValueError(name)


boot = (base / 'app/BootCompleteReceiver.kt').read_text()
hashes['BootCompleteReceiver.kt'] = hashlib.sha256(boot.encode()).hexdigest()
(out / 'BootCompleteReceiver.kt').write_text(boot)
app = (base / 'App.kt').read_text()
init = member(app, 'private fun initUnlockedOnce()')
unlock_receiver = member(app, 'private val userUnlockedReceiver')
(out / 'App.kt').write_text('''package com.andrerinas.openheadunit
import android.content.*
import android.os.*
import com.andrerinas.openheadunit.app.BootCompleteReceiver
import com.andrerinas.openheadunit.utils.*
import kotlinx.coroutines.*
class App(state: State) : Context(state) {
    private var unlockedInitDone = false
    private val component by lazy { state.componentReads++; check(state.unlocked) { "CE component while locked" }; AppComponent(state) }
    fun coldUnlockedStart() { initUnlockedOnce() }
    fun deliverUnlock() { userUnlockedReceiver.onReceive(this, Intent(Intent.ACTION_USER_UNLOCKED)) }
    companion object {
        fun isUserUnlocked(context: Context) = context.state.unlocked
        fun provide(context: Context) = context.state.app.component
    }
''' + init + '\n' + unlock_receiver + '''
}
class AppComponent(state: State) { val suExecutor = SUExecutor(); val commManager = CommManager(state) }
class SUExecutor { fun register() {} }
class CommManager(val state: State) {
    class ConnectionState { class TransportStarted }
    val connectionState = StateValue(Any())
    fun usbRecheckNow(): UsbRecheck { check(state.unlocked); state.minted++; return UsbRecheck(state.ticket) }
}
class StateValue<T>(var value:T)
data class UsbRecheck(val ticket: Long)
''')
attach = (base / 'app/UsbAttachedActivity.kt').read_text()
create = member(attach, 'override fun onCreate(savedInstanceState: Bundle?)')
create = create[:create.index('        val deviceCompat = UsbDeviceCompat(device)')] + '\n    }'
(out / 'UsbAttachedActivity.kt').write_text('''package com.andrerinas.openheadunit.app
import android.content.*
import android.os.*
import android.hardware.usb.*
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.*
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.utils.*
import com.andrerinas.openheadunit.connection.usb.*
class UsbAttachedActivity(state: State) : Activity(state) {
    private fun resolveUsbDevice(intent: Intent?) = state.devices.values.firstOrNull()
    private fun handOffToService() { error("unexpected settings handoff") }
''' + create + '''
}
object AutoConnectHoldPolicy { fun raisesUi(visible:Boolean,cancelled:Boolean)=true }
object SettingsActivity { val isVisible=false }
''')
(out / 'Content.kt').write_text('''package android.content
import com.andrerinas.openheadunit.App
import android.hardware.usb.*
class State {
    var unlocked=false; var ticket=11L; var boot=101; var componentReads=0; var credentialReads=0
    var minted=0; var permissionRequests=0; var permitted=true; var blacklisted=false
    var foregroundStartAllowed=false; var rejectedStarts=0
    var beforeBlacklist:(()->Unit)?=null
    val prefs=mutableMapOf<String,SharedPreferences>(); val devices=hashMapOf<String,UsbDevice>()
    val starts=mutableListOf<Intent>(); lateinit var app: App
    fun process(unlocked:Boolean):App { this.unlocked=unlocked; return App(this).also { app=it } }
}
open class Context(val state:State, private val deviceProtected:Boolean=false) {
    val contentResolver get()=ContentResolver(state)
    val applicationContext get()=state.app
    fun createDeviceProtectedStorageContext()=Context(state,true)
    fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
        if(!deviceProtected){state.credentialReads++;check(state.unlocked){"Credential storage is locked"}}
        return state.prefs.getOrPut((if(deviceProtected) "dp:" else "ce:")+name){SharedPreferences()}
    }
    fun unregisterReceiver(receiver:BroadcastReceiver){}
    companion object { const val MODE_PRIVATE=0 }
}
open class Activity(state:State):Context(state) {
    val intent=Intent("usb-attached")
    open fun onCreate(savedInstanceState:android.os.Bundle?){}
    fun finish(){}
}
class ContentResolver(val state:State)
abstract class BroadcastReceiver { abstract fun onReceive(context:Context?,intent:Intent?) }
class SharedPreferences {
    val values=mutableMapOf<String,Any?>()
    fun contains(key:String)=values.containsKey(key)
    fun getInt(key:String,default:Int)=values[key] as? Int ?: default
    fun getLong(key:String,default:Long)=values[key] as? Long ?: default
    fun getBoolean(key:String,default:Boolean)=values[key] as? Boolean ?: default
    fun getString(key:String,default:String?)=values[key] as? String ?: default
    fun getStringSet(key:String,default:Set<String>?)=values[key] as? Set<String> ?: default
    fun edit()=Editor(this)
    class Editor(val prefs:SharedPreferences) {
        fun putInt(key:String,value:Int)=apply {prefs.values[key]=value}
        fun putLong(key:String,value:Long)=apply {prefs.values[key]=value}
        fun clear()=apply {prefs.values.clear()}
        fun putBoolean(key:String,value:Boolean)=apply {prefs.values[key]=value}
        fun putString(key:String,value:String)=apply {prefs.values[key]=value}
        fun putStringSet(key:String,value:Set<String>)=apply {prefs.values[key]=value}
        fun remove(key:String)=apply {prefs.values.remove(key)}
        fun commit()=true
        fun apply(){}
    }
}
class Intent(var action:String?) {
    val extras=mutableMapOf<String,Any>()
    var ticket:Long?=null
    fun putExtra(name:String,value:Boolean)=apply {extras[name]=value}
    companion object {
        const val ACTION_BOOT_COMPLETED="boot-completed"
        const val ACTION_LOCKED_BOOT_COMPLETED="locked-boot-completed"
        const val ACTION_USER_UNLOCKED="user-unlocked"
    }
}
''')
# Android's nullable BroadcastReceiver signatures are flexible platform types. Use equivalent
# non-null arguments for the boot receiver, nullable ones for App's dynamically registered receiver.
boot = boot.replace('override fun onReceive(context: Context, intent: Intent)',
                    'override fun onReceive(context: Context?, intent: Intent?)')
boot = boot.replace('        val action = intent.action',
                    '        val context = requireNotNull(context)\n        val intent = requireNotNull(intent)\n        val action = intent.action')
(out / 'BootCompleteReceiver.kt').write_text(boot)
(out / 'Os.kt').write_text('''package android.os
object Build { object VERSION { var SDK_INT=35 }; object VERSION_CODES { const val N=24 } }
object SystemClock {var now=1000L;fun elapsedRealtime()=now}
class Bundle
''')
(out / 'AndroidSettings.kt').write_text('''package android.provider
object Settings { object Global {
    const val BOOT_COUNT="boot_count"
    fun getInt(resolver:android.content.ContentResolver,name:String,default:Int)=resolver.state.boot
} }
''')
(out / 'ContextCompat.kt').write_text('''package androidx.core.content
object ContextCompat {
    fun startForegroundService(context:android.content.Context,intent:android.content.Intent) {
        check(context.state.unlocked) { "Service started before unlock" }
        if(!context.state.foregroundStartAllowed) {
            context.state.rejectedStarts++
            throw IllegalStateException("USER_UNLOCKED has no foreground-service exemption")
        }
        context.state.starts += intent
    }
}
''')
(out / 'Main.kt').write_text('''package com.andrerinas.openheadunit.main
object FloatingButtonManager { fun update(context:android.content.Context){} }
''')
(out / 'WifiScan.kt').write_text('''package com.andrerinas.openheadunit.connection.wifi.scan
object WifiScanControl { fun initialize(context:android.content.Context){} }
''')
(out / 'Service.kt').write_text('''package com.andrerinas.openheadunit.aap
import android.content.*
import com.andrerinas.openheadunit.UsbRecheck
class AapService {
    fun usbCancelledByUser()=false
    companion object {
        var instance:AapService?=null
        const val ACTION_CHECK_USB="check-usb"
        fun usbCheckIntent(context:Context,recheck:UsbRecheck,action:String?=ACTION_CHECK_USB)=Intent(action).apply { ticket=recheck.ticket }
    }
}
''')
(out / 'Usb.kt').write_text('''package android.hardware.usb
class UsbDevice(val accessory:Boolean=true) {val vendorId=0x18d1;val productId=if(accessory)0x2d00 else 0x4ee7;val deviceName="fixture"}
class UsbManager(private val state:android.content.State) {
    val deviceList get()=state.devices
    fun hasPermission(device:UsbDevice)=state.permitted
    fun requestPermission(device:UsbDevice,intent:Any){state.permissionRequests++}
}
''')
usb_receiver_source = (base / 'connection/usb/UsbReceiver.kt').read_text()
hold_source = ''
if 'internal fun allowsAutomaticInput(' in usb_receiver_source:
    hold_source = usb_receiver_source.split('    companion object {', 1)[1].split('        const val ACTION_USB_DEVICE_PERMISSION', 1)[0]
    hashes['USB hold companion'] = hashlib.sha256(hold_source.encode()).hexdigest()
(out / 'UsbCompat.kt').write_text('''package com.andrerinas.openheadunit.connection.usb
import android.content.Context
import android.hardware.usb.*
import android.content.SharedPreferences
import android.os.Build
import android.os.SystemClock
class UsbDeviceCompat(device:UsbDevice) {
    companion object {
        fun usbManager(context:Context):UsbManager?=UsbManager(context.state)
        fun isAndroidDevice(device:UsbDevice)=true
        fun isInAccessoryMode(device:UsbDevice)=device.accessory
        fun isConnectable(context:Context,device:UsbDevice)=!context.state.blacklisted
    }
}
object UsbReceiver {fun createPermissionPendingIntent(context:Context,recheck:Any?)=Any()
''' + hold_source + '}\n')
settings = (base / 'utils/Settings.kt').read_text()
settings_members = '\n'.join(member(settings, name) for name in [
    'fun isAutoStartOnBootEnabled(', 'fun isAutoStartOnScreenOnEnabled(',
    'fun isAutoStartOnUsbEnabled(', 'fun isAutoStartOnWifiEnabled(',
    'fun syncAutoStartOnBootToDeviceStorage(', 'fun syncAutoStartOnUsbToDeviceStorage(',
    'fun syncAutoStartOnWifiToDeviceStorage(', 'fun syncAutoStartWifiSsidToDeviceStorage(',
    'fun syncAutoStartBtMacsToDeviceStorage(', 'fun syncUsbBlacklistToDeviceStorage(',
])
(out / 'Settings.kt').write_text('''package com.andrerinas.openheadunit.utils
import android.content.*
import android.os.Build
class Settings(private val context:Context) {
    private val prefs get()=context.getSharedPreferences(PREFS_NAME,0)
    val autoStartOnBoot get()=prefs.getBoolean("auto-start-on-boot",false)
    val autoStartOnUsb get()=prefs.getBoolean("auto-start-on-usb",false)
    val autoStartOnWifi get()=false
    val autoStartWifiSsid get()=""
    val autoStartBluetoothDeviceMacs get()=emptySet<String>()
    val usbBlacklist get()=emptySet<String>()
    val enableFloatingButton get()=false
    val autoKillOemApps get()=false
    companion object {
        const val PREFS_NAME="settings";private const val DEVICE_PREFS_NAME="settings_device_protected"
        private const val KEY_AUTO_START_ON_BOOT="auto-start-on-boot"
        private const val KEY_AUTO_START_ON_SCREEN_ON="auto-start-on-screen-on"
        private const val KEY_AUTO_START_ON_USB="auto-start-on-usb"
        private const val KEY_AUTO_START_ON_WIFI="auto-start-on-wifi"
        private const val KEY_AUTO_START_WIFI_SSID="auto-start-wifi-ssid"
        private const val KEY_AUTO_START_BT_MACS="auto-start-bt-macs"
        const val KEY_USB_BLACKLIST="usb-blacklist"
        fun getBootLoopStrikes(context:Context)=context.createDeviceProtectedStorageContext().getSharedPreferences(DEVICE_PREFS_NAME,0).getInt("strikes",0)
        fun setBootLoopStrikes(context:Context,value:Int){context.createDeviceProtectedStorageContext().getSharedPreferences(DEVICE_PREFS_NAME,0).edit().putInt("strikes",value).apply()}
        fun isUsbDeviceBlacklisted(context:Context,device:android.hardware.usb.UsbDevice):Boolean { context.state.beforeBlacklist?.invoke(); return context.state.blacklisted }
        fun formatUsbVidPidDisplay(vid:Int,pid:Int)="usb"
''' + settings_members + '''
    }
}
object AppLog { fun i(s:String){};fun w(s:String){};fun e(s:String){};fun init(settings:Settings,context:Context){} }
object AppThemeManager {fun reapply(context:Context,settings:Settings){} }
object OemAppManager {fun runAutoKillIfEnabled(context:Context){} }
''')
(out / 'BootLoopPolicy.kt').write_text((base / 'app/BootLoopPolicy.kt').read_text())
probe = '''package lockedunlock
import android.content.*
import android.hardware.usb.*
import com.andrerinas.openheadunit.*
import com.andrerinas.openheadunit.app.*
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.connection.usb.UsbReceiver

private fun fixture(usb:Boolean=true,boot:Boolean=false):State = State().also { s ->
    val app=s.process(false)
    app.createDeviceProtectedStorageContext().getSharedPreferences("settings_device_protected",0).edit()
        .putBoolean("auto-start-on-usb",usb).putBoolean("auto-start-on-boot",boot).apply()
    s.prefs["ce:settings"]=SharedPreferences().apply { values["auto-start-on-boot"]=boot }
}
private fun State.attach(accessory:Boolean=true,permitted:Boolean=true) {
    this.permitted=permitted;devices["phone"]=UsbDevice(accessory)
    UsbAttachedActivity(this).onCreate(null)
    check(starts.isEmpty()) { "locked attach started a service" }
    check(componentReads==0 && credentialReads==0 && minted==0) { "locked attach accessed CE or minted app authority" }
}
private fun State.unlock(replaced:Boolean=false) {
    ticket=47L
    if(replaced)process(true).coldUnlockedStart() else {unlocked=true;app.deliverUnlock()}
}
private fun State.bootComplete() {
    foregroundStartAllowed=true
    try {BootCompleteReceiver().onReceive(app,Intent(Intent.ACTION_BOOT_COMPLETED))} finally {foregroundStartAllowed=false}
}
private fun State.assertScan(message:String,expectedTicket:Long=47L) {
    check(starts.size==1) { "$message: expected 1 USB scan, got ${starts.size}" }
    check(rejectedStarts==0) { "USER_UNLOCKED attempted a non-exempt foreground service start" }
    check(minted==1 && starts.single().ticket==expectedTicket) { "unlock must mint exactly its current authority" }
}
fun main() {
    var failed=0;var passed=0
    fun test(name:String,block:()->Unit) {try {block();passed++;println("PASS $name")}catch(t:Throwable){failed++;println("FAIL $name: ${t.message}")}}
    test("locked normal then accessory connects at first unlock") {
        val s=fixture();s.attach(false);s.attach();s.unlock();s.bootComplete();s.assertScan("First unlock after locked AOA switch")
        s.bootComplete();s.assertScan("BOOT_COMPLETED duplicated the unlock scan")
    }
    test("unlock during locked attach does not lose the pending input") {
        val s=fixture();s.beforeBlacklist={s.unlocked=true;s.beforeBlacklist=null}
        s.attach();s.unlock();s.bootComplete();s.assertScan("Unlock racing the locked attach")
        s.bootComplete();s.assertScan("BOOT_COMPLETED duplicated the raced unlock")
    }
    test("locked accessory connects in surviving process") {
        val s=fixture();s.attach();s.unlock();s.bootComplete();s.assertScan("First unlock after accessory attach")
        s.app.deliverUnlock();s.bootComplete();s.assertScan("Repeated unlock duplicated the scan")
    }
    test("locked accessory connects in replacement process") {
        val s=fixture();s.attach();s.unlock(true);s.bootComplete();s.assertScan("Replacement process at first unlock")
        s.process(true).coldUnlockedStart();s.bootComplete();s.assertScan("Process replacement duplicated the scan")
    }
    test("unlock USB and boot startup share one scan") {
        val s=fixture(boot=true);s.attach();s.unlock();s.bootComplete();s.assertScan("Unlock with boot auto-start")
        check(s.starts.single().extras[BootCompleteReceiver.EXTRA_BOOT_START]==true) { "boot startup lost its marker" }
        s.bootComplete();s.process(true).coldUnlockedStart();s.bootComplete()
        s.assertScan("Boot auto-start duplicated the unlock scan")
        check(Settings.getBootLoopStrikes(s.app)==1) { "one boot counted more than one strike" }
    }
    test("locked permission reply can wait for unlock without CE access") {
        val s=fixture();s.attach(permitted=false);check(s.permissionRequests==1)
        s.permitted=true;s.unlock();s.bootComplete();s.assertScan("Granted accessory at first unlock")
    }
    test("unlock preparation survives another process replacement before boot delivery") {
        val s=fixture();s.attach();s.unlock()
        check(s.starts.isEmpty() && s.minted==0 && s.rejectedStarts==0) { "USER_UNLOCKED attempted foreground startup without an exemption" }
        s.process(true).coldUnlockedStart();s.ticket=71L;s.bootComplete()
        s.assertScan("Replacement after unlock preparation",expectedTicket=71L)
    }
    test("USB auto-start off does not resume locked accessory") {
        val s=fixture(usb=false);s.attach();s.unlock();s.bootComplete()
        check(s.starts.isEmpty() && s.minted==0) { "disabled USB auto-start enqueued a scan" }
    }
    test("USB auto-start disabled before unlock withdraws pending input") {
        val s=fixture();s.attach()
        s.app.createDeviceProtectedStorageContext().getSharedPreferences("settings_device_protected",0).edit().putBoolean("auto-start-on-usb",false).apply()
        s.unlock();s.bootComplete();check(s.starts.isEmpty() && s.minted==0) { "disabled pending USB input was resumed" }
    }
    test("phone gone before unlock does not start USB") {
        val s=fixture();s.attach();s.devices.clear();s.unlock();s.bootComplete()
        check(s.starts.isEmpty() && s.minted==0) { "absent phone enqueued a USB scan" }
    }
    test("phone blacklisted before unlock does not start USB") {
        val s=fixture();s.attach();s.blacklisted=true;s.unlock();s.bootComplete()
        check(s.starts.isEmpty() && s.minted==0) { "blacklisted phone enqueued a USB scan" }
    }
    test("pending USB from an earlier boot is not replayed") {
        val s=fixture();s.attach();s.boot++;s.unlock(true);s.bootComplete()
        check(s.starts.isEmpty() && s.minted==0) { "earlier boot USB input was replayed" }
    }
    // USB_HOLD_CONTROLS
    println("RESULT locked USB unlock: $passed passed, $failed failed")
    check(failed==0) { "$failed locked USB unlock regressions failed" }
}
'''
if hold_source:
    probe = probe.replace('// USB_HOLD_CONTROLS', '''test("pending unlock preserves persisted Stop in surviving and replacement processes") {
        for(replaced in listOf(false,true)) {
            val s=fixture();s.attach()
            check(UsbReceiver.allowsAutomaticInput(s.app))
            UsbReceiver.holdAfterUserStop(s.app,UsbReceiver.bootCount(s.app))
            s.unlock(replaced);s.bootComplete()
            check(s.starts.isEmpty() && s.minted==0) { "unlock bypassed the persisted USB Stop (replaced=$replaced)" }
            check(UsbReceiver.isUserStopped(s.app)) { "unlock released the persisted USB Stop" }
        }
    }
    test("held locked attach records no pending unlock input") {
        val s=fixture();s.devices["phone"]=UsbDevice()
        check(UsbReceiver.allowsAutomaticInput(s.app))
        UsbReceiver.holdAfterUserStop(s.app,UsbReceiver.bootCount(s.app));s.attach()
        check(s.prefs["dp:usb-unlock"]?.contains("pending-boot")!=true) { "held attach recorded a new unlock obligation" }
        s.unlock();s.bootComplete()
        check(s.starts.isEmpty() && s.minted==0) { "held attach resumed at unlock" }
    }''')
(out / 'Probe.kt').write_text(probe)
(out / 'extracted-method-sha256.json').write_text(json.dumps(hashes, indent=2))
cache = Path(os.environ.get('GRADLE_USER_HOME', str(Path.home() / '.gradle'))) / 'caches/modules-2/files-2.1'


def cached(group, artifact, version):
    return next((cache / group / artifact).glob(version + '/*/' + artifact + '-' + version + '.jar'))


meta = root / 'build/host-connection/toolchain.json'
if meta.exists():
    jars = [Path(j['path']) for j in json.loads(meta.read_text())['jars']]
else:
    jars = [cached(g, a, v) for g, a, v in [
        ('org.jetbrains.kotlin', 'kotlin-compiler-embeddable', '2.4.20'),
        ('org.jetbrains.kotlin', 'kotlin-stdlib', '2.4.20'),
        ('org.jetbrains.kotlin', 'kotlin-reflect', '1.6.10'),
        ('org.jetbrains.kotlin', 'kotlin-script-runtime', '2.4.20'),
        ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.8.0'),
        ('org.jetbrains', 'annotations', '13.0')]]
compiler = os.pathsep.join(map(str, jars))
runtime = os.pathsep.join(str(j) for j in jars if any(x in j.name for x in ('stdlib', 'coroutines', 'annotations')))
subprocess.run(['java', '-cp', compiler, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-nowarn', '-classpath', runtime, '-d', str(out / 'probe.jar'),
                *map(str, sorted(out.glob('*.kt')))], check=True, timeout=45)
subprocess.run(['java', '-cp', str(out / 'probe.jar') + os.pathsep + runtime, 'lockedunlock.ProbeKt'], check=True, timeout=35)
