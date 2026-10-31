#!/usr/bin/env python3
"""Exercise USB Stop persistence and the actual automatic input guards on a JVM.

The complete UsbReceiver (including its preference writes and bus observations), real
CommManager Stop/Exit/admission wrappers, the complete launcher selection, AppUiEntry dispatch
and Home single-USB method are compiled from the requested production tree. Activity
and listener prefixes end at their first downstream effect. Settings Save/reset/import
restart branches call the actual service Stop producer and manager acceptance. Those effects and USB
claim acquisition/permission/IO are recording boundaries. No hold
decision is copied into this fixture. The old tree needs no new-method compatibility
shims: it compiles its old receiver and old entry bodies and fails behavior assertions.
"""
from pathlib import Path
import hashlib, json, os, subprocess, sys

HERE = Path(__file__).resolve().parent
ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else HERE.parents[3]
OUT = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else ROOT / 'build/host-connection/usb-exit-hold'
HOST = Path(sys.argv[3]).resolve() if len(sys.argv) > 3 else ROOT / 'build/host-connection'
OUT.mkdir(parents=True, exist_ok=True)
base = ROOT / 'app/src/main/java/com/andrerinas/openheadunit'
hashes = {}


def member(path, declaration):
    source = (base / path).read_text()
    start = source.index(declaration)
    brace = source.index('{', start)
    depth = 0
    for end in range(brace, len(source)):
        if source[end] == '{': depth += 1
        elif source[end] == '}':
            depth -= 1
            if depth == 0:
                body = source[start:end + 1]
                hashes[path + ':' + declaration] = hashlib.sha256(body.encode()).hexdigest()
                return body
    raise ValueError(declaration)


def prefix(path, declaration, boundary, tail):
    body = member(path, declaration)
    return body[:body.index(boundary)] + tail + '\n    }'


def nested_block(path, declaration, header):
    source = member(path, declaration)
    start = source.index(header)
    brace = source.index('{', start)
    depth = 0
    for end in range(brace, len(source)):
        if source[end] == '{': depth += 1
        elif source[end] == '}':
            depth -= 1
            if depth == 0:
                return source[start:end + 1]
    raise ValueError(header)


for path in ['connection/usb/UsbReceiver.kt', 'connection/usb/UsbAttachPolicy.kt',
             'connection/AutoConnectHoldPolicy.kt', 'connection/AppExitPolicy.kt', 'connection/UsbRecheck.kt',
             'app/AppForegroundPolicy.kt', 'app/AppUiEntry.kt']:
    source = (base / path).read_bytes()
    (OUT / Path(path).name).write_bytes(source)
    hashes[path] = hashlib.sha256(source).hexdigest()

fixture = (HERE / 'UsbExitHoldFixture.kt').read_text()
inserts = {
    'STOP': member('connection/CommManager.kt', 'internal fun requestExplicitAppExit('),
    'EXIT': member('connection/CommManager.kt', 'internal fun disconnect('),
    'OPEN': member('connection/CommManager.kt', 'internal fun onAppRelaunched('),
    'RESUME': member('connection/CommManager.kt', 'internal fun acceptAppResume('),
    'DISCONNECT_PHYSICAL': member('connection/CommManager.kt', 'internal fun disconnectForAppExit('),
    'USB_ADMISSION': member('connection/CommManager.kt', 'internal fun admitUsbRequest('),
    'DISCONNECT_CURRENT': prefix('connection/CommManager.kt', 'private fun disconnectCurrent(',
        '        if (disconnectRequested ||', '        Unit'),
    'ACTIVITY_CREATE': prefix('app/UsbAttachedActivity.kt', 'override fun onCreate(',
        '        val isLocked =', '        accepted++'),
    'ACTIVITY_INTENT': prefix('app/UsbAttachedActivity.kt', 'override fun onNewIntent(',
        '        val isLocked =', '        accepted++'),
    'LISTENER_ATTACH': prefix('connection/usb/UsbLauncherListener.kt', 'override fun onUsbAttach(',
        '        val commManager =', '        accepted++'),
    'MANAGER_CHECK': member('connection/usb/UsbLauncherManager.kt', 'fun checkAlreadyConnected('),
    'HOME_SINGLE': member('main/HomeFragment.kt', 'private fun attemptSingleUsbAutoConnect('),
    'SETTINGS_SAVE': nested_block('main/SettingsFragment.kt', 'private fun saveSettings(',
        'if (requiresServiceRestart) {'),
    'MIC_SAVE': nested_block('main/MicSettingsFragment.kt', 'private fun saveSettings(',
        'if (requiresRestart) {'),
    'DARK_SAVE': nested_block('main/DarkModeFragment.kt', 'private fun saveSettings(',
        'if (requiresRestart) {'),
    'SETTINGS_RESET': nested_block('main/SettingsFragment.kt', 'private fun handleResetSettings(',
        'if (SettingsBackupManager.requiresProjectionRestart('),
    'SETTINGS_IMPORT': nested_block('main/SettingsFragment.kt', 'private fun handleImportedSettings(',
        'if (SettingsBackupManager.requiresProjectionRestart('),
}
for name, value in inserts.items():
    value = value.replace('CommManager.ConnectionState', 'Manager.ConnectionState')
    fixture = fixture.replace('// ' + name + '\n', value + '\n')
(OUT / 'Fixture.kt').write_text(fixture)

(OUT / 'Context.kt').write_text('''package android.content
import android.hardware.usb.UsbManager
import usbhold.Env
open class Context(var env: Env = Env()) {
    val packageName = "hold-test"
    val contentResolver = ContentResolver()
    fun getString(id:Int)="settings message"
    open fun getSystemService(name: String): Any? = if(name == USB_SERVICE) env.usb else null
    open fun createDeviceProtectedStorageContext(): Context = DeviceContext(env)
    fun holdUsbCheckForSettings(recheck:com.andrerinas.openheadunit.connection.UsbRecheck){}
    open fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        env.credentialReads++; error("Credential storage reached from USB observation")
    }
    class DeviceContext(env:Env):Context(env) {
        override fun getSharedPreferences(name:String, mode:Int) = SharedPreferences(env.disk.getOrPut(name){mutableMapOf()})
    }
    companion object { const val MODE_PRIVATE=0; const val USB_SERVICE="usb" }
}
class ContentResolver
class SharedPreferences(val data: MutableMap<String,Any>) {
    fun contains(key:String)=data.containsKey(key)
    fun getBoolean(key:String, fallback:Boolean) = data[key] as? Boolean ?: fallback
    fun getLong(key:String, fallback:Long) = data[key] as? Long ?: fallback
    fun getInt(key:String, fallback:Int) = data[key] as? Int ?: fallback
    fun edit() = Editor(data)
    class Editor(private val data:MutableMap<String,Any>) {
        private val pending=mutableMapOf<String,Any>();private val removed=mutableSetOf<String>();private var clear=false
        private fun put(key:String,value:Any){removed.remove(key);pending[key]=value}
        fun putBoolean(key:String,value:Boolean)=apply{put(key,value)}
        fun putLong(key:String,value:Long)=apply{put(key,value)}
        fun putInt(key:String,value:Int)=apply{put(key,value)}
        fun remove(key:String)=apply{pending.remove(key);removed.add(key)}
        fun clear()=apply{clear=true}
        fun commit():Boolean { if(clear)data.clear();removed.forEach{data.remove(it)};data.putAll(pending);return true }
        fun apply() {} // Does not commit: process-exit durability is part of the assertion.
    }
}
abstract class BroadcastReceiver { abstract fun onReceive(context:Context,intent:Intent) }
class Intent(var action:String?=null) {
    var data:android.net.Uri?=null; private val extras=mutableMapOf<String,Any>()
    fun setPackage(name:String)=this
    fun setData(value:android.net.Uri)=apply{data=value}
    fun putExtra(key:String,value:String)=apply{extras[key]=value}
    fun putExtra(key:String,value:Boolean)=apply{extras[key]=value}
    fun getStringExtra(key:String)=extras[key] as? String
    fun removeExtra(key:String){extras.remove(key)}
    fun getBooleanExtra(key:String,fallback:Boolean)=extras[key] as? Boolean ?: fallback
    fun <T> getParcelableExtra(key:String,type:Class<T>):T?=null
    fun <T> getParcelableExtra(key:String):T?=null
    companion object { const val ACTION_MAIN="android.intent.action.MAIN" }
}
class IntentFilter(var action:String?=null) { fun addAction(action:String){};fun addDataScheme(scheme:String){} }
''')
(OUT / 'Android.kt').write_text('''package android.os
object Build { object VERSION { var SDK_INT=35 }; object VERSION_CODES { const val LOLLIPOP=21;const val N=24;const val S=31;const val TIRAMISU=33 } }
object SystemClock { var now=1000L; fun elapsedRealtime()=now }
class Bundle
''')
(OUT / 'Activity.kt').write_text('''package android.app
import android.content.*
import android.os.Bundle
import usbhold.Env
open class Activity(env:Env=Env()):Context(env) {
    var intent=Intent();var isChangingConfigurations=false;var finished=false
    open fun onCreate(savedInstanceState:Bundle?){}
    open fun onNewIntent(intent:Intent){}
    fun finish(){finished=true}
}
class PendingIntent {
    companion object {
        const val FLAG_UPDATE_CURRENT=1;const val FLAG_MUTABLE=2
        fun getBroadcast(context:Context,request:Int,intent:Intent,flags:Int)=PendingIntent()
    }
}
''')
(OUT / 'Usb.kt').write_text('''package android.hardware.usb
class UsbDevice(val accessory:Boolean=true,val android:Boolean=true,var vendorId:Int=0x18d1) {
    val productId=if(accessory)0x2d00 else 0x4ee7;val deviceName="/dev/test"
    val productName:String?=null;val manufacturerName:String?=null
}
class UsbManager {
    val deviceList=hashMapOf<String,UsbDevice>()
    fun hasPermission(device:UsbDevice)=true
    companion object {
        const val ACTION_USB_DEVICE_ATTACHED="device-attached";const val ACTION_USB_DEVICE_DETACHED="device-detached"
        const val ACTION_USB_ACCESSORY_DETACHED="accessory-detached";const val EXTRA_DEVICE="device";const val EXTRA_PERMISSION_GRANTED="permission"
    }
}
''')
(OUT / 'Uri.kt').write_text('''package android.net
class Uri(val scheme:String,val schemeSpecificPart:String,val fragment:String?) {
    val host:String?=null
    companion object { fun fromParts(scheme:String,part:String,fragment:String?)=Uri(scheme,part,fragment) }
}
''')
(OUT / 'Global.kt').write_text('''package android.provider
object Settings { object Global { var boot=7;fun getInt(resolver:android.content.ContentResolver,key:String,fallback:Int)=boot } }
''')
(OUT / 'Compat.kt').write_text('''package com.andrerinas.openheadunit.connection.usb
import android.content.Context
import android.hardware.usb.*
class UsbDeviceCompat(val wrappedDevice:UsbDevice) {
    val uniqueName="phone"
    companion object {
    fun usbManager(context:Context)=context.getSystemService(Context.USB_SERVICE) as? UsbManager
    fun isAndroidDevice(device:UsbDevice)=device.android
    fun isInAccessoryMode(device:UsbDevice)=device.accessory
    fun isConnectable(context:Context,device:UsbDevice)=device.android
    fun connectableReason(context:Context,device:UsbDevice)="not Android"
    fun getUniqueName(device:UsbDevice)="phone"
    }
}
class UsbAccessoryMode(manager:UsbManager){fun connectAndSwitch(device:UsbDevice,useLibusb:Boolean,admission:Any)=true}
object UsbDeviceDiagnostics { fun logDeviceList(context:Context,manager:UsbManager,source:String){} }
''')
(OUT / 'App.kt').write_text('''package com.andrerinas.openheadunit
object App {
    fun provide(context:android.content.Context)=context.env
    fun isUserUnlocked(context:android.content.Context)=context.env.unlocked
}
''')
(OUT / 'Log.kt').write_text('''package com.andrerinas.openheadunit.utils
object AppLog { fun i(message:String){};fun w(message:String){};fun d(message:String){};fun e(message:String){} }
object Settings { fun formatUsbVidPidDisplay(vid:Int,pid:Int)="phone" }
''')
(OUT / 'Retained.kt').write_text('''package com.andrerinas.openheadunit.connection.wifi.direct
object RetainedNativeGroup { fun requestStop(isCurrent:()->Boolean){} }
''')
(OUT / 'Control.kt').write_text('''package com.andrerinas.openheadunit.aap.protocol.proto
object Control { enum class ByeByeReason { USER_SELECTION } }
''')
(OUT / 'ViewModel.kt').write_text('''package androidx.lifecycle
open class ViewModel
''')
(OUT / 'Main.kt').write_text('''package com.andrerinas.openheadunit.main
class MainActivity:android.app.Activity()
class SettingsActivity:android.app.Activity(){companion object {var isVisible=false}}
object AutoConnectAttemptPolicy { fun singleUsbShowsOverlay(connectable:Int)=connectable==1 }
''')
(OUT / 'Service.kt').write_text('''package com.andrerinas.openheadunit.aap
import android.content.*
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.UsbRecheck
class AapProjectionActivity:android.app.Activity()
object AapService {
    fun usbCheckIntent(context:Context,recheck:UsbRecheck)=Intent("usb-check")
    fun acceptedStopIntent(context:Context,owner:Long)=Intent("accepted-stop")
''' + member('aap/AapService.kt', 'fun stopIntent(') + '\n}\n')
(OUT / 'ContextCompat.kt').write_text('''package androidx.core.content
object ContextCompat { fun startForegroundService(context:android.content.Context,intent:android.content.Intent) { context.env.serviceStarts++ } }
''')
(OUT / 'Comm.kt').write_text('''package com.andrerinas.openheadunit.connection
typealias CommManager = usbhold.Manager
typealias ConnectionOperation = usbhold.Operation
object ConnectionPriorityPolicy { enum class Tier { USER,USB };enum class Owner { USB } }
''')
(OUT / 'extracted-method-sha256.json').write_text(json.dumps(hashes, indent=2) + '\n')
toolchain = json.loads((HOST / 'toolchain.json').read_text())
jars = [Path(entry['path']) for entry in toolchain['jars']]
for entry, jar in zip(toolchain['jars'], jars):
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == entry['sha256']
(OUT / 'toolchain.json').write_text(json.dumps(toolchain, indent=2) + '\n')
cp = os.pathsep.join(map(str, jars))
commands = [
    ['java', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-nowarn',
     '-classpath', cp, '-d', str(OUT / 'cases.jar'), *map(str, sorted(OUT.glob('*.kt')))],
    ['java', '-cp', str(OUT / 'cases.jar') + os.pathsep + cp, 'usbhold.FixtureKt', str(OUT / 'results.json')],
]
(OUT / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
for command in commands:
    subprocess.run(command, check=True, timeout=60)
