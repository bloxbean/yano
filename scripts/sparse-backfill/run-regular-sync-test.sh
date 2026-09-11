#!/usr/bin/env bash
# Isolated test-haskell-sync skill: regular production, no shift or catch-up.
set -euo pipefail
PROJECT_ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
source "$PROJECT_ROOT/scripts/sparse-backfill/lib/common.sh"
source "$PROJECT_ROOT/scripts/sparse-backfill/lib/yano.sh"
source "$PROJECT_ROOT/scripts/sparse-backfill/lib/haskell.sh"
YANO_JAR="$PROJECT_ROOT/app/build/yano.jar"
RUN_DIR=$(mktemp -d "$PROJECT_ROOT/test-data-dir/regular-genesis-sync.XXXXXX")
PID_FILE="$RUN_DIR/started-pids"
touch "$PID_FILE"
export PID_FILE PROJECT_ROOT YANO_JAR
trap cleanup_tracked EXIT
http=$(pick_port 23000)
n2n=$(pick_port "$((http + 1))")
hnode=$(pick_port "$((n2n + 1))")
ekg=$(pick_port "$((hnode + 1))")
prom=$(pick_port "$((ekg + 1))")
genesis="$RUN_DIR/genesis"
log "Run directory: $RUN_DIR"
python3 "$PROJECT_ROOT/scripts/sparse-backfill/tools/prepare_genesis.py" \
  --source "$PROJECT_ROOT/app/config/network/devnet/pv10" --dest "$genesis" \
  --slot-length 0.2 --epoch-length 600 --security-param 50 --active-slots-coeff 1 \
  > "$RUN_DIR/genesis-params.json"
pid=$(start_yano "$RUN_DIR" "$genesis" "$http" "$n2n" 1 live "$RUN_DIR/yano.log" regular)
wait_yano_ready "$http" 180 "$RUN_DIR/yano.log" "$pid"
ensure_haskell_binary > "$RUN_DIR/haskell-version.txt"
hdir="$RUN_DIR/haskell"
create_haskell_instance "$hdir" "$genesis" "$n2n" "$ekg" "$prom"
for name in shelley byron alonzo conway; do
  cmp "$genesis/$name-genesis.json" "$hdir/files/$name-genesis.json"
done
hpid=$(start_haskell_instance "$hdir" "$hnode" "$RUN_DIR/haskell.log" regular)
wait_haskell_socket "$hdir" 120 "$hpid"
for target in 610 1210; do
  wait_haskell_slot "$hdir" 42 "$target" 360
  python3 "$PROJECT_ROOT/scripts/sparse-backfill/tools/haskell_check.py" \
    --haskell-log "$RUN_DIR/haskell.log" --cli "$HASKELL_SHARED_DIR/bin/cardano-cli" \
    --node-dir "$hdir" --magic 42 --yano-base-url "http://127.0.0.1:$http" \
    --expect-slots "0,600" --phase live --reach-slot "$target" --max-tip-lag 2 \
    --out "$RUN_DIR/haskell-$target.json"
done
stop_tracked_pid "$hpid" haskell:regular 60
stop_tracked_pid "$pid" yano:regular 60
if jvm_crash_detail "$RUN_DIR" "$RUN_DIR/yano.log"; then
  die "JVM crash during shutdown"
fi
log "PASS: regular production followed through two epochs with matching hashes"
