# Continuous Integration

## `android-ci.yml` — Android CI

Runs on every pull request and on pushes to `main`. Two parallel jobs:

| Check                         | What it does                                                                                                                                         | Intended to block? |
| ----------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- | :----------------: |
| **Build (github debug)**      | Builds the APK, verifies its audio license notices, and uploads it with the exact checked-out source archive and revision. | Yes |
| **Unit tests (github debug)** | Runs Gradle tests, the connection lifecycle harness, native CTest suites and the JNI-enabled audio harness. Uploads reports and diagnostics. | Yes |

"Intended to block" reflects the design; a failing check only actually
prevents merge once the [branch-protection rule](#branch-protection) below is
configured.

The build is **debug-signed and uses no repository secrets**, so it runs
unchanged on pull requests opened from forks.

### Branch protection

To make CI enforce quality on `main`, add a branch-protection rule requiring
these status checks:

- `Build (github debug)`
- `Unit tests (github debug)`

### Notes

- **Connection lifecycle harness.** After Gradle populates the Kotlin dependency cache,
  `python3 app/src/test/host-connection/run.py` checks connection retirement, USB races
  and Self launch ownership without a device. Failure fails the unit-test job; the
  `host-connection-diagnostics` artifact includes the log, extracted sources and metadata.
  Gradle alone does not invoke this harness.
- **Audio regression gates.** After Gradle populates the Kotlin cache,
  `bash app/src/test/run-audio-host.sh` builds and runs the callback PCM ring
  tests and native time-stretch CTest suite (DSP, actual JNI parity/lifetime,
  and linked-library scope), then runs the production Kotlin audio harness
  with `OHU_TEST_NATIVE_DSP=1`. Failure to load the JNI library is a failure,
  not a successful OHU fallback. Shell `pipefail` propagates failures through
  log capture. Per-test CTest and overall workflow-step timeouts bound hangs.
  `host-audio-diagnostics` preserves toolchain, build and test logs even on failure.
  These host tests use Android test doubles, not a device HAL or a CPU benchmark.
- **Matching source and notices.** Both APK workflows run
  `tools/package-source.sh` before building, refusing a dirty checkout, and
  put `open-headunit-source.tar.gz`, `SOURCE_REVISION.txt` and
  `SOURCE_SHA256.txt` in the APK artifact. They verify the license text in the
  actual APK with `app/src/test/packaging/check_audio_notices.py` before upload.
  Preserve the paired source when publishing a release; Actions artifacts expire.
  See `docs/AUDIO_SOURCE.md` for rebuilding and distributor instructions.
- **`github` flavor only.** CI builds the `github` flavor. The `playstore`
  flavor also builds (`./gradlew :app:assemblePlaystoreDebug`); it is simply
  not part of the CI job yet.
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
  occasionally arrive corrupt on GitHub-hosted runners. The unit-test job
  separately uses Ubuntu's host C++ compiler and host CMake **3.24+** for the
  native regressions; it does not use the Android SDK CMake for host binaries.
- **Action pinning:** all actions (GitHub first-party and third-party alike)
  are pinned to major-version tags. Maintainers who want stricter supply-chain
  hygiene can repin them to full commit SHAs.
