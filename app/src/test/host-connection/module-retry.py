#!/usr/bin/env python3
"""Execute the service's real START_HANDSHAKE branch and module wake/completion methods.

Route measurement and physical sockets are doubles. The button branch, wake queuing,
completion and carrier-start method bodies are extracted from production on each run.
This does not simulate Android service dispatch or manufacturer radio behavior.
"""
from pathlib import Path
import hashlib
import json
import os
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
SRC = ROOT / 'app/src/main/java/com/andrerinas/openheadunit'
OUT = ROOT / 'build/host-connection/module-retry'
OUT.mkdir(parents=True, exist_ok=True)
methods = {}


def member(file, declaration):
    source = (SRC / file).read_text()
    start = source.index(declaration)
    body_start = start
    if 'fun ' in declaration:
        # Default callback arguments contain braces before the actual method body.
        parameter_start = source.index('(', start)
        parentheses = 0
        for position in range(parameter_start, len(source)):
            if source[position] == '(':
                parentheses += 1
            elif source[position] == ')':
                parentheses -= 1
                if parentheses == 0:
                    body_start = position + 1
                    break
    brace = source.index('{', body_start)
    depth = 0
    for end in range(brace, len(source)):
        if source[end] == '{':
            depth += 1
        elif source[end] == '}':
            depth -= 1
            if depth == 0:
                result = source[start:end + 1]
                methods[file + ':' + declaration] = hashlib.sha256(result.encode()).hexdigest()
                return result
    raise ValueError(declaration)


manager = 'connection/wifi/modes/nativeaa/NativeAaHandshakeManager.kt'
code = r'''
package moduleretry
import kotlinx.coroutines.*
object AppLog {fun i(text:String){};fun w(text:String){};fun e(text:String, error:Throwable){}}
object ToastUtils {var shown=0;fun showToast(context:Any,text:String){shown++}}
object R {object string {const val native_aa_poke_not_running=1}}
enum class ConnectionStage {PHONE_ANSWERED,WAKING_PHONE}
object ConnectionStageTracker {fun report(stage:ConnectionStage){}}
object NativeHandoffPolicy {fun shouldServeHandshake(failures:Int)=true}
object JoinRefusalPolicy {fun retryDelayMs(failures:Int,minimum:Long)=minimum}
object ZbtAttemptPolicy {const val MIN_ATTEMPT_INTERVAL_MS=1000L}
object ZbtDiscoveryPolicy {class Decision(val requiresIdentity:Boolean=false,val evidence:String="test")}
object ZbtDiscovery {
 class Snapshot(val decision:ZbtDiscoveryPolicy.Decision=ZbtDiscoveryPolicy.Decision())
 fun snapshot()=Snapshot();fun deferIdentityForArming(decision:ZbtDiscoveryPolicy.Decision){error("unexpected fallback")}
 object Measurement {enum class Completion {SETTLED,CARRIER_BUSY,CANCELLED}}
}
class ZbtAaCarrier(
 val serve:suspend (Any)->Unit, val isRunning:()->Boolean, val isFinishedForSession:()->Boolean,
 val isSessionConnected:()->Boolean, val isSettling:()->Boolean, val isHandshakeInFlight:()->Boolean,
 val mayServeHandshake:()->Boolean, val onPhoneEvidence:()->Unit, val onModuleAddress:(String)->Unit,
 val retryDelayMs:()->Long
) {
 var wakes=0;var explicitWakes=0;val sendsWake=true
 fun requestWake(userAsked:Boolean=false){wakes++;if(userAsked)explicitWakes++}
 suspend fun run(){}
}
class Manager {
 val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
 var measuringDaemon=false;var measureGeneration=4;var wakeAwaitingModule=false
 var moduleCarrier:ZbtAaCarrier?=null
 var reason:String?=null
 private var notStartedReason:String? get()=reason;set(value){reason=value}
 fun notStartedReason()=reason
 var isRunning=false;var aaListenersClosedForSession=false;var localRadioName=""
 var wakeStoodDown=false;var sessionEndedAt=0L;var consecutiveHandshakeFailures=0;var consecutiveJoinRefusals=0
 var isSelectionCanceled=false;var pendingSelectionTargetMac:String?=null
 var measurementRequired=true;var starts=0
 val commManager=Comm()
 class Comm {var isConnected=false}
 fun isMeasuringDaemon()=measuringDaemon
 // Replace only route selection/measurement; all downstream production methods are below.
 fun start(){starts++;if(measurementRequired)measuringDaemon=true else startOverExternalModule()}
 private fun start(resumingMeasurement:Boolean){start()}
 fun isHandoffSettling()=false;fun isHandshakeInFlight()=false
 fun resetHandshakeBackoff(){};fun resetJoinRefusals(){}
 fun seedBluetoothAddressFromModule(value:String){}
 suspend fun handleHandshake(link:Any){}
 fun manualPoke(mac:String){error("unexpected Android radio wake")}
 fun triggerPoke(){error("unexpected Android radio wake")}
// MANAGER_METHODS
}
class Native(val handshakeManager:Manager)
class Service(val native:Native?) {
 fun getString(id:Int)="not running"
 fun press(){when(ModuleRearmPolicy.action(true,false,native?.handshakeManager?.isRunning==true,
     native?.handshakeManager?.isMeasuringDaemon()==true)) {
// BUTTON_BRANCH
 else -> error("expected START_HANDSHAKE")
 }}
}
fun main() {
 for(immediate in listOf(false,true)) {
  val manager=Manager()
  manager.measuringDaemon=true;manager.wakeAwaitingModule=true
  manager.completeDaemonMeasurement(4,ZbtDiscoveryPolicy.Decision(),ZbtDiscovery.Measurement.Completion.CARRIER_BUSY)
  check(!manager.measuringDaemon && !manager.wakeAwaitingModule && manager.moduleCarrier==null)
  check(manager.starts==0)
  manager.measurementRequired=!immediate
  Service(Native(manager)).press() // Actual AapService branch, without a credential callback.
  check(manager.starts==1)
  if(!immediate) {
   check(manager.measuringDaemon && manager.wakeAwaitingModule && manager.moduleCarrier==null)
   manager.measurementRequired=false
   manager.completeDaemonMeasurement(4,ZbtDiscoveryPolicy.Decision(),ZbtDiscovery.Measurement.Completion.SETTLED)
  }
  check(manager.moduleCarrier?.wakes==1){"first WiFi retry did not wake the module: immediate=$immediate"}
  check(!manager.wakeAwaitingModule)
  check(ToastUtils.shown==0)
  manager.scope.cancel()
 }
 val retry=Manager()
 Service(Native(retry)).press()
 check(retry.wakeAwaitingModule)
 retry.completeDaemonMeasurement(4,ZbtDiscoveryPolicy.Decision(),ZbtDiscovery.Measurement.Completion.CARRIER_BUSY)
 check(!retry.wakeAwaitingModule && !retry.measuringDaemon && retry.moduleCarrier==null)
 retry.scope.cancel()
 println("PASS actual service START_HANDSHAKE: first retry queues or sends exactly one module wake without credential events; repeated BUSY clears queued wake")
}
'''
body = '\n'.join(member(manager, declaration) for declaration in [
    'fun wakeOverModule(): Boolean',
    'internal fun resumeAfterDaemonMeasurement(',
    'internal fun completeDaemonMeasurement(',
    'private fun startOverExternalModule()',
])
code = code.replace('// MANAGER_METHODS', body)
code = code.replace('// BUTTON_BRANCH', member('aap/AapService.kt',
    'ModuleRearmPolicy.Action.START_HANDSHAKE ->'))
(OUT / 'Fixture.kt').write_text(code)
policy = (SRC / 'connection/wifi/modes/nativeaa/ModuleRearmPolicy.kt').read_text()
(OUT / 'ModuleRearmPolicy.kt').write_text(policy.replace(
    'package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa', 'package moduleretry'))
(OUT / 'extracted-method-sha256.json').write_text(json.dumps(methods, indent=2))
toolchain = json.loads((OUT.parent / 'toolchain.json').read_text())
cp = os.pathsep.join(item['path'] for item in toolchain['jars'])
subprocess.run(['java', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
    '-no-stdlib', '-no-reflect', '-nowarn', '-classpath', cp, '-d', str(OUT / 'regression.jar'),
    *map(str, sorted(OUT.glob('*.kt')))], check=True, timeout=45)
subprocess.run(['java', '-cp', str(OUT / 'regression.jar') + os.pathsep + cp,
    'moduleretry.FixtureKt'], check=True, timeout=15)
