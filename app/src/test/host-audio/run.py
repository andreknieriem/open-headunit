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
cache = Path.home() / ".gradle/caches/modules-2/files-2.1"


def jar(group, artifact, version="*"):
    matches = sorted(path for path in (cache / group / artifact).glob(version + "/*/*.jar")
                     if path.name == artifact + "-" + path.parent.parent.name + ".jar")
    if not matches:
        raise SystemExit("Missing cached compiler dependency; run :app:testGithubDebugUnitTest first")
    return matches[0]


kotlin = re.search(r"kotlin-gradle-plugin:([0-9.]+)", (repo / "build.gradle.kts").read_text()).group(1)
stdlib = jar("org.jetbrains.kotlin", "kotlin-stdlib", kotlin)
annotations = jar("org.jetbrains", "annotations", "13.0")
protobuf_version = re.search(r"protobuf-java:([0-9.]+)", (repo / "app/build.gradle.kts").read_text()).group(1)
protobuf = jar("com.google.protobuf", "protobuf-java", protobuf_version)
protocol_classes = repo / "app/build/intermediates/javac/githubDebug/compileGithubDebugJavaWithJavac/classes"
if not (protocol_classes / "com/andrerinas/openheadunit/aap/protocol/proto/Media.class").is_file():
    raise SystemExit("Missing compiled protocol classes; run :app:testGithubDebugUnitTest first")
classpath = os.pathsep.join(map(str, [stdlib, annotations, protobuf, protocol_classes]))
compiler = [jar("org.jetbrains.kotlin", "kotlin-compiler-embeddable", kotlin), stdlib,
            jar("org.jetbrains.kotlin", "kotlin-reflect", "1.6.10"),
            jar("org.jetbrains.kotlin", "kotlin-script-runtime", kotlin),
            jar("org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0"), annotations]
sources = [repo / line for line in (base / "sources.txt").read_text().splitlines() if line]
sources += sorted((base / "stubs").glob("*.kt")) + [base / "Regression.kt"]
output = repo / "build/host-audio"
output.mkdir(parents=True, exist_ok=True)
target = output / "regression.jar"
subprocess.run(["java", "-cp", os.pathsep.join(map(str, compiler)),
                "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-nowarn",
                "-classpath", classpath, "-d", str(target),
                *map(str, sources)], check=True)
subprocess.run(["java", "-cp", str(target) + os.pathsep + classpath,
                "com.andrerinas.openheadunit.decoder.audio.RegressionKt"], check=True, timeout=30)
if os.environ.get("OHU_TEST_NATIVE_DSP") == "1":
    subprocess.run(["java", "-cp", str(target) + os.pathsep + classpath,
                    "com.andrerinas.openheadunit.decoder.audio.NativeMusicIntegrationKt"],
                   check=True, timeout=30)
