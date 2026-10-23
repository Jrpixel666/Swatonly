#!/bin/bash
# Swatonly: adds Omarchy (basecamp/omarchy) on top of the Manjaro Sway rootfs,
# under proot. Omarchy's own installer expects a bare-metal Arch with systemd,
# a bootloader and real hardware, so this does the parts that make sense in a
# container: its commands, configs, themes and the packages the repos carry.
#
# Skipped on purpose (useless or impossible under proot): bootloader (limine),
# plymouth, sddm, snapper, kernel and firmware packages, hardware fixes,
# firewall, docker, power management and systemd services.
set -uo pipefail

SW=/opt/swatonly
OMARCHY_PATH=/usr/share/omarchy
REF=$(cat "$SW/omarchy/REF" 2>/dev/null || echo HEAD)
export LANG=C.UTF-8 LC_ALL=C.UTF-8

c_green=$'\e[1;32m'; c_yellow=$'\e[1;33m'; c_red=$'\e[1;31m'; c_off=$'\e[0m'
step() { printf '\n%s==>%s %s\n' "$c_green" "$c_off" "$*"; }
warn() { printf '%s  ->%s %s\n' "$c_yellow" "$c_off" "$*"; }
die()  { printf '\n%s==> ERROR:%s %s\n' "$c_red" "$c_off" "$*"; exit 1; }
retry() { local n=0; until "$@"; do n=$((n+1)); [ $n -ge 3 ] && return 1; warn "retrying ($n) $*"; sleep 3; done; }

step "Fetching Omarchy ($REF)"
pacman -S --needed --noconfirm git >/dev/null || die "could not install git"
if [ -d "$OMARCHY_PATH/.git" ]; then
    git -C "$OMARCHY_PATH" fetch --depth 1 origin "$REF" && git -C "$OMARCHY_PATH" checkout -q -f FETCH_HEAD \
        || warn "could not update the Omarchy checkout, keeping the current one"
else
    rm -rf "$OMARCHY_PATH"
    mkdir -p "$OMARCHY_PATH"
    git -C "$OMARCHY_PATH" init -q
    git -C "$OMARCHY_PATH" remote add origin https://github.com/basecamp/omarchy
    retry git -C "$OMARCHY_PATH" fetch --depth 1 origin "$REF" || retry git -C "$OMARCHY_PATH" fetch --depth 1 origin HEAD \
        || die "could not download Omarchy"
    git -C "$OMARCHY_PATH" checkout -q -f FETCH_HEAD
fi
chmod 755 "$OMARCHY_PATH"/bin/* 2>/dev/null

step "Resolving Omarchy packages available for $(uname -m)"
# never under proot: boot, kernel, firmware, display manager, hardware daemons
SKIP='^(limine.*|plymouth.*|sddm.*|snapper.*|linux.*|.*-firmware|kernel-modules-hook|ufw.*|docker.*|power-profiles-daemon|tzupdate|bolt|brightnessctl|ddcutil|asdcontrol|cups.*|system-config-printer|bluez.*|networkmanager|iwd|wireless-regdb|zram-generator|rtkit|qemu-user-static-binfmt|gnome-keyring|avahi|nss-mdns|udiskie|gvfs-.*|plocate|superwhisper-bin)$'
lists=("$SW/omarchy/packages/omarchy-base.packages")
case "$(uname -m)" in
    aarch64) lists+=("$SW/omarchy/packages/omarchy-aarch64.packages") ;;
    x86_64) lists+=("$SW/omarchy/packages/omarchy-x86_64-only.packages") ;;
esac
want=()
for f in "${lists[@]}"; do
    [ -f "$f" ] || continue
    while read -r p; do
        p=${p%%#*}; p=${p//[[:space:]]/}
        [ -n "$p" ] || continue
        [[ $p =~ $SKIP ]] && continue
        want+=("$p")
    done < "$f"
done
avail=(); missing=()
for p in "${want[@]}"; do
    if pacman -Si "$p" >/dev/null 2>&1; then avail+=("$p"); else missing+=("$p"); fi
done
[ ${#missing[@]} -gt 0 ] && warn "not in the repositories for $(uname -m) (Omarchy's own repo or AUR): ${missing[*]}"

step "Installing ${#avail[@]} Omarchy packages"
if [ ${#avail[@]} -gt 0 ]; then
    retry pacman -S --needed --noconfirm "${avail[@]}" || warn "some Omarchy packages failed to install; continuing"
fi

step "Installing Omarchy commands and configuration"
cat > /etc/profile.d/omarchy.sh <<'PROFILE'
# Swatonly: Omarchy under proot
export OMARCHY_PATH=/usr/share/omarchy
case ":$PATH:" in *":$OMARCHY_PATH/bin:"*) ;; *) PATH="$OMARCHY_PATH/bin:$PATH" ;; esac
case ":$PATH:" in *":$HOME/.local/bin:"*) ;; *) PATH="$PATH:$HOME/.local/bin" ;; esac
export PATH
PROFILE
export PATH="$OMARCHY_PATH/bin:$PATH"
mkdir -p /root/.config /root/.local/state/omarchy /root/.local/share/applications /root/.config/omarchy/themes
# never overwrite what the user already has
cp -Rn "$OMARCHY_PATH/config/." /root/.config/ 2>/dev/null || true
[ -d "$OMARCHY_PATH/applications" ] && cp -Rn "$OMARCHY_PATH/applications/." /root/.local/share/applications/ 2>/dev/null
# the launcher reads the menu from here (falls back to its bundled copy)
install -Dm644 "$OMARCHY_PATH/default/omarchy/omarchy-menu.jsonc" /etc/swatonly/omarchy-menu.jsonc

step "Applying the default Omarchy theme"
if [ ! -s /root/.local/state/omarchy/current/theme.name ]; then
    OMARCHY_THEME_HEADLESS=1 OMARCHY_SETUP_CONTEXT=chroot timeout 300 omarchy-theme-set "Tokyo Night" \
        || warn "omarchy-theme-set reported errors (some theme targets need a running desktop)"
fi

mkdir -p /etc/swatonly
git -C "$OMARCHY_PATH" rev-parse HEAD > /etc/swatonly/omarchy-ref 2>/dev/null
step "Omarchy is installed."
