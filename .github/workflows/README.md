# Continuous Integration

## `android-ci.yml` — Android CI

Runs on every pull request and on pushes to `main`. The jobs run in parallel:

| Check                         | What it does                                                                                                                                         | Intended to block? |
| ----------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- | :----------------: |
| **Build (github debug)**      | `./gradlew :app:assembleGithubDebug` — compiles the app (including native code and the `:contract` module) and uploads the debug APK as an artifact. |        Yes         |
| **Unit tests (github debug)** | Runs `./gradlew :app:testGithubDebugUnitTest`, then the connection lifecycle harness. Failing tests show as PR annotations. Uploads JVM reports and harness diagnostics.                   |        Yes         |
| **Lint (old Android APIs, format strings)** | `./gradlew :app:lintGithubDebug -PlintGate`: runs only the API level, format string and new-Android crash checks listed in `app/build.gradle.kts`, and fails on a finding not in `app/lint-baseline-gate.xml`. | Yes |
| **Source checks (workflows, generated protobuf)** | `actionlint` and `zizmor` on `.github/workflows` (`.github/zizmor.yml` accepts actions pinned to tags), then protoc 25.1 on each `.proto`, failing if the committed Java in `aap/protocol/proto` changes. Seconds, no Gradle. | Yes |
| **Dex API check (java.* above minSdk)** | After Build, reads the APK's dex and fails on a call from `com.andrerinas` code to a `java.*` API newer than minSdk 16 that `app/dex-api-baseline.txt` does not list. Seconds. | Yes |
| **Start-up on API 16, 19, 21, 23** | After Build, installs its APK on an x86 emulator per level and starts it. Fails on a crash or a missing class, method or native library in logcat, or if the process is gone 30 s after start. | Yes |

"Intended to block" reflects the design; a failing check only actually
prevents merge once the [branch-protection rule](#branch-protection) below is
configured.

The build is **debug, unsigned, and uses no repository secrets**, so it runs
unchanged on pull requests opened from forks.

### Branch protection

To make CI enforce quality on `main`, add a branch-protection rule requiring
these status checks:

- `Build (github debug)`
- `Unit tests (github debug)`
- `Source checks (workflows, generated protobuf)`
- `Dex API check (java.* above minSdk)`
- `Start-up on API 16`, `Start-up on API 19`, `Start-up on API 21`, `Start-up on API 23`
- `Lint (old Android APIs, format strings)`

### Notes

- **Connection lifecycle harness.** After Gradle populates the Kotlin dependency cache,
  `python3 app/src/test/host-connection/run.py` checks connection retirement, USB races
  and Self launch ownership without a device. Failure fails the unit-test job; the
  `host-connection-diagnostics` artifact includes the log, extracted sources and metadata.
  Gradle alone does not invoke this harness.
- **`github` flavor only.** CI builds the `github` flavor. The `playstore`
  flavor also builds (`./gradlew :app:assemblePlaystoreDebug`); it is simply
  not part of the CI job yet.
- **`main` may show red** on `Build`/`Unit tests` until the known
  `SettingsFragment.kt:767` smart-cast compile error (issue #659) is fixed.
  This is expected, not a CI misconfiguration.
- **Lint gate.** A full lint run stays advisory (`abortOnError = false`). The
  CI job passes `-PlintGate`, which narrows lint to two groups of checks and
  makes them fail the build:
  - Old Android: `NewApi` and `InlinedApi` against minSdk 16, and the two
    format string checks.
  - New Android, from targetSdk 36: Bluetooth and other runtime permissions,
    receiver and `PendingIntent` flags, implicit intents, and the foreground
    service and exact alarm permissions.

  Core library desugaring is on, so Java 8 library calls such as `removeIf`,
  `Map.forEach` and `java.time` work on every level, and lint does not flag
  them. Calls the desugared library does not cover still crash below their
  level. Lint misses some of those, for example a
  member called on a Kotlin collection type. The dex API check covers that gap
  for `java.*`. Lint stays the check for `android.*`, where it understands
  `SDK_INT` guards. A helper that checks `SDK_INT` needs
  `@ChecksSdkIntAtLeast`, or lint does not see the guard. Findings that
  already exist are in `app/lint-baseline-gate.xml`; fix one and lint reports
  it as fixed. The 23 `home_page_position` entries are a lint error: both
  arguments are `Int`. To rebuild the baseline, delete the file and run the
  gate twice locally. The three Fragment checks stay disabled, as explained in
  `app/build.gradle.kts`.
- **Toolchain:** Temurin JDK 21, plus the pinned NDK `29.0.14206865` and
  CMake `3.22.1` installed via `sdkmanager`. The
  `sdkmanager` install is retried up to three times because its downloads
  occasionally arrive corrupt on GitHub-hosted runners.
- **Action pinning:** all actions (GitHub first-party and third-party alike)
  are pinned to major-version tags. Maintainers who want stricter supply-chain
  hygiene can repin them to full commit SHAs and drop the `ref-pin` policy in
  `.github/zizmor.yml`. Dependabot (`.github/dependabot.yml`) opens one grouped
  PR a month when an action has a newer tag. Every checkout sets
  `persist-credentials: false`, so no later step can read the token from disk.
- **Emulator start-up.** Robolectric runs API 23 and up only, so these jobs
  are the one check on the old versions minSdk 16 allows. They cover start-up
  paths only, as the app has no instrumented tests. Dalvik's "Could not find
  method" lines on old levels are normal for guarded calls and do not fail the
  job. The script is `.github/scripts/emulator-smoke.sh`; logs upload as
  `smoke-api-<level>`.
- **Robolectric jars.** Robolectric fetches its Android jars from Maven
  Central at test time into `~/.m2`, which `setup-gradle` does not cache. A
  failed fetch fails whole test classes ("classMethod FAILED" with an
  `IOException` in `MavenArtifactFetcher`), so the unit-test job caches them.
- **Dex API check.** `.github/scripts/dex-api-check.py` runs `dexdump` on the
  APK and looks up each `java.*` class, method and field our code uses in the
  SDK's `api-versions.xml`, through superclasses and interfaces. A member
  counts only from the level where its receiver class exists. Desugared calls
  go to `j$.*` classes, so the check does not see them. The baseline
  lists the calls that exist today. To rebuild it, run the script with
  `--write-baseline`.
