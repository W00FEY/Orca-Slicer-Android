#!/usr/bin/env bash
# Cross-compiles libslic3r's third-party dependencies for Android.
# Usage: scripts/build_deps.sh [abi]   (default abi: arm64-v8a)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ABI="${1:-arm64-v8a}"
API="${ANDROID_API:-29}"
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
# Prefer the pinned NDK (same as Gradle's ndkVersion) over whatever
# ANDROID_NDK_HOME points to, so dependencies and app use one toolchain.
NDK=""
if [ -n "$SDK" ] && [ -d "$SDK/ndk/$(cat "$ROOT/NDK_VERSION")" ]; then
    NDK="$SDK/ndk/$(cat "$ROOT/NDK_VERSION")"
fi
NDK="${NDK:-${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}}"
if [ ! -f "$NDK/build/cmake/android.toolchain.cmake" ]; then
    echo "Android NDK not found. Set ANDROID_NDK_HOME or ANDROID_HOME." >&2
    exit 1
fi

[ -d "$ROOT/third_party/OrcaSlicer/src" ] || "$ROOT/scripts/fetch_orca.sh"

BUILD="$ROOT/build/deps-$ABI"
cmake -S "$ROOT/native/deps" -B "$BUILD" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM="android-$API" \
    -DANDROID_STL=c++_shared \
    -DCMAKE_BUILD_TYPE=Release \
    -DORCA_SOURCE_DIR="$ROOT/third_party/OrcaSlicer"
cmake --build "$BUILD" -- -j1 "${@:2}"
echo "Dependencies installed to $BUILD/prefix"
