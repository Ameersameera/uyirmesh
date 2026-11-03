#!/usr/bin/env bash
# Compiles the :core module and its tests with a stand-alone Kotlin compiler and runs them
# (the same tests run under Gradle + JUnit in CI).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLINC="${KOTLINC:-/home/claude/tools/kotlinc/bin/kotlinc}"
KLIB="$(dirname "$KOTLINC")/../lib"
OUT="${OUT:-$ROOT/localtest/build}"
rm -rf "$OUT" && mkdir -p "$OUT/junit" "$OUT/runner"
javac -d "$OUT/junit" "$ROOT"/localtest/junit/org/junit/*.java
"$KOTLINC" "$ROOT/core/src/main/kotlin" "$ROOT/core/src/test/kotlin" -cp "$OUT/junit" -d "$OUT/core-tests.jar" -jvm-target 17 2>&1 | grep -v "^Picked up" || true
javac -cp "$OUT/junit" -d "$OUT/runner" "$ROOT/localtest/Runner.java"
CP="$OUT/core-tests.jar:$OUT/junit:$OUT/runner:$KLIB/kotlin-stdlib.jar:$ROOT/core/src/test/resources"
cd "$ROOT"
java -cp "$CP" Runner \
  com.ameer.uyirmesh.core.JsonBytesTest \
  com.ameer.uyirmesh.core.PacketCryptoTest \
  com.ameer.uyirmesh.core.MeshEngineTest \
  com.ameer.uyirmesh.core.KeysSecureTest \
  com.ameer.uyirmesh.core.GeoPriorityPayloadTest 2>&1 | grep -v "^Picked up"
