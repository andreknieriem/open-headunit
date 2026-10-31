#!/usr/bin/env python3
"""Real Native listener/reopen/HFP and SoftAP refresh/enable bodies, original arm + command Save.

Physical radios/hotspot and dispatch are controlled doubles; CommManager and HFP protocol are real.
Baseline adaptation adds only required command parameters/call plumbing, never an admission check.
"""
from pathlib import Path
import hashlib,json,os,subprocess,sys
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path(__file__).resolve().parents[4]
HOST=Path(sys.argv[2]).resolve() if len(sys.argv)>2 else ROOT/'build/host-connection'
OUT=Path(sys.argv[3]).resolve() if len(sys.argv)>3 else HOST/'native-commands'
OUT.mkdir(parents=True,exist_ok=True)
BASE=ROOT/'app/src/main/java/com/andrerinas/openheadunit'
new=(BASE/'connection/ConnectionOperation.kt').exists();hashes={}
def member(file,key,until=None):
    source=(BASE/file).read_text();start=source.index(key)
    if until:end=source.index(until,start)
    else:
        paren=source.index('(',start);depth=0
        for at in range(paren,len(source)):
            if source[at]=='(':depth+=1
            elif source[at]==')':
                depth-=1
                if depth==0:break
        brace=source.index('{',at+1);depth=0
        for end in range(brace,len(source)):
            if source[end]=='{':depth+=1
            elif source[end]=='}':
                depth-=1
                if depth==0:end+=1;break
    value=source[start:end]
    hashes[file+':'+key]=hashlib.sha256(value.encode()).hexdigest();return value
native='connection/wifi/modes/nativeaa/NativeAaHandshakeManager.kt'
softap='connection/wifi/modes/nativeaa/SoftApCredentialsProvider.kt'
listener=member(native,'private fun listenOnAaUuid(', '\n    /**')
reopen=member(native,'private fun reopenAaListener(')
hfp=member(native,'private suspend fun serveHfpSocket(')
refresh=member(softap,'fun refresh(')
enable=member(softap,'private fun enableHotspot(', '\n    private fun beginResolve(')
if not new:
    listener=listener.replace('adapter: BluetoothAdapter)', 'adapter: BluetoothAdapter, command: ConnectionOperation)')
    reopen=reopen.replace('why: String)', 'why: String, command: ConnectionOperation)').replace('launchAaAcceptLoop(adapter, localRadioName)', 'launchAaAcceptLoop(adapter, localRadioName, command = command)').replace('launchAaAcceptLoop(it.adapter, radioName, it.serviceName)', 'launchAaAcceptLoop(it.adapter, radioName, it.serviceName, command)')
    hfp=hfp.replace('closeWhenDone: Boolean,', 'closeWhenDone: Boolean, command: ConnectionOperation,')
    refresh=refresh.replace('fun refresh()', 'fun refresh(command: ConnectionOperation)').replace('beginResolve()', 'beginResolve(command)')
    enable=enable.replace('fun enableHotspot()', 'fun enableHotspot(command: ConnectionOperation)')
operation='' if new else 'class ConnectionOperation(val ticket:Long,val save:ConnectionState.Disconnected?)'
stop='comm.requestExplicitAppExit(ticket=comm.appConnectionTicket(),killProcess=false)' if new else 'comm.requestExplicitAppExit(killProcess=false)'
disconnect='comm.disconnect(ConnectionOperation(comm.appConnectionTicket(),null), ' if new else 'comm.disconnect('
code=r'''package lifecycle
import kotlinx.coroutines.*
import java.io.*

@OPERATION@
private object CmdLog {fun i(s:String){};fun d(s:String){};fun w(s:String){};fun e(s:String,e:Throwable){}}
private class BluetoothServerSocket {var closed=false;fun close(){closed=true}}
private class BluetoothAdapter {val name="radio";var registrations=0;fun listenUsingInsecureRfcommWithServiceRecord(name:String,uuid:String)=BluetoothServerSocket().also{registrations++};fun listenUsingRfcommWithServiceRecord(name:String,uuid:String)=BluetoothServerSocket().also{registrations++}}
private class Device {val address="recording peer"}
private class BluetoothSocket {val inputStream=ByteArrayInputStream(byteArrayOf());val outputStream=ByteArrayOutputStream();val remoteDevice=Device();val isConnected=false;var closes=0;fun close(){closes++}}
private class NativeSettings {var insecureAaRfcommListener=false}
private class NativeCommand(val commManager:CommManager,val operation:ConnectionOperation){
    val settings=NativeSettings();var isRunning=true;var aaListenerLost=true;var localRadioName="radio";val AA_UUID="aa"
    class Radio(val adapter:BluetoothAdapter,val serviceName:String)
    fun secondaryRadioHandles()=emptyList<Radio>()
    val pending=ArrayDeque<()->Unit>()
    private fun isCurrent(command:ConnectionOperation)=commManager.isCurrent(operation)&&commManager.isCurrent(command)
    fun enter(adapter:BluetoothAdapter,command:ConnectionOperation)=listenOnAaUuid(adapter,command)
    fun defer(adapter:BluetoothAdapter,command:ConnectionOperation)=reopenAaListener(adapter,"fixture",command)
    fun launchAaAcceptLoop(adapter:BluetoothAdapter,radio:String,serviceName:String?=null,command:ConnectionOperation){pending.add{listenOnAaUuid(adapter,command)}}
    fun drain(){while(pending.isNotEmpty())pending.removeFirst()()}
    fun closePhoneSocket(socket:BluetoothSocket){socket.close()}
    suspend fun serve(socket:BluetoothSocket,command:ConnectionOperation)=serveHfpSocket(socket,"fixture",true,true,command)
    @LISTENER@
    @REOPEN@
    @HFP@
}
private class HotspotContext {var enables=0}
private object HotspotManager {fun setHotspotEnabled(context:HotspotContext,enabled:Boolean):Boolean {context.enables++;return true}}
private class SoftApCommand(val commManager:CommManager,val operation:ConnectionOperation){
    val context=HotspotContext();var isRunning=true;var lastAutoEnableAtMs=0L;var autoEnableInFlight=false
    val pending=ArrayDeque<()->Unit>()
    private fun isCurrent(command:ConnectionOperation)=commManager.isCurrent(operation)&&commManager.isCurrent(command)
    fun beginResolve(command:ConnectionOperation){pending.add{enableHotspot(command)}}
    fun drain(){while(pending.isNotEmpty())pending.removeFirst()()}
    @REFRESH@
    @ENABLE@
}
private fun save(comm:CommManager):ConnectionOperation {@DISCONNECT@isUserExit=false,honorKillOnDisconnect=false,reason=DisconnectReason.SETTINGS_RESTART);comm.cleanups();return ConnectionOperation(comm.appConnectionTicket(),comm.state as ConnectionState.Disconnected)}
private fun withdraw(comm:CommManager,kind:String){if(kind=="stop"||kind=="stop_open"){@STOP@};if(kind=="stop_open")comm.onAppRelaunched();if(kind=="save_cancel"){@DISCONNECT@isUserExit=true,honorKillOnDisconnect=false);comm.cleanups()}}
fun main()=runBlocking {
    var failures=0;val results=mutableListOf<String>()
    fun case(name:String,body:()->String){try{val detail=body();println("PASS $name $detail");results+="{\"case\":\"$name\",\"pass\":true,\"detail\":\"$detail\"}"}catch(e:Throwable){failures++;println("FAIL $name ${e.message}");results+="{\"case\":\"$name\",\"pass\":false,\"detail\":\"${e.message?.replace('"','\'')}\"}"}}
    for(kind in listOf("accepted","stop","stop_open","save_cancel")){
        case("native_command_listener_$kind"){
            val comm=CommManager();val arm=ConnectionOperation(comm.appConnectionTicket(),null);val command=save(comm);val native=NativeCommand(comm,arm);val radio=BluetoothAdapter()
            try{withdraw(comm,kind);native.enter(radio,command);val expected=if(kind=="accepted")1 else 0;check(radio.registrations==expected){"registrations=${radio.registrations}, expected=$expected"};"registrations=${radio.registrations}"}finally{comm.close()}
        }
        case("native_queued_reopen_$kind"){
            val comm=CommManager();val arm=ConnectionOperation(comm.appConnectionTicket(),null);val command=save(comm);val native=NativeCommand(comm,arm);val radio=BluetoothAdapter()
            try{native.defer(radio,command);withdraw(comm,kind);native.drain();val expected=if(kind=="accepted")1 else 0;check(radio.registrations==expected){"registrations=${radio.registrations}, expected=$expected"};"registrations=${radio.registrations}"}finally{comm.close()}
        }
        case("softap_queued_refresh_$kind"){
            val comm=CommManager();val arm=ConnectionOperation(comm.appConnectionTicket(),null);val command=save(comm);val soft=SoftApCommand(comm,arm)
            try{soft.refresh(command);withdraw(comm,kind);soft.drain();val expected=if(kind=="accepted")1 else 0;check(soft.context.enables==expected){"enables=${soft.context.enables}, expected=$expected"};"enables=${soft.context.enables}"}finally{comm.close()}
        }
        case("native_hfp_first_write_$kind"){
            val comm=CommManager();val arm=ConnectionOperation(comm.appConnectionTicket(),null);val command=save(comm);val native=NativeCommand(comm,arm);val socket=BluetoothSocket()
            try{withdraw(comm,kind);runBlocking{native.serve(socket,command)};val written=socket.outputStream.size()>0;check(written==(kind=="accepted")){"bytes=${socket.outputStream.size()}"};check(socket.closes==1){"owned socket was not closed"};"bytes=${socket.outputStream.size()}, closes=${socket.closes}"}finally{comm.close()}
        }
    }
    case("native_reopen_fresh_command_successor"){
        val comm=CommManager();val arm=ConnectionOperation(comm.appConnectionTicket(),null);val first=save(comm);val old=NativeCommand(comm,arm);val oldRadio=BluetoothAdapter();old.defer(oldRadio,first)
        withdraw(comm,"save_cancel");val next=ConnectionOperation(comm.appConnectionTicket(),null);val fresh=NativeCommand(comm,arm);val freshRadio=BluetoothAdapter();fresh.defer(freshRadio,next)
        try{old.drain();fresh.drain();check(oldRadio.registrations==0&&freshRadio.registrations==1){"old=${oldRadio.registrations}, fresh=${freshRadio.registrations}"};"old=${oldRadio.registrations}, fresh=${freshRadio.registrations}"}finally{comm.close()}
    }
    java.io.File(System.getProperty("wireless.results")).writeText("[\n"+results.joinToString(",\n")+"\n]\n");check(failures==0){"$failures Native command cases failed"}
}
'''
# The baseline helpers retain the same unused admission shape, solely to compile the common fixture.
if not new:code=code.replace('commManager.isCurrent(operation)&&commManager.isCurrent(command)','commManager.acceptsAppConnectionTicket(operation.ticket)&&commManager.acceptsAppConnectionTicket(command.ticket)')
for key,val in {'OPERATION':operation,'LISTENER':listener,'REOPEN':reopen,'HFP':hfp,'REFRESH':refresh,'ENABLE':enable,'STOP':stop,'DISCONNECT':disconnect}.items():code=code.replace('@'+key+'@',val)
code=code.replace('AppLog.','CmdLog.')
(OUT/'NativeCommands.kt').write_text(code)
for name in ['HfpSlcInitiator.kt','HfpAtResponder.kt']:
    source=(BASE/'connection/wifi/modes/nativeaa'/name).read_text().replace('package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa','package lifecycle');(OUT/name).write_text(source)
(OUT/'production-method-sha256.json').write_text(json.dumps(hashes,indent=2))
(OUT/'adaptations.txt').write_text('Baseline adaptation only supplies required command parameters to real effect methods. Dispatch, Android devices and hotspot are recording doubles; CommManager, source bodies and HFP protocol are real. The unchanged constructor arm is always (ticket,null); command owns original Save.\n')
tc=json.loads((HOST/'toolchain.json').read_text());cp=os.pathsep.join(x['path'] for x in tc['jars'])
compile=['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-nowarn','-Xfriend-paths='+str(HOST/'probe.jar'),'-classpath',str(HOST/'probe.jar')+os.pathsep+cp,'-d',str(OUT/'cases.jar'),str(OUT/'NativeCommands.kt'),str(OUT/'HfpSlcInitiator.kt'),str(OUT/'HfpAtResponder.kt')]
run=['java','-Dwireless.results='+str(OUT/'results.json'),'-cp',str(OUT/'cases.jar')+os.pathsep+str(HOST/'probe.jar')+os.pathsep+cp,'lifecycle.NativeCommandsKt']
(OUT/'commands.json').write_text(json.dumps({'compile':compile,'run':run,'source_root':str(ROOT),'baseline_api_adapter':not new},indent=2))
subprocess.run(compile,check=True,timeout=90);subprocess.run(run,check=True,timeout=35)
