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
def member(source, signature):
    start=source.index(signature); opening=source.index('{',start); depth=0
    for end in range(opening,len(source)):
        if source[end]=='{': depth+=1
        elif source[end]=='}':
            depth-=1
            if depth==0: return source[start:end+1]
    raise ValueError(signature)
stop_method=member(s,'    private fun stopForAcceptedAppExit()')
# The launcher's real arm recording and terminal ticket lookup, against the fixture's CommManager.
w=(SRC/'connection/wifi/WifiLauncherManager.kt').read_text()
c=w.index('    internal fun cleanupTicketFor('); r=w.index('    internal fun noteRearm()',c)
e=w.index('\n    }\n',r)+len('\n    }\n')
launcher=w[c:e]
(OUT/'Fixture.kt').write_text((HERE/'WirelessCleanupFixture.kt').read_text().replace('// CALLBACK',method).replace('// LAUNCHER_ARM',launcher).replace('// SERVICE_STOP',stop_method))
s=(SRC/'connection/wifi/WirelessCleanupGeneration.kt').read_text().replace('package com.andrerinas.openheadunit.connection.wifi','package cleanup')
(OUT/'Generation.kt').write_text(s)
policy=(SRC/'aap/ServiceStopWaitPolicy.kt').read_text().replace('package com.andrerinas.openheadunit.aap','package cleanup')
(OUT/'ServiceStopWaitPolicy.kt').write_text(policy)
coordinator=(SRC/'connection/wifi/direct/NativeGroupCleanupCoordinator.kt').read_text().replace('package com.andrerinas.openheadunit.connection.wifi.direct','package cleanup')
(OUT/'NativeGroupCleanupCoordinator.kt').write_text(coordinator)
(OUT/'method-sha256.txt').write_text('\n'.join(hashlib.sha256(part.encode()).hexdigest() for part in [method,launcher,stop_method,policy,coordinator])+'\n')
cp=os.pathsep.join(x['path'] for x in json.loads((OUT.parent/'toolchain.json').read_text())['jars'])
subprocess.run(['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-nowarn','-classpath',cp,'-d',str(OUT/'cases.jar'),str(OUT/'Fixture.kt'),str(OUT/'Generation.kt'),str(OUT/'ServiceStopWaitPolicy.kt'),str(OUT/'NativeGroupCleanupCoordinator.kt')],check=True,timeout=45)
subprocess.run(['java','-cp',str(OUT/'cases.jar')+os.pathsep+cp,'cleanup.FixtureKt'],check=True,timeout=15)
