#!/usr/bin/env bash
set -Eeuo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# Resolve AndroidIDE JDK/SDK/NDK paths even when terminal PATH defaults to Java 8.
source "$ROOT/tools/androidide_env.sh"
APP="$ROOT/app"
LIBS="$APP/src/main/jniLibs"
WORK="$ROOT/.native-build"
mkdir -p "$APP/libs" "$LIBS"

need() { command -v "$1" >/dev/null 2>&1 || { echo "ERROR: '$1' is required. Install it in AndroidIDE and retry." >&2; exit 2; }; }
need git
need go
need java
# gomobile's host toolchain currently cannot run on the AndroidIDE Linux/ARM64
# host used by some phones. Use the repository GitHub Actions workflow instead.
HOST_ARCH="$(uname -m)"
if [[ "$HOST_ARCH" == "aarch64" || "$HOST_ARCH" == "arm64" ]]; then
  echo "ERROR: Native gomobile build is unsupported on this ARM64 AndroidIDE host (gomobile reports unsupported GOARCH: arm64)."
  echo "Use GitHub Actions: .github/workflows/android-build.yml"
  exit 20
fi
# Upstream DNSTT currently declares Go 1.24.0. Let Go's toolchain manager
# download the required toolchain when the installed Go is older.
export GOTOOLCHAIN=auto
GO_VERSION="$(go version | awk '{print $3}')"
echo "Using $GO_VERSION (toolchain auto-upgrade enabled when module requires it)."

NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" && -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
  NDK="$(find "$ANDROID_HOME/ndk" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -n1)"
fi
[[ -n "$NDK" && -x "$NDK/ndk-build" ]] || { echo "ERROR: Android NDK ndk-build not found. Set ANDROID_NDK_HOME to the installed NDK folder." >&2; exit 2; }
export ANDROID_NDK_HOME="$NDK"
export ANDROID_NDK_ROOT="$NDK"

mkdir -p "$WORK"

# 1) Official DNSTT Go mobile client -> Android AAR.
if [[ ! -f "$APP/libs/dnstt.aar" ]]; then
  echo "[1/2] Fetching official DNSTT Go source..."
  if [[ ! -d "$WORK/dnstt_xyz_app/.git" ]]; then
    # AndroidIDE may bundle an older Git that does not understand partial
    # clone (--filter) or sparse-checkout options. Use the portable shallow clone.
    git clone --depth 1 https://github.com/dnstt-xyz/dnstt_xyz_app.git "$WORK/dnstt_xyz_app"
  fi
  ( cd "$WORK/dnstt_xyz_app/go_src"
    mkdir -p dnsttbridge
    cp "$ROOT/tools/native/dnsttbridge/bridge.go" dnsttbridge/bridge.go
    gofmt -w dnsttbridge/bridge.go
    # Pin both tools to the same x/mobile revision. Do not run `gomobile init`:
    # upstream init unconditionally installs gobind@latest, which bypasses this
    # pin and can trigger an unwanted Go toolchain upgrade on AndroidIDE.
    MOBILE_VERSION="v0.0.0-20260112195712-5b9ecdfb8721"
    export GOMAXPROCS="${GOMAXPROCS:-2}"
    export GOFLAGS="${GOFLAGS:--p=1}"
    export GOGC="${GOGC:-50}"
    export GOBIN="$(go env GOPATH)/bin"
    mkdir -p "$GOBIN"
    go install "golang.org/x/mobile/cmd/gomobile@$MOBILE_VERSION"
    go install "golang.org/x/mobile/cmd/gobind@$MOBILE_VERSION"
    export PATH="$GOBIN:$PATH"
    command -v gomobile >/dev/null 2>&1 || { echo "ERROR: pinned gomobile was not installed." >&2; exit 4; }
    command -v gobind >/dev/null 2>&1 || { echo "ERROR: pinned gobind was not installed." >&2; exit 4; }
    gomobile version
    gomobile bind -v -androidapi 24 -target=android/arm64 -o "$APP/libs/dnstt.aar" ./dnsttbridge
  )
else
  echo "[1/2] DNSTT AAR already present; keeping it."
fi

# 2) Official hev-socks5-tunnel JNI library for the TUN -> SOCKS5 packet path.
if [[ ! -f "$LIBS/arm64-v8a/libhev-socks5-tunnel.so" ]]; then
  echo "[2/2] Building hev-socks5-tunnel for arm64-v8a (16 KB page-size compatible)..."
  if [[ ! -d "$WORK/hev-socks5-tunnel/.git" ]]; then
    git clone --depth 1 --recursive https://github.com/heiher/hev-socks5-tunnel.git "$WORK/hev-socks5-tunnel"
  else
    git -C "$WORK/hev-socks5-tunnel" submodule update --init --recursive
  fi
  cp "$ROOT/Application.mk" "$WORK/hev-socks5-tunnel/Application.mk"
  "$NDK/ndk-build" \
    NDK_PROJECT_PATH="$WORK/hev-socks5-tunnel" \
    APP_BUILD_SCRIPT="$WORK/hev-socks5-tunnel/Android.mk" \
    APP_ABI=arm64-v8a \
    APP_PLATFORM=android-24 \
    NDK_LIBS_OUT="$LIBS" \
    NDK_OUT="$WORK/hev-obj"
else
  echo "[2/2] hev native library already present; keeping it."
fi

[[ -f "$APP/libs/dnstt.aar" ]] || { echo "ERROR: DNSTT AAR was not generated." >&2; exit 3; }
[[ -f "$LIBS/arm64-v8a/libhev-socks5-tunnel.so" ]] || { echo "ERROR: hev JNI library was not generated." >&2; exit 3; }
echo "[3/3] Building BadVPN tun2socks + UDPGW JNI engine..."
bash "$ROOT/tools/build_badvpn_android.sh"
[[ -f "$LIBS/arm64-v8a/libanutunnel-badvpn.so" ]] || { echo "ERROR: BadVPN JNI library was not generated." >&2; exit 3; }
echo "Native engines ready: DNSTT AAR + Hev compatibility library + BadVPN tun2socks/UDPGW JNI."
