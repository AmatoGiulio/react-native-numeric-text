#!/bin/bash
# One fit round that captures BOTH the single crossing and the fast alternation from one build.
#
#   .agent/tools/round.sh <name>
#
# Writes artifacts/<name>/ (decrement 1,242 -> 1,160, three runs) and artifacts/<name>_alt/ (the
# 60 ms alternation, three runs) and prints compare.py plus band.py for each.
#
# Same trap as fit.sh: the taps are FIXED SCREEN COORDINATES, so adding or removing a preset button
# moves them. Verified against `describe` on 2026-08-03 at 1080x2400:
#   "1,242 -> 1,160"   centre (0.330, 0.4035) -> 356, 968
#   "1,160 -> 1,242"   centre (0.670, 0.4035) -> 724, 968
#   "alterna x20 60ms" centre (0.2865, 0.6265) -> 309, 1504
# If a run reads far worse than the last for no reason, check which preset the marks belong to
# before believing the number.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

NAME="${1:?usage: round.sh <name>}"
SERIAL="${SERIAL:-emulator-5554}"
PORT="${METRO_PORT:-8081}"
FILES="/sdcard/Android/data/numerictext.example/files"
APK="example/android/app/build/outputs/apk/debug/app-debug.apk"

SINGLE_X=356; SINGLE_Y=968
UP_X=724;     UP_Y=968
ALT_X=309;    ALT_Y=1504

echo "── build"
(cd example/android && ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}" \
  ./gradlew assembleDebug -PreactNativeArchitectures=arm64-v8a -q 2>&1 | grep -E "^e:" || true)

echo "── install"
adb -s "$SERIAL" shell rm -rf "$FILES/numerictext-record" 2>/dev/null || true
if ! adb -s "$SERIAL" install -r -d "$APK" 2>&1 | grep -qE "Success"; then
  echo "   install failed, reclaiming space"
  adb -s "$SERIAL" uninstall numerictext.example >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell pm trim-caches 999G >/dev/null 2>&1 || true
  adb -s "$SERIAL" install -r -d "$APK" 2>&1 | grep -E "Success|failed"
fi
adb -s "$SERIAL" shell mkdir -p "$FILES" 2>/dev/null || true
adb -s "$SERIAL" shell touch "$FILES/numerictext-record.on"
adb -s "$SERIAL" shell rm -rf "$FILES/numerictext-record"

echo "── launch"
adb -s "$SERIAL" reverse "tcp:$PORT" "tcp:$PORT" >/dev/null
adb -s "$SERIAL" shell am force-stop numerictext.example
adb -s "$SERIAL" shell am start -a android.intent.action.VIEW \
  -d "exp+react-native-numeric-text-example://expo-development-client/?url=http%3A%2F%2Flocalhost%3A$PORT" >/dev/null 2>&1
sleep 45

pull() { # <dir>
  rm -rf "artifacts/$1" artifacts/_pull
  mkdir -p "artifacts/$1"
  adb -s "$SERIAL" pull "$FILES/numerictext-record" artifacts/_pull >/dev/null 2>&1
  mv artifacts/_pull/* "artifacts/$1/" 2>/dev/null || true
  rmdir artifacts/_pull 2>/dev/null || true
  adb -s "$SERIAL" shell rm -rf "$FILES/numerictext-record"
}

echo "── drive: single crossing"
for _ in 1 2 3; do adb -s "$SERIAL" shell input tap $SINGLE_X $SINGLE_Y; sleep 7; done
pull "$NAME"

echo "── drive: single crossing, the other way"
for _ in 1 2 3; do adb -s "$SERIAL" shell input tap $UP_X $UP_Y; sleep 7; done
pull "${NAME}_up"

echo "── drive: alternation 60 ms"
for _ in 1 2 3; do adb -s "$SERIAL" shell input tap $ALT_X $ALT_Y; sleep 8; done
pull "${NAME}_alt"

echo
echo "── single crossing (decrement)"
python3 .agent/tools/compare.py "artifacts/$NAME" 2>&1 | grep -v "RuntimeWarning\|c: {m"
echo
echo "── single crossing (increment)"
python3 .agent/tools/compare.py "artifacts/${NAME}_up" --up 2>&1 | grep -v "RuntimeWarning\|c: {m"
echo
echo "── alternation, middle band"
python3 .agent/tools/band.py "artifacts/${NAME}_alt"
