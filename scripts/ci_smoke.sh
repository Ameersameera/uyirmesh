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

gradle :app:connectedDebugAndroidTest --stacktrace
status=$?

adb logcat -d -v time > logcat.txt || true
echo "================ app notes from the smoke test ================"
grep -E "UyirSmoke" logcat.txt | sed -e 's/^.*UyirSmoke[^:]*: //' | head -n 1500 || true
echo "================ crashes ================"
grep -E -A 40 "FATAL EXCEPTION|AndroidRuntime: Process" logcat.txt | head -n 300 || true
echo "================ failed tests ================"
find app/build/outputs/androidTest-results -name "*.xml" -print0 2>/dev/null | xargs -0 -r grep -h -A 25 "<failure" | head -n 400 || true
exit $status
