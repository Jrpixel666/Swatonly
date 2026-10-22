# Swatonly: a Manjaro Sway home screen for Android

Swatonly is an Android home screen launcher with the look of
[Manjaro Sway](https://github.com/manjaro-sway/manjaro-sway). It can also run
the real Manjaro Sway desktop under **proot**, with **no root**. Every Linux
window opens as **its own Android task**.

It is built from manjaro-sway release
[`202610180719`](https://github.com/manjaro-sway/manjaro-sway/releases/tag/202610180719).
The build bundles that release's themes, wallpapers, the `manjaro-sway-settings`
payload and the tweakable `/etc/sway` definitions into the APK.

## What you get

| | |
|---|---|
| **Bottom bar** (waybar style) | The green Arch logo on the left opens **My apps**. Pinned apps and open Linux windows sit in the middle, then the battery and clock modules. On the right, **+** adds apps to the bar and the **settings** icon opens every setting. |
| **My apps** | A menu styled like rofi (`drun` + `run`, like manjaro-sway's `$menu`). It lists Android apps and the Manjaro install's desktop entries, grouped by freedesktop category. CLI/TUI tools (btop, pulsemixer, bluetuith, pacseek…) get their own menu. Anything else you type runs as a command. |
| **Home screen** | The manjaro-sway background (`generate-bg.sh`'s template in the theme's colours) or the gruvbox wallpapers, plus a conky-like info widget. Swipe up for apps, swipe down for notifications, double-tap to switch day/night, long-press for the menu. |
| **Setup choice** | Every time the launcher starts it asks: **Launcher only** or **Launcher + Sway** (install on first use, start afterwards). You can turn the question off. |
| **Themes** | All manjaro-sway themes (matcha, catppuccin, dracula, nordic, atari…), read from `/usr/share/sway/themes` in the install when it exists. A day theme and a night theme work like `theme-toggle.sh`. The active theme can be copied to sway and foot. |
| **Tweaks** | Discovered from the install's `/etc/sway/autostart` and `/etc/sway/definitions`: every session daemon, every theme-application step and every sway variable. Window-manager options (gaps, borders, focus, layout, keyboard layout) are here too. Changes are written to `~/.config/sway/definitions.d` and `config.d`, then sway reloads. |
| **Linux windows** | A built-in Wayland compositor gives each `xdg_toplevel` its own Android task, so windows show up in Recents, split screen and freeform mode. Touch becomes mouse input (tap = click, long press = right click, two fingers = scroll). It has an extra-keys row (Esc, Tab, Ctrl, Alt, Super, arrows), supports the soft keyboard, and shares the clipboard with Android. |
| **Terminal** | A built-in terminal (Termux's terminal-view) on the rootfs, using the theme's foot colours. |

## How the Linux side works

* The rootfs is Manjaro ARM (`manjaro-arm/rootfs`, aarch64). Manjaro does not
  publish x86_64 or armv7 rootfs tarballs, so those devices get Arch Linux from
  proot-distro. A custom URL can be set in *Settings > Linux*.
* **Firmware and kernel modules are disabled under proot.** They are skipped
  while unpacking, deleted by `setup.sh`, and `pacman.conf` gets
  `NoExtract = usr/lib/firmware/* usr/lib/modules/* …`, so pacman never
  unpacks them. `CheckSpace`, `DownloadUser` and the pacman 7 sandbox are
  turned off because none of them work without real root.
* proot is Termux's build, shipped as `libproot.so` in the APK's native
  libraries. The app keeps `targetSdk 28`, like Termux, so that guest binaries
  in the app's data directory can run.
* `setup.sh` installs `manjaro-sway-settings`' dependencies (whichever exist
  for the CPU) and copies the bundled payload into `/`. It also installs a
  `systemctl` shim, because manjaro-sway starts waybar, mako, foot-server and
  the rest with `systemctl --user enable --now`, and systemd cannot run under
  proot.
* Sway runs nested (`WLR_BACKENDS=wayland`, pixman renderer) inside the
  launcher's compositor, as one more task. The compositor implements
  `wl_compositor`, `wl_subcompositor`, `wl_shm`, `wl_output`, `wl_seat`,
  `xdg_wm_base`, `zxdg_decoration_manager_v1` (always server side) and
  `wl_data_device_manager`.

## Building

GitHub Actions builds the APK on every push (`.github/workflows/build-apk.yml`).
You can download it from the run's artifacts. To publish a release, run the
workflow manually with *release* checked, or push a `v*` tag.

To build locally (you need the Android SDK and `patchelf`):

```sh
scripts/fetch-manjaro-sway.sh 202610180719   # themes, payload, wallpapers -> assets
scripts/fetch-proot.sh                       # Termux proot + talloc -> jniLibs
./gradlew assembleRelease
```

`scripts/gen-keymap.py` regenerates the US xkb keymap the compositor sends to
clients. When `xkbcli` exists in the rootfs, `setup.sh` compiles a keymap
there instead.

The APK is signed with `app/swatonly.jks` (password `swatonly`). The key is
committed so that CI builds can update each other. Use your own key for
anything you distribute.

## Known limits

* There is no GPU inside proot. Everything renders in software (`LIBGL_ALWAYS_SOFTWARE`,
  `GSK_RENDERER=cairo`, pixman), which is fine for terminals, GTK/Qt apps and
  sway, but slow for heavy 3D.
* The compositor supports shared-memory buffers only (no dmabuf), and has no
  Xwayland, drag and drop, or text-input protocol. Soft-keyboard text is sent
  as US-layout key presses.
* Some packages from the Manjaro Sway edition do not exist for aarch64.
  `setup.sh` skips them and lists them.

Manjaro Sway is MIT licensed; see the About page in the app.
