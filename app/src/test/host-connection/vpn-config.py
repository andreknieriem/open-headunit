#!/usr/bin/env python3
"""Exercise the production VPN configuration across API levels with a recording Builder."""
from pathlib import Path
import json, os, subprocess
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
SRC = ROOT / 'app/src/github/java/com/andrerinas/openheadunit'
OUT = ROOT / 'build/host-connection/vpn-config'
OUT.mkdir(parents=True, exist_ok=True)
source = (SRC / 'aap/DummyVpnService.kt').read_text()
start = source.index('private fun startVpn(excludeSelf:')
brace = source.index('{', start)
depth = 0
for end in range(brace, len(source)):
    if source[end] == '{': depth += 1
    elif source[end] == '}':
        depth -= 1
        if depth == 0: break
method = source[start:end+1].replace('com.andrerinas.openheadunit.utils.DummyVpnPolicy', 'DummyVpnPolicy')
(OUT / 'Fixture.kt').write_text((HERE / 'VpnConfigFixture.kt').read_text().replace('// CONFIG', method))
policy = ROOT / 'app/src/main/java/com/andrerinas/openheadunit/utils/DummyVpnPolicy.kt'
(OUT / 'Gate.kt').write_text(policy.read_text().replace('package com.andrerinas.openheadunit.utils', 'package vpnconfig'))
cp = os.pathsep.join(x['path'] for x in json.loads((OUT.parent / 'toolchain.json').read_text())['jars'])
subprocess.run(['java', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-nowarn', '-classpath', cp, '-d', str(OUT / 'cases.jar'), str(OUT / 'Fixture.kt'), str(OUT / 'Gate.kt')], check=True, timeout=45)
subprocess.run(['java', '-cp', str(OUT / 'cases.jar') + os.pathsep + cp, 'vpnconfig.FixtureKt'], check=True, timeout=15)
