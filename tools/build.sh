#!/usr/bin/env bash
# Builds the Stave APK without Gradle, using aapt2, javac, dx (d8 also works), zipalign and apksigner.
#
# Requirements (Debian/Ubuntu): openjdk-17+ aapt zipalign apksigner dalvik-exchange android-sdk-platform-23
#   sudo apt install aapt zipalign apksigner dalvik-exchange android-sdk-platform-23
# The Android 14/15 platform classes and resources are taken from Robolectric's "android-all"
# artifacts on Maven Central (downloaded once into $STAVE_CACHE).
#
# Signing: uses keystore/debug.jks by default; set STAVE_KEYSTORE, STAVE_KEYSTORE_PASS, STAVE_KEY_ALIAS
# for a release key.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app/src/main"
BUILD="$ROOT/build"
CACHE="${STAVE_CACHE:-$HOME/.cache/stave-build}"
VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0.0}"
MIN_SDK=24
TARGET_SDK=35
RES_JAR_VERSION=14-robolectric-10818077   # API 34 resources (aapt2 compatible)
CLS_JAR_VERSION=15-robolectric-13954326   # API 35 classes
MAVEN=https://repo.maven.apache.org/maven2/org/robolectric/android-all

DX="${DX:-/usr/lib/android-sdk/build-tools/debian/dx}"
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-}"

mkdir -p "$CACHE"
fetch() {
  local v=$1 f="$CACHE/android-all-$1.jar"
  if [ ! -s "$f" ]; then
    echo "Downloading android-all $v ..."
    curl -fL --retry 4 -o "$f.part" "$MAVEN/$v/android-all-$v.jar" && mv "$f.part" "$f"
  fi
}
fetch "$RES_JAR_VERSION"
fetch "$CLS_JAR_VERSION"
if [ ! -s "$CACHE/android-34-res.jar" ]; then
  T=$(mktemp -d); (cd "$T" && unzip -q "$CACHE/android-all-$RES_JAR_VERSION.jar" AndroidManifest.xml resources.arsc && zip -q "$CACHE/android-34-res.jar" AndroidManifest.xml resources.arsc); rm -rf "$T"
fi

rm -rf "$BUILD" && mkdir -p "$BUILD/gen" "$BUILD/classes"
echo "== resources"
# Gradle takes the package name from build.gradle's namespace; aapt2 needs it in the manifest.
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="io.github.vlesk.stave">|' \
  "$APP/AndroidManifest.xml" > "$BUILD/AndroidManifest.xml"
aapt2 compile --dir "$APP/res" -o "$BUILD/res.zip"
aapt2 link -I "$CACHE/android-34-res.jar" --manifest "$BUILD/AndroidManifest.xml" -A "$APP/assets" \
  --min-sdk-version $MIN_SDK --target-sdk-version $TARGET_SDK --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" \
  --java "$BUILD/gen" --auto-add-overlay -o "$BUILD/base.apk" "$BUILD/res.zip"
echo "== java"
javac -nowarn -encoding UTF-8 --release 8 \
  -cp "$CACHE/android-all-$CLS_JAR_VERSION.jar" \
  -d "$BUILD/classes" $(find "$APP/java" "$BUILD/gen" -name '*.java')
echo "== dex"
"$DX" --dex --min-sdk-version=$MIN_SDK --output="$BUILD/classes.dex" "$BUILD/classes"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
(cd "$BUILD" && zip -q unsigned.apk classes.dex)
zipalign -f -p 4 "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
echo "== sign"
KS="${STAVE_KEYSTORE:-$ROOT/keystore/debug.jks}"
apksigner sign --ks "$KS" --ks-pass "pass:${STAVE_KEYSTORE_PASS:-android}" --ks-key-alias "${STAVE_KEY_ALIAS:-stave}" \
  --min-sdk-version $MIN_SDK --out "$BUILD/stave.apk" "$BUILD/aligned.apk"
apksigner verify --min-sdk-version $MIN_SDK "$BUILD/stave.apk"
echo "Built $BUILD/stave.apk ($(du -h "$BUILD/stave.apk" | cut -f1))"
