#!/usr/bin/env python3
"""Full production external carrier/opening sources; only Android sockets and Process are doubled.

Run after run.py has recorded toolchain.json. --baseline changes test call signatures only.
No real TCP, Unix socket, shell, Bluetooth or USB effect is performed by these tests.
"""
from pathlib import Path
import argparse, hashlib, json, os, shutil, subprocess

p = argparse.ArgumentParser()
p.add_argument('root', nargs='?', type=Path, default=Path(__file__).resolve().parents[4])
p.add_argument('--baseline', action='store_true')
a = p.parse_args()
root = a.root.resolve()
base = root/'app/src/main/java/com/andrerinas/openheadunit'
out = root/'build/host-connection/native-carrier-effects'
out.mkdir(parents=True, exist_ok=True)
for old in out.glob('*.kt'): old.unlink()
meta = json.loads((root/'build/host-connection/toolchain.json').read_text())
jars = [Path(j['path']) for j in meta['jars']]
compiler = os.pathsep.join(map(str, jars))
runtime = os.pathsep.join(str(j) for j in jars if any(x in j.name for x in ('stdlib', 'coroutines', 'annotations')))
files = ['HandshakeLink', 'ExternalModuleCarrier', 'NativeHandoffPolicy', 'JoinRefusalPolicy', 'WppFraming']
files += ['zbt/'+x for x in ['ZbtAaCarrier', 'ZbtChannelOpening', 'ZbtByteChannel', 'ZbtDaemonReachability',
    'ZbtIdentityProbe', 'ZbtSocketCloser', 'ZbtAttemptPolicy', 'ZbtFraming', 'ZbtMessages', 'ZbtWakePolicy',
    'ZbtReopenPolicy', 'ZbtReachabilityPolicy']]
files += ['blink/'+x for x in ['BlinkAaCarrier', 'BlinkAutoSerialChannel', 'BlinkAutoLine', 'BlinkRefusal', 'StockCarLink']]
files += ['zjsocket/'+x for x in ['ZjSocketCarrier', 'ZjSocketConnector', 'ZjSocketHandshakeLink', 'ZjSocketPolicy', 'ZjSocketProbe']]
files += ['hct/HctLocalSocket']
hashes = {}
for name in files:
    source = base/'connection/wifi/modes/nativeaa'/(name+'.kt')
    shutil.copy2(source, out/source.name)
    hashes[str(source.relative_to(root))] = hashlib.sha256(source.read_bytes()).hexdigest()
admission = (base/'connection/ConnectionAdmission.kt').read_text()
admission = admission[:admission.index('/** Nearby retirement')]
(out/'ConnectionAdmission.kt').write_text(admission)
wpp = (base/'connection/wifi/modes/nativeaa/WppHandshakeSession.kt').read_text()
(out/'WppMessageType.kt').write_text(wpp[:wpp.index('/** Where a [WppHandshakeSession]')])
(out/'AndroidOs.kt').write_text('''package android.os
object SystemClock { var current=100_000L;fun elapsedRealtime()=current }
''')
(out/'AndroidBluetooth.kt').write_text('''package android.bluetooth
class BluetoothDevice { val name:String?="phone";val address:String?="00:11:22:33:44:55" }
class BluetoothSocket {
 val inputStream=java.io.ByteArrayInputStream(byteArrayOf());val outputStream=java.io.ByteArrayOutputStream()
 val remoteDevice:BluetoothDevice?=BluetoothDevice();fun close(){}
}
''')
(out/'AndroidLocalSocket.kt').write_text('''package android.net
class LocalSocketAddress(val name:String,val namespace:Namespace){enum class Namespace{FILESYSTEM}}
class LocalSocket {
 companion object {var created=0;var connects=0;var closes=0;var onCreate:(()->Unit)?=null;var onConnect:(()->Unit)?=null
  fun reset(){created=0;connects=0;closes=0;onCreate=null;onConnect=null}}
 init {created++;onCreate?.invoke()}
 val inputStream=java.io.ByteArrayInputStream(byteArrayOf());val outputStream=java.io.ByteArrayOutputStream()
 fun connect(address:LocalSocketAddress){connects++;onConnect?.invoke()}
 fun shutdownInput(){};fun shutdownOutput(){};fun close(){closes++}
}
''')
(out/'AndroidPackageManager.kt').write_text('''package android.content.pm
class ApplicationInfo { var enabled=true;var flags=0;companion object{const val FLAG_PERSISTENT=8} }
class PackageManager {
 class NameNotFoundException:Exception()
 fun getApplicationInfo(name:String,flags:Int)=ApplicationInfo();fun getApplicationEnabledSetting(name:String)=0
 companion object {const val GET_DISABLED_COMPONENTS=1;const val COMPONENT_ENABLED_STATE_DISABLED=2
 const val COMPONENT_ENABLED_STATE_DISABLED_USER=3;const val COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED=4
 const val COMPONENT_ENABLED_STATE_ENABLED=1}
}
''')
(out/'Annotations.kt').write_text('package androidx.annotation\nannotation class StringRes\n')
(out/'R.kt').write_text('''package com.andrerinas.openheadunit
object R {object string {const val external_bt_blink_refused_stock_enabled=1;const val external_bt_blink_refused_stock_running=2
const val external_bt_blink_refused_unverified=3;const val external_bt_blink_refused_root=4;const val external_bt_blink_refused_bridge=5}}
''')
(out/'Utils.kt').write_text('''package com.andrerinas.openheadunit.utils
object AppLog {fun i(s:String){};fun d(s:String){};fun w(s:String){};fun e(s:String){};fun e(s:String,t:Throwable){}}
object BluetoothHelper {fun normalizeMacAddress(s:String?)=s}
object ExternalBtPolicy {val ZJ_SOCKET_PATHS=listOf("/fixture/unused-zj-socket")}
''')
fixture = Path(__file__).with_name('NativeCarrierEffectsFixture.kt').read_text()
if a.baseline:
    fixture = fixture.replace('admission = admission,', '')
    fixture = fixture.replace('carrier.requestWake(true, admission = commandAdmission)', 'carrier.requestWake(true)')
    fixture = fixture.replace('ZjSocketConnector.connect("/fixture/unused", admission)', 'ZjSocketConnector.connect("/fixture/unused")')
(out/'NativeCarrierEffectsFixture.kt').write_text(fixture)
(out/'production-sha256.json').write_text(json.dumps(hashes, indent=2)+'\n')
(out/'compiled-source-sha256.json').write_text(json.dumps({
    source.name: hashlib.sha256(source.read_bytes()).hexdigest() for source in sorted(out.glob('*.kt'))
}, indent=2)+'\n')
commands = [
    ['java', '-Xmx768m', '-cp', compiler, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-nowarn',
     '-classpath', runtime, '-d', str(out/'native-carriers.jar'), *map(str, sorted(out.glob('*.kt')))],
    ['java', '-cp', str(out/'native-carriers.jar')+os.pathsep+runtime, 'carrierproof.NativeCarrierEffectsFixtureKt']]
(out/'commands.json').write_text(json.dumps(commands, indent=2)+'\n')
(out/'full.log').write_text('')
for command in commands:
    print('COMMAND', json.dumps(command), flush=True)
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
    print(result.stdout, end='', flush=True)
    with (out/'full.log').open('a') as log: log.write(result.stdout)
    if command is commands[0] and result.returncode:
        raise SystemExit('Compilation failed: not a behavioral result')
    if command is commands[1]:
        for line in result.stdout.splitlines():
            if line.startswith('RESULTS='):
                (out/'results.json').write_text(json.dumps(json.loads(line[len('RESULTS='):]), indent=2)+'\n')
        raise SystemExit(result.returncode)
