#!/bin/bash
# Start/stop an isolated Yano devnet node for one sparse-backfill case.
#
# Everything the node writes is inside the case directory: chainstate, history
# archive, app-chain store, and the genesis copy whose systemStart the runtime
# rewrites. The tracked fixtures under app/config/network/devnet/pv10/ are only
# ever read (prepare_genesis.py copies them out first).

yano_java_args() { # <case-dir> <genesis-dir> <http-port> <n2n-port> <interval> <mode>
  local case_dir="$1" genesis="$2" http_port="$3" n2n_port="$4" interval="$5" mode="$6"
  local args=(
    -Dquarkus.profile=devnet
    -Dquarkus.http.port="$http_port"
    -Dyano.server.port="$n2n_port"
    -Dyano.storage.path="$case_dir/chainstate"
    -Dyano.history.dir="$case_dir/history"
    -Dyano.app-chain.storage.path="$case_dir/appchain-chainstate"
    -Dyano.genesis.shelley-genesis-file="$genesis/shelley-genesis.json"
    -Dyano.genesis.byron-genesis-file="$genesis/byron-genesis.json"
    -Dyano.genesis.alonzo-genesis-file="$genesis/alonzo-genesis.json"
    -Dyano.genesis.conway-genesis-file="$genesis/conway-genesis.json"
    -Dyano.genesis.protocol-parameters-file="$genesis/protocol-param.json"
    -Dyano.genesis.shelley-genesis-hash=
    -Dyano.block-producer.vrf-skey-file="$genesis/vrf.skey"
    -Dyano.block-producer.kes-skey-file="$genesis/kes.skey"
    -Dyano.block-producer.opcert-file="$genesis/opcert.cert"
    -Dyano.block-producer.backfill-block-interval-slots="$interval"
  )
  if [ -n "${LEGACY_SLOT_LENGTH_MILLIS:-}" ]; then
    args+=(-Dyano.block-producer.slot-length-millis="$LEGACY_SLOT_LENGTH_MILLIS")
  fi
  case "$mode" in
    past-time-travel)
      args+=(-Dyano.block-producer.past-time-travel-mode=true) ;;
    slot-leader-time-travel)
      args+=(-Dyano.block-producer.past-time-travel-mode=true
             -Dyano.block-producer.past-time-travel-slot-leader-mode=true) ;;
    live)
      : ;;  # regular devnet producer, wall-clock slots (used for the restart check)
    *) die "unknown yano mode: $mode" ;;
  esac
  printf '%s\n' "${args[@]}"
}

start_yano() { # <case-dir> <genesis-dir> <http> <n2n> <interval> <mode> <log> <label>
  local case_dir="$1" genesis="$2" http_port="$3" n2n_port="$4" interval="$5" mode="$6"
  local log_file="$7" label="$8"
  local args=()
  while IFS= read -r line; do args+=("$line"); done < <(
    yano_java_args "$case_dir" "$genesis" "$http_port" "$n2n_port" "$interval" "$mode")

  {
    printf 'cd %q\n' "$case_dir"
    printf '%q ' java "${args[@]}" -jar "$YANO_JAR"
    printf '\n'
  } > "$case_dir/${label}-command.txt"

  ( cd "$case_dir" && exec java "${args[@]}" -jar "$YANO_JAR" ) > "$log_file" 2>&1 &
  local pid=$!
  track_pid "$pid" "yano:$label"
  echo "$pid"
}

wait_yano_ready() { # <http-port> <timeout> <log> <pid>
  local http_port="$1" timeout="$2" log_file="$3" pid="$4" waited=0
  while [ "$waited" -lt "$timeout" ]; do
    if ! kill -0 "$pid" 2>/dev/null; then
      warn "yano exited during startup; see $log_file"
      return 2
    fi
    if curl -sf -o /dev/null "http://127.0.0.1:$http_port/q/health/ready"; then
      log "yano ready on port $http_port after ${waited}s"
      return 0
    fi
    sleep 1
    waited=$((waited + 1))
  done
  warn "yano did not become ready within ${timeout}s"
  return 1
}
