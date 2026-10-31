#!/usr/bin/env bash
# Runs the plain-JVM tests of the model, editing engine, file formats and engraving.
# Usage: tools/test.sh [dir-with-mscx-files]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${TMPDIR:-/tmp}/stave-test-classes"
rm -rf "$OUT" && mkdir -p "$OUT"
SRC=$(find "$ROOT/app/src/main/java/io/github/vlesk/stave/model" "$ROOT/app/src/main/java/io/github/vlesk/stave/io" \
  "$ROOT/app/src/main/java/io/github/vlesk/stave/audio/Timeline.java" "$ROOT/app/src/main/java/io/github/vlesk/stave/audio/MidiWriter.java" \
  "$ROOT/app/src/main/java/io/github/vlesk/stave/engrave" "$ROOT/test/java" -name '*.java')
javac -nowarn -d "$OUT" $SRC
java -cp "$OUT" io.github.vlesk.stave.ModelTests "$@"
