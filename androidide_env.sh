#!/usr/bin/env bash
# Resolve AndroidIDE's non-standard JDK/SDK/NDK locations for terminal builds.
# Usage: source tools/androidide_env.sh
set -e

pick_dir() {
  local candidate
  for candidate in "$@"; do
    if [[ -n "$candidate" && -d "$candidate" ]]; then printf '%s\n' "$candidate"; return 0; fi
  done
  return 1
}

# AndroidIDE may use a private JDK while its terminal PATH still points at Java 8.
if [[ -z "${JAVA_HOME:-}" || ! -x "${JAVA_HOME:-}/bin/java" || "$("${JAVA_HOME:-/nonexistent}/bin/java" -version 2>&1 | head -n1)" == *'1.8.'* ]]; then
  JAVA_HOME="$(pick_dir \
    /opt/java/jdk-17.0.21+9 \
    /opt/java/jdk-17 \
    /opt/java/openjdk-17 \
    "$HOME/.androidide/jdk/17" 2>/dev/null || true)"
fi
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:$PATH"
fi

if [[ -z "${ANDROID_HOME:-}" || ! -d "$ANDROID_HOME" ]]; then
  ANDROID_HOME="$(pick_dir /opt/android_sdk "$HOME/Android/Sdk" "$HOME/.androidide/sdk" 2>/dev/null || true)"
fi
if [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME" ]]; then
  export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
  export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/build-tools/35.0.0:$PATH"
fi

if [[ -z "${ANDROID_NDK_HOME:-}" || ! -x "${ANDROID_NDK_HOME:-}/ndk-build" ]]; then
  for base in "${ANDROID_HOME:-}" /opt/android-ndk /opt/ndk "$HOME/.androidide" "$HOME/Android"; do
    [[ -n "$base" && -d "$base" ]] || continue
    found="$(find "$base" -type f -name ndk-build -print -quit 2>/dev/null || true)"
    if [[ -n "$found" ]]; then ANDROID_NDK_HOME="$(dirname "$found")"; break; fi
  done
fi
if [[ -n "${ANDROID_NDK_HOME:-}" && -x "$ANDROID_NDK_HOME/ndk-build" ]]; then
  export ANDROID_NDK_HOME ANDROID_NDK_ROOT="$ANDROID_NDK_HOME"
  export PATH="$ANDROID_NDK_HOME:$PATH"
fi

export GRADLE_OPTS="${GRADLE_OPTS:-} -Dorg.gradle.jvmargs=-Xmx2g\ -Dfile.encoding=UTF-8"
