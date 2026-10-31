#!/usr/bin/env python3
"""Execute UsbLauncherManager's exact preemptAttempt body against real coroutine Jobs.

Claim and request fixtures are identity-only field holders. No alternative cleanup guard is
implemented by the fixture; its pending scan and running job are changed only by production.
"""
from pathlib import Path
import argparse, hashlib, json, os, subprocess

p = argparse.ArgumentParser()
p.add_argument('root', nargs='?', type=Path, default=Path(__file__).resolve().parents[4])
p.add_argument('--baseline', action='store_true')
a = p.parse_args()
root = a.root.resolve()
out = root/'build/host-connection/usb-preemption'
out.mkdir(parents=True, exist_ok=True)
path = root/'app/src/main/java/com/andrerinas/openheadunit/connection/usb/UsbLauncherManager.kt'
source = path.read_text()
start = source.index('fun preemptAttempt(')
brace = source.index('{', start)
depth = 0
for end in range(brace, len(source)):
    if source[end] == '{': depth += 1
    elif source[end] == '}':
        depth -= 1
        if depth == 0: break
body = source[start:end + 1]
fixture = '''package usbpreemption
import kotlinx.coroutines.Job
object ConnectionArbiter { class Claim }
class Request(val claim:ConnectionArbiter.Claim)
class Launcher {
 val attemptLock=Any();var attemptRequest:Request?=null;var attemptJob:Job?=null;var pendingCheck:Any?=null
 // PRODUCTION
}
fun main() {
 val rows=mutableListOf<String>();var failures=0
 for(stale in listOf(true,false)) {
  val old=ConnectionArbiter.Claim();val current=ConnectionArbiter.Claim()
  val launcher=Launcher();val job=Job();val pending=Any()
  launcher.attemptRequest=Request(current);launcher.attemptJob=job;launcher.pendingCheck=pending
  val loser=if(stale)old else current
  // INVOKE
  val passed=if(stale)!job.isCancelled && launcher.pendingCheck===pending
    else job.isCancelled && launcher.pendingCheck==null
  val name=if(stale)"USB.preempt.stale-owner" else "USB.preempt.accepted-owner"
  println("${if(passed)"PASS" else "FAIL"} $name: jobCancelled=${job.isCancelled}, pendingKept=${launcher.pendingCheck===pending}")
  rows+="{\\"case\\":\\"$name\\",\\"passed\\":$passed}";if(!passed)failures++
  job.cancel()
 }
 println("RESULTS=[${rows.joinToString(",")}]")
 check(failures==0){"$failures preemption ownership regressions"}
}
'''.replace('// PRODUCTION', body).replace('// INVOKE',
    'launcher.preemptAttempt()' if a.baseline else 'launcher.preemptAttempt(loser)')
(out/'Fixture.kt').write_text(fixture)
(out/'source-sha256.json').write_text(json.dumps({
    str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest(),
    'preemptAttempt exact method': hashlib.sha256(body.encode()).hexdigest(),
    'compiled Fixture.kt': hashlib.sha256(fixture.encode()).hexdigest(),
}, indent=2)+'\n')
meta = json.loads((root/'build/host-connection/toolchain.json').read_text())
jars = [j['path'] for j in meta['jars']]
compiler = os.pathsep.join(jars)
runtime = os.pathsep.join(j for j in jars if any(x in Path(j).name for x in ('stdlib','coroutines','annotations')))
commands = [
    ['java','-Xmx512m','-cp',compiler,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-nowarn',
     '-classpath',runtime,'-d',str(out/'preemption.jar'),str(out/'Fixture.kt')],
    ['java','-cp',str(out/'preemption.jar')+os.pathsep+runtime,'usbpreemption.FixtureKt']]
(out/'commands.json').write_text(json.dumps(commands,indent=2)+'\n')
(out/'full.log').write_text('')
for command in commands:
    print('COMMAND',json.dumps(command),flush=True)
    result=subprocess.run(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=45)
    print(result.stdout,end='',flush=True)
    with (out/'full.log').open('a') as log:log.write(result.stdout)
    if command is commands[0] and result.returncode:raise SystemExit('Compilation failed: not a behavioral result')
    if command is commands[1]:
        for line in result.stdout.splitlines():
            if line.startswith('RESULTS='):(out/'results.json').write_text(json.dumps(json.loads(line[8:]),indent=2)+'\n')
        raise SystemExit(result.returncode)
