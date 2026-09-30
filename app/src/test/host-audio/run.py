#!/usr/bin/env python3
"""Exercise production audio owners with deterministic Android test doubles, without a device.

Run the Gradle unit tests first to populate the existing Kotlin compiler dependencies.
"""
from pathlib import Path
import subprocess
import os

base = Path(__file__).resolve().parent
repo = base.parents[3]
cache = Path.home() / ".gradle/caches/modules-2/files-2.1"


def jar(group, artifact, version="*"):
    matches = sorted((cache / group / artifact).glob(version + "/*/*.jar"))
    if not matches:
        raise SystemExit("Missing cached compiler dependency; run :app:testGithubDebugUnitTest first")
    return matches[0]


stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", "1.9.22")
annotations = jar("org.jetbrains", "annotations", "13.0")
compiler = [jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", "1.9.22"), stdlib,
            jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
            jar("org.jetbrains.kotlin", "kotlin-script-runtime", "1.9.22"),
            jar("org.jetbrains.intellij.deps", "trove4j"), annotations]
sources = [repo / line for line in (base / "sources.txt").read_text().splitlines() if line]
sources += sorted((base / "stubs").glob("*.kt")) + [base / "Regression.kt"]
output = repo / "build/host-audio"
output.mkdir(parents=True, exist_ok=True)
target = output / "regression.jar"
# Run the service's exact focus methods without its unrelated Android service dependencies.
# Only the system-service lookup and Bluetooth probe are doubled; focus behavior is not copied.
service = (repo / "app/src/main/java/com/andrerinas/openheadunit/aap/AapService.kt").read_text()


def member_block(declaration):
    start = service.index(declaration)
    brace = service.index("{", start)
    depth = 0
    for end in range(brace, len(service)):
        if service[end] == "{":
            depth += 1
        elif service[end] == "}":
            depth -= 1
            if depth == 0:
                return service[start:end + 1]
    raise RuntimeError("Unclosed service member: " + declaration)


fixture = output / "ServiceFocusFixture.kt"
fixture.write_text('''package com.andrerinas.openheadunit.decoder.audio
import android.media.*
import android.os.Build
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
private object Context { const val AUDIO_SERVICE = "audio" }
private object BluetoothHelper { fun isA2dpMediaLinkActive(context: Any) = false }
internal class ServiceFocusFixture(private val manager: AudioManager) {
    private val settings = Settings().apply { staticAudioFocus = true }
    private fun getSystemService(name: String): Any = manager
    fun acquire() { requestPermanentAudioFocus() }
    fun release() { releasePermanentAudioFocus() }
''' + next(line for line in service.splitlines() if "private var permanentFocusRequest:" in line)
    + '\n' + member_block("private val permanentFocusListener =")
    + '\n' + member_block("private fun requestPermanentAudioFocus()")
    + '\n' + member_block("private fun releasePermanentAudioFocus()") + '\n}\n')
sources.append(fixture)
subprocess.run(["java", "-cp", os.pathsep.join(map(str, compiler)),
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-nowarn",
                "-classpath", str(stdlib) + os.pathsep + str(annotations), "-d", str(target),
                *map(str, sources)], check=True)
subprocess.run(["java", "-cp", str(target) + os.pathsep + str(stdlib),
                "com.andrerinas.openheadunit.decoder.audio.RegressionKt"], check=True, timeout=30)
