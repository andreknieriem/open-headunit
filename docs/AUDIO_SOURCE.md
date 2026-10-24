# Audio dependencies: source, rebuilding and distribution

## Obtain the matching source

Repository: https://github.com/andreknieriem/open-headunit
Builds: https://github.com/andreknieriem/open-headunit/actions
Releases: https://github.com/andreknieriem/open-headunit/releases

The About screen shows the APK's build revision. For CI APKs, download the
accompanying `open-headunit-source.tar.gz` and `SOURCE_REVISION.txt` from the
same build artifact as the APK. This archive contains the checked-out tree
actually built, including PR merge revisions, native libraries, local changes,
interface definitions and build scripts. It is not an archive of a moving
`main` branch or only the upstream library releases. GitHub Actions artifact
downloads may require signing in and are subject to artifact expiry.

For a published release, use the corresponding source supplied alongside
that APK. With a Git checkout, use `git checkout <revision>` for that exact
revision. A revision ending in `-dirty` has local changes not identified by
the commit alone: obtain those changes from the APK distributor as well.
Do not substitute a newer source tree just because its version name matches.

## Build, modify and relink

Use JDK 21 and an Android SDK with platform 36. Android NDK 29.0.14206865
and Android CMake 3.22.1 are pinned in `app/build.gradle.kts`. Gradle and the
remaining dependencies are declared in the checked-in build files and wrapper.
Network access is needed to download those build dependencies on the first run.

From the source root (or the `open-headunit` directory after extracting):

    export ANDROID_HOME=/path/to/android-sdk
    sdkmanager "platforms;android-36" "ndk;29.0.14206865" "cmake;3.22.1"
    ./gradlew :app:assembleGithubDebug :app:testGithubDebugUnitTest

The APK is written to `app/build/outputs/apk/github/debug/`. An extracted
archive has no Git metadata, so its build-revision label may be `unknown`;
`SOURCE_REVISION.txt` identifies the original archive. This is a source-rebuild
procedure, not a promise of byte-identical APKs or matching signing certificates.

The editable SoundTouch and Rubber Band code is under
`app/src/main/cpp/third_party/`. Source selection and compiler definitions are
in that directory's `CMakeLists.txt`. OHU's adapter and JNI entry points are
`app/src/main/cpp/audio_time_stretcher.cpp` and `audio_time_stretcher_jni.cpp`.
The selected libraries are statically linked into `libhur_time_stretch.so`.
Change their source and rebuild the APK with the command above to relink them.
No opaque prebuilt SoundTouch or Rubber Band binaries are required.

For native regression tests, use a host C++17 compiler, JDK and CMake 3.24+.
See `app/src/test/native-time-stretch/README.md` and run
`bash app/src/test/run-audio-host.sh` after Gradle has populated its Kotlin cache.
Host tests use test doubles for Android APIs and do not replace device testing.

Install a debug build with Android's package installer or `adb install -r`.
It is signed with your local debug key, not the maintainer's release key.
If the installed app has a different signature, back up its settings/data
before uninstalling it to install your version; uninstalling removes app data.
A distributor of a device that restricts installation must also supply any
installation information required by the applicable licenses.

## Distributor checklist

- Keep `NOTICE_AUDIO.txt`, the full license texts and source/build guide in
  the APK; they are available offline in About. Verify packaging with
  `python3 app/src/test/packaging/check_audio_notices.py <apk>`.
- Supply the complete corresponding source for the exact distributed build,
  including any further modifications and build/interface files. The source
  archive is created before CI builds and accompanies the APK in both APK
  workflows. Do not distribute only the APK while dropping its source access.
- When publishing or mirroring a release, publish/preserve the matching source
  archive and revision beside the APK. An expiring Actions artifact or a link
  to the latest upstream branch is not a lasting release source distribution.
- Keep library copyright notices and document further modifications. Changing
  the APK's default algorithm does not remove obligations for included code.

This guide covers the newly added audio components. It is not a substitute
for reviewing the licenses and distribution requirements of all dependencies.
