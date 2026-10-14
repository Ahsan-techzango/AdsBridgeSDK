#!/usr/bin/env sh
# Portable Gradle 9.4.1 launcher. Official wrapper JAR could not be bundled offline.
set -eu
BASE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if command -v gradle >/dev/null 2>&1; then
  if gradle --version 2>/dev/null | grep -q '^Gradle 9\.4\.1$'; then
    exec gradle -p "$BASE" "$@"
  fi
fi
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/portable/gradle-9.4.1"
if [ ! -x "$CACHE/bin/gradle" ]; then
  mkdir -p "$(dirname "$CACHE")"
  ARCHIVE="$(dirname "$CACHE")/gradle-9.4.1-bin.zip"
  URL="https://services.gradle.org/distributions/gradle-9.4.1-bin.zip"
  if command -v curl >/dev/null 2>&1; then curl --fail --location --retry 3 "$URL" -o "$ARCHIVE";
  elif command -v wget >/dev/null 2>&1; then wget "$URL" -O "$ARCHIVE";
  else echo 'Install curl or wget, or install Gradle 9.4.1.' >&2; exit 1; fi
  unzip -oq "$ARCHIVE" -d "$(dirname "$CACHE")"
fi
exec "$CACHE/bin/gradle" -p "$BASE" "$@"
