#!/usr/bin/env bash
# Runs the RTP push harness on this computer: the app's own RTP code, a live x264 encoder in place
# of the phone, a proxy that loses packets, and a strict receiver. Needs python3 and an ffmpeg with
# libx264. Usage: run.sh <ffmpeg> [arms, e.g. A0,A1] [seconds] [A3 proxy args]. Results land in app/build/rtp-harness/out.
set -euo pipefail
FFMPEG=${1:?ffmpeg path}
ARMS=${2:-}
SECONDS_PER_ARM=${3:-50}
A3_ARGS=${4:---rcvbuf 32768 --drain-pps 1500}
PACING=${PACING:-on}
ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
DIR=$ROOT/app/build/rtp-harness
rm -rf "$DIR/out"; mkdir -p "$DIR/out"
cat > "$DIR/harness.properties" <<EOF
ffmpeg=$FFMPEG
python=$(command -v python3)
tools=$ROOT/tools/second-screen/rtp-harness
out=$DIR/out
arms=$ARMS
seconds=$SECONDS_PER_ARM
fps=30
size=1280x720
gopSeconds=60
phoneLatencyMs=1000
a3=$A3_ARGS
pacing=$PACING
EOF
cd "$ROOT"
status=0
~/.local/bin/ohu-gradle :app:testGithubDebugUnitTest --tests '*.RtpPushHarnessTest' --rerun || status=$?
rm -f "$DIR/harness.properties"
cat "$DIR/out/summary.txt"
exit $status
