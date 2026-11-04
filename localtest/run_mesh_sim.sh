#!/usr/bin/env bash
# Runs the measurements and mesh simulations of the project report (Chapter 5) with the real :core code:
# packet sizes, AI priority examples, a line of phones, random deployments, lossy links and store-and-forward
# with moving phones. Usage: localtest/run_mesh_sim.sh [all|sizes|priority|line|random|loss|mobility] [trials] [mobility runs]
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
KOTLINC="${KOTLINC:-/home/claude/tools/kotlinc/bin/kotlinc}"
KLIB="$(dirname "$KOTLINC")/../lib"
OUT="${OUT:-$ROOT/localtest/build/sim}"
WHAT="${1:-all}"
mkdir -p "$OUT"
"$KOTLINC" "$ROOT/core/src/main/kotlin" "$ROOT/localtest/MeshSim.kt" -d "$OUT/meshsim.jar" -jvm-target 17 2>&1 | grep -v "^Picked up" || true
java -cp "$OUT/meshsim.jar:$KLIB/kotlin-stdlib.jar" \
  -Dmodel="$ROOT/app/src/main/assets/priority_model.json" -Doutfile="$OUT/results_$WHAT.txt" \
  MeshSimKt "$WHAT" "${2:-300}" "${3:-50}" 2>&1 | grep -v "^Picked up"
echo "results in $OUT/results_$WHAT.txt"
