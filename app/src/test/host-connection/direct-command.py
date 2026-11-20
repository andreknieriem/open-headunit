#!/usr/bin/env python3
"""Execute actual Direct refresh/Helper/deferred callbacks with the original command Save.

SDK group queries and final create/credential effects are recording boundaries. The same
assertions read baseline and changed production method bodies, without adding baseline gates.
"""
from pathlib import Path
import hashlib,json,os,subprocess,sys
HERE=Path(__file__).resolve().parent
ROOT=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else HERE.parents[3]
OUT=Path(sys.argv[2]).resolve() if len(sys.argv)>2 else ROOT/'build/host-connection/direct-command'
HOST=Path(sys.argv[3]).resolve() if len(sys.argv)>3 else ROOT/'build/host-connection'
OUT.mkdir(parents=True,exist_ok=True)
base=ROOT/'app/src/main/java/com/andrerinas/openheadunit/connection/wifi/direct'
src=(base/'WifiDirectManager.kt').read_text(); hashes={}
def method(declaration):
    start=src.index(declaration);brace=src.index('{',start);depth=0
    for end in range(brace,len(src)):
        if src[end]=='{':depth+=1
        elif src[end]=='}':
            depth-=1
            if depth==0:
                body=src[start:end+1];hashes[declaration]=hashlib.sha256(body.encode()).hexdigest();return body
    raise ValueError(declaration)
fixed='fun refreshNativeCredentials(operation: ConnectionOperation)' in src
fixture=(HERE/'DirectCommandFixture.kt').read_text()
markers={'STOP_BODY':method('fun stop(keepGroup:'),'DEFER':method('private fun deferForGroupRemoval('),'SUPERSEDED':method('private fun supersededByStop('),'ACTIVATE':method('private fun activateGroup('),'NATIVE':method('fun refreshNativeCredentials('),'HELPER':method('fun refreshHelperGroup(')}
if fixed:
    start=src.index('    private fun isCurrent(): Boolean');end=src.index('\n    private var stopped',start)
    markers['CURRENT']=src[start:end]
    markers['RECHECK']='private var nativeRefreshRecheck: Runnable? = null\n'+method('private fun scheduleNativeRefreshRecheck(')
else:
    markers['CURRENT']=''
    markers['RECHECK']='private val nativeRefreshRecheck = Runnable { refreshNativeCredentials() }'
for name,value in markers.items():fixture=fixture.replace('// '+name+'\n',value+'\n')
fixture=fixture.replace('// ASK_NATIVE','refreshNativeCredentials('+('operation' if fixed else '')+')').replace('// ASK_HELPER','refreshHelperGroup('+('operation' if fixed else '')+')')
(OUT/'Fixture.kt').write_text(fixture)
(OUT/'NativeRefreshPolicy.kt').write_text((base/'NativeRefreshPolicy.kt').read_text().replace('package com.andrerinas.openheadunit.connection.wifi.direct','package directcommand'))
(OUT/'extracted-method-sha256.json').write_text(json.dumps(hashes,indent=2)+'\n')
toolchain=json.loads((HOST/'toolchain.json').read_text());jars=[Path(e['path']) for e in toolchain['jars']]
for entry,jar in zip(toolchain['jars'],jars):assert hashlib.sha256(jar.read_bytes()).hexdigest()==entry['sha256']
(OUT/'toolchain.json').write_text(json.dumps(toolchain,indent=2)+'\n')
cp=os.pathsep.join(map(str,jars))
commands=[['java','-cp',cp,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-nowarn','-classpath',cp,'-d',str(OUT/'cases.jar'),str(OUT/'Fixture.kt'),str(OUT/'NativeRefreshPolicy.kt')],['java','-cp',str(OUT/'cases.jar')+os.pathsep+cp,'directcommand.FixtureKt',str(OUT/'results.json')]]
(OUT/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
for command in commands:subprocess.run(command,check=True,timeout=60)
