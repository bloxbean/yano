#!/bin/bash
# Per-case Haskell cardano-node instance.
#
# The shared download at test-data-dir/haskell-node/ supplies the BINARY only.
# Every case gets its own instance directory (config, genesis, topology, db,
# socket, ports), so a run never touches another node's database and two cases
# can never share chain state. Existing user nodes and databases are untouched.

HASKELL_SHARED_DIR="$PROJECT_ROOT/test-data-dir/haskell-node"

ensure_haskell_binary() {
  if [ ! -x "$HASKELL_SHARED_DIR/bin/cardano-node" ]; then
    log "cardano-node binary missing; running setup-haskell-test-node.sh (downloads once)"
    bash "$PROJECT_ROOT/scripts/haskell-compatibility/setup-haskell-test-node.sh"
  fi
  [ -x "$HASKELL_SHARED_DIR/bin/cardano-node" ] || die "cardano-node binary not available"
  [ -x "$HASKELL_SHARED_DIR/bin/cardano-cli" ] || die "cardano-cli binary not available"
  "$HASKELL_SHARED_DIR/bin/cardano-node" --version | head -1
}

create_haskell_instance() { # <instance-dir> <genesis-dir> <yano-n2n-port> <ekg> <prometheus>
  local dir="$1" genesis="$2" n2n_port="$3" ekg="$4" prometheus="$5"
  mkdir -p "$dir/files" "$dir/db"

  # Same genesis BYTES as Yano: copied after the epoch shift rewrote systemStart.
  cp "$genesis/shelley-genesis.json" "$dir/files/"
  cp "$genesis/byron-genesis.json"   "$dir/files/"
  cp "$genesis/alonzo-genesis.json"  "$dir/files/"
  cp "$genesis/conway-genesis.json"  "$dir/files/"
  if [ -f "$HASKELL_SHARED_DIR/files/dijkstra-genesis.json" ]; then
    cp "$HASKELL_SHARED_DIR/files/dijkstra-genesis.json" "$dir/files/"
  fi

  python3 - "$HASKELL_SHARED_DIR/configuration.json" "$dir/configuration.json" "$ekg" "$prometheus" <<'PY'
import json, sys
source, dest, ekg, prometheus = sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4])
config = json.load(open(source))
config["hasEKG"] = ekg
config["hasPrometheus"] = ["127.0.0.1", prometheus]
json.dump(config, open(dest, "w"), indent=2, sort_keys=True)
PY

  cat > "$dir/files/topology.json" <<TOPO
{
  "bootstrapPeers": [
    {"address": "127.0.0.1", "port": $n2n_port}
  ],
  "localRoots": [
    {
      "accessPoints": [
        {"address": "127.0.0.1", "port": $n2n_port}
      ],
      "valency": 1
    }
  ],
  "publicRoots": [],
  "useLedgerAfterSlot": -1
}
TOPO
}

start_haskell_instance() { # <instance-dir> <node-port> <log> <label>
  local dir="$1" node_port="$2" log_file="$3" label="$4"
  local bin="$HASKELL_SHARED_DIR/bin/cardano-node"
  {
    printf 'cd %q\n' "$dir"
    printf '%q run --topology files/topology.json --database-path db --socket-path db/node.socket ' "$bin"
    printf -- '--host-addr 127.0.0.1 --port %s --config configuration.json\n' "$node_port"
  } > "$dir/command.txt"

  ( cd "$dir" && exec "$bin" run \
      --topology files/topology.json \
      --database-path db \
      --socket-path db/node.socket \
      --host-addr 127.0.0.1 \
      --port "$node_port" \
      --config configuration.json ) > "$log_file" 2>&1 &
  local pid=$!
  track_pid "$pid" "haskell:$label"
  echo "$pid"
}

wait_haskell_socket() { # <instance-dir> <timeout> <pid>
  local dir="$1" timeout="$2" pid="$3" waited=0
  while [ "$waited" -lt "$timeout" ]; do
    if ! kill -0 "$pid" 2>/dev/null; then
      warn "cardano-node exited during startup"
      return 2
    fi
    [ -S "$dir/db/node.socket" ] && { log "haskell socket up after ${waited}s"; return 0; }
    sleep 1
    waited=$((waited + 1))
  done
  return 1
}

# cardano-cli must reach the socket through a RELATIVE path: AF_UNIX paths are
# capped at 104 bytes, and an absolute run-directory path blows that limit
# ("pokeSockAddr: path is too long in SockAddrUnix"). The node binds it relative
# to its own cwd for the same reason.
haskell_tip() { # <instance-dir> <magic>
  ( cd "$1" && CARDANO_NODE_SOCKET_PATH=db/node.socket \
      "$HASKELL_SHARED_DIR/bin/cardano-cli" query tip --testnet-magic "$2" 2>/dev/null )
}

wait_haskell_slot() { # <instance-dir> <magic> <min-slot> <max-seconds> [stall-seconds]
  # Bulk sync of a long sparse history is slow (the ledger ticks every skipped slot),
  # so the overall cap is generous and progress is what actually decides: if the tip
  # has not moved for <stall-seconds>, the sync is stuck and we stop waiting.
  local dir="$1" magic="$2" min_slot="$3" timeout="$4" stall="${5:-240}"
  local waited=0 since_progress=0 slot last=-1
  while [ "$waited" -lt "$timeout" ]; do
    slot=$(haskell_tip "$dir" "$magic" | python3 -c \
      'import json,sys
try:
    print(json.load(sys.stdin)["slot"])
except Exception:
    print(-1)' 2>/dev/null || echo -1)
    if [ "${slot:--1}" -ge "$min_slot" ] 2>/dev/null; then
      log "haskell reached slot $slot (>= $min_slot) after ${waited}s"
      return 0
    fi
    if [ "${slot:--1}" -gt "$last" ] 2>/dev/null; then
      last="$slot"
      since_progress=0
      [ $((waited % 60)) -lt 5 ] && [ "$waited" -gt 0 ] \
        && log "haskell syncing: slot $slot / $min_slot after ${waited}s"
    else
      since_progress=$((since_progress + 5))
      if [ "$since_progress" -ge "$stall" ]; then
        warn "haskell tip stuck at ${last} for ${stall}s (target ${min_slot}); giving up"
        return 1
      fi
    fi
    sleep 5
    waited=$((waited + 5))
  done
  warn "haskell did not reach slot $min_slot within ${timeout}s (last=${last})"
  return 1
}
