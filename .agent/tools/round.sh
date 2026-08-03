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
#   "alterna x20 60ms"  centre (0.2865, 0.6265) -> 309, 1504
#   "alterna x20 120ms" centre (0.7030, 0.6265) -> 759, 1504
#   "alterna x20 240ms" centre (0.3120, 0.6815) -> 337, 1636
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
# All three cadences land in one directory: band.py groups its runs by the cadence it reads out of
# their marks, and the whole point of the sweep is that the reference is NOT monotonic in it —
# 0.760 at 60 ms, 1.460 at 120, 1.292 at 240. A knob fitted at one cadence alone can move the other
# two the wrong way without anyone noticing.
ALT60_X=309;  ALT60_Y=1504
ALT120_X=759; ALT120_Y=1504
ALT240_X=337; ALT240_Y=1636

echo "── build"
(cd example/android && ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}" \
  ./gradlew assembleDebug -PreactNativeArchitectures=arm64-v8a -q 2>&1 | grep -E "^e:" || true)

echo "── install"
# Reclaim BEFORE installing, not after a failure. Each round writes ~600 MB of recordings to the
# emulator and /data fills within a handful of them; `adb install` then fails with an EMPTY error
# message and the round drives the PREVIOUS build under the new name. That produced a burst
# regression that did not exist — two "different" builds, one binary.
adb -s "$SERIAL" shell rm -rf "$FILES/numerictext-record" 2>/dev/null || true
adb -s "$SERIAL" shell pm trim-caches 999G >/dev/null 2>&1 || true
echo "   /data: $(adb -s "$SERIAL" shell df /data | tail -1 | awk '{print $5" pieno, "int($4/1024)" MB liberi"}')"
if ! adb -s "$SERIAL" install -r -d "$APK" 2>&1 | grep -qE "Success"; then
  echo "   install failed, uninstalling and retrying"
  adb -s "$SERIAL" uninstall numerictext.example >/dev/null 2>&1 || true
  adb -s "$SERIAL" shell pm trim-caches 999G >/dev/null 2>&1 || true
  # This one is NOT allowed to fail quietly: everything after it would measure the previous build.
  adb -s "$SERIAL" install -r -d "$APK" 2>&1 | grep -qE "Success" || {
    echo "   INSTALL FALLITO — mi fermo, qualsiasi misura qui sarebbe della build precedente"
    exit 1
  }
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

# The dev client's menu opens by itself often enough to matter, and its scrim swallows every tap
# underneath it — which silently cost the 240 ms alternation on two consecutive rounds before it was
# noticed. Do NOT dismiss it with keyevent 4: with no menu up that backs out of the app, and the
# round then drives the launcher.
if adb -s "$SERIAL" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 &&
   adb -s "$SERIAL" shell cat /sdcard/ui.xml 2>/dev/null | grep -q "developer menu"; then
  echo "   dev menu aperto, lo chiudo"
  adb -s "$SERIAL" shell input tap 540 2167   # Continue
  sleep 3
  adb -s "$SERIAL" shell input tap 970 1104   # the X on the panel Continue opens
  sleep 3
fi

open_group() { # <dir> — start a group empty; drain() only ever appends to it
  rm -rf "artifacts/$1"
  mkdir -p "artifacts/$1"
}

# Drain after EVERY run, not once per group.
#
# A run is 90-240 MB of uncompressed alpha planes and a group is six of them — ~940 MB, against
# 611 MB free on a 6 G data partition. The fifth run then writes its .bin and never gets to write
# its .json, and since every glob here keys off the .json the file is silently skipped: `band.py`
# prints "(2 run)" for 60 and 120 ms, nothing at all for 240, and the round looks complete. That
# ate the 240 ms alternation on three consecutive rounds while the notes blamed the dev-client
# scrim. Draining per run caps what the device ever holds at ONE recording.
drain() { # <dir>
  rm -rf artifacts/_pull
  # `|| true`, and it matters: an empty record dir makes adb pull exit non-zero, and under
  # `set -e` that killed the whole round silently after the last drive — the analysis never ran and
  # the round looked like it had simply stopped.
  adb -s "$SERIAL" pull "$FILES/numerictext-record" artifacts/_pull >/dev/null 2>&1 || true
  mv artifacts/_pull/* "artifacts/$1/" 2>/dev/null || true
  rmdir artifacts/_pull 2>/dev/null || true
  adb -s "$SERIAL" shell rm -rf "$FILES/numerictext-record"
}

# A .bin with no .json is a run that ran out of disk half way. Say so — it is the one failure here
# that produces a NUMBER rather than an error, because the analysis just measures what is left.
report_group() { # <dir>
  local n orphans
  n=$(ls "artifacts/$1"/*.json 2>/dev/null | wc -l | tr -d ' ')
  orphans=0
  for b in "artifacts/$1"/*.bin; do
    [ -e "$b" ] || continue
    [ -e "${b%.bin}.json" ] || orphans=$((orphans + 1))
  done
  if [ "$orphans" -gt 0 ]; then
    echo "   $1: $n run  ⚠ $orphans registrazione/i TRONCATA/E (disco pieno) — non fidarsi di questo gruppo"
  else
    echo "   $1: $n run"
  fi
}

# Refuse to drive on a disk that cannot hold one recording. Silence here is what produces numbers
# from a build that was never installed, or from a run that was never written.
guard_disk() {
  local free
  free=$(adb -s "$SERIAL" shell df /data | tail -1 | awk '{print int($4/1024)}')
  if [ "${free:-0}" -lt 400 ]; then
    adb -s "$SERIAL" shell pm trim-caches 999G >/dev/null 2>&1 || true
    free=$(adb -s "$SERIAL" shell df /data | tail -1 | awk '{print int($4/1024)}')
    if [ "${free:-0}" -lt 400 ]; then
      echo "   /data ha $free MB liberi, una registrazione ne vuole fino a 240 — mi fermo"
      echo "   allarga la partizione: disk.dataPartition.size in ~/.android/avd/<AVD>.avd/config.ini"
      exit 1
    fi
  fi
}

# <dir> <x> <y> <sleep> <times>
drive() {
  local dir=$1 x=$2 y=$3 nap=$4 times=$5 i
  for i in $(seq 1 "$times"); do
    guard_disk
    wake
    adb -s "$SERIAL" shell input tap "$x" "$y"
    sleep "$nap"
    drain "$dir"
  done
}

# The app does not survive a whole round reliably — it was found sitting on the launcher after the
# alternation more than once, which cost the continuous roll entirely. Check before every drive
# rather than trusting the launch at the top.
wake() {
  if [ -z "$(adb -s "$SERIAL" shell pidof numerictext.example 2>/dev/null | tr -d '\r')" ]; then
    echo "   app assente, la rilancio"
    adb -s "$SERIAL" shell am start -a android.intent.action.VIEW \
      -d "exp+react-native-numeric-text-example://expo-development-client/?url=http%3A%2F%2Flocalhost%3A$PORT" >/dev/null 2>&1
    sleep 40
  fi
  if adb -s "$SERIAL" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 &&
     adb -s "$SERIAL" shell cat /sdcard/ui.xml 2>/dev/null | grep -q "developer menu"; then
    echo "   dev menu aperto, lo chiudo"
    adb -s "$SERIAL" shell input tap 540 2167
    sleep 3
    adb -s "$SERIAL" shell input tap 970 1104
    sleep 3
  fi
}

echo "── drive: single crossing"
open_group "$NAME"
drive "$NAME" $SINGLE_X $SINGLE_Y 7 3
report_group "$NAME"

echo "── drive: single crossing, the other way"
open_group "${NAME}_up"
drive "${NAME}_up" $UP_X $UP_Y 7 3
report_group "${NAME}_up"

# FIVE runs at 60 ms, two at the others. Not symmetry for its own sake: at 60 ms two runs of one
# build read 0.926 and 0.754 on the band, a spread of 0.172, while 120 and 240 ms repeat to 0.006.
# A round was chosen over its neighbour on 0.03 with two runs behind each — below the scatter, so
# the choice carried no information. Five runs bring the standard error of the median to about
# 0.02, which is the smallest difference worth acting on at that cadence.
echo "── drive: alternation, 60 / 120 / 240 ms"
open_group "${NAME}_alt"
drive "${NAME}_alt" $ALT60_X  $ALT60_Y   8 5
drive "${NAME}_alt" $ALT120_X $ALT120_Y  9 2
# 16 s, not 12: the 240 ms preset runs 1.2 s of settle plus 19 steps, and at 12 s one of the two
# taps went missing on every round — the recorder had not closed the previous file yet.
drive "${NAME}_alt" $ALT240_X $ALT240_Y 16 2
report_group "${NAME}_alt"

# The burst is the case a single crossing cannot stand in for, and any knob that reads how far apart
# two glyphs are is answering a different question here. "roll + x14 30ms", centre (0.714, 0.6815).
echo "── drive: continuous roll"
open_group "${NAME}_roll"
drive "${NAME}_roll" 771 1636 9 3
report_group "${NAME}_roll"

echo
echo "── single crossing (decrement)"
python3 .agent/tools/compare.py "artifacts/$NAME" 2>&1 | grep -v "RuntimeWarning\|c: {m"
echo
echo "── single crossing (increment)"
python3 .agent/tools/compare.py "artifacts/${NAME}_up" --up 2>&1 | grep -v "RuntimeWarning\|c: {m"
echo
echo "── alternation, middle band"
python3 .agent/tools/band.py "artifacts/${NAME}_alt"
echo
echo "── continuous roll"
python3 .agent/tools/burst.py "artifacts/${NAME}_roll"
echo
echo "── does the ink sit still, and is the pair even"
python3 .agent/tools/balance.py \
  "artifacts/$NAME" "artifacts/${NAME}_alt" "artifacts/${NAME}_roll"
