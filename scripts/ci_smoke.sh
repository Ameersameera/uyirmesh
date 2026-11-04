#!/usr/bin/env bash
# Runs the on-device smoke tests on the emulator started by the CI job and keeps the phone log.
set -uo pipefail
cd "$(dirname "$0")/.."

adb wait-for-device
adb shell svc power stayon true || true
adb shell wm dismiss-keyguard || true
adb shell input keyevent 82 || true
adb shell settings put global window_animation_scale 0 || true
adb shell settings put global transition_animation_scale 0 || true
adb shell settings put global animator_duration_scale 0 || true
adb logcat -c || true

# SHOTS=1 (the "App screenshots" workflow) also saves a screenshot of each main screen into ./shots.
extra=()
if [ "${SHOTS:-0}" = "1" ]; then
  adb shell rm -rf /data/local/tmp/uyir_shots || true
  # Screenshots only: no notification banners over the screens, and the emulator GPS placed in Salem
  # (near the test cases) instead of the emulator's default location.
  adb shell settings put global heads_up_notifications_enabled 0 || true
  adb emu geo fix 78.1550 11.6750 || true
  extra+=("-Pandroid.testInstrumentationRunnerArguments.shots=1")
fi

gradle :app:connectedDebugAndroidTest --stacktrace ${extra[@]+"${extra[@]}"}
status=$?

if [ "${SHOTS:-0}" = "1" ]; then
  mkdir -p shots
  adb pull /data/local/tmp/uyir_shots/. shots/ || true
  echo "================ screenshots ================"
  ls -la shots || true
fi

adb logcat -d -v time > logcat.txt || true
echo "================ app notes from the smoke test ================"
grep -E "UyirSmoke" logcat.txt | sed -e 's/^.*UyirSmoke[^:]*: //' | head -n 1500 || true
echo "================ crashes ================"
grep -E -A 40 "FATAL EXCEPTION|AndroidRuntime: Process" logcat.txt | head -n 300 || true
echo "================ failed tests ================"
find app/build/outputs/androidTest-results -name "*.xml" -print0 2>/dev/null | xargs -0 -r grep -h -A 25 "<failure" | head -n 400 || true
exit $status
