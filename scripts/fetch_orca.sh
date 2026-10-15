#!/usr/bin/env bash
# Fetches the pinned OrcaSlicer source into third_party/OrcaSlicer and applies
# the Android patches from native/patches/orca.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ORCA_TAG="$(cat "$ROOT/ORCA_VERSION")"
DEST="$ROOT/third_party/OrcaSlicer"

if [ ! -d "$DEST/.git" ]; then
    git clone --depth 1 --branch "$ORCA_TAG" https://github.com/OrcaSlicer/OrcaSlicer.git "$DEST"
else
    current="$(git -C "$DEST" describe --tags --exact-match 2>/dev/null || true)"
    if [ "$current" != "$ORCA_TAG" ]; then
        git -C "$DEST" fetch --depth 1 origin "refs/tags/$ORCA_TAG:refs/tags/$ORCA_TAG"
        git -C "$DEST" checkout -f "$ORCA_TAG"
    fi
    # Drop previously applied patches so they can be re-applied cleanly.
    git -C "$DEST" checkout -f -- .
    git -C "$DEST" clean -fdq -- src
fi

for p in "$ROOT"/native/patches/orca/*.patch; do
    [ -e "$p" ] || continue
    echo "Applying $(basename "$p")"
    git -C "$DEST" apply --whitespace=nowarn "$p"
done
