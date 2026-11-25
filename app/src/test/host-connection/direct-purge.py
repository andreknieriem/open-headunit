#!/usr/bin/env python3
"""Run the actual Direct recreate continuation and complete persistent-profile purge.

Android Handler delivery, profile enumeration and deletion are recording boundaries.
No manager/purge guards are reproduced in the fixture: they come from the selected
production tree, so this same runner demonstrates the before/after behavior.
"""
from pathlib import Path
import hashlib, json, os, subprocess, sys

HERE = Path(__file__).resolve().parent
ROOT = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else HERE.parents[3]
OUT = Path(sys.argv[2]).resolve() if len(sys.argv) > 2 else ROOT / 'build/host-connection/direct-purge'
HOST = Path(sys.argv[3]).resolve() if len(sys.argv) > 3 else ROOT / 'build/host-connection'
OUT.mkdir(parents=True, exist_ok=True)
base = ROOT / 'app/src/main/java/com/andrerinas/openheadunit/connection/wifi/direct'
source = (base / 'WifiDirectManager.kt').read_text()
hashes = {}


def member(declaration):
    start = source.index(declaration)
    brace = source.index('{', start)
    depth = 0
    for end in range(brace, len(source)):
        if source[end] == '{':
            depth += 1
        elif source[end] == '}':
            depth -= 1
            if depth == 0:
                body = source[start:end + 1]
                hashes[declaration] = hashlib.sha256(body.encode()).hexdigest()
                return body
    raise ValueError(declaration)


fixture = (HERE / 'DirectPurgeFixture.kt').read_text()
start = source.index('    private fun isCurrent(): Boolean')
end = source.index('\n    private var stopped', start)
current = source[start:end]
hashes['current authority'] = hashlib.sha256(current.encode()).hexdigest()
for name, body in {
    'CURRENT': current,
    'SUPERSEDED': member('private fun supersededByStop('),
    'RECREATE': member('private fun recreateNativeGroup('),
}.items():
    fixture = fixture.replace('// ' + name + '\n', body + '\n')
(OUT / 'Fixture.kt').write_text(fixture)
for name in ['P2pPersistentGroupPurge.kt', 'P2pIdentityRotationPolicy.kt', 'P2pGroupIdentityPolicy.kt']:
    content = (base / name).read_bytes()
    (OUT / name).write_bytes(content)
    hashes[name] = hashlib.sha256(content).hexdigest()
(OUT / 'extracted-method-sha256.json').write_text(json.dumps(hashes, indent=2) + '\n')
(OUT / 'Annotation.kt').write_text('package android.annotation\nannotation class SuppressLint(vararg val value:String)\n')
(OUT / 'Platform.kt').write_text('''package android.net.wifi.p2p
class WifiP2pDevice(val deviceAddress: String)
class WifiP2pGroup(val networkId: Int, val isGroupOwner: Boolean = true) { val owner: WifiP2pDevice? = null }
class WifiP2pGroupList(val groupList: Collection<WifiP2pGroup>)
class WifiP2pManager {
    class Channel
    interface ActionListener { fun onSuccess(); fun onFailure(reason: Int) }
    fun interface PersistentGroupInfoListener { fun onPersistentGroupInfoAvailable(groups: WifiP2pGroupList) }
    var info: PersistentGroupInfoListener? = null
    val deleted = mutableListOf<Int>()
    var requests = 0
    var onDelete: (Int, ActionListener) -> Unit = { _, listener -> listener.onSuccess() }
    fun requestPersistentGroupInfo(channel: Channel, listener: PersistentGroupInfoListener) { requests++; info = listener }
    fun deletePersistentGroup(channel: Channel, id: Int, listener: ActionListener) { deleted.add(id); onDelete(id, listener) }
    fun emit() { checkNotNull(info).onPersistentGroupInfoAvailable(WifiP2pGroupList(listOf(WifiP2pGroup(0), WifiP2pGroup(7)))) }
    companion object { const val ERROR=0; const val P2P_UNSUPPORTED=1; const val BUSY=2 }
}
''')
(OUT / 'Os.kt').write_text('''package android.os
object Build { object VERSION { var SDK_INT = 28 } }
class Handler {
    val pending = mutableListOf<Runnable>()
    fun postDelayed(task: Runnable, delay: Long): Boolean { pending.add(task); return true }
    fun removeCallbacks(task: Runnable) { pending.removeAll { it === task } }
    fun timeout() { pending.toList().forEach { it.run() } }
}
''')
(OUT / 'Log.kt').write_text('package com.andrerinas.openheadunit.utils\nobject AppLog { fun i(message:String) {}; fun d(message:String) {} }\n')
(OUT / 'Compat.kt').write_text('package com.andrerinas.openheadunit.connection.wifi.direct\nobject WifiDirectCompat { fun getGroupNetworkId(group:android.net.wifi.p2p.WifiP2pGroup):Int? = group.networkId }\n')
toolchain = json.loads((HOST / 'toolchain.json').read_text())
jars = [Path(entry['path']) for entry in toolchain['jars']]
for entry, jar in zip(toolchain['jars'], jars):
    assert hashlib.sha256(jar.read_bytes()).hexdigest() == entry['sha256']
(OUT / 'toolchain.json').write_text(json.dumps(toolchain, indent=2) + '\n')
cp = os.pathsep.join(map(str, jars))
commands = [
    ['java', '-cp', cp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-nowarn',
     '-classpath', cp, '-d', str(OUT / 'cases.jar'), *map(str, sorted(OUT.glob('*.kt')))],
    ['java', '-cp', str(OUT / 'cases.jar') + os.pathsep + cp, 'directpurge.FixtureKt', str(OUT / 'results.json')],
]
(OUT / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
for command in commands:
    subprocess.run(command, check=True, timeout=60)
