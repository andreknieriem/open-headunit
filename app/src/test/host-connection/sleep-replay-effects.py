#!/usr/bin/env python3
"""Execute Native Save -> radio sleep hold -> service replay with exact input ownership.

Production supplies the Save validity/cancellation rules, Direct refresh and sleep branch,
the complete sleep policy, service replay/Settings hold, and wireless arm admission.
Framework/radio operations are recording doubles. The same cases run against both trees;
only the explicit hold signature and the new Held wrapper need baseline adapters.
"""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import sys

HERE = Path(__file__).resolve().parent
ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else HERE.parents[3]
OUT = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else ROOT / 'build/host-connection/sleep-replay-effects'
HOST = Path(sys.argv[3]).resolve() if len(sys.argv) > 3 else ROOT / 'build/host-connection'
OUT.mkdir(parents=True, exist_ok=True)
SRC = ROOT / 'app/src/main/java/com/andrerinas/openheadunit'
service = (SRC / 'aap/AapService.kt').read_text()
direct = (SRC / 'connection/wifi/direct/WifiDirectManager.kt').read_text()
manager = (SRC / 'connection/wifi/WifiLauncherManager.kt').read_text()
comm = (SRC / 'connection/CommManager.kt').read_text()
hold = (SRC / 'connection/wifi/WirelessSleepHold.kt').read_text()
hashes = {}


def method(source, declaration):
    start = source.index(declaration)
    brace = source.index('{', start)
    depth = 0
    for end in range(brace, len(source)):
        if source[end] == '{':
            depth += 1
        elif source[end] == '}':
            depth -= 1
            if depth == 0:
                result = source[start:end + 1]
                hashes[declaration] = hashlib.sha256(result.encode()).hexdigest()
                return result
    raise ValueError(declaration)


def expression(source, declaration):
    start = source.rfind('\n', 0, source.index(declaration)) + 1
    lines = source[start:].splitlines()
    indent = len(lines[0]) - len(lines[0].lstrip())
    n = 1
    while n < len(lines) and lines[n].strip() and (
            len(lines[n]) - len(lines[n].lstrip()) > indent or lines[n].strip() == ')'):
        n += 1
    result = '\n'.join(lines[:n])
    hashes[declaration] = hashlib.sha256(result.encode()).hexdigest()
    return result


fixed = 'class Held' in hold
fixture = (HERE / 'SleepReplayEffectsFixture.kt').read_text()
quiet = method(direct, 'fun startNativeAaQuietHost(')
# The producer executes unchanged through its radio/sleep admission. The first group setup
# after the radio gate is the recording platform boundary; band/identity details are unrelated.
quiet = quiet[:quiet.index('        wifiEnableAttempts = 0')] + '''        platform.creates++
        platform.createdOperations.add(operation)
    }'''
markers = {
    'SAVE_CLASS': method(comm, 'class Disconnected('),
    'CM_CURRENT': method(comm, 'internal fun isCurrent(operation: ConnectionOperation)'),
    'CM_CANCEL_SAVE': method(comm, 'fun cancelPendingSettingsRestart()'),
    'DIRECT_CURRENT': expression(direct, 'private fun isCurrent(): Boolean') + '\n' + expression(direct, 'private fun isCurrent(command: ConnectionOperation)'),
    'DIRECT_DEFER': method(direct, 'private fun deferForGroupRemoval('),
    'DIRECT_SUPERSEDED': method(direct, 'private fun supersededByStop('),
    'DIRECT_ACTIVATE': method(direct, 'private fun activateGroup('),
    'DIRECT_REFRESH': method(direct, 'fun refreshNativeCredentials('),
    'DIRECT_START': quiet,
    'DIRECT_RECOVER': method(direct, 'private fun recoverNativeGroup('),
    'REPLAY': method(service, 'private fun replaySleepHold('),
    'REPLAY_FORCE': expression(service, 'private fun sleepReplayForce('),
    'REPLAY_CONNECTING': expression(service, 'private fun sleepReplayConnecting('),
    'SETTINGS_HOLD': method(service, 'private fun holdWirelessForSettings('),
    'SETTINGS_CHANGED': method(service, 'fun onSettingsScreenChanged('),
    'SET_ACTIVE_FROM_SETTINGS': method(manager, 'fun setActiveFromSettings('),
    'SET_ACTIVE_MODE': method(manager, 'fun setActive(\n        mode:'),
    'SET_ACTIVE': method(manager, 'fun setActive(\n        newLauncher:'),
}
if fixed:
    begin = service.index('    private class WirelessRearm(')
    end = service.index('\n\n    private fun holdWirelessForSettings(', begin)
    markers['HELD_FIELDS'] = service[begin:end]
    markers['NOTE_SLEEP'] = ''
    markers['DIRECT_HOLD'] = method(direct, 'private fun holdForSleep(')
else:
    markers['HELD_FIELDS'] = 'private var sleepHeldTicket: Long? = null\nprivate var wirelessRearmTicket = 0L\nprivate var wirelessRearmPendingForSettings = false'
    markers['NOTE_SLEEP'] = method(service, 'internal fun noteSleepHeld(')
    markers['DIRECT_HOLD'] = ''
for name, value in markers.items():
    fixture = fixture.replace('// ' + name + '\n', value + '\n')
fixture = fixture.replace('// SAVE_CLASS', markers['SAVE_CLASS'])
(OUT / 'Fixture.kt').write_text(fixture)

files = {
    'AppExitPolicy.kt': SRC / 'connection/AppExitPolicy.kt',
    'ConnectionOperation.kt': SRC / 'connection/ConnectionOperation.kt',
    'WirelessSleepHold.kt': SRC / 'connection/wifi/WirelessSleepHold.kt',
    'NativeRefreshPolicy.kt': SRC / 'connection/wifi/direct/NativeRefreshPolicy.kt',
    'NativeBringUpReentryPolicy.kt': SRC / 'connection/wifi/direct/NativeBringUpReentryPolicy.kt',
    'NativeJoinRecoveryPolicy.kt': SRC / 'connection/wifi/direct/NativeJoinRecoveryPolicy.kt',
    'WifiLauncherRestartPolicy.kt': SRC / 'connection/wifi/WifiLauncherRestartPolicy.kt',
    'WirelessSelectionPolicy.kt': SRC / 'connection/wifi/WirelessSelectionPolicy.kt',
    'WirelessCancelPolicy.kt': SRC / 'connection/wifi/WirelessCancelPolicy.kt',
    'UsbSessionQuiescePolicy.kt': SRC / 'connection/wifi/UsbSessionQuiescePolicy.kt',
    'SettingsScreenPausePolicy.kt': SRC / 'connection/wifi/SettingsScreenPausePolicy.kt',
    'BtAutoStartRearmPolicy.kt': SRC / 'app/BtAutoStartRearmPolicy.kt',
}
for name, path in files.items():
    source = path.read_text()
    hashes[str(path.relative_to(ROOT))] = hashlib.sha256(source.encode()).hexdigest()
    lines = [line for line in source.splitlines()
             if not line.startswith('package ') and not line.startswith('import com.andrerinas.openheadunit.')]
    (OUT / name).write_text('package sleepreplay\n' + '\n'.join(lines) + '\n')
(OUT / 'extracted-method-sha256.json').write_text(json.dumps(hashes, indent=2) + '\n')
toolchain = json.loads((HOST / 'toolchain.json').read_text())
jars = [Path(entry['path']) for entry in toolchain['jars']]
for entry, jar in zip(toolchain['jars'], jars):
    if hashlib.sha256(jar.read_bytes()).hexdigest() != entry['sha256']:
        raise ValueError(f'Toolchain digest mismatch: {jar}')
(OUT / 'toolchain.json').write_text(json.dumps(toolchain, indent=2) + '\n')
cp = os.pathsep.join(map(str, jars))
commands = [
    ['java', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-nowarn',
     '-classpath', cp, '-d', str(OUT / 'cases.jar'), str(OUT / 'Fixture.kt'), *map(lambda n: str(OUT / n), files)],
    ['java', '-cp', str(OUT / 'cases.jar') + os.pathsep + cp, 'sleepreplay.FixtureKt', str(OUT / 'results.json')],
]
(OUT / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
for command in commands:
    subprocess.run(command, check=True, timeout=60)
