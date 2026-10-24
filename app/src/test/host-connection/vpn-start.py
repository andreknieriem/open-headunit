#!/usr/bin/env python3
"""Exercise actual VPN start/stop and delayed onStartCommand against a bound-service double."""
from pathlib import Path
import hashlib, json, os, subprocess
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
SRC = ROOT / 'app/src/github/java/com/andrerinas/openheadunit'
OUT = ROOT / 'build/host-connection/vpn-start'
OUT.mkdir(parents=True, exist_ok=True)
methods = {}
def member(file, declaration):
    source = (SRC / file).read_text()
    start = source.index(declaration)
    brace = source.index('{', start)
    depth = 0
    for end in range(brace, len(source)):
        if source[end] == '{': depth += 1
        elif source[end] == '}':
            depth -= 1
            if depth == 0:
                code = source[start:end + 1]
                methods[declaration] = hashlib.sha256(code.encode()).hexdigest()
                return code
    raise ValueError(declaration)
fixture = (HERE / 'VpnStartFixture.kt').read_text()
for marker, file, declaration in [
    ('START', 'utils/VpnControl.kt', 'fun startVpn(context:'),
    ('STOP', 'utils/VpnControl.kt', 'fun stopVpn(context:'),
    ('COMMAND', 'aap/DummyVpnService.kt', 'override fun onStartCommand('),
]:
    fixture = fixture.replace('// ' + marker, member(file, declaration).replace('override fun', 'fun'))
(OUT / 'Fixture.kt').write_text(fixture)
(OUT / 'Gate.kt').write_text((ROOT / 'app/src/main/java/com/andrerinas/openheadunit/utils/DummyVpnStartRequests.kt').read_text().replace('package com.andrerinas.openheadunit.utils', 'package vpnstart'))
(OUT / 'extracted-method-sha256.json').write_text(json.dumps(methods, indent=2))
cp = os.pathsep.join(x['path'] for x in json.loads((OUT.parent / 'toolchain.json').read_text())['jars'])
subprocess.run(['java', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-nowarn', '-classpath', cp, '-d', str(OUT / 'cases.jar'), str(OUT / 'Fixture.kt'), str(OUT / 'Gate.kt')], check=True, timeout=45)
subprocess.run(['java', '-cp', str(OUT / 'cases.jar') + os.pathsep + cp, 'vpnstart.FixtureKt'], check=True, timeout=15)
