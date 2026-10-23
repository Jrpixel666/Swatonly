#!/usr/bin/env bash
# Fetches Omarchy (basecamp/omarchy) and stages the parts the launcher reads
# into app/src/main/assets/omarchy:
#
#   menu.jsonc     the Omarchy menu definition (the launcher's System menu)
#   themes/<name>/colors.toml   every Omarchy theme's palette
#   packages/      omarchy-*.packages (what "Launcher + Omarchy" installs)
#   REF            the commit setup clones inside proot, so both sides match
#   VERSION, LICENSE
#
# Usage: scripts/fetch-omarchy.sh [ref]   (default: the repo's default branch;
#        or OMARCHY_SRC=/path/to/checkout)
set -euo pipefail

REF="${1:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/omarchy"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [ -n "${OMARCHY_SRC:-}" ]; then
    SRC="$OMARCHY_SRC"
else
    echo "==> cloning basecamp/omarchy (${REF:-default branch})"
    if [ -z "$REF" ]; then
        git clone --quiet --depth 1 https://github.com/basecamp/omarchy "$WORK/src"
    else
        git clone --quiet --depth 1 --branch "$REF" https://github.com/basecamp/omarchy "$WORK/src" 2>/dev/null \
            || { git clone --quiet https://github.com/basecamp/omarchy "$WORK/src" && git -C "$WORK/src" checkout --quiet "$REF"; }
    fi
    SRC="$WORK/src"
fi

[ -f "$SRC/default/omarchy/omarchy-menu.jsonc" ] || { echo "omarchy-menu.jsonc not found in $SRC" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/themes" "$OUT/packages"
cp "$SRC/default/omarchy/omarchy-menu.jsonc" "$OUT/menu.jsonc"
for t in "$SRC"/themes/*/; do
    name=$(basename "$t")
    [ -f "$t/colors.toml" ] || continue
    mkdir -p "$OUT/themes/$name"
    cp "$t/colors.toml" "$OUT/themes/$name/"
done
cp "$SRC"/install/omarchy-*.packages "$OUT/packages/"
git -C "$SRC" rev-parse HEAD > "$OUT/REF" 2>/dev/null || echo "${REF:-HEAD}" > "$OUT/REF"
cat "$SRC/version" > "$OUT/VERSION" 2>/dev/null || echo unknown > "$OUT/VERSION"
cp "$SRC/LICENSE" "$OUT/LICENSE"

echo "==> staged omarchy $(cat "$OUT/VERSION") ($(cat "$OUT/REF")) into $OUT"
ls "$OUT/themes"
