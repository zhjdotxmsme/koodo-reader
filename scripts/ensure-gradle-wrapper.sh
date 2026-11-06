#!/usr/bin/env bash
# Pre-fetch Gradle 8.5 for android/gradlew when wrapper download times out or
# multiple gradlew processes fight over gradle-8.5-bin.zip.lck (WSL/Linux).
set -euo pipefail

GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
DIST_DIR="$GRADLE_USER_HOME/wrapper/dists/gradle-8.5-bin/5t9huq95ubn472n8rpzujfbqh"
ZIP="$DIST_DIR/gradle-8.5-bin.zip"
URL="https://services.gradle.org/distributions/gradle-8.5-bin.zip"

mkdir -p "$DIST_DIR"

if pgrep -f 'GradleWrapperMain' >/dev/null 2>&1; then
  echo "Stopping other gradlew processes (wrapper lock conflict)…" >&2
  pkill -f 'GradleWrapperMain' || true
  sleep 2
fi

rm -f "$DIST_DIR/gradle-8.5-bin.zip.lck"

MIN_BYTES=$((100 * 1024 * 1024)) # ~126 MiB distribution; reject truncated ~8 MiB failures

ok_zip() {
  [[ -f "$ZIP" ]] || return 1
  local size
  size=$(stat -c%s "$ZIP" 2>/dev/null || stat -f%z "$ZIP")
  [[ "$size" -ge "$MIN_BYTES" ]] || return 1
  unzip -t "$ZIP" >/dev/null 2>&1
}

if ok_zip; then
  echo "Gradle 8.5 already present: $ZIP ($(stat -c%s "$ZIP" 2>/dev/null || stat -f%z "$ZIP") bytes)"
  exit 0
fi

rm -f "$ZIP" "$DIST_DIR/gradle-8.5-bin.zip.part"
echo "Downloading Gradle 8.5 to $ZIP …" >&2
curl -L --fail --retry 8 --retry-delay 5 --connect-timeout 30 --max-time 3600 \
  -o "$ZIP.part" "$URL"
if [[ -f "$ZIP.part" ]]; then
  mv "$ZIP.part" "$ZIP"
elif ok_zip; then
  echo "Gradle zip already present (parallel download finished without .part)." >&2
else
  echo "Download finished but neither $ZIP.part nor a valid $ZIP exists." >&2
  exit 1
fi
if ! ok_zip; then
  echo "Download looks truncated or corrupt ($(stat -c%s "$ZIP" 2>/dev/null || stat -f%z "$ZIP") bytes). Remove and retry, or copy a full zip manually." >&2
  exit 1
fi
echo "OK: $ZIP"
