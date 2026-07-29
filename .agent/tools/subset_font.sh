#!/usr/bin/env bash
# Regenerates the bundled Sunghyun Sans subset in android/src/main/assets/fonts/.
#
# The upstream release is ~710 KB per weight, 6.1 MB for the nine — far too much to ship in a
# library. This view only ever draws a formatted number, so the subset keeps digits, the separators
# and signs that NumberFormat emits for Latin-script locales, and the "NaN"/"∞" it falls back to.
# That lands at ~15 KB per weight, ~130 KB for all nine.
#
# Locales whose digits are outside this set (ar-EG, hi-IN, …) are handled at runtime, not here:
# NumericTextView checks hasGlyph on the formatted string and falls back to the system typeface
# rather than drawing tofu. The full upstream font would need that guard too — its Latin release
# has no Arabic-Indic digits either — so subsetting costs nothing in coverage.
#
# Requires: python3, curl, unzip. fontTools is installed into a throwaway venv.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="$REPO_ROOT/android/src/main/assets/fonts"
UPSTREAM="https://github.com/anaclumos/sunghyun-sans"
RELEASE_ZIP="$UPSTREAM/raw/main/release/SunghyunSans-TTF.zip"
LICENSE_URL="https://raw.githubusercontent.com/anaclumos/sunghyun-sans/main/LICENSE"

# Space forms first (a French or Swiss grouping separator is one of these), then digits, signs,
# decimal and grouping marks, percent, and the glyphs NumberFormat uses for NaN and infinity.
UNICODES="U+0020,U+00A0,U+2007,U+2008,U+2009,U+202F"
UNICODES="$UNICODES,U+0030-0039"
UNICODES="$UNICODES,U+002B,U+002D,U+2212,U+2013"
UNICODES="$UNICODES,U+002C,U+002E,U+0027,U+2019,U+00B7,U+066B,U+066C,U+FF0C,U+FF0E"
UNICODES="$UNICODES,U+0025,U+2030,U+221E"
UNICODES="$UNICODES,U+0045,U+004E,U+0061"

WEIGHTS=(Thin ExtraLight Light Regular Medium SemiBold Bold ExtraBold Black)

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

echo "→ fetching $RELEASE_ZIP"
curl -sL -o "$WORK/shs.zip" "$RELEASE_ZIP"
unzip -oq "$WORK/shs.zip" -d "$WORK/shs"

echo "→ installing fontTools"
python3 -m venv "$WORK/venv"
"$WORK/venv/bin/pip" install -q fonttools

mkdir -p "$OUT_DIR"
for w in "${WEIGHTS[@]}"; do
  src="$WORK/shs/SunghyunSans-$w.ttf"
  dst="$OUT_DIR/SunghyunSans-$w.ttf"
  "$WORK/venv/bin/pyftsubset" "$src" \
    --unicodes="$UNICODES" \
    --layout-features="tnum,zero,frac" \
    --output-file="$dst"
  printf '  %-12s %6s KB\n' "$w" "$(( ($(wc -c < "$dst") + 1023) / 1024 ))"
done

curl -sL -o "$OUT_DIR/OFL.txt" "$LICENSE_URL"
echo "→ wrote $(ls "$OUT_DIR" | wc -l | tr -d ' ') files to ${OUT_DIR#"$REPO_ROOT"/}"
