# Swatonly: a Manjaro Sway home screen for Android

Swatonly is an Android home screen launcher with the look of
[Manjaro Sway](https://github.com/manjaro-sway/manjaro-sway). It can also run
the real Manjaro Sway desktop under **proot**, with **no root**, and add
[Omarchy](https://github.com/basecamp/omarchy) on top. Linux apps open
**inside the tiling window manager** (sway, or Omarchy's Hyprland), which
tiles them like on the desktop, and you drive it with on-screen **tiling buttons**.

It is built from manjaro-sway release
[`202610180719`](https://github.com/manjaro-sway/manjaro-sway/releases/tag/202610180719).
The build bundles that release's themes, wallpapers, the `manjaro-sway-settings`
payload and the tweakable `/etc/sway` definitions and keybindings into the APK,
plus Omarchy's menu definition, themes and package lists.

## What you get

| | |
|---|---|
| **Bottom bar** (waybar style) | The green **Monero** logo on the left opens **My apps** (long press: the system menu). Pinned apps, workspace buttons (while the desktop runs) and open Linux windows sit in the middle, then the battery and clock modules. On the right, **+** adds apps to the bar and the **settings** icon opens every setting. |
| **System menu** | The install's own menu, drawn by the launcher: Omarchy's `omarchy-menu.jsonc` (Apps, Learn, Appearance, Trigger, Setup, Install, Remove, Update, About, System) when Omarchy is installed, a Manjaro Sway menu in the same format otherwise. `when`/`checked`/`disabled` conditions are evaluated in the rootfs; actions run inside the desktop session. Add your own entries in `~/.config/swatonly/menu.jsonc`. |
| **Tiling buttons** | A button row over the desktop window: workspaces, focus/move, split h/v, tabbed, stacking, floating, fullscreen, resize, scratchpad, close, the system menu and the app launcher. Pick and order them in *Settings › Tiling buttons*. They send sway commands through `/opt/swatonly/wm-msg`, which translates them for Hyprland. |
| **My apps** | A menu styled like rofi (`drun` + `run`, like manjaro-sway's `$menu`). It lists Android apps and the Manjaro install's desktop entries, grouped by freedesktop category. CLI/TUI tools (btop, pulsemixer, bluetuith, pacseek…) get their own menu. Anything else you type runs as a command. |
| **Home screen** | The manjaro-sway background (`generate-bg.sh`'s template in the theme's colours) or the gruvbox wallpapers, plus a conky-like info widget. Swipe up for apps, swipe down for notifications, double-tap to switch day/night, long-press for the menu. |
| **Setup choice** | Every time the launcher starts it asks: **Launcher only** or **Launcher + Omarchy** (Manjaro Sway + Omarchy; installs on first use, starts afterwards). You can turn the question off. Manjaro Sway without Omarchy stays available in *Settings › Linux*. |
| **Themes** | All manjaro-sway themes (matcha, catppuccin, dracula, nordic, atari…), read from `/usr/share/sway/themes` in the install when it exists, and every Omarchy theme (tokyo-night, catppuccin, everforest, gruvbox, kanagawa, nord, rose-pine…), whose `colors.toml` becomes a sway and foot theme. A day theme and a night theme work like `theme-toggle.sh`. With Omarchy installed, picking an Omarchy theme also runs `omarchy-theme-set`. |
| **Tweaks** | Discovered from the install's `/etc/sway/autostart` and `/etc/sway/definitions`: every session daemon, every theme-application step and every sway variable. Window-manager options (gaps, borders, focus, layout, keyboard layout) are here too. Changes are written to `~/.config/sway/definitions.d` and `config.d`, then sway reloads. |
| **Linux windows** | Apps started from the launcher open inside the sway session (one Android window), where the sway tiling window manager arranges them. Without sway (or with *Open Linux apps inside sway* off) each app fills the screen on its own. Touch becomes mouse input (tap = click, long press = right click, two fingers = scroll). It has an extra-keys row (Esc, Tab, Ctrl, Alt, Super, arrows), supports the soft keyboard, and shares the clipboard with Android. |
| **Terminal** | A built-in terminal (Termux's terminal-view) on the rootfs, using the theme's foot colours. |
| **Full control** | Settings also has the Omarchy page (window manager, themes, update, restore configs), **Keybindings** (every `bindsym`, described by manjaro-sway's help comments), **Config files** (sway, waybar, foot, mako, rofi, Hyprland, Omarchy, pacman, shell; any file by path), the system menu's source and your own entries. |

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
* **Omarchy** (`setup.sh omarchy` → `omarchy-setup.sh`) is cloned at the commit
  the APK was built with into `/usr/share/omarchy`, its commands go on `PATH`,
  its configs are copied into `~/.config` without overwriting yours, and the
  packages from its lists that the Manjaro repositories carry are installed.
  Bootloader, plymouth, sddm, snapper, kernel, firmware, hardware fixes and
  system services are skipped: they cannot work under proot.
* Sway runs nested (`WLR_BACKENDS=wayland`, pixman renderer) inside the
  launcher's compositor, as one more task. The compositor implements
  `wl_compositor`, `wl_subcompositor`, `wl_shm`, `wl_output`, `wl_seat`,
  `xdg_wm_base`, `zxdg_decoration_manager_v1` (always server side) and
  `wl_data_device_manager`.

## XMR Vault (Monero wallet)

[`monero-wallet/`](monero-wallet/README.md) is a separate app: a hardened Monero wallet for
Android with a Rust core. It builds on its own workflow (`build-wallet-apk.yml`).

## Building

GitHub Actions builds the APK on every push (`.github/workflows/build-apk.yml`).
You can download it from the run's artifacts. To publish a release, run the
workflow manually with *release* checked, or push a `v*` tag.

To build locally (you need the Android SDK and `patchelf`):

```sh
scripts/fetch-manjaro-sway.sh 202610180719   # themes, payload, wallpapers -> assets
scripts/fetch-omarchy.sh                      # Omarchy menu, themes, package lists -> assets
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
  `setup.sh` skips them and lists them. Many Omarchy packages come from
  Omarchy's own repository or the AUR; `omarchy-setup.sh` lists the ones it
  could not find (install them later with *Install › AUR* in the menu).
* Hyprland needs a GPU render node and dmabuf, which proot and the launcher's
  compositor do not provide, so the Hyprland session usually cannot start; the
  session then falls back to sway, with Omarchy's themes, menu and tools.
  Omarchy menu entries that need Hyprland or its shell (quickshell) may do nothing.

Manjaro Sway is MIT licensed; see the About page in the app.
