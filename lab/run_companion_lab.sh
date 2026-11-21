#!/bin/sh
# Runs the Companion Cue-Lock & Sync Verification Lab on a bare JDK.
# Compiles the app's REAL engine classes (the same .java files shipped in the APK —
# no test doubles) together with lab/verify_companion_sync.java and runs them.
#
# Usage:
#   ./lab/run_companion_lab.sh                     # synthetic fixtures (CI default)
#   ./lab/run_companion_lab.sh --paired <file.json>  # a REAL /api/paired/<jobId>.json
#
# The --paired file is NEVER committed (real movie subtitles) — produce one with the
# worker repo's lab:
#   node ../anantplay-sinhala-bridge/lab/verify_worker_e2e.mjs   # then copy the job's
#   /api/paired/movie:tt34890576:0:0.json body into a local file.
set -e
cd "$(dirname "$0")/.."

OUT=lab/out
rm -rf "$OUT"
mkdir -p "$OUT/classes"

echo "── compiling the real engine classes + the lab (JDK, no Android needed)"
javac -encoding UTF-8 -nowarn -Xlint:-options \
  -d "$OUT/classes" \
  src/lk/techworld/anantplay/sicomp/MiniJson.java \
  src/lk/techworld/anantplay/sicomp/Cue.java \
  src/lk/techworld/anantplay/sicomp/CueMatcher.java \
  src/lk/techworld/anantplay/sicomp/ClockSyncEngine.java \
  src/lk/techworld/anantplay/sicomp/SrtLoader.java \
  src/lk/techworld/anantplay/sicomp/BridgeApi.java \
  lab/verify_companion_sync.java

echo "── running the lab"
java -cp "$OUT/classes" verify_companion_sync "$@"
