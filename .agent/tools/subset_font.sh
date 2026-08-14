#!/usr/bin/env bash
# Regenerates the bundled Sunghyun Sans subset in android/src/main/assets/fonts/.
#
# The upstream release is ~710 KB per weight, 6.1 MB for the nine — far too much to ship in a
# library. This view only ever draws a formatted number, so the subset keeps digits, the separators
# and signs that NumberFormat emits for Latin-script locales, the currency symbols and Latin
# letters a money format needs, and the "NaN"/"∞" it falls back to. That lands at ~33 KB per
# weight, ~300 KB for all nine.
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

# Money. Currency symbols, the whole currency-signs block (€ ₹ ₩ ₪ ₫ ₺ ₽ ₿ and the rest), the
# fullwidth and Arabic forms, and the brackets an accounting format wraps a negative amount in.
UNICODES="$UNICODES,U+0024,U+00A2-00A5,U+0192,U+058F,U+060B,U+07FE-07FF,U+09F2-09F3,U+09FB"
UNICODES="$UNICODES,U+0AF1,U+0BF9,U+0E3F,U+17DB,U+20A0-20C0,U+A838,U+FDFC,U+FE69,U+FF04"
UNICODES="$UNICODES,U+FFE0-FFE1,U+FFE5-FFE6"
UNICODES="$UNICODES,U+0028,U+0029"

# Letters, for `currencyDisplay: 'code'` (`USD 1,234.56`) and `'name'` (`1,234.56 US dollars`).
# They are the reason this subset is ~33 KB a weight rather than ~11 KB; without them the coverage
# check in NumericTextView falls the whole line back to the platform font, so a caller asking for
# a code or a name would silently lose the rounded face the library exists to provide.
UNICODES="$UNICODES,U+0041-005A,U+0061-007A"

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
