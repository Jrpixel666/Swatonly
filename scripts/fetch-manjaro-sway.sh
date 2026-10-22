#!/usr/bin/env bash
# Fetches the manjaro-sway release the launcher is built from and stages the
# parts the APK needs into app/src/main/assets/manjaro-sway:
#
#   payload.tar.gz   manjaro-sway-settings payload (installed into the proot rootfs)
#   themes/          sway themes (theme.conf, foot-theme.ini, packages)
#   templates/       background template used for the launcher wallpaper
#   wallpapers/      manjaro-sway-wallpapers (scalable svg)
#   sway/            /etc/sway definitions + autostart (tweak discovery)
#   Packages-Desktop the edition's package list (what "Launcher + Sway" installs)
#
# Usage: scripts/fetch-manjaro-sway.sh [tag]   (or MANJARO_SWAY_SRC=/path/to/checkout)
set -euo pipefail

TAG="${1:-202610180719}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/assets/manjaro-sway"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

if [ -n "${MANJARO_SWAY_SRC:-}" ]; then
    SRC="$MANJARO_SWAY_SRC"
else
    URL="https://github.com/manjaro-sway/manjaro-sway/archive/refs/tags/${TAG}.tar.gz"
    echo "==> downloading $URL"
    curl -fsSL --retry 4 -o "$WORK/src.tar.gz" "$URL"
    mkdir -p "$WORK/src"
    tar -xzf "$WORK/src.tar.gz" -C "$WORK/src" --strip-components=1
    SRC="$WORK/src"
fi

SETTINGS="$SRC/packages/manjaro-sway-settings/payload"
[ -d "$SETTINGS" ] || { echo "manjaro-sway-settings payload not found in $SRC" >&2; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT/themes" "$OUT/templates" "$OUT/wallpapers" "$OUT/sway"

# payload: same layout and modes as the PKGBUILD's package()
STAGE="$WORK/payload"
mkdir -p "$STAGE"
cp -a "$SETTINGS/etc" "$SETTINGS/usr" "$STAGE/"
find "$STAGE/usr/bin" "$STAGE/usr/local/bin" "$STAGE/usr/share/sway/scripts" -type f -exec chmod 755 {} +
tar --owner=0 --group=0 --numeric-owner -czf "$OUT/payload.tar.gz" -C "$STAGE" .

cp -a "$SETTINGS/usr/share/sway/themes/." "$OUT/themes/"
cp "$SETTINGS/usr/share/sway/templates/manjarosway-scalable.svg" "$OUT/templates/"
cp "$SRC/packages/manjaro-sway-wallpapers/static/"*.svg "$OUT/wallpapers/" 2>/dev/null || true
cp "$SETTINGS/etc/sway/definitions" "$SETTINGS/etc/sway/autostart" "$OUT/sway/"
cp "$SRC/iso-profiles/community/sway/Packages-Desktop" "$OUT/Packages-Desktop"
cp "$SRC/LICENSE" "$OUT/LICENSE"
echo "$TAG" > "$OUT/TAG"

echo "==> staged manjaro-sway $TAG into $OUT"
ls "$OUT/themes"
