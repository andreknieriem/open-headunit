#!/usr/bin/env python3
"""Execute native radio-wait and TCP-probe production bodies against the real host CommManager.

Optional arguments: source-root, host-build-dir, output-dir. The baseline adapter changes only
new required parameter plumbing; it never supplies a missing authority check to old production.
"""
from pathlib import Path
import hashlib, json, os, subprocess, sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path(__file__).resolve().parents[4]
HOST=Path(sys.argv[2]).resolve() if len(sys.argv)>2 else ROOT/'build/host-connection'
OUT=Path(sys.argv[3]).resolve() if len(sys.argv)>3 else HOST/'wireless-authority'
OUT.mkdir(parents=True,exist_ok=True)
BASE=ROOT/'app/src/main/java/com/andrerinas/openheadunit'
new=(BASE/'connection/ConnectionOperation.kt').exists()
hashes={}
def member(path, signature):
    text=(BASE/path).read_text(); start=text.index(signature); opening=text.index('{',start); depth=0
    for end in range(opening,len(text)):
        if text[end]=='{': depth+=1
        elif text[end]=='}':
            depth-=1
            if depth==0:
                value=text[start:end+1]
                hashes[str(path)+':'+signature]=hashlib.sha256(value.encode()).hexdigest()
                return value
    raise ValueError(signature)
def adapt(value):
    return value.replace('App.provide(context).commManager','commManager').replace('App.provide(service).commManager','service.commManager').replace('Dispatchers.Main','main')
native=adapt(member('connection/wifi/modes/WifiLauncherNative.kt','private fun createWhenRadioIsFree('))
probe=adapt(member('connection/wifi/NetworkDiscovery.kt','private suspend fun checkAndReport('))
port=adapt(member('connection/wifi/NetworkDiscovery.kt','private fun checkPort(')).replace('val socket = Socket()','val socket = DialSocket(this)')
if not new:
    native=native.replace('wifiDirect: WifiDirectManager)', 'wifiDirect: WifiDirectManager, operation: ConnectionOperation)').replace('startNativeAaQuietHost()', 'startNativeAaQuietHost(operation)')
    probe=probe.replace('ticket: Long)', 'operation: ConnectionOperation)').replace('        if (reportedIps', '        val ticket = operation.ticket\n        if (reportedIps',1)
    probe=probe.replace('null, ticket)', 'null, operation)').replace('serverSocket, ticket)', 'serverSocket, operation)')
    port=port.replace('port: Int, timeout:', 'port: Int, operation: ConnectionOperation, timeout:')
    probe=probe.replace('checkPort(ip, 5289, timeout', 'checkPort(ip, 5289, operation, timeout').replace('checkPort(ip, 5277, timeout','checkPort(ip, 5277, operation, timeout')
    operation='class ConnectionOperation(val ticket:Long, val save:ConnectionState.Disconnected?)'
else:
    operation=''
disconnect='comm.disconnect(ConnectionOperation(comm.appConnectionTicket(), null), ' if new else 'comm.disconnect('
stop='comm.requestExplicitAppExit(ticket = comm.appConnectionTicket(), killProcess = false)' if new else 'comm.requestExplicitAppExit(killProcess = false)'
fixture=r'''package lifecycle
import kotlinx.coroutines.*
import java.net.Socket
import java.net.SocketAddress
import java.net.InetSocketAddress
import kotlin.coroutines.coroutineContext
import java.util.concurrent.atomic.AtomicInteger

OPERATION
private object WireLog { fun i(s:String){}; fun w(s:String){}; fun e(s:String,e:Throwable){} }
private class NativeService(val commManager:CommManager) {
    val main=QueuedDispatcher()
    val serviceScope=CoroutineScope(SupervisorJob()+main)
}
private class Shared(val wifiDirectManager:WifiDirectManager, var hotspotTeardown:Job?)
private class Manager(val sharedServices:Shared) { var active:Any?=null }
private class WifiDirectManager {
    var creates=0
    var releases=0
    fun startNativeAaQuietHost(operation:ConnectionOperation){ creates++ }
    fun claimNativeCreateWindow(why:String, owner:ConnectionOperation){ }
    fun releaseNativeCreateWindow(why:String, owner:ConnectionOperation){ releases++ }
    fun claimNativeCreateWindow(why:String){ }
    fun releaseNativeCreateWindow(why:String){ releases++ }
}
private class WifiLauncherNative(val service:NativeService, val manager:Manager) {
    companion object { const val HOTSPOT_TEARDOWN_CEILING_MS=10_000L }
    fun enter(op:ConnectionOperation)=createWhenRadioIsFree(manager.sharedServices.wifiDirectManager,op)
    private fun isCurrent(operation:ConnectionOperation)=manager.active===this && CURRENT
    NATIVE
}
private interface ProbeListener {
    fun onServiceFound(ip:String,port:Int,socket:Socket?,operation:ConnectionOperation)
}
private class DialSocket(val owner:Probe) : Socket() {
    override fun connect(endpoint:SocketAddress, timeout:Int) {
        val port=(endpoint as InetSocketAddress).port
        owner.dials+=port
        owner.afterDial?.invoke(port)
        if (port != owner.answerPort) throw java.net.ConnectException("closed test endpoint")
    }
}
private class Probe(val commManager:CommManager, val main:QueuedDispatcher, val listener:ProbeListener) {
    var answerPort=5277
    var probeHelperPort=true
    val dials=mutableListOf<Int>()
    var afterDial:((Int)->Unit)?=null
    private val reportedIps=mutableSetOf<String>()
    private val probesFailed=AtomicInteger()
    private var lastProbeFailure:String?=null
    suspend fun run(op:ConnectionOperation)=checkAndReport("192.0.2.200",op)
    PROBE
    PORT
}
private fun withdraw(comm:CommManager,kind:String) {
    if(kind=="stop" || kind=="stop_open") { STOP }
    if(kind=="stop_open") comm.onAppRelaunched()
    if(kind=="save_cancel") { DISCONNECTisUserExit=true,honorKillOnDisconnect=false); comm.cleanups() }
}
fun main()=runBlocking {
    var failures=0
    val results=mutableListOf<String>()
    fun case(name:String, test:()->String) {
        try { val detail=test(); println("PASS $name $detail"); results+="{\"case\":\"$name\",\"pass\":true,\"detail\":\"$detail\"}" }
        catch(e:Throwable) { failures++; println("FAIL $name ${e.message}"); results+="{\"case\":\"$name\",\"pass\":false,\"detail\":\"${e.message?.replace('"','\'')}\"}" }
    }
    for(kind in listOf("accepted","stop","stop_open","save_cancel","replacement")) case("F4_native_wait_$kind") {
        val comm=CommManager()
        if(kind=="save_cancel") { DISCONNECTisUserExit=false,honorKillOnDisconnect=false,reason=DisconnectReason.SETTINGS_RESTART); comm.cleanups() }
        val save=if(kind=="save_cancel") comm.state as ConnectionState.Disconnected else null
        val op=ConnectionOperation(comm.appConnectionTicket(),save)
        val service=NativeService(comm)
        val gate=CompletableDeferred<Unit>()
        val radio=WifiDirectManager(); val manager=Manager(Shared(radio,gate)); val native=WifiLauncherNative(service,manager)
        manager.active=native
        try {
            native.enter(op); service.main.drain()
            withdraw(comm,kind)
            if(kind=="replacement") manager.active=Any()
            gate.complete(Unit); service.main.drain()
            val expected=if(kind=="accepted") 1 else 0
            check(radio.creates==expected){"creates=${radio.creates}, expected=$expected"}
            "creates=${radio.creates}, releases=${radio.releases}"
        } finally { service.serviceScope.cancel(); comm.close() }
    }
    for(kind in listOf("accepted","stop","stop_open","save_cancel")) for(port in listOf(5277,5289)) case("F5_first_probe_${port}_$kind") {
        val comm=CommManager()
        DISCONNECTisUserExit=false,honorKillOnDisconnect=false,reason=DisconnectReason.SETTINGS_RESTART); comm.cleanups()
        val save=comm.state as ConnectionState.Disconnected
        val op=ConnectionOperation(comm.appConnectionTicket(),save)
        val main=QueuedDispatcher(); val io=QueuedDispatcher(); val scope=CoroutineScope(SupervisorJob()+io)
        var delivered=0
        val listener=object:ProbeListener { override fun onServiceFound(ip:String,port:Int,socket:Socket?,operation:ConnectionOperation) { delivered++; if(socket!=null && HeldServerSocket.adopt(socket)) socket.close() } }
        val probe=Probe(comm,main,listener).apply { answerPort=port; probeHelperPort=port==5289 }
        try {
            scope.launch { try { probe.run(op) } catch(_:CancellationException){} }
            withdraw(comm,kind)
            io.drain(); Thread.sleep(510); io.drain(); main.drain(); io.drain()
            val expected=if(kind=="accepted") 1 else 0
            check(probe.dials.size==expected && delivered==expected){"dials=${probe.dials.size}, deliveries=$delivered, expected=$expected"}
            "dials=${probe.dials.size}, deliveries=$delivered"
        } finally { scope.cancel(); HeldServerSocket.discardAll(); comm.close() }
    }
    // The helper probe may finish just as Save is cancelled; the following 5277 dial must not start.
    case("F5_save_cancel_between_ports") {
        val comm=CommManager()
        DISCONNECTisUserExit=false,honorKillOnDisconnect=false,reason=DisconnectReason.SETTINGS_RESTART); comm.cleanups()
        val save=comm.state as ConnectionState.Disconnected
        val op=ConnectionOperation(comm.appConnectionTicket(),save)
        val main=QueuedDispatcher(); val io=QueuedDispatcher(); val scope=CoroutineScope(SupervisorJob()+io)
        var delivered=0
        val probe=Probe(comm,main,object:ProbeListener { override fun onServiceFound(ip:String,port:Int,socket:Socket?,operation:ConnectionOperation){delivered++;if(socket!=null && HeldServerSocket.adopt(socket))socket.close()} })
        probe.afterDial={port->if(port==5289)withdraw(comm,"save_cancel")}
        try {
            scope.launch{ try{probe.run(op)}catch(_:CancellationException){} }; io.drain(); main.drain(); io.drain()
            check(probe.dials==listOf(5289) && delivered==0){"dials=${probe.dials}, deliveries=$delivered"}
            "dials=${probe.dials}, deliveries=$delivered"
        } finally { scope.cancel(); HeldServerSocket.discardAll(); comm.close() }
    }
    java.io.File(System.getProperty("wireless.results")).writeText("[\n"+results.joinToString(",\n")+"\n]\n")
    check(failures==0){"$failures wireless authority regressions failed"}
}
'''
fixture=fixture.replace('OPERATION',operation).replace('CURRENT','service.commManager.isCurrent(operation)' if new else 'service.commManager.acceptsAppConnectionTicket(operation.ticket)')
fixture=fixture.replace('NATIVE',native).replace('PROBE',probe).replace('PORT',port).replace('STOP',stop).replace('DISCONNECT',disconnect).replace('AppLog.','WireLog.')
(OUT/'WirelessAuthorityFixture.kt').write_text(fixture)
(OUT/'production-method-sha256.json').write_text(json.dumps(hashes,indent=2))
(OUT/'adaptations.txt').write_text('Required API plumbing and Android/socket doubles only. Baseline authority parameters are ignored by the old producer. Assertions and schedules are identical.\n')
toolchain=json.loads((HOST/'toolchain.json').read_text())
cp=os.pathsep.join(x['path'] for x in toolchain['jars'])
compile_cmd=['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-nowarn','-Xfriend-paths='+str(HOST/'probe.jar'),'-classpath',str(HOST/'probe.jar')+os.pathsep+cp,'-d',str(OUT/'cases.jar'),str(OUT/'WirelessAuthorityFixture.kt')]
run_cmd=['java','-Dwireless.results='+str(OUT/'results.json'),'-cp',str(OUT/'cases.jar')+os.pathsep+str(HOST/'probe.jar')+os.pathsep+cp,'lifecycle.WirelessAuthorityFixtureKt']
(OUT/'commands.json').write_text(json.dumps({'compile':compile_cmd,'run':run_cmd,'source_root':str(ROOT),'host_build':str(HOST),'baseline_api_adapter':not new},indent=2))
subprocess.run(compile_cmd,check=True,timeout=90)
subprocess.run(run_cmd,check=True,timeout=35)
