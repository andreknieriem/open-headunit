#!/usr/bin/env python3
"""Exercise the actual service-owned HCT coordinator with a recording native carrier.

No firmware calls run. Client launch ordering, retained ownership and callback replacement
use the production class; Kotlin imports alone are adapted to lightweight service doubles.
"""
from pathlib import Path
import json, os, subprocess
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
SRC = ROOT / 'app/src/main/java/com/andrerinas/openheadunit'
OUT = ROOT / 'build/host-connection/hct-owner'
OUT.mkdir(parents=True, exist_ok=True)
src = (SRC / 'connection/wifi/modes/nativeaa/hct/HctWppOwner.kt').read_text()
src = '\n'.join(line for line in src.splitlines() if not line.startswith(('package ', 'import ', '@RequiresApi')))
(OUT / 'Owner.kt').write_text('package hctowner\nimport kotlinx.coroutines.*\n' + src)
def method(text, declaration):
    start = text.index(declaration)
    brace = text.index('{', start)
    depth = 0
    for end in range(brace, len(text)):
        if text[end] == '{': depth += 1
        elif text[end] == '}':
            depth -= 1
            if depth == 0: return text[start:end+1]
    raise ValueError(declaration)
fixture = (HERE / 'HctOwnerFixture.kt').read_text()
settings = (SRC / 'main/SettingsFragment.kt').read_text()
service = (SRC / 'aap/AapService.kt').read_text()
fixture = fixture.replace('// CONNECTION_DIALOG', method(settings, 'private fun showConnectionModeDialog()').replace('private fun', 'fun', 1))
fixture = fixture.replace('// STOP_WIRELESS', method(service, 'internal fun stopWirelessForCommand('))
(OUT / 'Fixture.kt').write_text(fixture)
cp = os.pathsep.join(x['path'] for x in json.loads((OUT.parent / 'toolchain.json').read_text())['jars'])
subprocess.run(['java', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-nowarn', '-classpath', cp, '-d', str(OUT/'cases.jar'), str(OUT/'Owner.kt'), str(OUT/'Fixture.kt')], check=True, timeout=45)
subprocess.run(['java','-cp',str(OUT/'cases.jar')+os.pathsep+cp,'hctowner.FixtureKt'], check=True, timeout=15)
