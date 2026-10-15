#!/usr/bin/env bash
# Builds the engine for the Linux host and runs the JNI smoke test.
#   tests/host/run.sh [printer name]
# The first run builds the host dependencies (build/deps-host), which takes a while.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
[ -d "$ROOT/third_party/OrcaSlicer/src" ] || "$ROOT/scripts/fetch_orca.sh"

if [ ! -f "$ROOT/build/deps-host/prefix/lib/libtbb.a" ] || [ ! -d "$ROOT/build/deps-host/prefix/lib/cmake" ]; then
    cmake -S "$ROOT/native/deps" -B "$ROOT/build/deps-host" -G Ninja -DCMAKE_BUILD_TYPE=Release \
        -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ \
        -DORCA_SOURCE_DIR="$ROOT/third_party/OrcaSlicer"
    cmake --build "$ROOT/build/deps-host" -- -j1
fi

# Clang, like the NDK (the engine uses some Clang-only warning flags, and GCC-built
# LTO archives from the dependencies could not be linked by Clang).
cmake -S "$ROOT/native/engine" -B "$ROOT/build/engine-host" -G Ninja -DCMAKE_BUILD_TYPE=Release \
    -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ \
    -DORCA_SOURCE_DIR="$ROOT/third_party/OrcaSlicer"
cmake --build "$ROOT/build/engine-host" --target orca_jni

OUT="$ROOT/build/host-test"
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/work/data/system"
javac -d "$OUT/classes" $(find "$ROOT/tests/host/java" -name '*.java')

# Install a few vendors the way the app does (vendor JSON + folder + filament library).
PROFILES="$ROOT/third_party/OrcaSlicer/resources/profiles"
for v in OrcaFilamentLibrary Prusa Creality Flashforge; do
    cp "$PROFILES/$v.json" "$OUT/work/data/system/"
    cp -r "$PROFILES/$v" "$OUT/work/data/system/"
done

java -cp "$OUT/classes" io.github.w00fey.orcaslicer.engine.EngineSmokeTest \
    "$ROOT/build/engine-host" "$ROOT/third_party/OrcaSlicer/resources" "$OUT/work" "$@"
