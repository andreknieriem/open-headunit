# Contributing to Open Headunit

Thank you for contributing to Open Headunit! To keep reviews fast and regressions low across Android 4.1 (API 16) through the latest Android releases, please follow these guidelines.

## 1. Keep Pull Requests Focused
- **One topic per PR:** Keep each PR scoped to a single bug fix or feature. Large multi-topic PRs (dozens of unrelated files) are hard to review and may be asked to be split.
- **Describe how you tested it:** Include the device/Android version (or emulator/unit tests) you verified the change on.

## 2. Architecture & Testing
- **Extract decision logic into pure Kotlin `*Policy` objects** (free of Android framework calls) and cover them with JVM unit tests under `app/src/test/`.
- **Avoid breaking older Android devices (`minSdk = 16`):**
  - Guard any API > 16 calls directly with `Build.VERSION.SDK_INT >= ...` (Android Lint does not trace SDK checks through helper functions).
  - Be careful with `java.time.*` and newer `java.*` APIs — CI checks DEX references against `minSdk` and boots the app on API 16, 19, 21, and 23.
- **Settings changes:** When adding a new preference, include it in `SettingsPendingCopy` (if edited in Settings) and `SettingsBackupManager` so backup/restore stays consistent.

## 3. Build Flavors (`github` vs. `playstore`)
- Features that rely on restricted permissions, root/ADB helpers, or sharing location/navigation data with third-party apps should be scoped to the `github` flavor when they could conflict with Google Play policies.

## 4. Checks to Run Before Opening a PR
Run these locally before pushing:

```bash
./gradlew :app:testGithubDebugUnitTest
./gradlew :app:lintGithubDebug -PlintGate
```

CI runs `Build (github debug)`, `Unit tests`, `Source checks`, `Lint (-PlintGate)`, `Dex API check`, and `Start-up` smoke tests on API 16, 19, 21, and 23.
