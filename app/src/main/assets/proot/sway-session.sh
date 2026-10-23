#!/bin/bash
# Swatonly: starts the Linux desktop as one window (Android task) of the
# launcher's Wayland compositor. sway by default; with SWATONLY_WM=hyprland
# (Omarchy) Hyprland is tried first and sway takes over if it cannot start
# (Hyprland wants a GPU render node, which proot rarely has).
. /etc/profile
export WLR_BACKENDS=wayland
export WLR_RENDERER=pixman
export WLR_NO_HARDWARE_CURSORS=1
export WLR_LIBINPUT_NO_DEVICES=1
export WAYLAND_DISPLAY=wayland-0
runtime="${XDG_RUNTIME_DIR:-/run/user/0}"

mkdir -p "$HOME/.config/sway/config.d" "$HOME/.config/sway/definitions.d"
[ -f "$HOME/.config/sway/config" ] || cp /etc/skel/.config/sway/config "$HOME/.config/sway/config"
[ -f "$HOME/.config/sway/definitions.d/theme.conf" ] || cp /etc/skel/.config/sway/definitions.d/theme.conf "$HOME/.config/sway/definitions.d/theme.conf" 2>/dev/null
cp -f /opt/swatonly/sway-proot.conf "$HOME/.config/sway/config.d/00-swatonly-proot.conf"

# sway's own clients (waybar, foot, rofi...) connect to the display sway creates
unset DISPLAY

if [ "${SWATONLY_WM:-sway}" = "hyprland" ] && command -v Hyprland >/dev/null; then
    echo hyprland > "$runtime/swatonly-wm"
    export XDG_CURRENT_DESKTOP=Hyprland XDG_SESSION_DESKTOP=Hyprland
    export AQ_NO_ATOMIC=1 AQ_NO_MODIFIERS=1 HYPRLAND_NO_SD_NOTIFY=1
    started=$(date +%s)
    Hyprland
    code=$?
    # a session that lasted is a real exit (logout), not a failure to start
    [ $(( $(date +%s) - started )) -gt 20 ] && exit $code
    echo "swatonly: Hyprland exited with $code right away; starting sway instead" >&2
fi

echo sway > "$runtime/swatonly-wm"
export XDG_CURRENT_DESKTOP=sway:wlroots XDG_SESSION_DESKTOP=sway
exec sway "$@"
