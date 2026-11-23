# Continuous Integration

## `android-ci.yml` — Android CI

Runs on every pull request and on pushes to `main`. The jobs run in parallel:

| Check                         | What it does                                                                                                                                         | Intended to block? |
| ----------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- | :----------------: |
| **Build (github debug)**      | `./gradlew :app:assembleGithubDebug` — compiles the app (including native code and the `:contract` module) and uploads the debug APK as an artifact. |        Yes         |
| **Unit tests (github debug)** | Runs `./gradlew :app:testGithubDebugUnitTest`, then the connection lifecycle harness. Failing tests show as PR annotations. Uploads JVM reports and harness diagnostics.                   |        Yes         |
| **Source checks (workflows, generated protobuf)** | `actionlint` and `zizmor` on `.github/workflows` (`.github/zizmor.yml` accepts actions pinned to tags), then protoc 25.1 on each `.proto`, failing if the committed Java in `aap/protocol/proto` changes. Seconds, no Gradle. | Yes |

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
- **No lint job (yet).** Android Lint is not run in CI. The long hang in
  `:app:lintAnalyzeGithubDebug` was not the generated protobuf sources: it was
  `androidx.fragment`'s `UnsafeFragmentLifecycleObserverDetector` (the detector
  behind `FragmentLiveDataObserve`, `FragmentBackPressedCallback` and
  `FragmentAddMenuProvider`), which walks `HomeFragment`'s call graph with no
  depth limit in the fragment version this module can use. Those three checks
  are disabled in `app/build.gradle.kts` while the app keeps minSdk 16, since
  the first fragment release with the cap (1.9.0) needs minSdk 23. Run lint
  locally with `./gradlew :app:lintGithubDebug`; the project sets
  `lint { abortOnError = false }`, so findings are advisory by design. A CI
  lint job can be added now that the run is fast again.
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
- **Robolectric jars.** Robolectric fetches its Android jars from Maven
  Central at test time into `~/.m2`, which `setup-gradle` does not cache. A
  failed fetch fails whole test classes ("classMethod FAILED" with an
  `IOException` in `MavenArtifactFetcher`), so the unit-test job caches them.
