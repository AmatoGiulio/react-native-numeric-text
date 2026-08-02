#!/bin/bash
# One fit round: build, install, drive the decrement preset, pull, compare against iOS.
#
#   .agent/tools/fit.sh <name>
#
# Always runs from the repo root, whatever the caller's directory — getting that wrong silently
# writes the captures into example/android/artifacts and the comparison then finds nothing.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

NAME="${1:?usage: fit.sh <name>}"
SERIAL="${SERIAL:-emulator-5554}"
PORT="${METRO_PORT:-8081}"
FILES="/sdcard/Android/data/numerictext.example/files"
APK="example/android/app/build/outputs/apk/debug/app-debug.apk"

# The decrement preset, 1,242 -> 1,160. It must match the iOS reference: every metric is
# normalised against the column's own settled glyph, so the other direction is a different number.
TAP_X=356
TAP_Y=1234

echo "── build"
(cd example/android && ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}" \
  ./gradlew assembleDebug -PreactNativeArchitectures=arm64-v8a -q 2>&1 | grep -E "^e:" || true)

echo "── install"
adb -s "$SERIAL" install -r -d "$APK" 2>&1 | grep -E "Success|failed"
# The external files dir only exists once the app has run; a fresh install has none.
adb -s "$SERIAL" shell mkdir -p "$FILES" 2>/dev/null || true
adb -s "$SERIAL" shell touch "$FILES/numerictext-record.on"
adb -s "$SERIAL" shell rm -rf "$FILES/numerictext-record"

echo "── launch"
# Reach Metro over adb reverse rather than 10.0.2.2: after a reinstall the dev client drops its
# server list and the 10.0.2.2 deep link lands on the connect screen instead of the app.
adb -s "$SERIAL" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null
adb -s "$SERIAL" shell am force-stop numerictext.example
adb -s "$SERIAL" shell am start -a android.intent.action.VIEW \
  -d "exp+react-native-numeric-text-example://expo-development-client/?url=http%3A%2F%2Flocalhost%3A$PORT" >/dev/null 2>&1
sleep 45

echo "── drive"
for _ in 1 2 3; do adb -s "$SERIAL" shell input tap $TAP_X $TAP_Y; sleep 7; done

echo "── pull"
rm -rf "artifacts/$NAME" artifacts/_pull
mkdir -p "artifacts/$NAME"
adb -s "$SERIAL" pull "$FILES/numerictext-record" artifacts/_pull >/dev/null 2>&1
mv artifacts/_pull/* "artifacts/$NAME/" 2>/dev/null || true
rmdir artifacts/_pull 2>/dev/null || true
adb -s "$SERIAL" shell rm -rf "$FILES/numerictext-record"

echo "── compare"
python3 .agent/tools/compare.py "artifacts/$NAME" 2>&1 | grep -v "RuntimeWarning\|c: {m"
