#!/bin/sh
# Applies patches with `patch -p1` in the current directory, skipping any that
# are already applied (ExternalProject may re-run the patch step).
set -e
for p in "$@"; do
    if patch -p1 -R --dry-run -s -f -i "$p" >/dev/null 2>&1; then
        echo "Already applied: $p"
    else
        patch -p1 -N --batch -i "$p"
    fi
done
