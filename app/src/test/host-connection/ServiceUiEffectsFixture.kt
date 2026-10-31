package serviceeffects

import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

// Android, preference I/O and radio boundaries record effects. The executed service/UI method
// bodies and AppExitPolicy come from the chosen source tree, not a second guard implementation.
class Queue : CoroutineDispatcher() {
    val tasks=ConcurrentLinkedQueue<Runnable>()
    override fun dispatch(context:CoroutineContext, block:Runnable) { tasks.add(block) }
    fun drain() { while(true) (tasks.poll()?:return).run() }
}
class CommManager {
    val policy=AppExitPolicy()
    val appExitPolicy get()=policy
    private val lock=Any()
    private val transportLifecycleLock get()=lock
    private var usbRecoveryOwner:Any=Any()
    fun retireInvalidRequest(){}
    fun cancelAppExitCallback(){}
    fun withdrawUsbRechecks(){}
    fun ownsAppExit(id:Long)=policy.owns(id,false)
    var isConnected=false
    var cancels=0
    fun appConnectionTicket()=synchronized(lock){policy.stopEpoch}
    fun acceptsAppConnectionTicket(ticket:Long)=synchronized(lock){policy.acceptsConnection(ticket)}
    fun isCurrent(operation:ConnectionOperation)=acceptsAppConnectionTicket(operation.ticket)
    fun isCurrent(recheck:UsbRecheck)=acceptsAppConnectionTicket(recheck.ticket)
    fun <T> withAppConnectionLifecycleLock(action:()->T):T=synchronized(lock){action()}
    // CM_STOP
    fun cancelPendingSettingsRestart() { cancels++ }
    fun stop(open:Boolean=false) { policy.request(false,true,false); if(open) policy.supersede() }
}
object RetainedNativeGroup { fun requestStop(owns:()->Boolean){} }
class ConnectionOperation(val ticket:Long,val save:Any?)
class UsbRecheck(val ticket:Long)
open class Context(val commManager:CommManager) {
    val settings=Settings()
    val connectivity=ConnectivityManager()
    val packageName="test"
    val started=mutableListOf<Intent>()
    fun getSystemService(name:String):Any=connectivity
    fun startService(intent:Intent) { started.add(intent) }
    fun sendBroadcast(intent:Intent) {}
    fun getString(id:Int,vararg args:Any):String=id.toString()
    companion object { const val CONNECTIVITY_SERVICE="connectivity" }
}
object App { fun provide(context:Context)=context }
class Intent {
    var action:String?=null
    private val extras=mutableMapOf<String,Any>()
    constructor(action:String) { this.action=action }
    constructor(context:Context,target:Class<*>)
    fun putExtra(name:String,value:Any)=apply { extras[name]=value }
    fun getLongExtra(name:String,fallback:Long)=(extras[name] as? Long)?:fallback
    fun getBooleanExtra(name:String,fallback:Boolean)=(extras[name] as? Boolean)?:fallback
    fun setAction(action:String?)=apply {this.action=action}
    fun setPackage(name:String)=this
}
object ContextCompat {
    val starts=mutableListOf<Intent>()
    fun startForegroundService(context:Context,intent:Intent) { starts.add(intent) }
}
object Build { object VERSION { var SDK_INT=35 }; object VERSION_CODES {const val M=23; const val LOLLIPOP=21} }
class Network
class NetworkCapabilities { fun hasTransport(x:Int)=false; companion object {const val TRANSPORT_WIFI=1} }
class NetworkRequest { class Builder { fun addTransportType(x:Int)=this; fun build()=NetworkRequest() } }
class NetworkInfo { val isConnected=false; val type=1 }
class ConnectivityManager {
    var activeNetwork:Network?=null
    val activeNetworkInfo:NetworkInfo?=null
    val registered=mutableListOf<NetworkCallback>()
    val removed=mutableListOf<NetworkCallback>()
    open class NetworkCallback { open fun onAvailable(network:Network) {} }
    fun getNetworkCapabilities(network:Network)=NetworkCapabilities()
    fun registerNetworkCallback(request:NetworkRequest,callback:NetworkCallback) { registered.add(callback) }
    fun unregisterNetworkCallback(callback:NetworkCallback) { removed.add(callback) }
    companion object { const val TYPE_WIFI=1 }
}
enum class WifiLauncherMode { HELPER, MANUAL }
enum class HelperStrategy { WIFI_DIRECT }
class Settings {
    var killOnDisconnect=false
    var wifiConnectionMode=WifiLauncherMode.HELPER
    var helperConnectionStrategy=HelperStrategy.WIFI_DIRECT
    var waitForWifiBeforeWifiDirect=true
    var waitForWifiTimeout=10000
    var autoStartSelfMode=false
    var wirelessSelected=false
    var nativeApStrategy=0
    var bluetoothManagerServiceName=""
    var wifiDirectBand=0
    var fiveGhzChannel=0
    var externalBtZbtTransport=false
    var externalBtBlinkTransport=false
    var externalBtZjSocketTransport=false
    var nativeAaIgnoreExternalBt=false
    var autoEnableHotspot=false
    var insecureAaRfcommListener=false
    fun showsWifi()=wirelessSelected
    companion object { fun clearBootLoopState(context:Context) {} }
}
class Launcher(private val manager:CommManager) {
    val arms=mutableListOf<Long>()
    var stops=0
    fun setActiveFromSettings(ticket:Long) { if(manager.acceptsAppConnectionTicket(ticket)) arms.add(ticket) }
    fun setActiveFromSettings(operation:ConnectionOperation)=setActiveFromSettings(operation.ticket)
    fun liftUserCancel(reason:String) {}
    fun stop(){ stops++ }
    fun stopForOperation(operation:ConnectionOperation):Boolean { if(!manager.isCurrent(operation))return false; stop();return true }
    fun detachForOperation(operation:ConnectionOperation):(() -> Unit)? = if(manager.isCurrent(operation)) ({ stop() }) else null
}
class AapService(manager:CommManager):Context(manager) {
    val dispatcher=Queue()
    val serviceScope=CoroutineScope(SupervisorJob()+dispatcher)
    val wifiLauncherManager=Launcher(manager)
    var isDestroying=false
    var wirelessPausedForSettings=false
    var wirelessRearmPendingForSettings=false
    var wirelessRearmRequest:Any?=null
    var parkedReleases=0
    private var initializedForStart=false
    var bootstrapEffects=0
    fun bootstrap(ticket:Long)=initializeForStart(ticket,UsbRecheck(ticket))
    // BOOTSTRAP_ENTRY
    fun releaseParkedHct(){parkedReleases++}
    fun captureParkedHctRelease():(() -> Unit)? = { parkedReleases++ }
    fun holdWirelessForSettings(ticket:Long){wirelessRearmPendingForSettings=true}
    fun holdWirelessForSettings(operation:ConnectionOperation){wirelessRearmPendingForSettings=true;wirelessRearmRequest=operation}
    fun deferWirelessForUsbHandoff(ticket:Long)=false
    fun waitForWifi(ticket:Long)=initWifiModeWithOptionalWait(ticket)
    fun close(){ serviceScope.cancel() }
    // WAIT_FIELDS
    // WIFI_INIT
    // WIFI_COMPLETE
    // WAIT_HELPERS
    // START_WIRELESS
    // STOP_WIRELESS
    companion object {
        var instance:AapService?=null
        const val ACTION_START_WIRELESS="wireless-start"
        const val ACTION_STOP_WIRELESS="wireless-stop"
        const val ACTION_STOP_SERVICE="stop"
        const val ACTION_START_SELF_MODE="self"
        const val EXTRA_SELF_MODE_VPN="vpn"
        const val EXTRA_SETTINGS_REARM="rearm"
        const val ACTION_REQUEST_NIGHT_MODE_UPDATE="night"
        const val ACTION_NATIVE_AA_CANCEL_POKE="picker-cancel"
        const val ACTION_NATIVE_AA_PROMPT_DISMISSED="picker-dismiss"
        fun acceptedStopIntent(context:Context,owner:Long)=Intent(context,AapService::class.java).setAction(ACTION_STOP_SERVICE).putExtra("owner",owner)
        fun operationIntent(context:Context,ticket:Long)=Intent(context,AapService::class.java).putExtra("ticket",ticket)
        // Baseline-only producer remains exactly its original minting behavior.
        fun commandIntent(context:Context,action:String?)=operationIntent(context,context.commManager.appConnectionTicket()).setAction(action)
        // STOP_INTENT
        // APPLY_WIRELESS
    }
}
class Timer { fun cancel(){} }
class Picker(val host:Context,val openedTicket:Long) {
    val context:Context? get()=host
    val commManager get()=host.commManager
    var driverCountdownTimer:Timer?=null
    var activeDialog:Any?=null
    var selectionResolved=false
    fun requireContext()=host
    // PICKER_NOTIFY
    // PICKER_DISMISS
    // PICKER_CANCEL
}
object HomeFragment { var forceSelfModeTicket:Long?=null; var view:Any?=null }
class Fragments { val fragments=listOf(HomeFragment) }
class Nav { val childFragmentManager=Fragments() }
class FragmentManager { fun findFragmentById(id:Int)=Nav() }
object SelfLaunchRoutePolicy { fun needsDummyVpn(path:Int,offline:Boolean,available:Boolean)=offline&&available&&path==0 }
object SelfLauncherManager { var path=1; fun installedPath(context:Context)=path }
object VpnControl { var available=false; var consent:Intent?=null; fun isVpnAvailable()=available; fun consentIntent(context:Context)=consent }
object android { object app { object Activity { const val RESULT_OK=1 } } }
class ActivityResult(val resultCode:Int)
class Permission { val launches=mutableListOf<Intent>(); fun launch(intent:Intent){launches.add(intent)} }
class MainActivity(manager:CommManager):Context(manager) {
    private class ForcedSelfConsent(val ticket:Long, var cancelled:Boolean=false)
    private var pendingForcedSelf:ForcedSelfConsent?=null
    private var queuedForcedSelfTicket:Long?=null
    val forcedSelfVpnPermission=Permission()
    val supportFragmentManager=FragmentManager()
    enum class ConnectionUiMode { OVERLAY }
    fun beginAutoConnect(source:String,mode:ConnectionUiMode){}
    fun force(ticket:Long)=forceSelfModeLaunch(ticket)
    // FORCE_SELF
    // SELF_DISPATCH
    // SELF_CONSENT
    // SELF_CANCEL
}
class Home(val host:MainActivity) {
    val commManager get()=host.commManager
    val appSettings get()=host.settings
    var hasAutoStarted=false
    val autoConnectTicket get()=host.commManager.appConnectionTicket()
    var forceSelfModeTicket:Long?
        get()=HomeFragment.forceSelfModeTicket
        set(value){HomeFragment.forceSelfModeTicket=value}
    fun requireActivity():Any=host
    fun startSelfMode(ticket:Long){ ContextCompat.startForegroundService(host,AapService.operationIntent(host,ticket).setAction(AapService.ACTION_START_SELF_MODE)) }
    // HOME_SELF
}
class ImportSnapshot(val connectionTicket:Long) {
    val wifiConnectionMode=WifiLauncherMode.HELPER
    val helperConnectionStrategy=HelperStrategy.WIFI_DIRECT
    val nativeApStrategy=0
    val bluetoothManagerServiceName=""
    val wirelessSelected=true
    val wifiDirectBand=0
    val fiveGhzChannel=0
    val externalBtZbtTransport=false
    val externalBtBlinkTransport=false
    val externalBtZjSocketTransport=false
    val nativeAaIgnoreExternalBt=false
    val autoEnableHotspot=false
    val insecureAaRfcommListener=false
}
object WirelessRearmPolicy {
    class Config(val wifiConnectionMode:WifiLauncherMode,val helperConnectionStrategy:HelperStrategy,val nativeApStrategy:Int,val bluetoothManagerServiceName:String,val wirelessSelected:Boolean,val wifiDirectBand:Int,val fiveGhzChannel:Int,val externalBtZbtTransport:Boolean,val externalBtBlinkTransport:Boolean,val externalBtZjSocketTransport:Boolean,val nativeAaIgnoreExternalBt:Boolean,val autoEnableHotspot:Boolean,val insecureAaRfcommListener:Boolean)
    fun requiresRearm(before:Config,after:Any)=true
}
object SettingsBackupManager {
    var commits=0
    class ImportResult(val changedKeys:Set<String> =setOf("audio"),val importedKeys:Int=1,val skippedKeys:Int=0)
    fun applyImport(context:Context,prepared:Unit):ImportResult { commits++; return ImportResult() }
    fun requiresProjectionRestart(keys:Set<String>)=true
}
object AppThemeManager { var applied=0; fun reapply(ctx:Context,settings:Settings){applied++} }
class SettingsScreen(val host:Context) {
    val context:Context? get()=host
    var settings=host.settings
    var hasChanges=true
    var requiresRestart=true
    var requiresServiceRestart=true
    var reloads=0
    val activity:Activity?=null
    class Activity { fun recreate(){} }
    fun requireContext()=host
    fun getString(id:Int,vararg args:Any)=id.toString()
    fun wirelessRearmConfig():Any=settings
    fun reloadPendingStateFromSettings(){reloads++}
    fun updateSaveButtonState(){}
    fun updateSettingsList(){}
    fun shouldRecreateAfterImport(snapshot:ImportSnapshot)=false
    fun complete(snapshot:ImportSnapshot)=handleImportedSettings(snapshot,SettingsBackupManager.ImportResult())
    // IMPORT_APPLY
    // IMPORT_COMPLETE
    // WIRELESS_SIDE_EFFECTS
}
object AppLog { fun i(s:String){}; fun w(s:String){} }
object Toast { const val LENGTH_SHORT=0; const val LENGTH_LONG=1 }
object ToastUtils { fun showToast(context:Context?,text:String,duration:Int,force:Boolean=false){} }
object R { object id {const val main_content=1}; object string {const val stopping_service=1;const val settings_imported=2; const val failed_start_android_auto=3} }

fun main(args:Array<String>) {
    val results=mutableListOf<Pair<String,String>>()
    fun case(name:String,run:()->Unit){
        ContextCompat.starts.clear();HomeFragment.forceSelfModeTicket=null;HomeFragment.view=null
        AapService.instance=null;SettingsBackupManager.commits=0;AppThemeManager.applied=0
        VpnControl.available=false;VpnControl.consent=null;SelfLauncherManager.path=1
        try { run(); results.add(name to "PASS"); println("PASS $name") }
        catch(e:Throwable){results.add(name to ("FAIL: "+e.message));println("FAIL $name: ${e.message}")}
    }
    for(open in listOf(false,true)) case("F1-bootstrap-entry-stop${if(open)"-open" else ""}") {
        val manager=CommManager();val service=AapService(manager)
        try{val ticket=manager.appConnectionTicket();manager.stop(open);service.bootstrap(ticket)
            check(service.bootstrapEffects==0){"withdrawn command initialized service effects"}
        }finally{service.close()}
    }
    case("F1-bootstrap-entry-control") {
        val manager=CommManager();val service=AapService(manager)
        try{val ticket=manager.appConnectionTicket();service.bootstrap(ticket);service.bootstrap(ticket)
            check(service.bootstrapEffects==1)
        }finally{service.close()}
    }
    for(open in listOf(false,true)) for(kind in listOf("dismiss","cancel")) {
        case("F1-$kind-stop${if(open)"-open" else ""}") {
            val manager=CommManager();val ctx=Context(manager);val ticket=manager.appConnectionTicket();val picker=Picker(ctx,ticket)
            manager.stop(open)
            if(kind=="dismiss")picker.dismiss() else picker.cancel()
            check(ContextCompat.starts.isEmpty()){ "revoked picker dispatched ${ContextCompat.starts.size} service starts" }
        }
    }
    for(kind in listOf("dismiss","cancel")) case("F1-$kind-control") {
        val manager=CommManager();val ctx=Context(manager);val ticket=manager.appConnectionTicket();val picker=Picker(ctx,ticket)
        if(kind=="dismiss")picker.dismiss() else picker.cancel()
        check(ContextCompat.starts.size==1)
        check(ContextCompat.starts.single().getLongExtra("ticket",-1)==ticket)
    }
    for(open in listOf(false,true)) case("F2-import-before-commit-stop${if(open)"-open" else ""}") {
        val manager=CommManager();val service=AapService(manager);AapService.instance=service
        try {
            val screen=SettingsScreen(service);val snapshot=ImportSnapshot(manager.appConnectionTicket());manager.stop(open);manager.isConnected=true
            val epoch=manager.appConnectionTicket();screen.applyPrepared(snapshot)
            check(manager.appConnectionTicket()==epoch){"old import issued a new Stop"}
            check(service.wifiLauncherManager.stops==0){"old import stopped successor wireless"}
            check(SettingsBackupManager.commits==0){"withdrawn import wrote preferences"}
        }finally{service.close()}
    }
    case("F2-import-committed-before-stop-keeps-view-consistent") {
        val manager=CommManager();val service=AapService(manager);AapService.instance=service
        try{
            val screen=SettingsScreen(service);val snapshot=ImportSnapshot(manager.appConnectionTicket());manager.stop(true);manager.isConnected=true
            val epoch=manager.appConnectionTicket();screen.complete(snapshot)
            check(manager.appConnectionTicket()==epoch){"completion stopped replacement"}
            check(service.wifiLauncherManager.stops==0){"completion stopped replacement radio"}
            check(screen.reloads==1&&!screen.hasChanges&&AppThemeManager.applied==1)
        }finally{service.close()}
    }
    for(start in listOf(false,true)) case("F2-wireless-${if(start)"start" else "stop"}-revoked") {
        val manager=CommManager();val service=AapService(manager);AapService.instance=service
        try{val ticket=manager.appConnectionTicket();manager.stop(true)
            AapService.applyWirelessSettings(service,start,ticket)
            check(service.parkedReleases==0&&service.wifiLauncherManager.stops==0&&service.wifiLauncherManager.arms.isEmpty()){"revoked inline radio effect executed"}
        }finally{service.close()}
    }
    case("F2-import-control") {
        val manager=CommManager();val service=AapService(manager);AapService.instance=service
        try{val screen=SettingsScreen(service);val snapshot=ImportSnapshot(manager.appConnectionTicket());manager.isConnected=true
            screen.applyPrepared(snapshot)
            check(SettingsBackupManager.commits==1&&screen.reloads==1&&!screen.hasChanges)
            check(service.wifiLauncherManager.stops==1&&ContextCompat.starts.single().action==AapService.ACTION_STOP_SERVICE)
        }finally{service.close()}
    }
    for(open in listOf(false,true)) case("F7-old-callback-stop${if(open)"-open" else ""}") {
        val manager=CommManager();val service=AapService(manager)
        try{val old=manager.appConnectionTicket();service.waitForWifi(old);val callback=service.connectivity.registered.last()
            callback.onAvailable(Network());manager.stop(open)
            if(open)service.waitForWifi(manager.appConnectionTicket())
            val newer=if(open)service.connectivity.registered.last() else null
            service.dispatcher.drain()
            check(service.wifiLauncherManager.arms.isEmpty()){"old callback armed ${service.wifiLauncherManager.arms}"}
            if(newer!=null){check(newer !in service.connectivity.removed){"old callback unregistered successor"};newer.onAvailable(Network());service.dispatcher.drain();check(service.wifiLauncherManager.arms==listOf(manager.appConnectionTicket()))}
        }finally{service.close()}
    }
    case("F7-replacement-same-ticket") {
        val manager=CommManager();val service=AapService(manager)
        try{val ticket=manager.appConnectionTicket();service.waitForWifi(ticket);val old=service.connectivity.registered.last();old.onAvailable(Network())
            service.waitForWifi(ticket);val newer=service.connectivity.registered.last();service.dispatcher.drain()
            check(service.wifiLauncherManager.arms.isEmpty()&&newer !in service.connectivity.removed){"old callback consumed same-epoch successor"}
            newer.onAvailable(Network());service.dispatcher.drain();newer.onAvailable(Network());service.dispatcher.drain();check(service.wifiLauncherManager.arms==listOf(ticket))
        }finally{service.close()}
    }
    case("F7-timeout-and-callback-complete-once") {
        val manager=CommManager();val service=AapService(manager)
        try{service.settings.waitForWifiTimeout=0;val ticket=manager.appConnectionTicket();service.waitForWifi(ticket)
            val callback=service.connectivity.registered.last();service.dispatcher.drain();callback.onAvailable(Network());service.dispatcher.drain()
            check(service.wifiLauncherManager.arms==listOf(ticket))
        }finally{service.close()}
    }
    case("F7-normal-control") {
        val manager=CommManager();val service=AapService(manager)
        try{val ticket=manager.appConnectionTicket();service.waitForWifi(ticket);val callback=service.connectivity.registered.last();callback.onAvailable(Network());service.dispatcher.drain()
            check(service.wifiLauncherManager.arms==listOf(ticket)&&callback in service.connectivity.removed)
        }finally{service.close()}
    }
    for(ended in listOf("finished","cancelled")) case("F8-direct-then-home-$ended") {
        val manager=CommManager();val activity=MainActivity(manager);activity.force(manager.appConnectionTicket())
        check(ContextCompat.starts.count{it.action==AapService.ACTION_START_SELF_MODE}==1)
        manager.isConnected=false
        Home(activity).enterHome()
        check(ContextCompat.starts.count{it.action==AapService.ACTION_START_SELF_MODE}==1){"Home replayed the $ended direct Self input"}
    }
    case("F8-normal-home-auto-control") {
        val manager=CommManager();val activity=MainActivity(manager);activity.settings.autoStartSelfMode=true;Home(activity).enterHome()
        check(ContextCompat.starts.size==1)
    }
    for(open in listOf(false,true)) case("F8-revoked-forced-stop${if(open)"-open" else ""}") {
        val manager=CommManager();val activity=MainActivity(manager);val ticket=manager.appConnectionTicket();manager.stop(open);activity.force(ticket)
        check(ContextCompat.starts.isEmpty()){ "revoked forced input dispatched" }
    }
    val activityOwnsConsent = false
    if(activityOwnsConsent) {
        for(stop in listOf(false,true)) case("F8-consent-${if(stop)"revoked" else "accepted"}-control") {
            val manager=CommManager();val activity=MainActivity(manager);SelfLauncherManager.path=0;VpnControl.available=true;VpnControl.consent=Intent("consent")
            val ticket=manager.appConnectionTicket();activity.force(ticket)
            check(ContextCompat.starts.isEmpty()&&activity.forcedSelfVpnPermission.launches.size==1)
            if(stop)manager.stop(true)
            activity.deliverConsent(ActivityResult(android.app.Activity.RESULT_OK))
            check(ContextCompat.starts.size==if(stop)0 else 1)
            if(!stop)check(ContextCompat.starts.single().getBooleanExtra(AapService.EXTRA_SELF_MODE_VPN,false))
            Home(activity).enterHome();check(ContextCompat.starts.size==if(stop)0 else 1)
        }
        for(fresh in listOf(false,true)) case("F8-consent-same-ticket-cancel${if(fresh)"-fresh-input" else ""}") {
            val manager=CommManager();val activity=MainActivity(manager);SelfLauncherManager.path=0;VpnControl.available=true;VpnControl.consent=Intent("consent")
            val ticket=manager.appConnectionTicket();activity.force(ticket);activity.cancelPendingSelfLaunch()
            check(manager.appConnectionTicket()==ticket)
            if(fresh)activity.force(ticket)
            activity.deliverConsent(ActivityResult(android.app.Activity.RESULT_OK))
            check(ContextCompat.starts.isEmpty()){"cancelled permission started Self"}
            if(fresh){
                check(activity.forcedSelfVpnPermission.launches.size==2)
                activity.deliverConsent(ActivityResult(android.app.Activity.RESULT_OK))
                check(ContextCompat.starts.size==1)
            }
        }
        case("F8-consent-keeps-original-owner-before-new-input") {
            val manager=CommManager();val activity=MainActivity(manager);SelfLauncherManager.path=0;VpnControl.available=true;VpnControl.consent=Intent("consent")
            val original=manager.appConnectionTicket();activity.force(original);manager.stop(true)
            val fresh=manager.appConnectionTicket();activity.force(fresh)
            check(activity.forcedSelfVpnPermission.launches.size==1)
            activity.deliverConsent(ActivityResult(android.app.Activity.RESULT_OK))
            check(ContextCompat.starts.isEmpty()&&activity.forcedSelfVpnPermission.launches.size==2)
            VpnControl.consent=null
            activity.deliverConsent(ActivityResult(android.app.Activity.RESULT_OK))
            check(ContextCompat.starts.size==1&&ContextCompat.starts.single().getLongExtra("ticket",-1)==fresh)
        }
    }
    val json=results.joinToString(",\n",prefix="[\n",postfix="\n]\n"){(name,outcome)->"  {\"case\":\"$name\",\"result\":\"${outcome.replace("\\","\\\\").replace("\"","\\\"")}\"}"}
    java.io.File(args.single()).writeText(json)
    val failed=results.count{it.second!="PASS"};println("RESULT ${results.size-failed}/${results.size} PASS, $failed FAIL")
    if(failed>0)throw IllegalStateException("$failed behavioral regressions failed")
}
