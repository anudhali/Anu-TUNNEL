#!/usr/bin/env bash
set -eu
APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
# Resolve AndroidIDE JDK/SDK/NDK paths before invoking Gradle.
if [ -f "$APP_HOME/tools/androidide_env.sh" ]; then
  . "$APP_HOME/tools/androidide_env.sh"
fi
WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ -f "$WRAPPER_JAR" ]; then
  if [ -n "${JAVA_HOME:-}" ]; then JAVACMD="$JAVA_HOME/bin/java"; else JAVACMD=java; fi
  exec "$JAVACMD" -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"
fi
if command -v gradle >/dev/null 2>&1; then
  exec gradle "$@"
fi
echo "Gradle executable/wrapper not found. Open the project in AndroidIDE and run the Gradle task there, or install Gradle 8.10.2+." >&2
exit 127
