#!/usr/bin/env bash
# Runs the UI smoke tests on the JVM with Robolectric (native graphics) and writes screenshots.
# Requires a prior tools/build.sh and the Robolectric jars in $ROBO_LIB (see README).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="${STAVE_CACHE:-$HOME/.cache/stave-build}"
ROBO_LIB="${ROBO_LIB:?set ROBO_LIB to a directory with robolectric 4.14.x jars and dependencies}"
OUT="$ROOT/build/robotest"
SHOTS="${SHOTS:-$ROOT/build/screenshots}"
rm -rf "$OUT" && mkdir -p "$OUT/classes" "$OUT/config/com/android/tools"
cat > "$OUT/config/com/android/tools/test_config.properties" <<P
android_merged_manifest=$ROOT/build/AndroidManifest.xml
android_merged_assets=$ROOT/app/src/main/assets
android_resource_apk=$ROOT/build/base.apk
android_custom_package=io.github.vlesk.stave
P
CP="$(ls "$ROBO_LIB"/*.jar | tr '\n' ':')"
javac -nowarn -encoding UTF-8 -d "$OUT/classes" -cp "$CP$ROOT/build/classes:$CACHE/android-all-14-robolectric-10818077.jar" \
  $(find "$ROOT/test/robolectric" -name '*.java')
java -Xmx3g -Drobolectric.dependency.repo.url=https://repo.maven.apache.org/maven2 -Drobolectric.logging=stdout \
  -Dshots="$SHOTS" ${IMPORT_FILE:+-DimportFile="$IMPORT_FILE"} \
  -cp "$OUT/config:$OUT/classes:$ROOT/build/classes:$CP$CACHE/android-all-14-robolectric-10818077.jar" org.junit.runner.JUnitCore io.github.vlesk.stave.UiSmokeTest
