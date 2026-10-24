#!/usr/bin/env python3
"""Run the complete AapService disconnect callback with queued Main and teardown gates."""
from pathlib import Path
import json, os, subprocess, hashlib
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
SRC=ROOT/'app/src/main/java/com/andrerinas/openheadunit'
OUT=ROOT/'build/host-connection/wireless-cleanup'
OUT.mkdir(parents=True,exist_ok=True)
s=(SRC/'aap/AapService.kt').read_text()
a=s.index('    private fun onDisconnected(state:'); b=s.index('    /** Resume the saved route once',a)
method=s[a:b].replace('this@AapService','this@Service')
# The launcher's real arm recording and terminal ticket lookup, against the fixture's CommManager.
w=(SRC/'connection/wifi/WifiLauncherManager.kt').read_text()
c=w.index('    internal fun cleanupTicketFor('); r=w.index('    internal fun noteRearm()',c)
e=w.index('\n    }\n',r)+len('\n    }\n')
launcher=w[c:e]
(OUT/'Fixture.kt').write_text((HERE/'WirelessCleanupFixture.kt').read_text().replace('// CALLBACK',method).replace('// LAUNCHER_ARM',launcher))
s=(SRC/'connection/wifi/WirelessCleanupGeneration.kt').read_text().replace('package com.andrerinas.openheadunit.connection.wifi','package cleanup')
(OUT/'Generation.kt').write_text(s)
(OUT/'method-sha256.txt').write_text(hashlib.sha256(method.encode()).hexdigest()+'\n'+hashlib.sha256(launcher.encode()).hexdigest())
cp=os.pathsep.join(x['path'] for x in json.loads((OUT.parent/'toolchain.json').read_text())['jars'])
subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-nowarn','-classpath',cp,'-d',str(OUT/'cases.jar'),str(OUT/'Fixture.kt'),str(OUT/'Generation.kt')],check=True,timeout=45)
subprocess.run(['java','-cp',str(OUT/'cases.jar')+os.pathsep+cp,'cleanup.FixtureKt'],check=True,timeout=15)
