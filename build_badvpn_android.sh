#!/usr/bin/env bash
set -Eeuo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-}}"
if [[ -z "$NDK" || ! -f "$NDK/build/cmake/android.toolchain.cmake" ]]; then
  echo "ERROR: Android NDK CMake toolchain not found. Set ANDROID_NDK_HOME." >&2
  exit 2
fi
command -v git >/dev/null 2>&1 || { echo "ERROR: git is required" >&2; exit 2; }
command -v cmake >/dev/null 2>&1 || { echo "ERROR: cmake is required" >&2; exit 2; }
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 is required" >&2; exit 2; }
WORK="${BADVPN_BUILD_DIR:-$ROOT/.native-build/badvpn}"
SRC="$WORK/src"
BUILD="$WORK/build-arm64"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
PIN="07268f02706e78e282e19641b5d1d41e8e89bf31"
mkdir -p "$WORK" "$OUT"
if [[ ! -d "$SRC/.git" ]]; then
  git init "$SRC"
  git -C "$SRC" remote add origin https://github.com/ambrop72/badvpn.git
fi
if ! git -C "$SRC" cat-file -e "$PIN^{commit}" 2>/dev/null; then
  git -C "$SRC" fetch --depth 1 origin "$PIN"
fi
git -C "$SRC" checkout --detach "$PIN"
python3 "$ROOT/tools/native/badvpn/prepare.py" "$SRC"
cmake -S "$SRC" -B "$BUILD" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-24 \
  -DANDROID_STL=c++_static \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
  -DBUILD_NOTHING_BY_DEFAULT=ON \
  -DBUILD_TUN2SOCKS=ON \
  -DBUILD_UDPGW=OFF \
  -DBUILD_EXAMPLES=OFF \
  -DBUILD_TESTS=OFF \
  -DBUILD_SERVER=OFF \
  -DBUILD_CLIENT=OFF \
  -DBUILD_FLOODER=OFF \
  -DBUILD_NCD=OFF \
  -DBUILD_DOSTEST=OFF
cmake --build "$BUILD" --target anutunnel-badvpn-jni --parallel "${CMAKE_BUILD_PARALLEL_LEVEL:-2}"
LIB="$BUILD/tun2socks/libanutunnel-badvpn.so"
test -s "$LIB" || { echo "ERROR: BadVPN JNI shared library not found at $LIB" >&2; find "$BUILD" -name '*anutunnel*badvpn*.so' -o -name '*badvpn*.so'; exit 3; }
install -m 0644 "$LIB" "$OUT/libanutunnel-badvpn.so"
echo "Built $(file "$OUT/libanutunnel-badvpn.so")"
sha256sum "$OUT/libanutunnel-badvpn.so"
