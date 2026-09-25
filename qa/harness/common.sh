#!/bin/bash
# Shared helpers for the release-QA harness (see qa/README.md).
# Isolation rules: developer nodes commonly hold 7070/13337, 7079/18337, 3002,
# 31000/32000 and their Prometheus ports. The harness uses its own ports below,
# never kills by port, and only kills PIDs it started (tracked in $PIDFILE).

REPO=${REPO:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}
QA_HOME=${QA_HOME:-$REPO/qa/work}
QA_BIN=${QA_BIN:-$QA_HOME/bin}          # yano.jar and native-dist/ from the build step
SP=${SP:-$QA_HOME}                      # runs/ and pids.txt for the current run
JAR=${JAR:-$QA_BIN/yano.jar}
NATIVE=${NATIVE:-$(ls -d "$QA_BIN"/native-dist/yano-native-*/yano 2>/dev/null | head -1)}
if [ -z "${JAVA:-}" ]; then
  if [ -n "${JAVA_HOME:-}" ]; then JAVA=$JAVA_HOME/bin/java; else JAVA=java; fi
fi
HASKELL_NODE_DIR=${HASKELL_NODE_DIR:-$REPO/test-data-dir/haskell-node}

HTTP_A=7171
N2N_A=13441
HTTP_B=7172
N2N_B=13442
HS_PORT=3103
HS_EKG=12889
HS_PROM=12899

PIDFILE=${PIDFILE:-$SP/pids.txt}
mkdir -p "$SP"

log() { echo "[$(date +%H:%M:%S)] $*"; }

track_pid() { echo "$1 $2" >> "$PIDFILE"; }

kill_tracked() {
  [ -f "$PIDFILE" ] || return 0
  while read -r pid name; do
    if kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null
      for _ in 1 2 3 4 5 6 7 8 9 10; do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
      kill -9 "$pid" 2>/dev/null
      log "killed $name ($pid)"
    fi
  done < "$PIDFILE"
  rm -f "$PIDFILE"
}

assert_ports_free() {
  for p in "$@"; do
    if lsof -nP -iTCP:"$p" -sTCP:LISTEN >/dev/null 2>&1; then
      echo "PORT $p IS BUSY" >&2; lsof -nP -iTCP:"$p" -sTCP:LISTEN >&2; return 1
    fi
  done
}

# start_yano <mode jvm|native> <run_dir> <http_port> <n2n_port> <logfile> [extra -D args...]
# Runs with cwd=app/ (devnet profile resolves key files relative to it) but every
# mutable path (chainstate, history, log file) is pointed into run_dir.
start_yano() {
  local mode=$1 run=$2 http=$3 n2n=$4 out=$5; shift 5
  mkdir -p "$run"
  local common=(-Dquarkus.http.port=$http -Dyano.server.port=$n2n
    -Dyano.storage.path=$run/chainstate -Dyano.history.dir=$run/history
    -Dquarkus.log.file.path=$run/yano-file.log)
  cd "$REPO/app" || return 1
  if [ "$mode" = native ]; then
    "$NATIVE" "${common[@]}" "$@" > "$out" 2>&1 &
  else
    "$JAVA" "${common[@]}" "$@" -jar "$JAR" > "$out" 2>&1 &
  fi
  YANO_PID=$!
  cd - >/dev/null
  track_pid $YANO_PID "yano-$mode-$http"
  log "started yano $mode pid=$YANO_PID http=$http n2n=$n2n"
}

wait_ready() { # <http_port> <timeout_s>
  local t=${2:-60}
  for i in $(seq 1 "$t"); do
    curl -sf "http://localhost:$1/q/health/ready" >/dev/null 2>&1 && { log "ready on $1 after ${i}s"; return 0; }
    sleep 1
  done
  log "NOT READY on $1 after ${t}s"; return 1
}

yano_tip() { curl -s "http://localhost:$1/api/v1/node/tip"; }

# Prepare an isolated Haskell node dir: bin symlinked, config copied, ports patched.
prepare_haskell() { # <hs_dir> <yano_n2n_port> <genesis_dir>
  local hs=$1 yport=$2 gdir=$3 src=$HASKELL_NODE_DIR
  rm -rf "$hs"; mkdir -p "$hs/files" "$hs/db"
  ln -s "$src/bin" "$hs/bin"
  cp "$src/tip.sh" "$hs/"
  cp "$src/files/dijkstra-genesis.json" "$hs/files/"
  for g in shelley byron alonzo conway; do cp "$gdir/$g-genesis.json" "$hs/files/"; done
  jq --argjson ekg $HS_EKG --argjson prom $HS_PROM \
    '.hasEKG = $ekg | .hasPrometheus = ["127.0.0.1", $prom]' "$src/configuration.json" > "$hs/configuration.json"
  sed "s/13337/$yport/g" "$src/files/topology.json" > "$hs/files/topology.json"
}

start_haskell() { # <hs_dir> <logfile>
  cd "$1" || return 1
  ./bin/cardano-node run --topology files/topology.json --database-path db \
    --socket-path db/node.socket --host-addr 127.0.0.1 --port $HS_PORT \
    --config configuration.json > "$2" 2>&1 &
  HS_PID=$!
  cd - >/dev/null
  track_pid $HS_PID "haskell-$HS_PORT"
  log "started haskell pid=$HS_PID port=$HS_PORT"
}

# Relative socket path: the absolute scratchpad path exceeds macOS's 104-byte sun_path limit.
hs_tip() { (cd "$1" && CARDANO_NODE_SOCKET_PATH=db/node.socket ./bin/cardano-cli query tip --testnet-magic 42 2>/dev/null); }
