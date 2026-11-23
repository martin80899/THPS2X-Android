#!/usr/bin/env bash
# android_build.sh - THPS 2X for Android (arm64): the player's default.xbe -> an installable APK.
#
#   tools/android_build.sh <default.xbe | disc.iso> <shell.apk> [out.apk]
#
# 1. lifts the XBE into C (tools/lift.sh, ../private/thps2x/gen)
# 2. builds the toolkit and the game for Android arm64 -> libmain.so
# 3. adds libmain.so to the shell APK (CI artifact thps2x-android-shell) and signs it (tools/android_pack.py)
#
# Needs: Python 3.10+ with capstone, CMake + Ninja, and either ANDROID_NDK (a full NDK r27 or later: its
# android.toolchain.cmake is used) or ANDROID_TOOLCHAIN_FILE (any CMake toolchain file for aarch64 Android),
# plus SDL3 and glslang built for Android under ANDROID_DEPS (CMAKE_PREFIX_PATH).
# The toolkit: ../xboxrecomp (almost-drowned-rat/xboxrecomp-vulkan) with patches/xboxrecomp-android.patch.
set -euo pipefail
REPO="$(cd "$(dirname "$0")/.." && pwd)"
IN="${1:?usage: tools/android_build.sh <default.xbe | disc.iso> <shell.apk> [out.apk]}"
SHELL_APK="${2:?shell APK (CI artifact thps2x-android-shell)}"
OUT="${3:-$REPO/../private/THPS2X-android.apk}"
TOOLKIT="${XBOXRECOMP_DIR:-$REPO/../xboxrecomp}"
PRIV="$REPO/../private"
BUILD="$PRIV/build/thps2x-android"
DEPS="${ANDROID_DEPS:?ANDROID_DEPS: install prefix of SDL3 + glslang built for Android arm64}"

for patch in xboxrecomp-android.patch xboxrecomp-android-saves.patch; do
    if ! git -C "$TOOLKIT" apply --reverse --check "$REPO/patches/$patch" 2>/dev/null; then
        git -C "$TOOLKIT" apply "$REPO/patches/$patch"
    fi
done

"$REPO/tools/lift.sh" "$IN" "$TOOLKIT" "$PRIV/thps2x"

if [ -n "${ANDROID_TOOLCHAIN_FILE:-}" ]; then
    TC=(-DCMAKE_TOOLCHAIN_FILE="$ANDROID_TOOLCHAIN_FILE")
else
    TC=(-DCMAKE_TOOLCHAIN_FILE="${ANDROID_NDK:?ANDROID_NDK or ANDROID_TOOLCHAIN_FILE}/build/cmake/android.toolchain.cmake"
        -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=31 -DANDROID_STL=c++_shared)
fi
cmake -S "$REPO" -B "$BUILD" -G Ninja "${TC[@]}" -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_PREFIX_PATH="$DEPS" -DXBOXRECOMP_DIR="$TOOLKIT" -DRECOMP_GEN_DIR="$PRIV/thps2x/gen" ${CMAKE_EXTRA:-}
cmake --build "$BUILD"
python3 "$REPO/tools/android_pack.py" "$SHELL_APK" "$BUILD/libmain.so" "$OUT" --key "$PRIV/android-key.json"
echo "APK: $OUT"
