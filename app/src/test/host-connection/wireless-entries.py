#!/usr/bin/env python3
"""Production wireless effect-entry/owner cleanup bodies with actual host CommManager.

Baseline adaptation supplies only the new operation parameters/return-to-caller shape;
it does not add admission or owner checks. Android radio, sockets and service fields are doubled.
"""
from pathlib import Path
import hashlib,json,os,subprocess,sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path(__file__).resolve().parents[4]
HOST=Path(sys.argv[2]).resolve() if len(sys.argv)>2 else ROOT/'build/host-connection'
OUT=Path(sys.argv[3]).resolve() if len(sys.argv)>3 else HOST/'wireless-entries'
OUT.mkdir(parents=True,exist_ok=True)
BASE=ROOT/'app/src/main/java/com/andrerinas/openheadunit'
new=(BASE/'connection/ConnectionOperation.kt').exists()
hashes={}
def member(path,signature,until_doc=False):
    text=(BASE/path).read_text();start=text.index(signature)
    if until_doc:end=text.index('\n    /**',start)
    else:
        opening=text.index('{',start);depth=0
        for end in range(opening,len(text)):
            if text[end]=='{':depth+=1
            elif text[end]=='}':
                depth-=1
                if depth==0:end+=1;break
    body=text[start:end]
    hashes[str(path)+':'+signature]=hashlib.sha256(body.encode()).hexdigest()
    return body
native=member('connection/wifi/modes/nativeaa/NativeAaHandshakeManager.kt','private fun listenOnAaUuid(',True)
wpp=member('connection/wifi/modes/nativeaa/WppTcpServer.kt','fun start(')
wpp_stop=member('connection/wifi/modes/nativeaa/WppTcpServer.kt','fun stop(')
manager_path='connection/wifi/WifiLauncherManager.kt'
manager=member(manager_path,'fun stop(owner:' if new else 'fun stop(seq:')
if new:
    manager+='\n'+member(manager_path,'private fun detachCurrent(')+'\n'+member(manager_path,'internal fun detachForOperation(')+'\n'+member(manager_path,'internal fun detachForClaim(')+'\n'+member(manager_path,'fun stopForOperation(')
    shared='\n'.join(member('connection/wifi/WifiLauncherSharedServices.kt',name) for name in [
        'internal fun detachAll(', 'private fun detachWifiDirect(', 'private fun detachWirelessServer(', 'private fun detachLocalDiscovery('])
    station=member('connection/wifi/direct/StationStandDown.kt','private fun restoreOwned(')
    station='fun restore(context:Context, operation:ConnectionOperation)=restoreOwned(context, operation)\n'+station
else:
    # Old producer has no detachable authority API: queue its actual global stop body.
    manager+='\nfun detachForOperation(operation:ConnectionOperation): (() -> Unit)? = { stop() }\nfun detachForClaim(owner:ConnectionArbiter.Claim): (() -> Unit)? = { stop() }\nfun stopForOperation(operation:ConnectionOperation):Boolean { stop(); return true }'
    shared=''
    station=member('connection/wifi/direct/StationStandDown.kt','private fun restoreLocked(')
    station='fun restore(context:Context, operation:ConnectionOperation)=synchronized(this) { restoreLocked(context) }\n'+station
server_start=member('connection/wifi/WifiLauncherSharedServices.kt','fun startWirelessServer(')
if not new: server_start=server_start.replace('launcher: WifiLauncher)', 'launcher: WifiLauncher, operation: ConnectionOperation)')
server_start=server_start.replace('App.provide(service).commManager','service.commManager')
station=station.replace('App.provide(context).settings','context.settings')
station=station.replace('StationStandDownPolicy','StationPolicyDouble').replace('StationStandDownReassertPolicy','StationReassertDouble')
manager=manager.replace('App.provide(service).commManager','service.commManager')
shared=shared.replace('scanningState.value','service.scanning')
wpp=wpp.replace('Dispatchers.IO','io')
if not new:
    native=native.replace('adapter: BluetoothAdapter)', 'adapter: BluetoothAdapter, command: ConnectionOperation)')
operation='' if new else 'class ConnectionOperation(val ticket:Long,val save:ConnectionState.Disconnected?)'
stop='comm.requestExplicitAppExit(ticket=comm.appConnectionTicket(),killProcess=false)' if new else 'comm.requestExplicitAppExit(killProcess=false)'
disconnect='comm.disconnect(ConnectionOperation(comm.appConnectionTicket(),null), ' if new else 'comm.disconnect('
fixture=r'''package lifecycle
import kotlinx.coroutines.*
import java.net.*
import android.os.SystemClock

@OPERATION@
private object EntryLog { fun i(s:String){};fun w(s:String){};fun d(s:String){};fun e(s:String,e:Throwable){} }
private class BluetoothServerSocket { var closed=false; fun close(){closed=true} }
private class BluetoothAdapter {
    var registrations=0
    fun listenUsingInsecureRfcommWithServiceRecord(name:String,uuid:String)=BluetoothServerSocket().also{registrations++}
    fun listenUsingRfcommWithServiceRecord(name:String,uuid:String)=BluetoothServerSocket().also{registrations++}
}
private class NativeSettings {var insecureAaRfcommListener=false}
private class NativeListener(val commManager:CommManager,val operation:ConnectionOperation) {
    val settings=NativeSettings(); var isRunning=true
    val AA_UUID="test"
    private fun isCurrent(command:ConnectionOperation)=commManager.isCurrent(operation)&&commManager.isCurrent(command)
    fun enter(adapter:BluetoothAdapter)=listenOnAaUuid(adapter,operation)
    @LISTENER@
}
private class BoundSocket:ServerSocket() {
    var binds=0
    override fun setReuseAddress(on:Boolean) {}
    override fun close() {}
    override fun bind(endpoint:SocketAddress?) { binds++ }
    override fun accept():Socket=throw SocketException("fixture has no inbound peer")
}
private class WppTcpServer(val commManager:CommManager,val operation:ConnectionOperation,val io:QueuedDispatcher) {
    val scope=CoroutineScope(SupervisorJob()+io)
    val listener=BoundSocket()
    val createServerSocket:()->ServerSocket={listener}
    val createTlsFactory:()->String={"tls-double"}
    var serverSocket:ServerSocket?=null;var acceptJob:Job?=null;var running=false;var runGeneration=0L
    val connections=mutableMapOf<InetAddress,Socket>()
    val PORT=5299;val BIND_ATTEMPTS=3;val BIND_RETRY_DELAY_MS=1L;val MAX_CONTROL_PEERS=4
    fun isCurrentRun(generation:Long)=running && runGeneration==generation
    suspend fun handleConnection(socket:Socket,factory:String,generation:Long){}
    @WPP@
    @WPP_STOP@
}
private enum class WifiLauncherStopSequence { ANY,LAST,BEFORE_HOTSPOT_DISABLE;fun handledAt(seq:WifiLauncherStopSequence)=this==ANY||this==seq }
private object WirelessSleepHold {fun stopped(){}}
private class Resource {var stops=0;fun stop(keep:Boolean=false){stops++};fun stopServer(){stops++}}
private class Launcher {var stops=0;fun stop(seq:WifiLauncherStopSequence){stops++}}
private class Service(val commManager:CommManager) {var rescanWithoutWaiting=false;var discoveryDormantAfterWifiLoss=false;var scanning=false}
private class Shared(val service:Service) {
    var wifiDirectManager:Resource?=null;var wirelessServer:Resource?=null;var localDiscovery:Resource?=null
    var hotspotTeardown:Job?=null;var discoveryOperation:ConnectionOperation?=null
    var directStartOwner:Any?=null;var serverStartOwner:Any?=null;var discoveryStartOwner:Any?=null
    fun stopAll(keep:Boolean) {wifiDirectManager?.stop(keep);wirelessServer?.stopServer();localDiscovery?.stop();wifiDirectManager=null;wirelessServer=null;localDiscovery=null}
    @SHARED@
}
private class LauncherManager(val service:Service) {
    val cleanupGeneration=WirelessCleanupGeneration()
    val sharedServices=Shared(service)
    var active:Launcher?=null;var armedOperation:ConnectionOperation?=null;var activeIsStarted=false;var lastNativeRearmAtMs=0L
    fun stopOwned(owner:Any) { @STOP_OWNED@ }
    @MANAGER@
}
private class WifiLauncher {fun hasLocalDiscovery()=false}
private class WirelessServer(val registerNsd:Boolean,val service:Service,val history:WirelessServerHistory,val operation:ConnectionOperation?=null) {
    companion object {var onConstruct:(()->Unit)?=null;val created=mutableListOf<WirelessServer>()}
    var starts=0;var stops=0;var isAlive=false;var isListening=false
    init {created+=this;val hook=onConstruct;onConstruct=null;hook?.invoke()}
    fun start(){starts++;isAlive=true}
    fun stopServer(){stops++;isAlive=false}
}
private class ServerShared(val service:Service) {
    var wirelessServer:WirelessServer?=null
    var serverStartOwner:Any?=null
    val wirelessServerHistory=WirelessServerHistory()
    @SERVER_START@
}
private class StationSettings {var stationStandDownNetworkId=7}
private class WifiManager {val enabled=mutableListOf<Int>();var reconnects=0;fun enableNetwork(id:Int,disableOthers:Boolean):Boolean{enabled+=id;return true};fun reconnect(){reconnects++}}
private class Context(val settings:StationSettings=StationSettings(),val wifi:WifiManager=WifiManager()) {
    companion object{val WIFI_SERVICE="wifi"}
    val applicationContext get()=this
    fun getSystemService(key:String):Any=wifi
}
private object StationPolicyDouble {fun shouldRestore(id:Int)=id>=0}
private object StationReassertDouble {fun retiresRejoinIssue(live:Boolean,won:Boolean)=false}
private object ConnectionIssues {fun clear(context:Context,issue:String){}}
private object ConnectionIssue {val HOME_WIFI_REJOINED_BESIDE_GROUP="station"}
private class StationOwner {
    var owner:ConnectionOperation?=null;var sessionLiveSeen=false;var platformWon=false
    fun clearReassertState(){}
    @STATION@
}
private fun withdraw(comm:CommManager,kind:String){
    if(kind=="stop"||kind=="stop_open"){@STOP@}
    if(kind=="stop_open")comm.onAppRelaunched()
    if(kind=="save_cancel"){@DISCONNECT@isUserExit=true,honorKillOnDisconnect=false);comm.cleanups()}
}
fun main()=runBlocking {
    var failures=0;val results=mutableListOf<String>()
    fun case(name:String,body:()->String){try{val detail=body();println("PASS $name $detail");results+="{\"case\":\"$name\",\"pass\":true,\"detail\":\"$detail\"}"}catch(e:Throwable){failures++;println("FAIL $name ${e.message}");results+="{\"case\":\"$name\",\"pass\":false,\"detail\":\"${e.message?.replace('"','\'')}\"}"}}
    for(kind in listOf("accepted","stop","stop_open","save_cancel")){
        case("F4_listener_effect_$kind"){
            val comm=CommManager()
            @DISCONNECT@isUserExit=false,honorKillOnDisconnect=false,reason=DisconnectReason.SETTINGS_RESTART);comm.cleanups()
            val op=ConnectionOperation(comm.appConnectionTicket(),comm.state as ConnectionState.Disconnected)
            val listener=NativeListener(comm,op);val radio=BluetoothAdapter()
            try{withdraw(comm,kind);listener.enter(radio);val expected=if(kind=="accepted")1 else 0;check(radio.registrations==expected){"registrations=${radio.registrations}, expected=$expected"};"registrations=${radio.registrations}"}finally{comm.close()}
        }
        case("F4_wpp_queued_bind_$kind"){
            val comm=CommManager()
            @DISCONNECT@isUserExit=false,honorKillOnDisconnect=false,reason=DisconnectReason.SETTINGS_RESTART);comm.cleanups()
            val op=ConnectionOperation(comm.appConnectionTicket(),comm.state as ConnectionState.Disconnected)
            val io=QueuedDispatcher();val server=WppTcpServer(comm,op,io)
            try{server.start();withdraw(comm,kind);io.drain();val expected=if(kind=="accepted")1 else 0;check(server.listener.binds==expected){"binds=${server.listener.binds}, expected=$expected"};"binds=${server.listener.binds}"}finally{server.stop();server.scope.cancel();comm.close()}
        }
    }
    for(kind in listOf("accepted","stop","stop_open","save_cancel","replacement"))case("shared_server_publication_$kind"){
        val comm=CommManager()
        @DISCONNECT@isUserExit=false,honorKillOnDisconnect=false,reason=DisconnectReason.SETTINGS_RESTART);comm.cleanups()
        val op=ConnectionOperation(comm.appConnectionTicket(),if(kind=="save_cancel")comm.state as ConnectionState.Disconnected else null)
        val shared=ServerShared(Service(comm));val launcher=WifiLauncher()
        WirelessServer.created.clear()
        if(kind!="accepted")WirelessServer.onConstruct={withdraw(comm,kind);if(kind=="stop_open"||kind=="replacement")shared.startWirelessServer(launcher,ConnectionOperation(comm.appConnectionTicket(),null))}
        try{
            shared.startWirelessServer(launcher,op)
            val original=WirelessServer.created.first();val successor=WirelessServer.created.last()
            when(kind){
                "accepted"->check(shared.wirelessServer===original && original.starts==1 && original.stops==0){"accepted candidate missing"}
                "stop_open","replacement"->check(shared.wirelessServer===successor && original!==successor && original.starts==0 && original.stops==1 && successor.starts==1 && successor.stops==0){"successor overwritten: original=${original.starts}/${original.stops}, successor=${successor.starts}/${successor.stops}"}
                else->check(shared.wirelessServer==null && original.starts==0 && original.stops==1){"withdrawn candidate published: starts=${original.starts}, stops=${original.stops}"}
            }
            "candidates=${WirelessServer.created.size}, original=${original.starts}/${original.stops}"
        }finally{WirelessServer.onConstruct=null;comm.close()}
    }
    for(replace in listOf(false,true))case("station_restore_owner_$replace"){
        val comm=CommManager();val original=ConnectionOperation(comm.appConnectionTicket(),null);val successor=ConnectionOperation(comm.appConnectionTicket(),null)
        val context=Context();val station=StationOwner();station.owner=if(replace)successor else original
        try{station.restore(context,original);val expected=if(replace)0 else 1;check(context.wifi.enabled.size==expected){"restores=${context.wifi.enabled.size}, expected=$expected"};check(context.settings.stationStandDownNetworkId==if(replace)7 else -1){"successor persisted record changed"};"restores=${context.wifi.enabled.size}, record=${context.settings.stationStandDownNetworkId}"}finally{comm.close()}
    }
    for(replace in listOf(false,true))case("manager_detached_cleanup_$replace"){
        val comm=CommManager();val service=Service(comm);val manager=LauncherManager(service)
        val op=ConnectionOperation(comm.appConnectionTicket(),null);val old=Launcher();val oldServer=Resource();val successor=Launcher();val freshServer=Resource()
        manager.active=old;manager.armedOperation=op;manager.sharedServices.wirelessServer=oldServer
        try{val cleanup=checkNotNull(manager.detachForOperation(op));if(replace){manager.active=successor;manager.armedOperation=ConnectionOperation(op.ticket,null);manager.sharedServices.wirelessServer=freshServer};cleanup();check(old.stops==1 && oldServer.stops==1 && successor.stops==0 && freshServer.stops==0){"old=${old.stops}/${oldServer.stops}, successor=${successor.stops}/${freshServer.stops}"};if(replace)check(manager.active===successor && manager.sharedServices.wirelessServer===freshServer);"old=${old.stops}/${oldServer.stops}, successor=${successor.stops}/${freshServer.stops}"}finally{comm.close()}
    }
    for(kind in listOf("stop","stop_open"))case("manager_command_stop_$kind"){
        val comm=CommManager();val manager=LauncherManager(Service(comm));val op=ConnectionOperation(comm.appConnectionTicket(),null);val owner=Launcher();manager.active=owner;manager.armedOperation=op
        try{withdraw(comm,kind);check(!manager.stopForOperation(op) && owner.stops==0 && manager.active===owner){"old command stopped=${owner.stops}"};"stops=${owner.stops}"}finally{comm.close()}
    }
    for(kind in listOf("accepted","replaced_before","successor_after"))case("manager_claim_detach_$kind"){
        val comm=CommManager();val manager=LauncherManager(Service(comm));val old=Launcher();val oldServer=Resource();val successor=Launcher();val freshServer=Resource()
        manager.active=old;manager.activeIsStarted=true;manager.armedOperation=ConnectionOperation(comm.appConnectionTicket(),null);manager.sharedServices.wirelessServer=oldServer
        val owner=checkNotNull(ConnectionArbiter.claim(ConnectionPriorityPolicy.Tier.USER,ConnectionPriorityPolicy.Owner.MANUAL,"first"))
        if(kind=="replaced_before")checkNotNull(ConnectionArbiter.claim(ConnectionPriorityPolicy.Tier.USER,ConnectionPriorityPolicy.Owner.MANUAL,"next"))
        try{
            val cleanup=comm.withAppConnectionLifecycleLock { manager.detachForClaim(owner).also { check(old.stops==0&&oldServer.stops==0){"external cleanup ran under caller lifecycle lock"} } }
            if(kind=="replaced_before")check(cleanup==null&&manager.active===old&&old.stops==0){"replaced claim detached current launcher"}
            else {
                checkNotNull(cleanup)
                if(kind=="successor_after"){manager.active=successor;manager.activeIsStarted=true;manager.armedOperation=ConnectionOperation(comm.appConnectionTicket(),null);manager.sharedServices.wirelessServer=freshServer}
                cleanup()
                check(old.stops==1&&oldServer.stops==1&&successor.stops==0&&freshServer.stops==0){"old=${old.stops}/${oldServer.stops}, successor=${successor.stops}/${freshServer.stops}"}
            }
            "old=${old.stops}/${oldServer.stops}, successor=${successor.stops}/${freshServer.stops}"
        }finally{comm.close()}
    }
    for(replace in listOf(false,true))case("manager_cleanup_generation_$replace"){
        val comm=CommManager();val manager=LauncherManager(Service(comm));val launcher=Launcher();val server=Resource();manager.active=launcher;manager.activeIsStarted=true;manager.sharedServices.wirelessServer=server
        val owner=manager.cleanupGeneration.capture();if(replace)manager.cleanupGeneration.advance()
        try{manager.stopOwned(owner);val expected=if(replace)0 else 1;check(launcher.stops==expected&&server.stops==expected){"cleanup=${launcher.stops}/${server.stops}, expected=$expected"};"cleanup=${launcher.stops}/${server.stops}"}finally{comm.close()}
    }
    java.io.File(System.getProperty("wireless.results")).writeText("[\n"+results.joinToString(",\n")+"\n]\n")
    check(failures==0){"$failures wireless entry cases failed"}
}
'''
for key,value in {'STOP_OWNED':'stop(owner)' if new else 'stop()','OPERATION':operation,'LISTENER':native,'WPP':wpp,'WPP_STOP':wpp_stop,'SERVER_START':server_start,'SHARED':shared,'MANAGER':manager,'STATION':station,'STOP':stop,'DISCONNECT':disconnect}.items():fixture=fixture.replace('@'+key+'@',value)
fixture=fixture.replace('AppLog.','EntryLog.')
if not new:fixture=fixture.replace('commManager.isCurrent(operation)&&commManager.isCurrent(command)', 'commManager.acceptsAppConnectionTicket(operation.ticket)&&commManager.acceptsAppConnectionTicket(command.ticket)')
(OUT/'WirelessEntries.kt').write_text(fixture)
held=(BASE/'connection/HeldServerSocket.kt').read_text().replace('package com.andrerinas.openheadunit.connection','package lifecycle')
(OUT/'HeldServerSocket.kt').write_text(held)
cleanup=(BASE/'connection/wifi/WirelessCleanupGeneration.kt').read_text().replace('package com.andrerinas.openheadunit.connection.wifi','package lifecycle')
(OUT/'WirelessCleanupGeneration.kt').write_text(cleanup)
for name in ['WirelessServerHistory.kt','WirelessServerRestartPolicy.kt']:
    policy=(BASE/'connection/wifi/server'/name).read_text().replace('package com.andrerinas.openheadunit.connection.wifi.server','package lifecycle')
    (OUT/name).write_text(policy)

(OUT/'production-method-sha256.json').write_text(json.dumps(hashes,indent=2))
(OUT/'adaptations.txt').write_text('Baseline: extra operation is unused by original listener/stop/restore. Baseline detachForOperation returns its actual stop closure; after returns exact production detachable cleanup. Same schedules/assertions. Radio/socket doubles only; actual CommManager remains from probe.jar.\n')
tc=json.loads((HOST/'toolchain.json').read_text());cp=os.pathsep.join(x['path'] for x in tc['jars'])
compile=['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-nowarn','-Xfriend-paths='+str(HOST/'probe.jar'),'-classpath',str(HOST/'probe.jar')+os.pathsep+cp,'-d',str(OUT/'cases.jar'),str(OUT/'WirelessEntries.kt'),str(OUT/'HeldServerSocket.kt'),str(OUT/'WirelessServerHistory.kt'),str(OUT/'WirelessServerRestartPolicy.kt'),str(OUT/'WirelessCleanupGeneration.kt')]
run=['java','-Dwireless.results='+str(OUT/'results.json'),'-cp',str(OUT/'cases.jar')+os.pathsep+str(HOST/'probe.jar')+os.pathsep+cp,'lifecycle.WirelessEntriesKt']
(OUT/'commands.json').write_text(json.dumps({'compile':compile,'run':run,'source_root':str(ROOT),'baseline_api_adapter':not new},indent=2))
subprocess.run(compile,check=True,timeout=90)
subprocess.run(run,check=True,timeout=35)
