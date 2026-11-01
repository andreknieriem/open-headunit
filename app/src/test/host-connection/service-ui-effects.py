#!/usr/bin/env python3
"""Run extracted service/UI effect entries against deterministic Android boundaries.

The same behavioural assertions run on the immutable pre-fix tree and current tree.
Only signature/callback storage adapters differ; no guard is inserted into old code.
"""
from pathlib import Path
import hashlib, json, os, subprocess, sys
HERE = Path(__file__).resolve().parent
ROOT = Path(sys.argv[1]).resolve() if len(sys.argv)>1 else HERE.parents[3]
OUT = Path(sys.argv[2]).resolve() if len(sys.argv)>2 else ROOT/'build/host-connection/service-ui-effects'
HOST_BUILD = Path(sys.argv[3]).resolve() if len(sys.argv)>3 else ROOT/'build/host-connection'
OUT.mkdir(parents=True, exist_ok=True)
SRC=ROOT/'app/src/main/java/com/andrerinas/openheadunit'
service=(SRC/'aap/AapService.kt').read_text()
home=(SRC/'main/HomeFragment.kt').read_text()
main=(SRC/'main/MainActivity.kt').read_text()
settings=(SRC/'main/SettingsFragment.kt').read_text()
methods={}
def block(source, declaration):
    start=source.index(declaration); brace=source.index('{',start); depth=0
    for end in range(brace,len(source)):
        if source[end]=='{':depth+=1
        elif source[end]=='}':
            depth-=1
            if depth==0:
                value=source[start:end+1]
                methods[declaration]=hashlib.sha256(value.encode()).hexdigest()
                return value
    raise ValueError(declaration)
def body(source, declaration):
    value=block(source,declaration)
    return value[value.index('{')+1:-1]
def expression(source,declaration):
    start=source.rfind('\n',0,source.index(declaration))+1
    lines=source[start:].splitlines(); indent=len(lines[0])-len(lines[0].lstrip()); n=1
    while n<len(lines) and lines[n].strip() and len(lines[n])-len(lines[n].lstrip())>indent:n+=1
    value='\n'.join(lines[:n]);methods[declaration]=hashlib.sha256(value.encode()).hexdigest();return value
fixed='private class WifiWait(' in service
fixture=(HERE/'ServiceUiEffectsFixture.kt').read_text()
markers={
 'STOP_INTENT':block(service,'fun stopIntent('),
 'CM_STOP':block((SRC/'connection/CommManager.kt').read_text(),'internal fun requestExplicitAppExit(').replace('com.andrerinas.openheadunit.connection.wifi.direct.RetainedNativeGroup','RetainedNativeGroup'),
 'APPLY_WIRELESS':block(service,'internal fun applyWirelessSettings('),
 'START_WIRELESS':block(service,'private fun startWirelessForSettings('),
 'STOP_WIRELESS':block(service,'internal fun stopWirelessForCommand('),
 'WIFI_INIT':block(service,'private fun initWifiModeWithOptionalWait('),
 'WIFI_COMPLETE':block(service,'private fun completeWifiWait('),
 'FORCE_SELF':block(main,'private fun forceSelfModeLaunch('),
 'IMPORT_COMPLETE':block(settings,'private fun handleImportedSettings('),
 'WIRELESS_SIDE_EFFECTS':block(settings,'private fun applyWirelessSideEffects('),
}
initialization=block(service,'private fun initializeForStart(')
initialization=initialization[:initialization.index('        fillBluetoothAddressIfUnset()')]+'        bootstrapEffects++\n'+('        return true\n' if fixed else '')+'    }'
markers['BOOTSTRAP_ENTRY']=initialization
markers['WAIT_FIELDS']=(block(service,'private class WifiWait(')+'\nprivate var wifiWait:WifiWait?=null\nprivate var wifiModeInitialized=false') if fixed else '''private var wifiReadyCallback: ConnectivityManager.NetworkCallback? = null
private var wifiReadyTimeoutJob: Job? = null
private var wifiModeInitialized = false
private var wifiWaitTicket = 0L'''
markers['WAIT_HELPERS']=('\n'.join(block(service,x) for x in ['private fun abandonWifiWait(','private fun disposeWifiWait(']) if fixed else '')
selector=block(home,'private fun showNativeAaDeviceSelector(')
markers['PICKER_DISMISS']='fun dismiss() {'+body(selector,'dialog.setOnDismissListener {')+'}'
markers['PICKER_CANCEL']='fun cancel() {'+body(selector,'val cancelDriverSelection = {')+'}'
markers['PICKER_NOTIFY']=block(home,'private fun notifyNativePrompt(') if 'private fun notifyNativePrompt(' in home else ''
markers['SELF_CANCEL']=block(main,'internal fun cancelPendingSelfLaunch(') if fixed else 'fun cancelPendingSelfLaunch() {}'
markers['SELF_CONSENT']=('fun deliverConsent(result: ActivityResult) {'+body(main,'private val forcedSelfVpnPermission').split('->',1)[1].replace('return@registerForActivityResult','return')+'}') if fixed else 'fun deliverConsent(result: ActivityResult) {}'
markers['SELF_DISPATCH']=block(main,'private fun dispatchForcedSelf(') if 'private fun dispatchForcedSelf(' in main else ''
self_branch=body(home,'Settings.AUTO_CONNECT_SELF_MODE -> {')
markers['HOME_SELF']='fun enterHome() {'+self_branch+'}'
prepare_body=body(block(settings,'private fun importSettingsFromUri('),'apply = { prepared ->').split('->',1)[1]
markers['IMPORT_APPLY']='fun applyPrepared(snapshot: ImportSnapshot) { val appContext=requireContext(); val prepared=Unit\n'+prepare_body+'}'
for name,value in markers.items():
    value=value.replace('com.andrerinas.openheadunit.App.provide','App.provide').replace('@androidx.annotation.MainThread','')
    fixture=fixture.replace('// '+name+'\n', value+'\n')
fixture=fixture.replace('val activityOwnsConsent = false', 'val activityOwnsConsent = '+str(fixed).lower())
fixture=fixture.replace('// CALL_STOP\n',('return AapService.stopIntent(this, ticket)' if fixed else 'return AapService.stopIntent(this)')+'\n')
# Production policy supplies Stop/Open epoch semantics; I/O/service callbacks are recording doubles.
policy=(SRC/'connection/AppExitPolicy.kt').read_text().replace('package com.andrerinas.openheadunit.connection','package serviceeffects').replace('internal class AppExitPolicy', 'class AppExitPolicy')
(OUT/'AppExitPolicy.kt').write_text(policy)
(OUT/'Fixture.kt').write_text(fixture)
(OUT/'extracted-method-sha256.json').write_text(json.dumps(methods,indent=2))
toolchain=json.loads((HOST_BUILD/'toolchain.json').read_text())
jars=[Path(entry['path']) for entry in toolchain['jars']]
for entry,jar in zip(toolchain['jars'],jars):
    if hashlib.sha256(jar.read_bytes()).hexdigest()!=entry['sha256']:
        raise ValueError(f'Generated host toolchain digest mismatch: {jar}')
cp=os.pathsep.join(map(str,jars))
# The main harness owns the real persistent USB hold extraction and Android prefs/USB doubles.
host_jar=HOST_BUILD/'probe.jar'
runtime=str(host_jar)+os.pathsep+cp
(OUT/'toolchain.json').write_text(json.dumps(toolchain,indent=2))
compile_cmd=['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-nowarn','-Xfriend-paths='+str(host_jar),'-classpath',runtime,'-d',str(OUT/'cases.jar'),str(OUT/'AppExitPolicy.kt'),str(OUT/'Fixture.kt')]
run_cmd=['java','-cp',str(OUT/'cases.jar')+os.pathsep+runtime,'serviceeffects.FixtureKt',str(OUT/'results.json')]
(OUT/'commands.json').write_text(json.dumps([compile_cmd,run_cmd],indent=2))
subprocess.run(compile_cmd,check=True,timeout=60)
subprocess.run(run_cmd,check=True,timeout=20)
