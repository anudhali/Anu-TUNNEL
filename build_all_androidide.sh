#!/usr/bin/env bash
# One-shot AndroidIDE bootstrap + native engine + APK build for Anu TUNNEL.
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
fail(){ echo "BUILD FAILED: $*" >&2; exit 1; }
need(){ command -v "$1" >/dev/null 2>&1 || fail "Required command missing: $1"; }
source "$ROOT/tools/androidide_env.sh"
[[ -x "${JAVA_HOME:-}/bin/java" ]] || fail "JDK 17 not found. Select/download JDK 17 in AndroidIDE."
[[ -d "${ANDROID_HOME:-}" ]] || fail "Android SDK not found."
[[ -x "${ANDROID_NDK_HOME:-}/ndk-build" ]] || fail "NDK r27d not found/configured."
need tar; need unzip; need git
# AndroidIDE commonly ships wget but not curl. Prefer curl when available,
# otherwise use wget with equivalent failure/retry behavior.
download() {
  local url="$1" out="$2"
  if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 --connect-timeout 20 "$url" -o "$out"
  elif command -v wget >/dev/null 2>&1; then
    wget -O "$out" --tries=4 --timeout=30 "$url"
  else
    fail "Neither curl nor wget is installed. AndroidIDE must provide at least one downloader."
  fi
}
case "$(uname -m)" in aarch64|arm64) ;; *) fail "Expected ARM64 AndroidIDE; detected $(uname -m)." ;; esac
TOOLS="$HOME/.local/anu-tunnel-tools"
mkdir -p "$TOOLS"
# Install Go 1.24.4 ARM64 locally; never overwrite AndroidIDE-managed files.
if [[ ! -x "$TOOLS/go/bin/go" ]]; then
  echo "[1/4] Downloading Go 1.24.4 for Linux ARM64..."
  download https://go.dev/dl/go1.24.4.linux-arm64.tar.gz "$TOOLS/go.tar.gz"
  rm -rf "$TOOLS/go" "$TOOLS/go-extract"
  mkdir -p "$TOOLS/go-extract"
  tar -xzf "$TOOLS/go.tar.gz" -C "$TOOLS/go-extract"
  [[ -x "$TOOLS/go-extract/go/bin/go" ]] || fail "Go archive layout unexpected."
  mv "$TOOLS/go-extract/go" "$TOOLS/go"
  rm -rf "$TOOLS/go-extract"
fi
export GOROOT="$TOOLS/go" GOPATH="$TOOLS/gopath" GOTOOLCHAIN=auto
export PATH="$GOROOT/bin:$GOPATH/bin:$TOOLS/gradle-8.10.2/bin:$PATH"
go version
if [[ ! -x "$TOOLS/gradle-8.10.2/bin/gradle" ]]; then
  echo "[2/4] Downloading Gradle 8.10.2..."
  download https://services.gradle.org/distributions/gradle-8.10.2-bin.zip "$TOOLS/gradle.zip"
  rm -rf "$TOOLS/gradle-8.10.2"
  unzip -q "$TOOLS/gradle.zip" -d "$TOOLS"
fi
export PATH="$TOOLS/gradle-8.10.2/bin:$PATH"
gradle --version
echo "[3/4] Building DNSTT AAR and Hev arm64 JNI..."
bash "$ROOT/tools/build_native.sh"
echo "[4/4] Building APK..."
gradle --no-daemon --stacktrace :app:assembleDebug
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[[ -s "$APK" ]] || fail "APK missing after Gradle task: $APK"
echo "BUILD SUCCESS"
echo "APK: $APK"
ls -lh "$APK"
