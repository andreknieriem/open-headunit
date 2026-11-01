package usbhold

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.app.AppUiEntry
import com.andrerinas.openheadunit.connection.AppExitPolicy
import com.andrerinas.openheadunit.connection.UsbRecheck
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy
import com.andrerinas.openheadunit.connection.AutoConnectHoldPolicy
import com.andrerinas.openheadunit.connection.usb.UsbAccessoryMode
import com.andrerinas.openheadunit.connection.usb.UsbAttachPolicy
import com.andrerinas.openheadunit.connection.usb.UsbDeviceCompat
import com.andrerinas.openheadunit.connection.usb.UsbDeviceDiagnostics
import com.andrerinas.openheadunit.connection.usb.UsbReceiver
import com.andrerinas.openheadunit.main.AutoConnectAttemptPolicy
import com.andrerinas.openheadunit.main.SettingsActivity
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

class Env(val disk:MutableMap<String,MutableMap<String,Any>> = mutableMapOf(), val usb:UsbManager = UsbManager()) {
    val settings=Preferences()
    lateinit var commManager:Manager
    var credentialReads=0
    var serviceStarts=0
    var unlocked=true
}
class Preferences {
    var autoConnectSingleUsbDevice=true;var hasAcceptedDisclaimer=true
    var autoStartOnUsb=true;var autoConnectLastSession=false;var allowedDevices=emptySet<String>();var useLibusb=false
    var killOnDisconnect=false
    fun isConnectingDevice(device:UsbDeviceCompat)=allowedDevices.contains(device.uniqueName)
}
typealias Tier = ConnectionPriorityPolicy.Tier
enum class ConnectionStage { USB_ATTACHED,USB_SWITCHING }
object ConnectionStageTracker { fun beginAttempt(stage:ConnectionStage){};fun report(stage:ConnectionStage){} }
class Operation(val ticket:Long,val save:Any?=null)
typealias ConnectionOperation = Operation

class Manager(val context:Context) {
    val transportLifecycleLock=Any()
    internal val appExitPolicy=AppExitPolicy()
    var usbRecoveryOwner:Any=Any()
    var epoch=0L
    var isConnected=false
    var restores=0
    var admittedUsb=0
    var restoredScan:UsbRecheck?=null
    fun appConnectionTicket()=appExitPolicy.stopEpoch
    fun usbRecheckNow()=UsbRecheck(appConnectionTicket(),epoch)
    var afterValidation:(()->Unit)?=null
    fun isCurrent(scan:UsbRecheck):Boolean {
        val valid=appExitPolicy.acceptsConnection(scan.ticket)&&scan.epoch==epoch
        afterValidation?.also{afterValidation=null}?.invoke()
        return valid
    }
    fun isCurrent(operation:Operation)=appExitPolicy.acceptsConnection(operation.ticket)
    fun acceptsAppRunTicket(ticket:Long)=isCurrent(Operation(ticket))&&appExitPolicy.isRunOwner(appExitPolicy.revision)
    fun appOwnershipRevision()=appExitPolicy.revision
    fun <T> withAppConnectionLifecycleLock(action:()->T):T=synchronized(transportLifecycleLock){action()}
    fun retireInvalidRequest(){}
    fun cancelAppExitCallback(){}
    fun cancelPendingSettingsRestart(){}
    fun withdrawUsbRechecks(){epoch++}
    fun ownsAppExit(id:Long)=appExitPolicy.owns(id,false)
    fun restoreAppForEntry(owner:Long,scan:UsbRecheck?=null) { restores++;restoredScan=scan }
    enum class DisconnectReason { CONNECTION_ENDED, SETTINGS_RESTART, PROJECTION_UNRAISED }
    class ConnectionState { class Disconnected {fun acceptsSettingsRestart(state:Any)=true} }
    class State {val value=Any()}
    val connectionState=State()
    val _connectionState=connectionState
    fun usbRecheckOwner():Any=this
    fun deferUsbCheckForConnectionAttempt(recheck:UsbRecheck)=false
    fun recheckCurrent(recheck:UsbRecheck)=isCurrent(recheck)
    class ConnectionRequest(val recheck:UsbRecheck)
    sealed class Admission {class Admitted(val request:ConnectionRequest):Admission()}
    fun admitHeld(tier:Tier,description:String,ticket:Long,save:ConnectionState.Disconnected?,endpoint:String?,
        owned:Any?,owner:ConnectionPriorityPolicy.Owner,usbRecheck:UsbRecheck):Admission {
        admittedUsb++
        return Admission.Admitted(ConnectionRequest(usbRecheck))
    }
    // USB_ADMISSION
    // DISCONNECT_CURRENT
    // DISCONNECT_PHYSICAL
    // STOP
    // EXIT
    // OPEN
    // RESUME
}

class AttachActivity(env:Env):Activity(env) {
    var accepted=0
    fun resolveUsbDevice(intent:Intent?)=env.usb.deviceList.values.firstOrNull()
    // ACTIVITY_CREATE
    // ACTIVITY_INTENT
}
class Launcher(val service:Context) {
    var accepted=0
    var cancelledByUser=false
    var noUsbServiceLogged=false
    fun liftUserCancel(reason:String){cancelledByUser=false}
    fun discardCancelledAttempt(manager:Manager){}
    fun deferCheckWhileSwitching(force:Boolean,userRequested:Boolean,settingsRestart:Manager.ConnectionState.Disconnected?,
        owner:Any,accessoryOnly:Boolean,recheck:UsbRecheck)=false
    fun beginAttempt(tier:Tier,what:String,settingsRestart:Manager.ConnectionState.Disconnected?,recheck:UsbRecheck):Boolean {
        accepted++;return false
    }
    fun performSingleConnect(device:UsbDevice,tier:Tier,settingsRestart:Manager.ConnectionState.Disconnected?,recheck:UsbRecheck){accepted++}
    fun requestPermission(device:UsbDevice,settingsRestart:Manager.ConnectionState.Disconnected?,recheck:UsbRecheck){accepted++}
    fun launchAttempt(context:CoroutineContext=EmptyCoroutineContext,block:suspend(Any)->Unit){}
    suspend fun awaitAccessoryPermission(manager:UsbManager,device:UsbDevice,name:String,request:Any)=true
    suspend fun connectWithRetry(device:UsbDevice,request:Any){}
    fun switchAdmission(request:Any):Any=request
    fun noteAccessorySwitchRequested(){}
    // MANAGER_CHECK
}
class AttachListener(val service:Context):UsbReceiver.Listener {
    var accepted=0
    // LISTENER_ATTACH
    override fun onUsbDetach(device:UsbDevice){}
    override fun onUsbAccessoryDetach(){}
    override fun onUsbPermission(granted:Boolean,connect:Boolean,device:UsbDevice,recheck:UsbRecheck,token:String?){}
}
class Home(val context:Context) {
    val commManager get()=context.env.commManager
    // HOME_SINGLE
    fun automatic()=attemptSingleUsbAutoConnect(commManager.appConnectionTicket(),commManager.usbRecheckNow())
}

// Configuration writes and UI are boundaries; the exact production restart branch remains.
object R {object string {const val stopping_service=1}}
object Toast {const val LENGTH_SHORT=0}
object ToastUtils {fun showToast(context:Context,message:String,duration:Int,force:Boolean){}}
object SettingsBackupManager {fun requiresProjectionRestart(keys:Set<String>)=true}
class SettingsStop(val context:Context,val connectionTicket:Long) {
    val requiresServiceRestart=true
    val requiresRestart=true
    val ctx=context
    class Snapshot(val connectionTicket:Long)
    class Result {val changedKeys=setOf("projection")}
    val snapshot=Snapshot(connectionTicket)
    val result=Result()
    fun requireContext()=context
    fun getString(id:Int)=context.getString(id)
    fun save() {
        // SETTINGS_SAVE
    }
    fun mic() {
        // MIC_SAVE
    }
    fun dark() {
        // DARK_SAVE
    }
    fun reset() {
        // SETTINGS_RESET
    }
    fun imported() {
        // SETTINGS_IMPORT
    }
    fun apply(route:String)=when(route) {
        "save"->save();"mic"->mic();"dark"->dark();"reset"->reset();"import"->imported()
        else->error(route)
    }
}

class Scenario(disk:MutableMap<String,MutableMap<String,Any>> = mutableMapOf(),usb:UsbManager=UsbManager()) {
    val env=Env(disk,usb)
    val context=Context(env)
    val manager=Manager(context)
    val launcher=Launcher(context)
    val activity=AttachActivity(env)
    val listener=AttachListener(context)
    val observer=UsbReceiver(object:UsbReceiver.Listener {
        override fun onUsbAttach(device:UsbDevice){}
        override fun onUsbDetach(device:UsbDevice){}
        override fun onUsbAccessoryDetach(){}
        override fun onUsbPermission(granted:Boolean,connect:Boolean,device:UsbDevice,recheck:UsbRecheck,token:String?){}
    })
    init {env.commManager=manager}
    fun plug(accessory:Boolean=true){env.usb.deviceList["phone"]=UsbDevice(accessory)}
    fun unplug(){env.usb.deviceList.clear();observer.onReceive(context,Intent(UsbManager.ACTION_USB_DEVICE_DETACHED))}
    fun stop(){checkNotNull(manager.requestExplicitAppExit(manager.appConnectionTicket(),false))}
    fun exit(){manager.disconnect(Operation(manager.appConnectionTicket()),sendByeBye=false,honorKillOnDisconnect=false)}
    fun scan(manual:Boolean=false,recheck:UsbRecheck=manager.usbRecheckNow()) {
        launcher.checkAlreadyConnected(force=true,userRequested=manual,recheck=recheck)
    }
    fun attach(){activity.onCreate(null)}
    fun newIntent(){activity.onNewIntent(Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED))}
    fun broadcast(){listener.onUsbAttach(env.usb.deviceList.values.first())}
    fun process()=Scenario(env.disk,env.usb)
    fun open() {
        AppUiEntry.delivered(Activity(env),Intent(Intent.ACTION_MAIN))
        val scan=checkNotNull(manager.restoredScan){"A genuine Open must carry the held USB scan even with an existing service"}
        scan(manual=true,recheck=scan)
    }
}

fun main(args:Array<String>) {
    val results=mutableListOf<Pair<String,String>>()
    fun test(name:String,body:()->Unit) {
        SystemClock.now=1000L
        android.provider.Settings.Global.boot=7
        try {body();results+=name to "PASS";println("PASS $name")}
        catch(failure:Throwable){results+=name to "FAIL: ${failure.message}";println("FAIL $name: ${failure.message}")}
    }
    for(accessory in listOf(false,true)) {
        val mode=if(accessory)"accessory" else "normal"
        test("USB-hold-Exit-activity-$mode") {
            val s=Scenario();s.plug();s.exit();s.unplug();SystemClock.now+=2200;s.plug(accessory);s.attach()
            check(s.activity.accepted==0){"Exit must block the automatic $mode attach before its first downstream effect"}
        }
        test("USB-hold-Stop-new-intent-$mode") {
            val s=Scenario();s.plug();s.stop();s.unplug();SystemClock.now+=2200;s.plug(accessory);s.newIntent()
            check(s.activity.accepted==0){"Stop must block a $mode onNewIntent attach before its first downstream effect"}
        }
        test("USB-hold-Stop-service-attach-$mode") {
            val s=Scenario();s.plug();s.stop();s.unplug();SystemClock.now+=2700;s.plug(accessory);s.broadcast()
            check(s.listener.accepted==0){"Stop must block the service's automatic $mode attach"}
        }
        test("USB-hold-Exit-forced-launcher-$mode") {
            val s=Scenario();s.plug();s.exit();s.unplug();SystemClock.now+=2200;s.plug(accessory);s.scan()
            check(s.launcher.accepted==0){"Exit must block a fresh forced automatic $mode scan"}
        }
    }
    test("USB-hold-Home-single-USB-after-Exit") {
        val s=Scenario();s.plug();s.exit()
        check(!Home(s.context).automatic() && s.env.serviceStarts==0){"Home single-USB must not start a service or show an overlay after Exit"}
    }
    test("USB-hold-Stop-during-ACC-START-gap") {
        val s=Scenario();s.plug(false);s.unplug();s.stop();SystemClock.now+=2700;s.plug();s.scan()
        check(s.launcher.accepted==0){"Stop during ACC START re-enumeration must hold the returning accessory"}
    }
    test("USB-hold-process-replacement") {
        val s=Scenario();s.plug();s.stop();val fresh=s.process();fresh.scan()
        check(fresh.launcher.accepted==0){"A new process must honor the committed USB Stop"}
    }
    test("USB-hold-no-expiry-while-attached") {
        val s=Scenario();s.plug();s.exit();SystemClock.now+=120000;s.scan()
        check(s.launcher.accepted==0){"Time since Exit is not evidence of USB absence"}
    }
    test("USB-hold-gap-9999ms") {
        val s=Scenario();s.plug();s.stop();s.unplug();SystemClock.now+=9999;s.plug();s.scan()
        check(s.launcher.accepted==0){"A USB absence below 10 seconds must keep the Stop"}
    }
    test("USB-hold-genuine-unplug-10000ms") {
        val s=Scenario();s.plug();s.stop();s.unplug();val fresh=s.process();SystemClock.now+=10000;fresh.plug();fresh.scan()
        check(fresh.launcher.accepted==1){"A genuine unplug must release the hold for the next plug"}
    }
    test("USB-hold-status-pill-cancel-genuine-replug") {
        val s=Scenario();s.plug();s.launcher.cancelledByUser=true;s.exit();s.unplug();SystemClock.now+=10000;s.plug();s.scan()
        check(!s.launcher.cancelledByUser && s.launcher.accepted==1){"A genuine replug must release the earlier status-pill cancellation"}
    }
    test("USB-hold-Open-resumes-attached-phone") {
        val s=Scenario();s.plug();s.exit();s.open()
        check(s.launcher.accepted==1){"Open must resume the attached phone"}
    }
    test("USB-hold-explicit-USB-resumes-phone") {
        val s=Scenario();s.plug();s.stop();s.scan(manual=true)
        check(s.launcher.accepted==1){"An explicit USB input must resume the attached phone"}
    }
    test("USB-hold-explicit-normal-phone-overrides-auto-selection") {
        val s=Scenario();s.plug(false);s.env.usb.deviceList["phone"]!!.vendorId=0x04e8
        s.env.settings.apply{autoStartOnUsb=false;autoConnectSingleUsbDevice=false;autoConnectLastSession=false;allowedDevices=setOf("other phone")}
        s.exit();s.scan(manual=true)
        check(s.launcher.accepted==1){"An explicit USB input must connect the chosen normal phone despite automatic selection settings"}
    }
    test("USB-hold-control-automatic-selection-still-honored") {
        val s=Scenario();s.plug(false);s.env.usb.deviceList["phone"]!!.vendorId=0x04e8
        s.env.settings.apply{autoStartOnUsb=false;autoConnectSingleUsbDevice=false;autoConnectLastSession=false;allowedDevices=setOf("other phone")}
        s.scan()
        check(s.launcher.accepted==0){"An automatic input must still honor the configured phone selection"}
    }
    test("USB-hold-old-manual-relay-cannot-clear-Stop") {
        val s=Scenario();s.plug();val old=s.manager.usbRecheckNow();s.stop();s.scan(manual=true,recheck=old);s.scan()
        check(s.launcher.accepted==0){"A stale manual relay must leave the newer USB Stop intact"}
    }
    test("USB-hold-Exit-between-activity-gate-and-admission") {
        val s=Scenario();s.plug(false);s.attach()
        check(s.activity.accepted==1){"An ordinary initial attach must pass its activity gate"}
        // The peer Exit may arrive on IO after the activity's initial bus gate. Its later
        // admission must not borrow the new epoch and override the persisted user decision.
        s.exit()
        val admitted=s.manager.admitUsbRequest(Tier.USB,"activity USB switch",null,s.manager.usbRecheckNow())
        check(admitted==null && s.manager.admittedUsb==0){"Exit between the activity gate and admission must prevent its AOA switch"}
    }
    test("USB-hold-control-fresh-automatic-admission") {
        val s=Scenario();s.plug(false)
        val admitted=s.manager.admitUsbRequest(Tier.USB,"ordinary USB switch",null,s.manager.usbRecheckNow())
        check(admitted!=null && s.manager.admittedUsb==1){"An automatic input without Stop must still admit its USB request"}
    }
    test("USB-hold-explicit-input-restores-admission") {
        val s=Scenario();s.plug(false);s.stop();s.scan(manual=true)
        val admitted=s.manager.admitUsbRequest(Tier.USER,"explicit USB switch",null,s.manager.usbRecheckNow())
        check(admitted!=null && s.manager.admittedUsb==1){"Explicit USB input must release the admission hold"}
    }
    test("USB-hold-newer-Open-survives-older-physical-Stop") {
        val s=Scenario();s.plug()
        val request=checkNotNull(s.manager.requestExplicitAppExit(s.manager.appConnectionTicket(),false))
        val physical=checkNotNull(s.manager.appExitPolicy.commit(request,false))
        AppUiEntry.delivered(Activity(s.env),Intent(Intent.ACTION_MAIN))
        // The accepted Open captures its scan now, before the old physical teardown continues.
        val openScan=s.manager.restoredScan ?: s.manager.usbRecheckNow()
        s.manager.disconnectForAppExit(physical)
        s.manager.appExitPolicy.destroyed(physical,false)
        s.scan(manual=true,recheck=openScan)
        check(s.launcher.accepted==1){"A newer Open's USB input must survive older physical Stop cleanup"}
    }
    test("USB-hold-Exit-racing-explicit-delivery-remains-held") {
        val s=Scenario();s.plug();s.exit()
        val entered=java.util.concurrent.CountDownLatch(1)
        val completed=java.util.concurrent.CountDownLatch(1)
        val error=java.util.concurrent.atomic.AtomicReference<Throwable?>()
        lateinit var exit:Thread
        s.manager.afterValidation={
            exit=Thread{
                entered.countDown()
                try{s.exit()}catch(failure:Throwable){error.set(failure)}finally{completed.countDown()}
            }.apply{isDaemon=true;start()}
            check(entered.await(3,java.util.concurrent.TimeUnit.SECONDS)){"Exit worker did not enter"}
            // An accepted explicit release serializes with Exit. If validation and release are
            // separate, Exit completes in this gap and the old input wrongly clears its hold.
            completed.await(100,java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        s.scan(manual=true)
        check(completed.await(3,java.util.concurrent.TimeUnit.SECONDS)){"Exit could not finish after explicit delivery"}
        exit.join(3000)
        error.get()?.let{throw it}
        s.launcher.accepted=0;s.scan()
        check(s.launcher.accepted==0){"An Exit accepted during explicit USB delivery must remain held afterward"}
    }
    test("USB-hold-locked-observer-keeps-DP-hold") {
        val s=Scenario();s.plug();s.stop();val fresh=s.process();fresh.env.unlocked=false;fresh.unplug()
        SystemClock.now+=2200;fresh.plug();fresh.observer.onReceive(fresh.context,Intent(UsbManager.ACTION_USB_DEVICE_ATTACHED));fresh.attach()
        check(fresh.env.credentialReads==0){"Locked observation must not read credential storage"}
        check(fresh.activity.accepted==0){"A locked process replacement must retain the USB Stop"}
    }
    for(reason in listOf("ordinary-switch","link-loss","settings-restart")) test("USB-hold-control-$reason") {
        val s=Scenario();s.plug(false)
        if(reason!="ordinary-switch") s.manager.disconnect(Operation(s.manager.appConnectionTicket()),
            sendByeBye=false,isUserExit=false,honorKillOnDisconnect=false,
            reason=if(reason=="settings-restart")Manager.DisconnectReason.SETTINGS_RESTART else Manager.DisconnectReason.CONNECTION_ENDED)
        s.unplug();SystemClock.now+=2200;s.plug();s.scan()
        check(s.launcher.accepted==1){"$reason must allow the phone's ordinary re-enumeration"}
    }
    for(route in listOf("save","mic","dark","reset","import")) test("USB-hold-control-settings-service-$route") {
        val s=Scenario();s.plug();s.manager.isConnected=true
        SettingsStop(s.context,s.manager.appConnectionTicket()).apply(route)
        check(s.env.serviceStarts==1){"Settings $route must still dispatch its accepted service Stop"}
        s.manager.isConnected=false;s.scan()
        check(s.launcher.accepted==1){"Settings $route must not create a user USB Stop"}
    }
    test("USB-hold-settings-service-preserves-prior-Stop") {
        val s=Scenario();s.plug();s.stop();s.manager.isConnected=true
        SettingsStop(s.context,s.manager.appConnectionTicket()).save()
        check(s.env.serviceStarts==1){"Settings Save must retain its existing service Stop behavior"}
        s.manager.isConnected=false;s.scan()
        check(s.launcher.accepted==0){"Settings Save must preserve an earlier user USB Stop"}
    }
    test("USB-hold-stale-settings-service-is-refused") {
        val s=Scenario();s.plug();val saveTicket=s.manager.appConnectionTicket();s.stop();s.manager.isConnected=true
        SettingsStop(s.context,saveTicket).save()
        check(s.env.serviceStarts==0){"A stale Settings Save must not dispatch a new service Stop"}
        s.manager.isConnected=false;s.scan()
        check(s.launcher.accepted==0){"A stale Settings Save must leave the newer user USB Stop intact"}
    }
    test("USB-hold-duplicate-detach-and-late-detach") {
        val s=Scenario();s.plug();s.stop();s.unplug();s.unplug();SystemClock.now+=2000;s.plug()
        s.observer.onReceive(s.context,Intent(UsbManager.ACTION_USB_DEVICE_DETACHED))
        SystemClock.now+=12000;s.scan()
        check(s.launcher.accepted==0){"A delayed detach with a present phone must not start an absence interval"}
    }
    test("USB-hold-peripheral-detach-with-phone-present") {
        val s=Scenario();s.plug();s.stop();s.observer.onReceive(s.context,Intent(UsbManager.ACTION_USB_DEVICE_DETACHED))
        SystemClock.now+=12000;s.scan()
        check(s.launcher.accepted==0){"An unrelated peripheral detach must not release the phone's Stop"}
    }
    test("USB-hold-new-boot-keeps-Stop") {
        val s=Scenario();s.plug();s.stop();android.provider.Settings.Global.boot++;SystemClock.now=100
        val fresh=s.process();fresh.scan()
        check(fresh.launcher.accepted==0){"A reboot does not prove that the stopped phone was genuinely unplugged"}
    }
    test("USB-hold-new-boot-discards-old-absence") {
        val s=Scenario();s.plug();s.stop();s.unplug();android.provider.Settings.Global.boot++;SystemClock.now=20000
        val fresh=s.process();fresh.plug();fresh.scan()
        check(fresh.launcher.accepted==0){"USB absence measured in another boot cannot release the Stop"}
    }
    test("USB-hold-elapsed-rollback-keeps-Stop") {
        android.provider.Settings.Global.boot=-1
        val s=Scenario();s.plug();s.stop();SystemClock.now=100
        val fresh=s.process();fresh.scan()
        check(fresh.launcher.accepted==0){"An elapsed-clock rollback must preserve the user's USB Stop"}
    }
    test("USB-hold-control-unheld-boot-stays-unheld") {
        val s=Scenario();s.plug();s.scan();android.provider.Settings.Global.boot++;SystemClock.now=100
        val fresh=s.process();fresh.scan()
        check(fresh.launcher.accepted==1){"A reboot without a preceding Stop must still connect the attached phone"}
    }
    val json=results.joinToString(",\n",prefix="{\n",postfix="\n}\n") { (name,result)->
        "  \"${name}\": \"${result.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")}\""
    }
    java.io.File(args[0]).writeText(json)
    check(results.all{it.second=="PASS"}){"${results.count{it.second!="PASS"}} USB hold regressions failed"}
}
