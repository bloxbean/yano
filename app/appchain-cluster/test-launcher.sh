#!/usr/bin/env bash
# Focused launcher regression: occupied default port, saved-port discovery,
# and a retained devnet restart with byte-identical genesis.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CLUSTER="$SCRIPT_DIR/cluster.sh"
WORK="$(mktemp -d /tmp/yano-cluster-launcher-test.XXXXXX)"
DATA="$WORK/cluster"
EXPLICIT_DATA="$WORK/explicit"
BLOCKER_PID=""

die() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

cleanup() {
  "$CLUSTER" stop --data-dir "$DATA" >/dev/null 2>&1 || true
  "$CLUSTER" stop --data-dir "$EXPLICIT_DATA" >/dev/null 2>&1 || true
  [ -n "$BLOCKER_PID" ] && kill "$BLOCKER_PID" >/dev/null 2>&1 || true
  rm -rf "$WORK"
}
trap cleanup EXIT INT TERM

# Occupy the preferred HTTP port if the host does not already have a listener.
if ! lsof -nP -iTCP:7070 -sTCP:LISTEN >/dev/null 2>&1; then
  python3 -m http.server 7070 --bind 0.0.0.0 >"$WORK/blocker.log" 2>&1 &
  BLOCKER_PID="$!"
  sleep 1
fi
lsof -nP -iTCP:7070 -sTCP:LISTEN >/dev/null 2>&1 \
  || die "could not occupy preferred HTTP port 7070"

# Explicit ports are strict and must fail before state is created.
if "$CLUSTER" start 1 --data-dir "$EXPLICIT_DATA" --http-base 7070 \
    >"$WORK/explicit.log" 2>&1; then
  die "busy explicit HTTP base unexpectedly started"
fi
grep -q 'explicit HTTP range.*busy' "$WORK/explicit.log" \
  || die "busy explicit range did not produce the expected diagnostic"
[ ! -f "$EXPLICIT_DATA/node0/chainstate/CURRENT" ] \
  || die "busy-port preflight modified chain state"

CLUSTER_WARMUP=0 "$CLUSTER" start 1 --data-dir "$DATA" \
  >"$WORK/start-1.log" 2>&1 || { cat "$WORK/start-1.log"; die "first start failed"; }

HTTP1="$(sed -n 's/^HTTP_BASE=//p' "$DATA/cluster.env")"
[ -n "$HTTP1" ] && [ "$HTTP1" != 7070 ] \
  || die "default HTTP range was not relocated"
"$CLUSTER" status --data-dir "$DATA" >"$WORK/status-1.log" 2>&1 \
  || die "status did not discover the saved HTTP base"
grep -q "http $HTTP1" "$WORK/status-1.log" \
  || die "status did not use relocated HTTP base $HTTP1"

GENESIS_SHA1="$(shasum -a 256 "$DATA/node0/shelley-genesis.json" | awk '{print $1}')"
SYSTEM_START1="$(jq -r .systemStart "$DATA/node0/shelley-genesis.json")"
"$CLUSTER" submit orders-chain regression before-restart --data-dir "$DATA" \
  >"$WORK/submit.log" 2>&1 || die "pre-restart submit failed"

tip=0; attempts=0
while [ "$tip" -lt 1 ] && [ "$attempts" -lt 20 ]; do
  tip="$(curl -s "http://localhost:$HTTP1/api/v1/app-chain/chains/orders-chain/status" \
    | jq -r '.tipHeight // 0' 2>/dev/null)"
  attempts=$(( attempts + 1 )); [ "$tip" -lt 1 ] && sleep 1
done
[ "$tip" -ge 1 ] || die "message did not finalize before restart"

"$CLUSTER" stop --data-dir "$DATA" >/dev/null || die "first stop failed"
CLUSTER_WARMUP=0 "$CLUSTER" start 1 --data-dir "$DATA" \
  >"$WORK/start-2.log" 2>&1 || { cat "$WORK/start-2.log"; die "retained restart failed"; }

HTTP2="$(sed -n 's/^HTTP_BASE=//p' "$DATA/cluster.env")"
GENESIS_SHA2="$(shasum -a 256 "$DATA/node0/shelley-genesis.json" | awk '{print $1}')"
SYSTEM_START2="$(jq -r .systemStart "$DATA/node0/shelley-genesis.json")"
[ "$GENESIS_SHA1" = "$GENESIS_SHA2" ] || die "genesis bytes changed across restart"
[ "$SYSTEM_START1" = "$SYSTEM_START2" ] || die "systemStart changed across restart"

tip="$(curl -s "http://localhost:$HTTP2/api/v1/app-chain/chains/orders-chain/status" \
  | jq -r '.tipHeight // 0' 2>/dev/null)"
[ "$tip" -ge 1 ] || die "app-chain tip was not retained across restart"

"$CLUSTER" stop --data-dir "$DATA" >/dev/null || die "second stop failed"

# A retained genesis mismatch must be reported promptly instead of polling
# readiness for three minutes.
cp "$DATA/node0/shelley-genesis.json" "$DATA/node0/shelley-genesis.original"
printf '\n' >> "$DATA/node0/shelley-genesis.json"
if CLUSTER_WARMUP=0 "$CLUSTER" start 1 --data-dir "$DATA" >"$WORK/mismatch.log" 2>&1; then
  die "changed retained genesis unexpectedly started"
fi
grep -q 'runtime failed during startup' "$WORK/mismatch.log" \
  || die "changed retained genesis did not fail promptly"
[ ! -f "$DATA/node0.pid" ] || die "failed startup left a stale PID file"
mv "$DATA/node0/shelley-genesis.original" "$DATA/node0/shelley-genesis.json"

mv "$DATA/node0/shelley-genesis.json" "$DATA/node0/shelley-genesis.saved"
if CLUSTER_WARMUP=0 "$CLUSTER" start 1 --data-dir "$DATA" >"$WORK/missing.log" 2>&1; then
  die "retained state without genesis unexpectedly started"
fi
grep -q 'retained node 0 state has no shelley-genesis.json' "$WORK/missing.log" \
  || die "missing retained genesis did not fail with the expected diagnostic"
mv "$DATA/node0/shelley-genesis.saved" "$DATA/node0/shelley-genesis.json"

printf 'PASS: auto-port=%s, retained genesis=%s, tip=%s\n' \
  "$HTTP2" "$GENESIS_SHA2" "$tip"
