#!/usr/bin/env bash
# Rebuilds tree-stdio.jar (the device-side tree reader, TreeStdio.java) into the shared resources.
# Needs a JDK and the Android SDK (ANDROID_HOME, default ~/Android/Sdk) with the pinned build-tools and platform
# below: another d8 gives other bytes. Run it after a change to TreeStdio.java, then set U2Reader.STDIO_SHA256
# to the sha256 it prints.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
sdk=${ANDROID_HOME:-$HOME/Android/Sdk}
d8=$sdk/build-tools/37.0.0/d8
android_jar=$sdk/platforms/android-37.0/android.jar
out=$here/../main/resources/com/botmaker/shared/emulator/tree/tree-stdio.jar
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
export TZ=UTC

javac --release 8 -nowarn -d "$work/classes" "$here/java/com/botmaker/android/TreeStdio.java"
"$d8" --release --min-api 21 --lib "$android_jar" --output "$work" $(find "$work/classes" -name '*.class')
# A fixed timestamp, in UTC, keeps the jar the same bytes on every build.
touch -d '2026-01-01 00:00:00' "$work/classes.dex"
(cd "$work" && rm -f "$out" && zip -X -q "$out" classes.dex)
sha256sum "$out"
