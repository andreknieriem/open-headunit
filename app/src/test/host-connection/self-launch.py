#!/usr/bin/env python3
"""Run actual Self launch and service-cancel paths with queued Main and IO dispatchers.

Stop/Open overlap cases admit fresh input before obsolete Main/IO/network cleanup. Android listener, VPN and Activity effects are counters; the modern launch body and coroutine cancellation remain real. Queued dispatchers control entry, completion and delay deadlines, and production method bodies are extracted on every run.
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


def paragraph(file, declaration):
    """A declaration without braces of its own: through to the next blank line."""
    source = (SRC / file).read_text()
    start = source.index(declaration)
    result = source[start:source.index('\n\n', start)]
    methods[file + ':' + declaration] = hashlib.sha256(result.encode()).hexdigest()
    return result


fixture = (HERE / 'SelfLaunchFixture.kt').read_text()
comm = 'connection/CommManager.kt'
manager = 'connection/self/SelfLauncherManager.kt'
legacy = 'connection/self/launchers/SelfLauncherLegacy.kt'
service = 'aap/AapService.kt'
for marker, file, declaration, braced in [
    ('XYAUTO', service, 'private fun refreshXyAutoMediaPolicy(', True),
    ('STATE', comm, 'class Disconnected(', True),
    ('CANCEL', comm, 'fun cancelPendingSettingsRestart()', True),
    # CommManager's attempt authority and its terminal publisher, as production runs them.
    ('ATTEMPT', comm, 'internal class Attempt(', True),
    ('IN_FLIGHT', comm, 'private fun attemptInFlight()', True),
    ('BEGIN_ATTEMPT', comm, 'private fun beginAttempt(', True),
    ('REACH_PHYSICAL', comm, 'private fun reachPhysicalConnection()', True),
    ('RECORD_TERMINAL', comm, 'private fun recordTerminal(', True),
    ('LAUNCH_OUTCOME', comm, 'internal sealed class LaunchOutcome {', True),
    # The request authority a launch's wait and the dial's JOIN decide by.
    ('CONNECTION_REQUEST', comm, 'private class ConnectionRequest(', True),
    ('IS_VALID', comm, 'private fun ConnectionRequest.isValid()', False),
    ('VALID_REQUEST', comm, 'private fun validRequest()', False),
    ('RETIRE_REQUEST', comm, 'private fun retireInvalidRequest()', True),
    ('LAUNCH_MARK_CLASS', comm, 'internal class LaunchMark internal constructor(', False),
    ('LAUNCH_MARK', comm, 'internal fun launchMark(', True),
    ('ARRIVED_SINCE', comm, 'internal fun arrivedSince(', True),
    ('REPORT_FAILURE', comm, 'internal fun reportLaunchFailure(', True),
    ('AWAIT_SETTLED', comm, 'internal suspend fun awaitSettled(', True),
    ('LIVE_LOOPBACK', comm, 'internal val isLiveLoopbackSession:', True),
    ('LOOPBACK', comm, 'val isLoopbackSession:', False),
    ('REACHED_SSL', comm, 'private fun reachedSsl()', True),
    ('DISCONNECT', comm, 'fun disconnect(', True),
    ('IS_ACTIVE', manager, 'val isActive: Boolean', False),
    ('LAUNCH_PENDING', manager, 'val isLaunchPending: Boolean', True),
    ('START', manager, 'fun start(', True),
    ('LAUNCH_OUTCOME_WAIT', manager, 'private suspend fun launchOutcome(', True),
    ('ADOPT_VPN', manager, 'private fun adoptDummyVpn(', True),
    ('ESTABLISHED', manager, 'internal fun onConnectionEstablished()', True),
    ('SETTLE_ANSWERED', manager, 'private fun settleAnswered()', True),
    ('RETIRE_FULFILLED', manager, 'internal fun retireFulfilledLaunch()', True),
    ('STOP_CURRENT', manager, 'internal fun stopIfCurrent(', True),
    ('STOP', manager, 'fun stop(wasConnected:', True),
    ('DETACH_LAUNCH', manager, 'private fun detachLaunch()', True),
    ('ALLOW_LAUNCH', 'connection/self/SelfLauncherServices.kt', 'internal suspend fun ensureLaunchAllowed()', True),
    ('ARRIVED_SINCE_LAUNCH', 'connection/self/SelfLauncherServices.kt', 'internal fun arrivedSinceLaunch()', True),
    ('LEGACY_RUN', legacy, 'override suspend fun run()', True),
    ('LEGACY_WAIT', legacy, 'suspend fun runWifiLauncher()', True),
    ('MODERN_RUN', 'connection/self/launchers/SelfLauncherV17_4.kt', 'override suspend fun run()', True),
    ('OBSERVER', service, 'private fun observeConnectionState()', True),
    # The session-end VPN decision of onDisconnected; wireless-cleanup.py runs the whole callback.
    ('VPN_RELEASE', service, 'if (!superseded && !selfLauncherManager.isLaunchPending) {', True),
    ('MAIN_COMMAND', 'main/MainActivity.kt', 'private fun forceSelfModeLaunch()', True),
    ('HOME_COMMAND', 'main/HomeFragment.kt', 'private fun startSelfModeInternal(', True),
    ('START_ACTION', service, 'ACTION_START_SELF_MODE       ->', True),
    ('CANCEL_ACTION', service, 'ACTION_CANCEL_WIRELESS       ->', True),
]:
    code = member(file, declaration) if braced else paragraph(file, declaration)
    # Main and its delay scheduler are controlled; production launch/delay calls and Jobs remain intact.
    code = code.replace('Dispatchers.Main', 'service.main').replace('this@AapService', 'this@Service').replace('AapService.', 'Service.').replace('AapService::class', 'Service::class')
    code = code.replace('com.andrerinas.openheadunit.aap.protocol.proto.Control.ByeByeReason', 'ByeByeReason')
    if marker == 'MAIN_COMMAND':
        code = code.replace('Intent(this,', 'Intent(service,').replace('startForegroundService(this,', 'startForegroundService(service,')
        code = code.replace('App.provide(this)', 'App.provide(service)').replace('operationIntent(this,', 'operationIntent(service,')
    if marker == 'MODERN_RUN':
        code = code.replace('Dispatchers.IO', 'services.aap.io')
    if marker == 'REPORT_FAILURE':
        # Observation only: count standing failures, and let a case publish just before the
        # decision takes the lock, as IO can after the deadline's own checks.
        hooked = code.replace('            return LaunchOutcome.Failed', '            reports++\n            return LaunchOutcome.Failed')
        hooked = hooked.replace('        synchronized(transportLifecycleLock) {\n            if (!appExitPolicy.acceptsConnection(mark.ticket))',
                                '        beforeReport?.let { beforeReport = null; it() }\n        synchronized(transportLifecycleLock) {\n            if (!appExitPolicy.acceptsConnection(mark.ticket))')
        assert hooked.count('reports++') == 1 and hooked.count('beforeReport') == 2, 'reportLaunchFailure hooks moved'
        code = hooked
    assert '// ' + marker + '\n' in fixture, 'fixture lost marker ' + marker
    fixture = fixture.replace('// ' + marker + '\n', code + '\n')
# Extract the full Self branch of the actual service policy; remaining branches are unrelated
# wireless/USB effects. Fail visibly if its boundary is renamed rather than testing a copy.
policy = member(service, 'private fun scheduleReconnectIfNeeded(')
policy = policy[:policy.index('        if (state.holdsRecovery)')] + '    }'
policy = policy.replace('private fun scheduleReconnectIfNeeded(', 'fun applySelfEndPolicy(')
fixture = fixture.replace('// SELF_END_POLICY\n', policy + '\n')
(OUT / 'SelfModeDisconnectPolicy.kt').write_text((SRC / 'connection/self/SelfModeDisconnectPolicy.kt').read_text()
    .replace('package com.andrerinas.openheadunit.connection.self', 'package selflaunch'))
(OUT / 'SelfLaunchFixture.kt').write_text(fixture)
(OUT / 'ProjectionRaiseDeadlinePolicy.kt').write_text((SRC / 'aap/ProjectionRaiseDeadlinePolicy.kt').read_text()
    .replace('package com.andrerinas.openheadunit.aap', 'package selflaunch'))
for name in ['SelfLaunchCoalescePolicy.kt', 'SelfLaunchTimeoutPolicy.kt']:
    source = (SRC / 'connection/self' / name).read_text()
    (OUT / name).write_text(source.replace('package com.andrerinas.openheadunit.connection.self', 'package selflaunch'))
(OUT / 'DummyVpnStartRequests.kt').write_text((SRC / 'utils/DummyVpnStartRequests.kt').read_text().replace('package com.andrerinas.openheadunit.utils', 'package selflaunch'))
(OUT / 'DummyVpnPolicy.kt').write_text((SRC / 'utils/DummyVpnPolicy.kt').read_text().replace('package com.andrerinas.openheadunit.utils', 'package selflaunch'))
(OUT / 'SettingsRestartRecovery.kt').write_text((SRC / 'connection/SettingsRestartRecovery.kt').read_text()
    .replace('package com.andrerinas.openheadunit.connection', 'package selflaunch')
    .replace('import com.andrerinas.openheadunit.utils.AppLog\n', ''))
(OUT / 'extracted-method-sha256.json').write_text(json.dumps(methods, indent=2))
(OUT / 'WirelessCleanupGeneration.kt').write_text((SRC / 'connection/wifi/WirelessCleanupGeneration.kt').read_text()
    .replace('package com.andrerinas.openheadunit.connection.wifi', 'package selflaunch'))
# Reuse the toolchain selected by run.py, including its installed-Kotlin fallback.
toolchain = json.loads((OUT.parent / 'toolchain.json').read_text())
jars = [item['path'] for item in toolchain['jars']]
cp = os.pathsep.join(jars)
# The extracted service observer also contains the wireless scan-control boundary.
# Share its production policy and recording doubles with the audio lifecycle fixture.
scan_sources = [SRC / 'connection/wifi/scan/ScanControlPolicy.kt',
                SRC / 'connection/wifi/modes/nativeaa/NativeStrategy.kt',
                HERE / 'audio-stubs/WifiLauncherNative.kt',
                HERE / 'audio-stubs/WifiScanControl.kt',
                HERE / 'audio-stubs/VideoFocusEvent.kt',
                HERE / 'audio-stubs/XyAutoMediaSession.kt']
subprocess.run(['java', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                '-no-stdlib', '-no-reflect', '-nowarn', '-classpath', cp,
                '-d', str(OUT / 'regression.jar'), *map(str, sorted(OUT.glob('*.kt')) + scan_sources)],
               check=True, timeout=45)
subprocess.run(['java', '-cp', str(OUT / 'regression.jar') + os.pathsep + cp,
                'selflaunch.SelfLaunchFixtureKt'], check=True, timeout=15)
