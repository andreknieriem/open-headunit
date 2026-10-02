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
# Extract lifecycle entrypoints without the unrelated Android service dependencies.
service = (repo / "app/src/main/java/com/andrerinas/openheadunit/aap/AapService.kt").read_text()


def member_block(declaration, source=service):
    start = source.index(declaration)
    brace = source.index("{", start)
    depth = 0
    for end in range(brace, len(source)):
        if source[end] == "{":
            depth += 1
        elif source[end] == "}":
            depth -= 1
            if depth == 0:
                return source[start:end + 1]
    raise RuntimeError("Unclosed service member: " + declaration)


# Exercise the production decision to renegotiate instead of rebuilding stale tracks. The
# connection itself is doubled: this runner cannot establish USB/Wi-Fi projection sessions.
comm = (repo / "app/src/main/java/com/andrerinas/openheadunit/connection/CommManager.kt").read_text()
settings_fixture = output / "AudioSettingsFixture.kt"
settings_fixture.write_text('''package com.andrerinas.openheadunit.decoder.audio
import com.andrerinas.openheadunit.aap.AapAudio
import com.andrerinas.openheadunit.utils.AppLog
internal class AudioSettingsFixture(audio: AapAudio?) {
    private class Transport(val aapAudio: AapAudio)
    private val _transport = audio?.let { Transport(it) }
    private val transportLifecycleLock = Any()
    var disconnected = false
    private fun disconnect(isUserExit: Boolean, honorKillOnDisconnect: Boolean) {
        check(!isUserExit && !honorKillOnDisconnect) { "Audio settings must keep the app available for reconnection" }
        disconnected = true
    }
''' + member_block("fun applyAudioSettings()", comm) + '\n}\n')
sources.append(settings_fixture)

lifecycle_fixture = output / "LifecycleFixture.kt"
lifecycle = (base / "LifecycleFixture.kt").read_text()
for marker, declaration, source in [
    ("PUBLICATION", "val transport = synchronized(transportLifecycleLock)", comm),
    ("APPLY", "fun applyAudioSettings()", comm),
    ("ADVANCE", "private inline fun withLiveTransport(", comm),
    ("DISCONNECT", "fun disconnect(", comm),
    ("QUIT", "private fun transportedQuited(", comm),
    ("OBSERVER", "private fun observeConnectionState()", service),
]:
    lifecycle = lifecycle.replace("// PRODUCTION " + marker,
        member_block(declaration, source).replace("CommManager.ConnectionState", "ConnectionState"))
lifecycle_fixture.write_text(lifecycle)
sources.append(lifecycle_fixture)
coroutines = jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.7.3")
classpath = os.pathsep.join(map(str, [stdlib, annotations, coroutines]))

subprocess.run(["java", "-cp", os.pathsep.join(map(str, compiler)),
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-nowarn",
                "-classpath", classpath, "-d", str(target),
                *map(str, sources)], check=True)
subprocess.run(["java", "-cp", str(target) + os.pathsep + classpath,
                "com.andrerinas.openheadunit.decoder.audio.RegressionKt"], check=True, timeout=30)

# Complete startup and teardown paths need their own thread/I/O doubles.
subprocess.run(["python3", str(base / "transport-lifecycle.py"), str(repo)], check=True)
