#!/usr/bin/env python3
"""Exercise production audio owners with deterministic Android test doubles, without a device.

Run the Gradle unit tests first to populate the existing Kotlin compiler dependencies.
"""
from pathlib import Path
import subprocess
import os
import re

base = Path(__file__).resolve().parent
repo = base.parents[3]
audio_base = base.parent / "host-audio"
if not (audio_base / "sources.txt").exists():
    print("Audio/connection integration is available when the playback PR is also present.")
    raise SystemExit(0)
cache = Path.home() / ".gradle/caches/modules-2/files-2.1"


def jar(group, artifact, version="*"):
    matches = sorted((cache / group / artifact).glob(version + "/*/*.jar"))
    if not matches:
        raise SystemExit("Missing cached compiler dependency; run :app:testGithubDebugUnitTest first")
    return matches[0]


# The build's Kotlin version, so the jars are the ones Gradle already cached.
kotlin = re.search(r"kotlin-gradle-plugin:([0-9.]+)", (repo / "build.gradle.kts").read_text()).group(1)
stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", kotlin)
annotations = jar("org.jetbrains", "annotations", "13.0")
coroutines = jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0")
compiler = [jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", kotlin), stdlib,
            jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
            jar("org.jetbrains.kotlin", "kotlin-script-runtime", kotlin), coroutines, annotations]
sources = [repo / line for line in (audio_base / "sources.txt").read_text().splitlines() if line]
overrides = {p.name for p in (base / "audio-stubs").glob("*.kt")}
sources += [p for p in sorted((audio_base / "stubs").glob("*.kt")) if p.name not in overrides]
sources += sorted((base / "audio-stubs").glob("*.kt"))
output = repo / "build/host-connection/audio-integration"
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


comm = (repo / "app/src/main/java/com/andrerinas/openheadunit/connection/CommManager.kt").read_text()

lifecycle_fixture = output / "LifecycleFixture.kt"
lifecycle = (base / "LifecycleFixture.kt").read_text()
for marker, declaration, source in [
    ("PUBLICATION", "val transport = synchronized(transportLifecycleLock)", comm),
    ("APPLY", "fun applyAudioSettings()", comm),
    ("ADVANCE", "private inline fun withLiveTransport(", comm),
    ("DISCONNECT", "fun disconnect(", comm),
    ("CANCEL_SETTINGS", "fun cancelPendingSettingsRestart()", comm),
    ("DISCONNECTED_STATE", "class Disconnected(", comm),
    ("REACHED_SSL", "private fun reachedSsl()", comm),
    ("QUIT", "private fun transportedQuited(", comm),
    ("OBSERVER", "private fun observeConnectionState()", service),
]:
    lifecycle = lifecycle.replace("// PRODUCTION " + marker + "\n",
        member_block(declaration, source).replace("CommManager.ConnectionState", "ConnectionState").replace("this@AapService", "this@ObserverFixture") + "\n")
lifecycle_fixture.write_text(lifecycle)
sources.append(lifecycle_fixture)
recovery = output / "SettingsRestartRecovery.kt"
recovery.write_text((repo / "app/src/main/java/com/andrerinas/openheadunit/connection/SettingsRestartRecovery.kt").read_text()
                   .replace("package com.andrerinas.openheadunit.connection", "package com.andrerinas.openheadunit.decoder.audio"))
sources.append(recovery)
entrypoint = output / "IntegrationMain.kt"
entrypoint.write_text("package com.andrerinas.openheadunit.decoder.audio\nfun main() { audioLifecycleBoundaryRegression() }\n")
sources.append(entrypoint)
classpath = os.pathsep.join(map(str, [stdlib, annotations, coroutines]))

subprocess.run(["java", "-cp", os.pathsep.join(map(str, compiler)),
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-nowarn",
                "-classpath", classpath, "-d", str(target),
                *map(str, sources)], check=True)
subprocess.run(["java", "-cp", str(target) + os.pathsep + classpath,
                "com.andrerinas.openheadunit.decoder.audio.IntegrationMainKt"], check=True, timeout=30)
