# Swatonly: environment for programs running under proot on Android.
export XDG_RUNTIME_DIR=/run/user/0
export XDG_SESSION_TYPE=wayland
export GDK_BACKEND=wayland
export QT_QPA_PLATFORM="wayland;xcb"
export SDL_VIDEODRIVER=wayland
export CLUTTER_BACKEND=wayland
export MOZ_ENABLE_WAYLAND=1
export ELECTRON_OZONE_PLATFORM_HINT=wayland
# no GPU is reachable from proot: render in software
export LIBGL_ALWAYS_SOFTWARE=1
export GSK_RENDERER=cairo
export WLR_RENDERER=pixman
export NO_AT_BRIDGE=1
[ -S "$XDG_RUNTIME_DIR/bus" ] && export DBUS_SESSION_BUS_ADDRESS="unix:path=$XDG_RUNTIME_DIR/bus"
[ -z "$WAYLAND_DISPLAY" ] && export WAYLAND_DISPLAY=wayland-0
