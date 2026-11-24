#!/usr/bin/env bash
set -u
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# shellcheck source=androidide_env.sh
source "$ROOT/tools/androidide_env.sh"
fail=0
check_cmd() { if command -v "$1" >/dev/null 2>&1; then printf 'PASS  %-18s %s\n' "$1" "$(command -v "$1")"; else printf 'MISS  %-18s not found\n' "$1"; fail=1; fi; }
check_cmd java
check_cmd javac
check_cmd go
check_cmd git
if command -v java >/dev/null 2>&1; then java -version 2>&1 | head -n 1; fi
if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]]; then printf 'PASS  %-18s %s\n' ANDROID_HOME "$ANDROID_HOME"; else printf 'MISS  %-18s SDK not found\n' ANDROID_HOME; fail=1; fi
if [[ -n "${ANDROID_NDK_HOME:-}" && -x "$ANDROID_NDK_HOME/ndk-build" ]]; then printf 'PASS  %-18s %s\n' ANDROID_NDK_HOME "$ANDROID_NDK_HOME"; else printf 'MISS  %-18s NDK r27d not found; finish AndroidIDE NDK install\n' ANDROID_NDK_HOME; fail=1; fi
if [[ -d "${ANDROID_HOME:-}/platforms/android-35" ]]; then echo 'PASS  Android SDK Platform 35'; else echo 'MISS  Android SDK Platform 35'; fail=1; fi
if command -v go >/dev/null 2>&1; then echo 'INFO  DNSTT upstream requires Go 1.24+; GOTOOLCHAIN=auto is enabled in build_native.sh.'; fi
if command -v gradle >/dev/null 2>&1; then gradle --version | head -n 8; else echo 'INFO  Gradle executable not on terminal PATH; use AndroidIDE Gradle task runner.'; fi
if [[ $fail -eq 0 ]]; then echo 'Environment preflight passed.'; else echo 'Environment incomplete. See MISS items above.'; fi
exit "$fail"
