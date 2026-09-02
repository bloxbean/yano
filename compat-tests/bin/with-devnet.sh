#!/usr/bin/env bash
#
# Convenience wrapper: start a disposable Yano devnet, run the compatibility
# suite against it, then tear it down.
#
# This is NOT how the suite is normally used and nothing in the suite depends on
# it - run-suite.sh talks to whatever node you point it at. Use this when you
# just want a verdict against the current working tree and do not care about the
# node afterwards.
#
#   compat-tests/bin/with-devnet.sh [--port 7070] [--keep] [-- <run-suite args>]
#
# See adr/053-sdk-compatibility-and-load-suite.md (D3, C-M2).

set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUITE="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$SUITE/.." && pwd)"

PORT=7070
KEEP=0
BUILD=1
# Chained-transaction workloads need slow blocks. At the devnet default the
# parent confirms mid-test and the chaining cases pass for the wrong reason -
# a false positive that has cost real debugging time more than once.
BLOCK_TIME_MS="${BLOCK_TIME_MS:-20000}"
SUITE_ARGS=()

usage() {
  cat <<'USAGE'
Usage: with-devnet.sh [options] [-- <run-suite.sh args>]

  --port N        HTTP port for the devnet (default 7070)
  --keep          keep the chainstate directory and leave the node running
  --no-build      do not run `./gradlew :app:quarkusBuild` first
  --block-time N  block time in ms (default 20000; chaining needs slow blocks)
  -h, --help      this message

Everything after `--` is passed straight to run-suite.sh, e.g.

  with-devnet.sh -- --compat-only --label local
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --port) PORT="$2"; shift 2 ;;
    --keep) KEEP=1; shift ;;
    --no-build) BUILD=0; shift ;;
    --block-time) BLOCK_TIME_MS="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    --) shift; while [ $# -gt 0 ]; do SUITE_ARGS[${#SUITE_ARGS[@]}]="$1"; shift; done ;;
    *) echo "unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

JAR="$REPO/app/build/yano.jar"
if [ "$BUILD" = "1" ] || [ ! -f "$JAR" ]; then
  echo "--- building $JAR ---"
  ( cd "$REPO" && ./gradlew :app:quarkusBuild -PskipSigning=true ) || {
    echo "build failed" >&2; exit 1; }
fi
[ -f "$JAR" ] || { echo "no uber-jar at $JAR" >&2; exit 1; }

STATE="$(mktemp -d "${TMPDIR:-/tmp}/yano-compat-devnet.XXXXXX")"
LOG="$STATE/node.log"
NODE_PID=""

cleanup() {
  if [ -n "$NODE_PID" ] && [ "$KEEP" = "0" ]; then
    echo "--- stopping devnet (pid $NODE_PID) ---"
    kill -TERM "$NODE_PID" 2>/dev/null
    for _ in $(seq 1 40); do
      kill -0 "$NODE_PID" 2>/dev/null || break
      sleep 0.5
    done
    kill -0 "$NODE_PID" 2>/dev/null && kill -KILL "$NODE_PID" 2>/dev/null
  fi
  if [ "$KEEP" = "1" ]; then
    echo "--- kept: state=$STATE log=$LOG${NODE_PID:+ pid=$NODE_PID} ---"
  else
    rm -rf "$STATE"
  fi
}
trap cleanup EXIT INT TERM

echo "--- starting devnet on :$PORT (block time ${BLOCK_TIME_MS}ms, state $STATE) ---"
# The devnet profile resolves genesis and key files relative to app/, so the
# node must run with that working directory.
(
  cd "$REPO/app" && exec java \
    -Dquarkus.profile=devnet \
    -Dquarkus.http.port="$PORT" \
    -Dyano.storage.path="$STATE/chainstate" \
    -Dyano.block-producer.block-time-millis="$BLOCK_TIME_MS" \
    -jar "$JAR"
) > "$LOG" 2>&1 &
NODE_PID=$!

BASE="http://localhost:$PORT"
echo -n "waiting for readiness"
ready=0
for _ in $(seq 1 120); do
  if ! kill -0 "$NODE_PID" 2>/dev/null; then
    echo; echo "node exited during startup, last lines of $LOG:" >&2
    tail -30 "$LOG" >&2
    exit 1
  fi
  if curl -fsS --max-time 2 "$BASE/q/health/ready" >/dev/null 2>&1; then ready=1; break; fi
  echo -n "."
  sleep 1
done
echo
if [ "$ready" != "1" ]; then
  echo "devnet did not become ready within 120s; last lines of $LOG:" >&2
  tail -30 "$LOG" >&2
  exit 1
fi
echo "--- devnet ready at $BASE (pid $NODE_PID, log $LOG) ---"
echo

if [ ${#SUITE_ARGS[@]} -gt 0 ]; then
  "$SUITE/run-suite.sh" --url "$BASE/api/v1" --node-pid "$NODE_PID" "${SUITE_ARGS[@]}"
else
  "$SUITE/run-suite.sh" --url "$BASE/api/v1" --node-pid "$NODE_PID"
fi
rc=$?

echo
echo "--- node log: $LOG ---"
exit $rc
