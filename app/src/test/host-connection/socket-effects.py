#!/usr/bin/env python3
"""Run the complete socket driver with real extracted CommManager authority methods.

Run host-connection/run.py first. An explicit --host permits before/after comparisons
against a frozen extraction. Only Android/network boundaries are doubles. The production
SocketProjectionConnection source is copied byte-for-byte; its old/new constructor shape
is adapted at the test call sites, never by inserting checks into production code.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('root', nargs='?', type=Path, default=Path(__file__).resolve().parents[4])
parser.add_argument('--host', type=Path)
parser.add_argument('--out', type=Path)
args = parser.parse_args()
root = args.root.resolve()
host = (args.host or root/'build/host-connection').resolve()
out = (args.out or host/'socket-effects').resolve()
out.mkdir(parents=True, exist_ok=True)
base = root/'app/src/main/java/com/andrerinas/openheadunit'
driver_source = (base/'connection/projection/SocketProjectionConnection.kt').read_text()
driver_admission = ',admission' if 'private val admission: ConnectionAdmission' in driver_source else ''
peer_policy = base/'aap/GalVersionPolicy.kt'
has_peer = peer_policy.is_file()
meta = json.loads((host/'toolchain.json').read_text())
jars = [Path(j['path']) for j in meta['jars']]
compiler = os.pathsep.join(map(str, jars))
runtime = os.pathsep.join(meta.get('runtime_jars', [
    str(j) for j in jars if any(x in j.name for x in ('stdlib', 'coroutines', 'annotations'))
]))
for old in out.glob('*.kt'):
    old.unlink()

def class_span(source, signature):
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 0
    for end in range(opening, len(source)):
        if source[end] == '{':
            depth += 1
        elif source[end] == '}':
            depth -= 1
            if depth == 0:
                return start, end + 1
    raise ValueError(signature)

adapter = '''class SocketProjectionConnection private constructor(
 val actual:com.andrerinas.openheadunit.connection.projection.SocketProjectionConnection,
 private val ip:String
):ProjectionConnection(){
 constructor(ip:String,port:Int,context:Context,admission:ConnectionAdmission=ConnectionAdmission.UNRESTRICTED):
  this(com.andrerinas.openheadunit.connection.projection.SocketProjectionConnection(ip,port,contextIP_AUTH),ip)
 constructor(socket:Socket,context:Context,admission:ConnectionAdmission=ConnectionAdmission.UNRESTRICTED,inbound:Boolean=true):
  this(com.andrerinas.openheadunit.connection.projection.SocketProjectionConnection(socket,contextSOCKET_ARGS),
   socket.inetAddress?.hostAddress?:"fixture")
 override fun disconnect(){super.disconnect();actual.disconnect()}
 val isLoopbackPeer get()=actual.isLoopbackPeer
 val isSingleMessage get()=actual.isSingleMessage
 init { created.add(this);prepare?.invoke(this);constructionHook?.invoke() }
 override fun connect():Boolean = connectionHook?.invoke(this) ?: runBlocking{actual.connect()}
 companion object {
  @Volatile var prepare:((SocketProjectionConnection)->Unit)?=null
  @Volatile var constructionHook:(()->Unit)?=null
  @Volatile var connectionHook:((ProjectionConnection)->Boolean)?=null
  val created=ConcurrentLinkedQueue<ProjectionConnection>()
 }
}'''.replace('IP_AUTH', driver_admission).replace('SOCKET_ARGS', driver_admission + (',inbound' if has_peer else ''))

for source in sorted(host.glob('*.kt')):
    value = source.read_text()
    if source.name == 'Probe.kt':
        start, end = class_span(value, 'class SocketProjectionConnection(')
        value = value[:start] + adapter + value[end:]
    elif source.name == 'Content.kt':
        value = value.replace('class Context{', 'class Context{\n val connectivity=android.net.ConnectivityManager()')
        value = value.replace('when(s){', 'when(s){"connectivity"->connectivity;')
        value = value.replace('companion object{const val ACTIVITY_SERVICE=', 'companion object{const val CONNECTIVITY_SERVICE="connectivity";const val ACTIVITY_SERVICE=')
    elif source.name == 'Os.kt':
        value = value.replace('object VERSION_CODES{', 'object VERSION_CODES{const val M=23;')
    elif source.name == 'UsbLog.kt':
        value = '''package com.andrerinas.openheadunit.utils
object AppLog {
 fun i(s:String,vararg args:Any?){}; fun w(s:String,vararg args:Any?){}
 fun e(s:String,vararg args:Any?){};fun e(t:Throwable){}
}
'''
    (out/source.name).write_text(value)

hashes = {}
for relative in ['connection/projection/SocketProjectionConnection.kt', 'connection/projection/LoopbackBindPolicy.kt', 'connection/projection/ProjectionConnection.kt']:
    source = base/relative
    shutil.copy2(source, out/source.name)
    hashes[str(source.relative_to(root))] = hashlib.sha256(source.read_bytes()).hexdigest()
# The production driver only uses this identity type, not the negotiation logic or protobuf.
if has_peer:
    policy_source = peer_policy.read_text()
    start, end = class_span(policy_source, 'sealed interface Peer')
    (out/'GalVersionPeer.kt').write_text(
        'package com.andrerinas.openheadunit.aap\ninternal class GalVersionPolicy {\n' +
        policy_source[start:end] + '\n}\n')
    hashes[str(peer_policy.relative_to(root))] = hashlib.sha256(policy_source.encode()).hexdigest()
hashes['app/src/main/java/com/andrerinas/openheadunit/connection/CommManager.kt'] = hashlib.sha256((base/'connection/CommManager.kt').read_bytes()).hexdigest()
shutil.copy2(host/'extracted-method-sha256.json', out/'extracted-method-sha256.json')
(out/'SocketNearbyBoundary.kt').write_text('''package com.andrerinas.openheadunit.connection.wifi.modes.helper
open class NearbySocket:java.net.Socket()
''')
(out/'SocketNetworkBoundary.kt').write_text('''package android.net
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
class Network {
 var binds=0
 var onBind:((Socket)->Unit)?=null
 fun bindSocket(socket:Socket){onBind?.invoke(socket);binds++}
}
class NetworkCapabilities {
 fun hasTransport(transport:Int)=transport==TRANSPORT_WIFI
 companion object { const val TRANSPORT_WIFI=1; const val TRANSPORT_CELLULAR=0 }
}
class NetworkRequest { class Builder { fun addTransportType(type:Int)=this;fun build()=NetworkRequest() } }
class NetworkInfo(val isConnected:Boolean)
class ConnectivityManager {
 var allNetworks=emptyArray<Network>()
 var activeNetwork:Network?=null
 val registered=CountDownLatch(1)
 val callbacks=ConcurrentLinkedQueue<NetworkCallback>()
 var registrations=0;var unregistrations=0
 var onBoundary:(()->Unit)?=null
 fun getNetworkCapabilities(network:Network)=NetworkCapabilities()
 open class NetworkCallback { open fun onAvailable(network:Network){} }
 fun registerNetworkCallback(request:NetworkRequest,callback:NetworkCallback){
  onBoundary?.invoke();registrations++;callbacks.add(callback);registered.countDown()
 }
 fun unregisterNetworkCallback(callback:NetworkCallback){onBoundary?.invoke();unregistrations++;callbacks.remove(callback)}
 fun offer(network:Network){callbacks.toList().forEach{it.onAvailable(network)}}
 fun getNetworkInfo(type:Int)=NetworkInfo(false)
 companion object { const val TYPE_WIFI=1 }
}
''')
fixture = Path(__file__).with_name('SocketEffectsFixture.kt').read_text()
fixture = fixture.replace('/* DRIVER_AUTHORITY */', driver_admission)
if not has_peer:  # Historical comparison trees predate production peer identity.
    start, end = class_span(fixture, '    private fun socketPeerUsesServiceSideOfConnection()')
    fixture = fixture[:start] + fixture[end:]
    fixture = fixture.replace('import com.andrerinas.openheadunit.aap.GalVersionPolicy\n', '')
    fixture = fixture.replace('        case("socket_version_peer_service_endpoint") { socketPeerUsesServiceSideOfConnection() }\n', '')
(out/'SocketEffectsFixture.kt').write_text(fixture)
(out/'production-sha256.json').write_text(json.dumps(hashes, indent=2)+'\n')
(out/'compiled-source-sha256.json').write_text(json.dumps({
    p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(out.glob('*.kt'))
}, indent=2)+'\n')
commands = [
    ['java','-cp',compiler,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-nowarn',
     '-classpath',runtime,'-d',str(out/'socket-effects.jar'),*map(str,sorted(out.glob('*.kt')))],
    ['java','-cp',str(out/'socket-effects.jar')+os.pathsep+runtime,'lifecycle.SocketEffectsMain']]
(out/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
(out/'full.log').write_text('')
for index, command in enumerate(commands):
    print('COMMAND',json.dumps(command),flush=True)
    result = subprocess.run(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=60)
    print(result.stdout,end='',flush=True)
    with (out/'full.log').open('a') as log:
        log.write(result.stdout)
    if index == 0 and result.returncode:
        raise SystemExit('Compilation failed: not a behavioral result')
    if index == 1:
        for line in result.stdout.splitlines():
            if line.startswith('RESULTS='):
                (out/'results.json').write_text(json.dumps(json.loads(line[len('RESULTS='):]),indent=2)+'\n')
        raise SystemExit(result.returncode)
