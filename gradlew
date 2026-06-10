#!/bin/sh
##############################################################################
# Gradle wrapper — généré pour EnerShip Travel
##############################################################################
APP_HOME="$(cd "$(dirname "$0")" && pwd)"
GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

JAVA_OPTS="${JAVA_OPTS:-}"
exec java $JAVA_OPTS \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain "$@"
