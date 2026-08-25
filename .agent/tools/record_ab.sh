#!/bin/bash
# Family B Android recording script.
# Records baseline (ZERO_VEL=false) and ZERO_VEL=true for A/B comparison.
# Requires: Metro on :8081, emulator booted, adb reverse set up.
#
# Usage:
#   .agent/tools/record_ab.sh A    # records build A (ZERO_VEL=false)
#   .agent/tools/record_ab.sh B    # records build B (ZERO_VEL=true)
#   .agent/tools/record_ab.sh A2   # second acquisition of A
#   .agent/tools/record_ab.sh B2   # second acquisition of B
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SERIAL="${SERIAL:-emulator-5554}"
FILES="/sdcard/Android/data/numerictext.example/files"
TAG="${1:?usage: record_ab.sh <A|B|A2|B2>}"
OUT="$ROOT/artifacts/ab_${TAG}"
APK="$ROOT/example/android/app/build/outputs/apk/debug/app-debug.apk"

echo "=== Recording $TAG ==="

# Clear and set up recorder
adb -s "$SERIAL" shell "rm -rf $FILES/numerictext-record && mkdir -p $FILES && touch $FILES/numerictext-record.on" 2>/dev/null
adb -s "$SERIAL" logcat -c 2>/dev/null

# Install
adb -s "$SERIAL" install -r "$APK" 2>&1 | tail -1

# Launch and wait for Showcase (must be already connected to Metro)
adb -s "$SERIAL" shell am force-stop numerictext.example 2>/dev/null
sleep 1
adb -s "$SERIAL" shell am start -n numerictext.example/.MainActivity 2>/dev/null

# After manual connection to Metro, press Enter to start recording sequence
echo "Connect to Metro (http://10.0.2.2:8081) in the dev client."
echo "Once the Showcase screen (1,000) is showing, press Enter to start."
read -r

# Sequence: alt60 (Fast alternation), single (-), roll (Continuous roll)
# These coordinates work on 1080×2400 emulator with Showcase screen.
ALT60_X=697 ALT60_Y=515
SINGLE_X=406 SINGLE_Y=438
ROLL_X=313  ROLL_Y=682

echo "Recording alt60..."
adb -s "$SERIAL" shell input tap $ALT60_X $ALT60_Y
sleep 5

echo "Recording single..."
adb -s "$SERIAL" shell input tap $SINGLE_X $SINGLE_Y
sleep 4

echo "Recording roll..."
adb -s "$SERIAL" shell input tap $ROLL_X $ROLL_Y
sleep 6

# Pull
mkdir -p "$OUT"
adb -s "$SERIAL" pull "$FILES/numerictext-record/" "$OUT/" 2>&1 | tail -2
adb -s "$SERIAL" logcat -d 2>/dev/null | grep "numerictext-entry" > "$OUT/logcat.txt"

echo "Recordings in $OUT"
ls "$OUT"/*.json | wc -l
echo "Log lines: $(wc -l < "$OUT/logcat.txt")"
