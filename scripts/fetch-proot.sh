#!/usr/bin/env bash
# Fetches Termux's proot build (plus libtalloc) for every ABI the APK ships and
# installs them as app/src/main/jniLibs/<abi>/lib*.so so Android extracts them
# to the executable native library directory:
#
#   libproot.so          proot
#   libproot-loader.so   proot's loader (PROOT_LOADER)
#   libproot-loader32.so 32-bit loader (PROOT_LOADER_32), where available
#   libtalloc.so         talloc, with proot's DT_NEEDED rewritten to this name
#   libandroid-shmem.so  SysV shared memory emulation proot links against
#
# Requires: curl, ar (binutils) or dpkg-deb, tar, xz, patchelf
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/app/src/main/jniLibs"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

MIRRORS=(
    "https://packages.termux.dev/apt/termux-main"
    "https://grimler.se/termux/termux-main"
    "https://packages-cf.termux.dev/apt/termux-main"
)
declare -A ABIS=( [aarch64]=arm64-v8a [arm]=armeabi-v7a [x86_64]=x86_64 )

fetch() { # url dest
    curl -fsSL --retry 4 --retry-delay 2 -o "$2" "$1"
}

extract_deb() { # deb dir
    mkdir -p "$2"
    (cd "$2" && ar x "$1")
    local data
    data=$(ls "$2" | grep '^data.tar' | head -1)
    tar -xf "$2/$data" -C "$2"
}

for arch in "${!ABIS[@]}"; do
    abi=${ABIS[$arch]}
    echo "==> $arch ($abi)"
    index="$WORK/Packages-$arch"
    base=""
    for m in "${MIRRORS[@]}"; do
        if fetch "$m/dists/stable/main/binary-$arch/Packages" "$index"; then base=$m; break; fi
    done
    [ -n "$base" ] || { echo "no termux mirror reachable" >&2; exit 1; }

    pkgfile() { # package -> Filename
        awk -v p="$1" '$1=="Package:"{cur=$2} $1=="Filename:" && cur==p {print $2; exit}' "$index"
    }

    dir="$WORK/$arch"
    for pkg in proot libtalloc libandroid-shmem; do
        f=$(pkgfile "$pkg")
        [ -n "$f" ] || { echo "$pkg not in index for $arch" >&2; exit 1; }
        echo "    $pkg: $f"
        fetch "$base/$f" "$WORK/$pkg-$arch.deb"
        extract_deb "$WORK/$pkg-$arch.deb" "$dir/$pkg"
    done

    prefix="data/data/com.termux/files/usr"
    mkdir -p "$OUT/$abi"
    cp -L "$dir/proot/$prefix/bin/proot" "$OUT/$abi/libproot.so"
    cp -L "$dir/proot/$prefix/libexec/proot/loader" "$OUT/$abi/libproot-loader.so"
    if [ -f "$dir/proot/$prefix/libexec/proot/loader32" ]; then
        cp -L "$dir/proot/$prefix/libexec/proot/loader32" "$OUT/$abi/libproot-loader32.so"
    fi
    talloc=$(ls "$dir/libtalloc/$prefix/lib/"libtalloc.so* | head -1)
    cp -L "$talloc" "$OUT/$abi/libtalloc.so"

    old_needed=$(patchelf --print-needed "$OUT/$abi/libproot.so" | grep '^libtalloc' || true)
    if [ -n "$old_needed" ] && [ "$old_needed" != "libtalloc.so" ]; then
        patchelf --replace-needed "$old_needed" libtalloc.so "$OUT/$abi/libproot.so"
    fi
    patchelf --set-soname libtalloc.so "$OUT/$abi/libtalloc.so"
    shmem=$(ls "$dir/libandroid-shmem/$prefix/lib/"libandroid-shmem.so* | head -1)
    cp -L "$shmem" "$OUT/$abi/libandroid-shmem.so"
    patchelf --remove-rpath "$OUT/$abi/libandroid-shmem.so" || true
    patchelf --remove-rpath "$OUT/$abi/libproot.so" || true
    patchelf --remove-rpath "$OUT/$abi/libtalloc.so" || true
    echo "    needed: $(patchelf --print-needed "$OUT/$abi/libproot.so" | tr '\n' ' ')"
done

ls -lR "$OUT"
