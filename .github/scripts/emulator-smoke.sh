#!/usr/bin/env bash
# Installs the debug APK on the running emulator, starts the main screen, and fails if the
# app crashes or a class or method is missing on this API level.
set -u
PKG=com.andrerinas.headunitrevived
OUT=${SMOKE_OUT:-smoke}
mkdir -p "$OUT"
api=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "Emulator API level: $api"

apk=$(ls apk/*.apk | head -n 1)
adb install -r "$apk" > "$OUT/install.txt" 2>&1
cat "$OUT/install.txt"
if ! grep -q Success "$OUT/install.txt"; then
  echo "::error::API $api: install failed"
  exit 1
fi

adb logcat -c
# A crash at start-up can leave "am start -W" waiting for a launch that never ends.
if ! timeout 60 adb shell am start -W -n "$PKG/com.andrerinas.openheadunit.main.MainActivity" > "$OUT/start.txt" 2>&1; then
  echo "am start did not finish within 60 s" >> "$OUT/start.txt"
fi
cat "$OUT/start.txt"
sleep 30
adb logcat -d -v threadtime > "$OUT/logcat.txt"
adb shell ps > "$OUT/ps.txt"

status=0
if grep -nE "FATAL EXCEPTION|NoSuchMethodError|NoClassDefFoundError|NoSuchFieldError|VerifyError|UnsatisfiedLinkError|ClassNotFoundException" "$OUT/logcat.txt" > "$OUT/faults.txt"; then
  echo "::error::API $api: the app logged a crash or a missing API"
  head -n 40 "$OUT/faults.txt"
  status=1
fi
if ! grep -q "$PKG" "$OUT/ps.txt"; then
  echo "::error::API $api: the app process is not running 30 s after start"
  status=1
fi

{
  echo "### API $api start-up smoke"
  if [ "$status" -eq 0 ]; then echo "Passed: the app started and logged no crash."; else echo "Failed: see faults.txt in the artifact."; fi
  if [ -s "$OUT/faults.txt" ]; then echo '```'; head -n 20 "$OUT/faults.txt"; echo '```'; fi
} >> "${GITHUB_STEP_SUMMARY:-/dev/null}"
exit "$status"
