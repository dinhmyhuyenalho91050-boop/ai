#!/bin/sh

set -e

DIR="$(dirname "$0")"
APP_BASE_NAME="$(basename "$0")"

# Resolve symlinks
while [ -h "$DIR/$APP_BASE_NAME" ]; do
  ls="$(ls -ld "$DIR/$APP_BASE_NAME")"
  link="${ls#*-> }"
  case $link in
    /*) DIR="$(dirname "$link")" ;;
    *) DIR="$DIR/$(dirname "$link")" ;;
  esac
  APP_BASE_NAME="$(basename "$link")"
done

GRADLE_WRAPPER=gradle/wrapper/gradle-wrapper.jar

if [ ! -f "$DIR/$GRADLE_WRAPPER" ]; then
  echo "Gradle wrapper JAR not found: $DIR/$GRADLE_WRAPPER" >&2
  exit 1
fi

exec "$JAVA_HOME/bin/java" -jar "$DIR/$GRADLE_WRAPPER" "$@"
