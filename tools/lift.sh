#!/usr/bin/env bash
# lift.sh - THPS 2X: default.xbe (or a disc image) -> generated C. The POSIX counterpart of tools/lift.ps1,
# used for the Android build (tools/android_build.sh).
#
#   tools/lift.sh <default.xbe | disc.iso> [toolkit dir] [output dir]
#
# Defaults: toolkit ../xboxrecomp, output ../private/thps2x (pipeline/ and gen/; never committed).
# Needs Python 3.10+ with capstone (PYTHONPATH may point at it).
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
IN="${1:?usage: tools/lift.sh <default.xbe | disc.iso> [toolkit] [out]}"
IN="$(cd "$(dirname "$IN")" && pwd)/$(basename "$IN")"
TOOLKIT="$(cd "${2:-$REPO/../xboxrecomp}" && pwd)"
OUT="${3:-$REPO/../private/thps2x}"
XBE_SHA1="469818e015aed2730641c0bdcbd2557d8a6b8bb4"   # THPS 2X (USA), docs/xbe.md
PY="${PYTHON:-python3}"
mkdir -p "$OUT"; OUT="$(cd "$OUT" && pwd)"
PIPE="$OUT/pipeline"; GEN="$OUT/gen"
DIS="$PIPE/disasm"; FID="$PIPE/func_id"; ABI="$PIPE/abi"; REC="$PIPE/recomp"
mkdir -p "$DIS" "$FID" "$ABI" "$REC" "$GEN"

case "$IN" in
  *.iso|*.ISO)
    echo "== extract disc -> $OUT/game"
    (cd "$TOOLKIT" && "$PY" -m tools.xiso unpack "$IN" -o "$OUT/game")
    XBE="$OUT/game/default.xbe" ;;
  *) XBE="$IN" ;;
esac
SHA="$(sha1sum "$XBE" | cut -d' ' -f1)"
[ "$SHA" = "$XBE_SHA1" ] || echo "WARNING: unknown default.xbe version (SHA1 $SHA), continuing"

SEEDS=()
for f in "$REPO"/seeds/*_seeds.json; do SEEDS+=(--seed-functions "$f"); done
run() { echo "== $1"; shift; (cd "$TOOLKIT" && "$PY" "$@"); }

run xbe_parser   -m tools.xbe_parser "$XBE" --json "$PIPE/xbe.json"
run disasm       -m tools.disasm "$XBE" -o "$DIS" --analysis-json "$PIPE/xbe.json" --force "${SEEDS[@]}"
run func_id      -m tools.func_id "$XBE" --functions "$DIS/functions.json" --strings "$DIS/strings.json" \
                 --xrefs "$DIS/xrefs.json" -o "$FID"
run abi_analysis -m tools.abi_analysis "$XBE" --disasm-dir "$DIS" --func-id-dir "$FID" --output-dir "$ABI"
rm -f "$GEN"/*
run recomp       -m tools.recomp "$XBE" --all --split 1000 --game-name THPS2X --gen-dir "$GEN" \
                 --exclude-manual "$REPO/src/recomp_manual.c" --weak-functions --hooks "$REPO/config/hooks.toml" \
                 --disasm-dir "$DIS" --func-id-dir "$FID" --abi-dir "$ABI" -o "$REC"
echo "Done: $(ls "$GEN"/*.c | wc -l) C files in $GEN"
