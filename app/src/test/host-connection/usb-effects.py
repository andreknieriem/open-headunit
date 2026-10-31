#!/usr/bin/env python3
"""Behavioral USB authority checks using generated real CM methods and real switch workers.

Run run.py first to extract the target tree. --baseline adapts only the changed test-facing
signatures and deliberately executes the unmodified old production bodies; failed compilation
is never treated as a regression result. The source tree is never edited by this script.
"""
from pathlib import Path
import argparse, hashlib, json, os, shutil, subprocess

parser = argparse.ArgumentParser()
parser.add_argument('root', nargs='?', type=Path, default=Path(__file__).resolve().parents[4])
parser.add_argument('--baseline', action='store_true')
parser.add_argument('--libusb', action='store_true', help='compile the full Libusb driver and run its four permission-boundary cases')
a = parser.parse_args()
root = a.root.resolve()
generated = root/'build/host-connection'
out = generated/('libusb-effects' if a.libusb else 'usb-effects')
out.mkdir(parents=True, exist_ok=True)
base = root/'app/src/main/java/com/andrerinas/openheadunit'
meta = json.loads((generated/'toolchain.json').read_text())
jars = [Path(j['path']) for j in meta['jars']]
compiler = os.pathsep.join(map(str, jars))
runtime = os.pathsep.join(str(j) for j in jars if any(x in j.name for x in ('stdlib','coroutines','annotations')))
for old in out.glob('*.kt'): old.unlink()
for source in generated.glob('*.kt'):
    text = source.read_text()
    if source.name == 'Probe.kt':
        text = text.replace('private var onUsbHandshakeEnded:', 'var onUsbHandshakeEnded:')
        text = text.replace('object AppLog {', 'object AppLog {\n fun e(error:Throwable){}', 1)
        text = text.replace('object Utils{', 'object Utils{fun getAccVersion(buffer:ByteArray)=2;')
        text = text.replace('enum class HandshakeFailure{NONE,PEER_SILENT,OTHER}', 'enum class HandshakeFailure{NONE,PEER_SILENT,TRANSPORT_ERROR,SSL,OTHER}')
    if source.name == 'Usb.kt':
        text = text.replace('class UsbEndpoint{', 'class UsbEndpoint{val address=1;')
        text = text.replace('class UsbDeviceConnection{', 'class UsbDeviceConnection{\n @Volatile var closeHook:(()->Unit)?=null\n val controls=AtomicInteger()\n fun controlTransfer(type:Int,request:Int,value:Int,index:Int,bytes:ByteArray,length:Int,timeout:Int):Int { controls.incrementAndGet();return length }')
        text = text.replace('fun close(){closes.incrementAndGet()}', 'fun close(){closes.incrementAndGet();closeHook?.invoke()}')
        text += '\nobject UsbConstants { const val USB_DIR_IN=128;const val USB_TYPE_VENDOR=64 }\n'
    (out/source.name).write_text(text)

hashes = {}
for name in ['ProjectionConnection.kt', 'AbstractUsbProjectionConnection.kt', 'StandardUsbProjectionConnection.kt']:
    source = base/'connection/projection'/name
    shutil.copy2(source, out/name)
    hashes[str(source.relative_to(root))] = hashlib.sha256(source.read_bytes()).hexdigest()
for relative in ['connection/CommManager.kt']:
    source = base/relative
    hashes[str(source.relative_to(root))] = hashlib.sha256(source.read_bytes()).hexdigest()
if a.libusb:
    source = base/'connection/projection/LibusbProjectionConnection.kt'
    shutil.copy2(source, out/source.name)
    hashes[str(source.relative_to(root))] = hashlib.sha256(source.read_bytes()).hexdigest()
    (out/'UsbNativeDriver.kt').write_text('''package com.andrerinas.openheadunit.connection.usb
class UsbNative {
    fun wrap(connection:android.hardware.usb.UsbDeviceConnection,input:Int,output:Int)=true
    fun read(buffer:java.nio.ByteBuffer,timeout:Int)=0
    fun write(bytes:ByteArray,length:Int,timeout:Int)=length
    fun close(){}
}
''')
shutil.copy2(generated/'extracted-method-sha256.json', out/'extracted-method-sha256.json')
for name in ['UsbSwitchClaim.kt', 'UsbAttachSwitch.kt', 'UsbAccessoryHandoffPolicy.kt', 'StaleAccessoryRecoveryPolicy.kt', 'UsbAccessoryMode.kt', 'AoaSwitchFailurePolicy.kt']:
    source = base/'connection/usb'/name
    text = source.read_text()
    hashes[str(source.relative_to(root))] = hashlib.sha256(source.read_bytes()).hexdigest()
    text = text.replace('package com.andrerinas.openheadunit.connection.usb', 'package lifecycle')
    text = text.replace('import com.andrerinas.openheadunit.aap.AapTransport.HandshakeFailure', 'import lifecycle.AapTransport.HandshakeFailure')
    text = '\n'.join(line for line in text.splitlines() if not line.startswith('import com.andrerinas.openheadunit.'))+'\n'
    text = text.replace('UsbDeviceCompat(device).uniqueName', 'UsbDeviceCompat.getUniqueName(device)')
    (out/name).write_text(text)

(out/'UsbNativeEffects.kt').write_text('''package lifecycle
class UsbNative {
    fun wrap(connection:android.hardware.usb.UsbDeviceConnection,input:Int,output:Int)=true
    fun accModeSwitch()=0
    fun reset(){resets++}
    fun close(){}
    companion object { var resets=0 }
}
''')
fixture = Path(__file__).with_name('UsbEffectsFixture.kt').read_text()
if a.baseline:
    replacement = {
        '// ACCESSORY_EFFECT': 'val accepted = if (reset) accessory.resetDevice(UsbDevice()) else accessory.connectAndSwitch(UsbDevice(), false)',
        '// DRIVER_CONSTRUCTION': 'val driver = com.andrerinas.openheadunit.connection.projection.StandardUsbProjectionConnection(usb, UsbDevice())',
        '// REQUEST_STOP': 'c.requestExplicitAppExit(killProcess = false)',
        '// CAPTURE_HANDSHAKE': 'val authority = Unit',
        '// START_HANDSHAKE': 'c.startHandshake()',
        '// ACCEPTED_HANDSHAKE': 'c.startHandshake()',
        '// CAPTURE_READING': 'val authority = Unit',
        '// START_READING': 'c.startReading()',
        '// OUTCOME_RECHECK': 'val recheck = c.usbRecheckNow()',
        '// USB_INITIAL_DISCONNECT': 'c.disconnect(sendByeBye=false, honorKillOnDisconnect=false)',
        '/* USB_HOME_EXIT */': 'c.disconnect(sendByeBye=false, isUserExit=true, honorKillOnDisconnect=false)',
        '// SWITCH_ADAPTERS': '''private fun stake(owner:ConnectionArbiter.Claim) { UsbSwitchClaim.stake() }
    private fun release(owner:ConnectionArbiter.Claim) { UsbSwitchClaim.release() }
    private fun note(owner:ConnectionArbiter.Claim) { UsbSwitchClaim.noteSwitched() }'''
    }
else:
    replacement = {
        '// ACCESSORY_EFFECT': 'val accepted = if (reset) accessory.resetDevice(UsbDevice(), admission) else accessory.connectAndSwitch(UsbDevice(), false, admission)',
        '// DRIVER_CONSTRUCTION': 'val driver = com.andrerinas.openheadunit.connection.projection.StandardUsbProjectionConnection(usb, UsbDevice(), admission)',
        '// REQUEST_STOP': 'checkNotNull(c.requestExplicitAppExit(c.appConnectionTicket(),killProcess = false))',
        '// CAPTURE_HANDSHAKE': 'val authority = checkNotNull(c.captureHandshake())',
        '// START_HANDSHAKE': 'c.startHandshake(authority)',
        '// ACCEPTED_HANDSHAKE': 'c.startHandshake(checkNotNull(c.captureHandshake()))',
        '// CAPTURE_READING': 'val authority = checkNotNull(c.captureReading())',
        '// START_READING': 'c.startReading(authority)',
        '// OUTCOME_RECHECK': 'val recheck = outcome.recheck',
        '// USB_INITIAL_DISCONNECT': 'c.disconnect(ConnectionOperation(c.appConnectionTicket(),null),sendByeBye=false,honorKillOnDisconnect=false)',
        '/* USB_HOME_EXIT */': 'c.disconnect(ConnectionOperation(c.appConnectionTicket(),null),sendByeBye=false,isUserExit=true,honorKillOnDisconnect=false)',
        '// SWITCH_ADAPTERS': '''private fun stake(owner:ConnectionArbiter.Claim) { check(UsbSwitchClaim.stake(owner)) }
    private fun release(owner:ConnectionArbiter.Claim) { UsbSwitchClaim.release(owner) }
    private fun note(owner:ConnectionArbiter.Claim) { UsbSwitchClaim.noteSwitched(owner) }'''
    }
for key, value in replacement.items(): fixture = fixture.replace(key, value)
if a.libusb:
    fixture = fixture.replace('projection.StandardUsbProjectionConnection(usb,', 'projection.LibusbProjectionConnection(usb,')
(out/'UsbEffectsFixture.kt').write_text(fixture)
(out/'production-sha256.json').write_text(json.dumps(hashes, indent=2)+'\n')
(out/'compiled-source-sha256.json').write_text(json.dumps({
    source.name: hashlib.sha256(source.read_bytes()).hexdigest() for source in sorted(out.glob('*.kt'))
}, indent=2)+'\n')
commands = [
    ['java','-cp',compiler,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-nowarn',
     '-classpath',runtime,'-d',str(out/'usb-effects.jar'), *map(str,sorted(out.glob('*.kt')))],
    ['java','-cp',str(out/'usb-effects.jar')+os.pathsep+runtime,'lifecycle.UsbEffectsMain'] + (['libusb'] if a.libusb else [])]
(out/'commands.json').write_text(json.dumps(commands, indent=2)+'\n')
(out/'full.log').write_text('')
for command in commands:
    print('COMMAND',json.dumps(command),flush=True)
    result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=60)
    print(result.stdout,end='',flush=True)
    with (out/'full.log').open('a') as log: log.write(result.stdout)
    if command is commands[0] and result.returncode:
        raise SystemExit('Compilation failed: not a behavioral result')
    if command is commands[1]:
        for line in result.stdout.splitlines():
            if line.startswith('RESULTS='):
                (out/'results.json').write_text(json.dumps(json.loads(line[len('RESULTS='):]),indent=2)+'\n')
        raise SystemExit(result.returncode)
