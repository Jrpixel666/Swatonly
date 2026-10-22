#!/bin/bash
# Swatonly: turns the extracted rootfs into Manjaro Sway, running under proot.
#
#   setup.sh sway   install sway + the manjaro-sway-settings payload
#   setup.sh base   only prepare pacman and the launcher helpers
#
# Firmware and kernel modules are useless under proot; pacman.conf carries a
# NoExtract rule (written by the launcher) so pacman never unpacks them.
set -uo pipefail

MODE="${1:-sway}"
SW=/opt/swatonly
export LANG=C.UTF-8 LC_ALL=C.UTF-8

c_green=$'\e[1;32m'; c_yellow=$'\e[1;33m'; c_red=$'\e[1;31m'; c_off=$'\e[0m'
step() { printf '\n%s==>%s %s\n' "$c_green" "$c_off" "$*"; }
warn() { printf '%s  ->%s %s\n' "$c_yellow" "$c_off" "$*"; }
die()  { printf '\n%s==> ERROR:%s %s\n' "$c_red" "$c_off" "$*"; exit 1; }

retry() { local n=0; until "$@"; do n=$((n+1)); [ $n -ge 3 ] && return 1; warn "retrying ($n) $*"; sleep 3; done; }

step "Removing firmware and kernel modules (not usable under proot)"
find /usr/lib/firmware /usr/lib/modules -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null || true

step "Configuring pacman mirrors"
if ! grep -qE '^\s*Server\s*=' /etc/pacman.d/mirrorlist 2>/dev/null; then
    if [ -f /etc/manjaro-release ] || command -v pacman-mirrors >/dev/null; then
        cat > /etc/pacman.d/mirrorlist <<'MIRRORS'
## swatonly fallback mirrors (run `pacman-mirrors --fasttrack` to rank them)
Server = https://mirror.alpix.eu/manjaro/arm-stable/$repo/$arch
Server = https://ftp.halifax.rwth-aachen.de/manjaro/arm-stable/$repo/$arch
Server = https://mirrors.dotsrc.org/manjaro/arm-stable/$repo/$arch
MIRRORS
    fi
fi
if command -v pacman-mirrors >/dev/null; then
    timeout 120 pacman-mirrors --fasttrack 5 >/dev/null 2>&1 && echo "ranked mirrors" || warn "pacman-mirrors failed, keeping the current mirrorlist"
fi

step "Initialising the pacman keyring (this takes a while under proot)"
find /etc/pacman.d/gnupg -mindepth 1 -delete 2>/dev/null || true
pacman-key --init || die "pacman-key --init failed"
keyrings=$(find /usr/share/pacman/keyrings -name '*.gpg' -printf '%f\n' 2>/dev/null | sed 's/\.gpg$//' | tr '\n' ' ')
# shellcheck disable=SC2086
pacman-key --populate $keyrings || warn "keyring populate reported errors"

step "Upgrading the base system"
retry pacman -Syu --noconfirm || die "system upgrade failed (check your connection and run setup again)"

if [ "$MODE" = "base" ]; then
    PKGS=(bash-completion sudo which nano dbus xdg-utils libxkbcommon-tools)
else
    PKGS=(
        # manjaro-sway-settings dependencies (packages/manjaro-sway-settings/PKGBUILD)
        sway waybar mako swaybg swayidle foot foot-terminfo grim slurp wob jq calcurse
        lm_sensors wf-recorder wl-clipboard btop swappy inotify-tools bc xdg-terminal-exec
        dex rofimoji noto-fonts-emoji ttf-jetbrains-mono-nerd ttf-roboto phinger-cursors
        papirus-maia-icon-theme papirus-icon-theme matcha-gtk-theme kvantum qt5ct qt6ct
        gtk-nocsd nwg-wrapper swayr bluetuith grimshot
        # edition extras from iso-profiles/community/sway/Packages-Desktop that make sense in proot
        pcmanfm-qt file-roller p7zip pulsemixer playerctl imv zathura zathura-pdf-poppler
        celluloid helix cliphist wl-clip-persist autotiling flashfocus pacseek curl wget
        # needed to run nested inside the launcher's compositor
        dbus xdg-utils xdg-user-dirs libxkbcommon-tools mesa gsettings-desktop-schemas
        glib2 adwaita-icon-theme sudo which nano bash-completion zsh
    )
    # rofi-wayland was merged into rofi; install whichever the repos carry
    if pacman -Si rofi-wayland >/dev/null 2>&1; then PKGS+=(rofi-wayland); else PKGS+=(rofi); fi
fi

step "Resolving packages available for $(uname -m)"
avail=()
for p in "${PKGS[@]}"; do
    if pacman -Si "$p" >/dev/null 2>&1 || pacman -Sg "$p" >/dev/null 2>&1; then
        avail+=("$p")
    else
        warn "$p is not in the repositories for $(uname -m), skipping"
    fi
done

step "Installing ${#avail[@]} packages"
retry pacman -S --needed --noconfirm "${avail[@]}" || die "package installation failed"

if [ "$MODE" != "base" ]; then
    step "Installing manjaro-sway-settings $(cat $SW/manjaro-sway/TAG 2>/dev/null) from the bundled source"
    cp -a "$SW/manjaro-sway/payload/etc/." /etc/
    cp -a "$SW/manjaro-sway/payload/usr/." /usr/
    find /usr/share/sway/scripts /usr/local/bin -type f -exec chmod 755 {} + 2>/dev/null
    for f in manjaro-sway-theme manjaro-sway-mirrors manjaro-sway-align waybar-signal clipboard \
             emoji-picker inhibit-idle termbin termpaste mjr; do
        [ -f "/usr/bin/$f" ] && chmod 755 "/usr/bin/$f"
    done

    step "Seeding the user configuration (skel)"
    cp -an /etc/skel/. /root/ 2>/dev/null || true
    mkdir -p /root/.config/sway/config.d /root/.config/sway/definitions.d
    cp -f "$SW/sway-proot.conf" /root/.config/sway/config.d/00-swatonly-proot.conf
fi

step "Installing proot helpers"
install -Dm755 "$SW/systemctl" /usr/local/bin/systemctl
install -Dm755 "$SW/swatonly-run" /usr/local/bin/swatonly-run
install -Dm644 "$SW/profile.sh" /etc/profile.d/swatonly.sh
if command -v xkbcli >/dev/null; then
    xkbcli compile-keymap --layout us > "$SW/keymap.xkb.new" 2>/dev/null && mv -f "$SW/keymap.xkb.new" "$SW/keymap.xkb"
fi
dbus-uuidgen --ensure 2>/dev/null || true
command -v xdg-user-dirs-update >/dev/null && HOME=/root xdg-user-dirs-update || true
if [ -x /usr/bin/zsh ] && [ "$MODE" != "base" ]; then chsh -s /usr/bin/zsh root >/dev/null 2>&1 || true; fi

mkdir -p /etc/swatonly
echo "$MODE" > /etc/swatonly/installed
date -u +%FT%TZ > /etc/swatonly/installed-at

step "Done. Manjaro ($MODE) is ready."
echo "You can close this terminal; the launcher picks up your Linux apps automatically."
