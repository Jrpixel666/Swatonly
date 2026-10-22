#!/bin/bash
# Swatonly: starts the Manjaro Sway desktop as one window (Android task) of
# the launcher's Wayland compositor.
. /etc/profile
export WLR_BACKENDS=wayland
export WLR_RENDERER=pixman
export WLR_NO_HARDWARE_CURSORS=1
export WLR_LIBINPUT_NO_DEVICES=1
export XDG_CURRENT_DESKTOP=sway:wlroots
export XDG_SESSION_DESKTOP=sway
export WAYLAND_DISPLAY=wayland-0

mkdir -p "$HOME/.config/sway/config.d" "$HOME/.config/sway/definitions.d"
[ -f "$HOME/.config/sway/config" ] || cp /etc/skel/.config/sway/config "$HOME/.config/sway/config"
[ -f "$HOME/.config/sway/definitions.d/theme.conf" ] || cp /etc/skel/.config/sway/definitions.d/theme.conf "$HOME/.config/sway/definitions.d/theme.conf" 2>/dev/null
cp -f /opt/swatonly/sway-proot.conf "$HOME/.config/sway/config.d/00-swatonly-proot.conf"

# sway's own clients (waybar, foot, rofi...) connect to the display sway creates
unset DISPLAY
exec sway "$@"
