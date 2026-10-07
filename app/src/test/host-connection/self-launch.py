#!/usr/bin/env python3
"""Run actual Self launch and service-cancel paths with a queued Main dispatcher.

Android listener, VPN and Activity effects are counters. Coroutine cancellation is real and the queued dispatcher controls delay deadlines; production method bodies are extracted on every run.
"""
from pathlib import Path
import hashlib
import json
import os
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
SRC = ROOT / 'app/src/main/java/com/andrerinas/openheadunit'
OUT = ROOT / 'build/host-connection/self-launch'
OUT.mkdir(parents=True, exist_ok=True)
methods = {}


def member(file, declaration):
    source = (SRC / file).read_text()
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
                methods[file + ':' + declaration] = hashlib.sha256(result.encode()).hexdigest()
                return result
    raise ValueError(declaration)


fixture = (HERE / 'SelfLaunchFixture.kt').read_text()
comm = 'connection/CommManager.kt'
manager = 'connection/self/SelfLauncherManager.kt'
legacy = 'connection/self/launchers/SelfLauncherLegacy.kt'
for marker, file, declaration in [
    ('STATE', comm, 'data class Disconnected('),
    ('CANCEL', comm, 'fun cancelPendingSettingsRestart()'),
    ('START', manager, 'fun start(settingsRestart:'),
    ('STOP', manager, 'fun stop(wasConnected:'),
    ('ESTABLISHED', manager, 'internal fun onConnectionEstablished()'),
    ('STOP_CURRENT', manager, 'internal fun stopIfCurrent('),
    ('ALLOW_LAUNCH', 'connection/self/SelfLauncherServices.kt', 'internal suspend fun ensureLaunchAllowed()'),
    ('LEGACY_RUN', legacy, 'override suspend fun run()'),
    ('LEGACY_WAIT', legacy, 'suspend fun runWifiLauncher()'),
    ('CANCEL_ACTION', 'aap/AapService.kt', 'ACTION_CANCEL_WIRELESS       ->'),
]:
    code = member(file, declaration)
    # Main and its delay scheduler are controlled; production launch/delay calls and Jobs remain intact.
    code = code.replace('Dispatchers.Main', 'service.main')
    fixture = fixture.replace('// ' + marker + '\n', code + '\n')
(OUT / 'SelfLaunchFixture.kt').write_text(fixture)
for name in ['SelfLaunchCoalescePolicy.kt', 'SelfLaunchTimeoutPolicy.kt']:
    source = (SRC / 'connection/self' / name).read_text()
    (OUT / name).write_text(source.replace('package com.andrerinas.openheadunit.connection.self', 'package selflaunch'))
(OUT / 'extracted-method-sha256.json').write_text(json.dumps(methods, indent=2))
# Reuse the toolchain selected by run.py, including its installed-Kotlin fallback.
toolchain = json.loads((OUT.parent / 'toolchain.json').read_text())
jars = [item['path'] for item in toolchain['jars']]
cp = os.pathsep.join(jars)
subprocess.run(['java', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-nowarn', '-classpath', cp,
                '-d', str(OUT / 'regression.jar'), *map(str, sorted(OUT.glob('*.kt')))],
               check=True, timeout=45)
subprocess.run(['java', '-cp', str(OUT / 'regression.jar') + os.pathsep + cp,
                'selflaunch.SelfLaunchFixtureKt'], check=True, timeout=15)
