#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

YANO_REPO="${YANO_REPO:-$PROJECT_ROOT}"
if [ -z "${YACI_STORE_REPO:-}" ]; then
  if [ -d "$PROJECT_ROOT/../yaci-store" ]; then
    YACI_STORE_REPO="$(cd "$PROJECT_ROOT/../yaci-store" && pwd)"
  else
    YACI_STORE_REPO=""
  fi
fi

YANO_REPO="$(cd "$YANO_REPO" && pwd)"
if [ -n "$YACI_STORE_REPO" ] && [ -d "$YACI_STORE_REPO" ]; then
  YACI_STORE_REPO="$(cd "$YACI_STORE_REPO" && pwd)"
fi

FIXTURE_DIR="${FIXTURE_DIR:-$SCRIPT_DIR/fixtures/pv10}"
if [ -d "$FIXTURE_DIR" ]; then
  FIXTURE_DIR="$(cd "$FIXTURE_DIR" && pwd)"
fi
TEST_DIR="${TEST_DIR:-/tmp/yano-devnet-adapot-comparison}"
TARGET_EPOCH="${TARGET_EPOCH:-32}"
NETWORK_MAGIC="${NETWORK_MAGIC:-42}"

YANO_HTTP_PORT="${YANO_HTTP_PORT:-7070}"
YANO_N2N_PORT="${YANO_N2N_PORT:-13337}"
HASKELL_PORT="${HASKELL_PORT:-3002}"
YACI_STORE_PORT="${YACI_STORE_PORT:-8081}"

BUILD_YANO="${BUILD_YANO:-0}"
BUILD_YACI_STORE="${BUILD_YACI_STORE:-0}"
SETUP_HASKELL="${SETUP_HASKELL:-0}"
KEEP_RUNNING="${KEEP_RUNNING:-0}"
CLEAN_PORTS="${CLEAN_PORTS:-1}"

EPOCH_LENGTH="${EPOCH_LENGTH:-60}"
SECURITY_PARAM="${SECURITY_PARAM:-5}"
POOL_MARGIN="${POOL_MARGIN:-0.2}"

STARTUP_TIMEOUT_SECONDS="${STARTUP_TIMEOUT_SECONDS:-180}"
TARGET_TIMEOUT_SECONDS="${TARGET_TIMEOUT_SECONDS:-900}"
ADAPOT_TIMEOUT_SECONDS="${ADAPOT_TIMEOUT_SECONDS:-180}"

YANO_JAR="${YANO_JAR:-$YANO_REPO/app/build/yano.jar}"
HASKELL_NODE_DIR="${HASKELL_NODE_DIR:-$YANO_REPO/test-data-dir/haskell-node}"
CARDANO_NODE="${CARDANO_NODE:-$HASKELL_NODE_DIR/bin/cardano-node}"
CARDANO_CLI="${CARDANO_CLI:-$HASKELL_NODE_DIR/bin/cardano-cli}"
YACI_STORE_JAR="${YACI_STORE_JAR:-}"

RUN_GENESIS_DIR="$TEST_DIR/genesis/pv10"
LOG_DIR="$TEST_DIR/logs"
YANO_CHAINSTATE_DIR="$TEST_DIR/yano-chainstate"
HASKELL_DB_DIR="$TEST_DIR/haskell-db"
HASKELL_SOCKET="$HASKELL_DB_DIR/node.socket"
YACI_STORE_DB_DIR="$TEST_DIR/yaci-store-db"
YACI_STORE_LOG_DIR="$TEST_DIR/yaci-store-logs"
YACI_STORE_PROPERTIES="$TEST_DIR/yaci-store.properties"
HASKELL_TOPOLOGY="$TEST_DIR/haskell-topology.json"

PIDS=()

usage() {
  cat <<EOF
Run a local AdaPot comparison:

  Yano block producer -> Haskell cardano-node -> yaci-store

Usage:
  bash $0

Common configuration:
  YANO_REPO              default: $YANO_REPO
  YACI_STORE_REPO        default: ${YACI_STORE_REPO:-<required>}
  HASKELL_NODE_DIR       default: $HASKELL_NODE_DIR
  FIXTURE_DIR            default: $FIXTURE_DIR
  TEST_DIR               default: $TEST_DIR
  TARGET_EPOCH           default: $TARGET_EPOCH

Ports:
  YANO_HTTP_PORT         default: $YANO_HTTP_PORT
  YANO_N2N_PORT          default: $YANO_N2N_PORT
  HASKELL_PORT           default: $HASKELL_PORT
  YACI_STORE_PORT        default: $YACI_STORE_PORT

Build/setup toggles:
  BUILD_YANO=1           build $YANO_REPO/app/build/yano.jar first
  BUILD_YACI_STORE=1     build yaci-store bootJar first
  SETUP_HASKELL=1        run scripts/haskell-compatibility/setup-haskell-test-node.sh first
  KEEP_RUNNING=1         leave processes running after comparison

Java:
  YANO_JAVA_HOME         optional JAVA_HOME for Yano
  JAVA21_HOME            optional JAVA_HOME for yaci-store build/run

The script writes all runtime state under TEST_DIR and patches only that copied
genesis. Checked-in fixtures are not modified.
EOF
}

log() {
  printf '[adapot-comparison] %s\n' "$*"
}

die() {
  printf '[adapot-comparison] ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || die "Required command not found on PATH: $1"
}

resolve_java21_home() {
  if [ -n "${JAVA21_HOME:-}" ]; then
    printf '%s\n' "$JAVA21_HOME"
    return 0
  fi
  if [ -x /usr/libexec/java_home ]; then
    /usr/libexec/java_home -v 21 2>/dev/null && return 0
  fi
  if [ -n "${JAVA_HOME:-}" ]; then
    printf '%s\n' "$JAVA_HOME"
    return 0
  fi
  return 1
}

run_java21() {
  local home
  home="$(resolve_java21_home || true)"
  if [ -n "$home" ]; then
    JAVA_HOME="$home" PATH="$home/bin:$PATH" "$@"
  else
    "$@"
  fi
}

run_yano_java() {
  if [ -n "${YANO_JAVA_HOME:-}" ]; then
    JAVA_HOME="$YANO_JAVA_HOME" PATH="$YANO_JAVA_HOME/bin:$PATH" "$@"
  else
    "$@"
  fi
}

cleanup() {
  if [ "$KEEP_RUNNING" = "1" ]; then
    log "KEEP_RUNNING=1, leaving processes running. Logs are in $LOG_DIR"
    return 0
  fi

  local pid
  for pid in "${PIDS[@]:-}"; do
    if kill -0 "$pid" >/dev/null 2>&1; then
      kill "$pid" >/dev/null 2>&1 || true
    fi
  done
  sleep 2
  for pid in "${PIDS[@]:-}"; do
    if kill -0 "$pid" >/dev/null 2>&1; then
      kill -9 "$pid" >/dev/null 2>&1 || true
    fi
  done
}
trap cleanup EXIT

kill_port() {
  local port="$1"
  command -v lsof >/dev/null 2>&1 || return 0
  local pids
  pids="$(lsof -tiTCP:"$port" -sTCP:LISTEN 2>/dev/null || true)"
  if [ -n "$pids" ]; then
    log "Killing existing listener(s) on port $port: $pids"
    # shellcheck disable=SC2086
    kill $pids >/dev/null 2>&1 || true
    sleep 1
    # shellcheck disable=SC2086
    kill -9 $pids >/dev/null 2>&1 || true
  fi
}

wait_for_http() {
  local name="$1"
  local url="$2"
  local timeout="$3"
  local start
  start="$(date +%s)"
  while true; do
    if curl -fsS "$url" >/dev/null 2>&1; then
      log "$name is ready: $url"
      return 0
    fi
    if [ $(( $(date +%s) - start )) -ge "$timeout" ]; then
      die "Timed out waiting for $name at $url"
    fi
    sleep 2
  done
}

wait_for_socket() {
  local socket="$1"
  local timeout="$2"
  local start
  start="$(date +%s)"
  while true; do
    if [ -S "$socket" ]; then
      log "Haskell node socket is ready: $socket"
      return 0
    fi
    if [ $(( $(date +%s) - start )) -ge "$timeout" ]; then
      die "Timed out waiting for Haskell socket at $socket"
    fi
    sleep 2
  done
}

discover_yaci_store_jar() {
  if [ -n "$YACI_STORE_JAR" ]; then
    printf '%s\n' "$YACI_STORE_JAR"
    return 0
  fi
  find "$YACI_STORE_REPO/applications/all/build/libs" -maxdepth 1 -type f \
    -name 'yaci-store-*.jar' \
    ! -name '*-plain.jar' \
    ! -name '*-sources.jar' \
    ! -name '*-javadoc.jar' \
    | sort | tail -1
}

query_haskell_tip() {
  CARDANO_NODE_SOCKET_PATH="$HASKELL_SOCKET" \
    "$CARDANO_CLI" query tip --testnet-magic "$NETWORK_MAGIC"
}

query_haskell_ledger_state() {
  CARDANO_NODE_SOCKET_PATH="$HASKELL_SOCKET" \
    "$CARDANO_CLI" latest query ledger-state --testnet-magic "$NETWORK_MAGIC" 2>/dev/null \
    || CARDANO_NODE_SOCKET_PATH="$HASKELL_SOCKET" \
      "$CARDANO_CLI" query ledger-state --testnet-magic "$NETWORK_MAGIC"
}

json_field_or_empty() {
  local json="$1"
  local filter="$2"
  jq -r "$filter // empty" <<<"$json"
}

get_yano_epoch() {
  curl -fsS "http://localhost:$YANO_HTTP_PORT/api/v1/blocks/latest" 2>/dev/null \
    | jq -r '.epoch // empty'
}

get_store_epoch() {
  curl -fsS "http://localhost:$YACI_STORE_PORT/api/v1/blocks/latest" 2>/dev/null \
    | jq -r '.epoch // empty'
}

get_haskell_epoch() {
  query_haskell_tip 2>/dev/null | jq -r '.epoch // empty'
}

wait_for_target_epoch() {
  local start y_epoch h_epoch s_epoch
  start="$(date +%s)"
  while true; do
    y_epoch="$(get_yano_epoch || true)"
    h_epoch="$(get_haskell_epoch || true)"
    s_epoch="$(get_store_epoch || true)"
    if [ -n "$y_epoch" ] && [ -n "$h_epoch" ] && [ -n "$s_epoch" ] \
      && [ "$y_epoch" -ge "$TARGET_EPOCH" ] \
      && [ "$h_epoch" -ge "$TARGET_EPOCH" ] \
      && [ "$s_epoch" -ge "$TARGET_EPOCH" ]; then
      log "All products reached target epoch $TARGET_EPOCH (Yano=$y_epoch, Haskell=$h_epoch, yaci-store=$s_epoch)"
      return 0
    fi
    if [ $(( $(date +%s) - start )) -ge "$TARGET_TIMEOUT_SECONDS" ]; then
      die "Timed out waiting for target epoch $TARGET_EPOCH (Yano=${y_epoch:-?}, Haskell=${h_epoch:-?}, yaci-store=${s_epoch:-?})"
    fi
    log "Waiting for epoch $TARGET_EPOCH (Yano=${y_epoch:-?}, Haskell=${h_epoch:-?}, yaci-store=${s_epoch:-?})"
    sleep 5
  done
}

wait_for_yano_adapot() {
  local epoch="$1"
  local start json treasury reserves deposits
  start="$(date +%s)"
  while true; do
    json="$(curl -fsS "http://localhost:$YANO_HTTP_PORT/api/v1/epochs/$epoch/adapot" 2>/dev/null || true)"
    treasury="$(json_field_or_empty "$json" '.treasury' || true)"
    reserves="$(json_field_or_empty "$json" '.reserves' || true)"
    deposits="$(json_field_or_empty "$json" '.deposits' || true)"
    if [ -n "$treasury" ] && [ -n "$reserves" ] && [ -n "$deposits" ]; then
      printf '%s\n' "$json"
      return 0
    fi
    if [ $(( $(date +%s) - start )) -ge "$ADAPOT_TIMEOUT_SECONDS" ]; then
      die "Timed out waiting for Yano AdaPot epoch $epoch"
    fi
    sleep 2
  done
}

wait_for_store_adapot() {
  local epoch="$1"
  local start json treasury reserves deposits
  start="$(date +%s)"
  while true; do
    json="$(curl -fsS "http://localhost:$YACI_STORE_PORT/api/v1/adapot/epochs/$epoch" 2>/dev/null || true)"
    treasury="$(json_field_or_empty "$json" '.treasury' || true)"
    reserves="$(json_field_or_empty "$json" '.reserves' || true)"
    deposits="$(json_field_or_empty "$json" '.deposits_stake' || true)"
    if [ -n "$treasury" ] && [ -n "$reserves" ] && [ -n "$deposits" ]; then
      printf '%s\n' "$json"
      return 0
    fi
    if [ $(( $(date +%s) - start )) -ge "$ADAPOT_TIMEOUT_SECONDS" ]; then
      die "Timed out waiting for yaci-store AdaPot epoch $epoch"
    fi
    sleep 2
  done
}

print_reward_version() {
  local label="$1"
  local jar="$2"
  local props
  props="$(unzip -p "$jar" META-INF/maven/org.cardanofoundation/cf-rewards-calculation/pom.properties 2>/dev/null || true)"
  if [ -n "$props" ]; then
    log "$label reward library $(printf '%s\n' "$props" | awk -F= '/^version=/{print $2}')"
  fi
}

print_store_reward_version() {
  local jar="$1"
  if ! unzip -tq "$jar" >/dev/null 2>&1; then
    log "yaci-store binary is not a jar; embedded reward library version cannot be inspected from archive"
    return 0
  fi
  local nested="$TEST_DIR/cf-rewards-store-check.jar"
  if unzip -p "$jar" BOOT-INF/lib/cf-rewards-calculation-*.jar > "$nested" 2>/dev/null; then
    local props
    props="$(unzip -p "$nested" META-INF/maven/org.cardanofoundation/cf-rewards-calculation/pom.properties 2>/dev/null || true)"
    if [ -n "$props" ]; then
      log "yaci-store reward library $(printf '%s\n' "$props" | awk -F= '/^version=/{print $2}')"
    fi
  fi
}

prepare_fixture() {
  rm -rf "$RUN_GENESIS_DIR"
  mkdir -p "$RUN_GENESIS_DIR"
  cp -a "$FIXTURE_DIR/." "$RUN_GENESIS_DIR/"

  local pool
  pool="$(jq -r '(.staking.pools // {}) | keys[0] // empty' "$RUN_GENESIS_DIR/shelley-genesis.json")"
  local tmp="$RUN_GENESIS_DIR/shelley-genesis.json.tmp"
  if [ -n "$pool" ]; then
    jq \
      --arg pool "$pool" \
      --argjson margin "$POOL_MARGIN" \
      --argjson epochLength "$EPOCH_LENGTH" \
      --argjson securityParam "$SECURITY_PARAM" \
      '.epochLength = $epochLength
       | .securityParam = $securityParam
       | .staking.pools[$pool].margin = $margin' \
      "$RUN_GENESIS_DIR/shelley-genesis.json" > "$tmp"
  else
    jq \
      --argjson epochLength "$EPOCH_LENGTH" \
      --argjson securityParam "$SECURITY_PARAM" \
      '.epochLength = $epochLength | .securityParam = $securityParam' \
      "$RUN_GENESIS_DIR/shelley-genesis.json" > "$tmp"
  fi
  mv "$tmp" "$RUN_GENESIS_DIR/shelley-genesis.json"

  log "Prepared genesis fixture at $RUN_GENESIS_DIR"
  log "Patched Shelley genesis: epochLength=$EPOCH_LENGTH securityParam=$SECURITY_PARAM poolMargin=$POOL_MARGIN"
}

expected_genesis_deposits() {
  jq -r '
    ((.protocolParams.poolDeposit // 0) * ((.staking.pools // {}) | length))
    + ((.protocolParams.keyDeposit // 0) * ((.staking.stake // {}) | length))
  ' "$RUN_GENESIS_DIR/shelley-genesis.json"
}

copy_genesis_to_haskell() {
  local files="$HASKELL_NODE_DIR/files"
  [ -d "$files" ] || die "Haskell node files dir missing: $files. Run SETUP_HASKELL=1 or scripts/haskell-compatibility/setup-haskell-test-node.sh first."
  cp "$RUN_GENESIS_DIR/shelley-genesis.json" "$files/shelley-genesis.json"
  cp "$RUN_GENESIS_DIR/byron-genesis.json" "$files/byron-genesis.json"
  cp "$RUN_GENESIS_DIR/alonzo-genesis.json" "$files/alonzo-genesis.json"
  cp "$RUN_GENESIS_DIR/conway-genesis.json" "$files/conway-genesis.json"

  local yano_system_start haskell_system_start
  yano_system_start="$(jq -r '.systemStart' "$RUN_GENESIS_DIR/shelley-genesis.json")"
  haskell_system_start="$(jq -r '.systemStart' "$files/shelley-genesis.json")"
  [ "$yano_system_start" = "$haskell_system_start" ] || die "systemStart mismatch after Haskell copy"
  log "Copied Yano-mutated genesis to Haskell files with systemStart=$yano_system_start"
}

write_haskell_topology() {
  cat > "$HASKELL_TOPOLOGY" <<EOF
{
  "bootstrapPeers": [
    {"address": "127.0.0.1", "port": $YANO_N2N_PORT}
  ],
  "localRoots": [
    {
      "accessPoints": [
        {"address": "127.0.0.1", "port": $YANO_N2N_PORT}
      ],
      "valency": 1
    }
  ],
  "publicRoots": [],
  "useLedgerAfterSlot": -1
}
EOF
}

write_yaci_store_properties() {
  mkdir -p "$YACI_STORE_DB_DIR" "$YACI_STORE_LOG_DIR"
  cat > "$YACI_STORE_PROPERTIES" <<EOF
server.port=$YACI_STORE_PORT
store.cardano.host=localhost
store.cardano.port=$HASKELL_PORT
store.cardano.protocol-magic=$NETWORK_MAGIC
store.cardano.byron-genesis-file=$RUN_GENESIS_DIR/byron-genesis.json
store.cardano.shelley-genesis-file=$RUN_GENESIS_DIR/shelley-genesis.json
store.cardano.alonzo-genesis-file=$RUN_GENESIS_DIR/alonzo-genesis.json
store.cardano.conway-genesis-file=$RUN_GENESIS_DIR/conway-genesis.json
spring.datasource.url=jdbc:h2:file:$YACI_STORE_DB_DIR/storedb;MV_STORE=TRUE;AUTO_SERVER=TRUE;AUTO_RECONNECT=TRUE;LOCK_TIMEOUT=120000
spring.datasource.username=sa
spring.datasource.password=password
spring.jpa.properties.hibernate.jdbc.batch_size=20
spring.jpa.properties.hibernate.order_inserts=true
logging.file.name=$YACI_STORE_LOG_DIR/yaci-store.log
store.blocks.epoch-calculation-interval=3600
store.account.enabled=true
store.account.api-enabled=true
store.account.balance-aggregation-enabled=true
store.account.history-cleanup-enabled=false
store.adapot.enabled=true
store.adapot.api-enabled=true
store.governance-aggr.enabled=true
store.governance-aggr.api-enabled=true
store.live.enabled=true
store.epoch.endpoints.epoch.local.enabled=false
spring.batch.job.enabled=false
yaci.store.mcp-server.enabled=false
EOF
}

preflight() {
  [ "${1:-}" != "--help" ] || { usage; exit 0; }
  [ "${1:-}" != "-h" ] || { usage; exit 0; }

  require_command jq
  require_command curl
  require_command java
  require_command unzip

  [ -d "$YANO_REPO" ] || die "YANO_REPO does not exist: $YANO_REPO"
  [ -n "$YACI_STORE_REPO" ] || die "YACI_STORE_REPO is not set and ../yaci-store was not found"
  [ -d "$YACI_STORE_REPO" ] || die "YACI_STORE_REPO does not exist: $YACI_STORE_REPO"
  [ -d "$FIXTURE_DIR" ] || die "FIXTURE_DIR does not exist: $FIXTURE_DIR"

  if [ "$SETUP_HASKELL" = "1" ]; then
    log "Setting up Haskell test node"
    (cd "$YANO_REPO" && bash scripts/haskell-compatibility/setup-haskell-test-node.sh)
  fi

  if [ "$BUILD_YANO" = "1" ]; then
    log "Building Yano jar"
    (cd "$YANO_REPO" && ./gradlew :app:quarkusBuild --refresh-dependencies --no-daemon)
  fi

  if [ "$BUILD_YACI_STORE" = "1" ]; then
    log "Building yaci-store bootJar"
    (cd "$YACI_STORE_REPO" && run_java21 ./gradlew :applications:all:bootJar --refresh-dependencies --no-daemon)
  fi

  [ -f "$YANO_JAR" ] || die "Yano jar not found: $YANO_JAR. Run BUILD_YANO=1 or build it manually."
  [ -x "$CARDANO_NODE" ] || die "cardano-node not found/executable: $CARDANO_NODE. Run SETUP_HASKELL=1 first."
  [ -x "$CARDANO_CLI" ] || die "cardano-cli not found/executable: $CARDANO_CLI. Run SETUP_HASKELL=1 first."

  YACI_STORE_JAR="$(discover_yaci_store_jar)"
  [ -f "$YACI_STORE_JAR" ] || die "yaci-store bootJar not found. Run BUILD_YACI_STORE=1 or set YACI_STORE_JAR."

  if [ "$CLEAN_PORTS" = "1" ]; then
    kill_port "$YANO_HTTP_PORT"
    kill_port "$YANO_N2N_PORT"
    kill_port "$HASKELL_PORT"
    kill_port "$YACI_STORE_PORT"
  fi

  rm -rf "$TEST_DIR"
  mkdir -p "$TEST_DIR" "$LOG_DIR" "$HASKELL_DB_DIR"
}

start_yano() {
  log "Starting Yano from $YANO_JAR"
  (
    cd "$YANO_REPO/app"
    run_yano_java nohup java \
      -Dquarkus.profile=devnet \
      -Dquarkus.http.port="$YANO_HTTP_PORT" \
      -Dyano.server.port="$YANO_N2N_PORT" \
      -Dyano.storage.path="$YANO_CHAINSTATE_DIR" \
      -Dyano.genesis.shelley-genesis-file="$RUN_GENESIS_DIR/shelley-genesis.json" \
      -Dyano.genesis.byron-genesis-file="$RUN_GENESIS_DIR/byron-genesis.json" \
      -Dyano.genesis.alonzo-genesis-file="$RUN_GENESIS_DIR/alonzo-genesis.json" \
      -Dyano.genesis.conway-genesis-file="$RUN_GENESIS_DIR/conway-genesis.json" \
      -Dyano.genesis.protocol-parameters-file="$RUN_GENESIS_DIR/protocol-param.json" \
      -Dyano.block-producer.vrf-skey-file="$RUN_GENESIS_DIR/vrf.skey" \
      -Dyano.block-producer.kes-skey-file="$RUN_GENESIS_DIR/kes.skey" \
      -Dyano.block-producer.opcert-file="$RUN_GENESIS_DIR/opcert.cert" \
      -jar "$YANO_JAR" > "$LOG_DIR/yano.log" 2>&1 &
    printf '%s\n' "$!" > "$TEST_DIR/yano.pid"
  )
  PIDS+=("$(cat "$TEST_DIR/yano.pid")")
  wait_for_http "Yano" "http://localhost:$YANO_HTTP_PORT/q/health/ready" "$STARTUP_TIMEOUT_SECONDS"
  wait_for_http "Yano latest block" "http://localhost:$YANO_HTTP_PORT/api/v1/blocks/latest" "$STARTUP_TIMEOUT_SECONDS"
}

start_haskell_node() {
  copy_genesis_to_haskell
  write_haskell_topology

  log "Starting Haskell cardano-node from $CARDANO_NODE"
  (
    cd "$HASKELL_NODE_DIR"
    nohup "$CARDANO_NODE" run \
      --topology "$HASKELL_TOPOLOGY" \
      --database-path "$HASKELL_DB_DIR" \
      --socket-path "$HASKELL_SOCKET" \
      --host-addr 0.0.0.0 \
      --port "$HASKELL_PORT" \
      --config configuration.json > "$LOG_DIR/haskell-node.log" 2>&1 &
    printf '%s\n' "$!" > "$TEST_DIR/haskell-node.pid"
  )
  PIDS+=("$(cat "$TEST_DIR/haskell-node.pid")")
  wait_for_socket "$HASKELL_SOCKET" "$STARTUP_TIMEOUT_SECONDS"
}

start_yaci_store() {
  write_yaci_store_properties
  log "Starting yaci-store from $YACI_STORE_JAR"
  if unzip -tq "$YACI_STORE_JAR" >/dev/null 2>&1; then
    (
      run_java21 nohup java -jar "$YACI_STORE_JAR" \
        --spring.config.additional-location="$YACI_STORE_PROPERTIES" \
        > "$LOG_DIR/yaci-store-stdout.log" 2>&1 &
      printf '%s\n' "$!" > "$TEST_DIR/yaci-store.pid"
    )
  else
    [ -x "$YACI_STORE_JAR" ] || die "yaci-store binary is not executable: $YACI_STORE_JAR"
    (
      nohup "$YACI_STORE_JAR" \
        --spring.config.additional-location="$YACI_STORE_PROPERTIES" \
        > "$LOG_DIR/yaci-store-stdout.log" 2>&1 &
      printf '%s\n' "$!" > "$TEST_DIR/yaci-store.pid"
    )
  fi
  PIDS+=("$(cat "$TEST_DIR/yaci-store.pid")")
  wait_for_http "yaci-store latest block API" "http://localhost:$YACI_STORE_PORT/api/v1/blocks/latest" "$STARTUP_TIMEOUT_SECONDS"
}

compare_adapot() {
  wait_for_target_epoch

  local ledger_state comparison_epoch
  ledger_state="$(query_haskell_ledger_state)"
  comparison_epoch="$(jq -r '.lastEpoch // empty' <<<"$ledger_state")"
  [ -n "$comparison_epoch" ] || die "Could not read lastEpoch from Haskell ledger-state"

  log "Waiting for Yano and yaci-store AdaPot rows for Haskell lastEpoch=$comparison_epoch"
  local yano_json store_json yano0_json store0_json
  yano_json="$(wait_for_yano_adapot "$comparison_epoch")"
  store_json="$(wait_for_store_adapot "$comparison_epoch")"
  yano0_json="$(wait_for_yano_adapot 0)"
  store0_json="$(wait_for_store_adapot 0)"

  local h_deposits h_treasury h_reserves
  h_deposits="$(jq -r '.stateBefore.esLState.utxoState.deposited' <<<"$ledger_state")"
  h_treasury="$(jq -r '.stateBefore.esChainAccountState.treasury' <<<"$ledger_state")"
  h_reserves="$(jq -r '.stateBefore.esChainAccountState.reserves' <<<"$ledger_state")"

  local y_deposits y_treasury y_reserves
  y_deposits="$(jq -r '.deposits' <<<"$yano_json")"
  y_treasury="$(jq -r '.treasury' <<<"$yano_json")"
  y_reserves="$(jq -r '.reserves' <<<"$yano_json")"

  local s_deposits s_treasury s_reserves
  s_deposits="$(jq -r '.deposits_stake' <<<"$store_json")"
  s_treasury="$(jq -r '.treasury' <<<"$store_json")"
  s_reserves="$(jq -r '.reserves' <<<"$store_json")"

  local expected y0_deposits s0_deposits
  expected="$(expected_genesis_deposits)"
  y0_deposits="$(jq -r '.deposits' <<<"$yano0_json")"
  s0_deposits="$(jq -r '.deposits_stake' <<<"$store0_json")"

  printf '\nCOMPARISON_EPOCH=%s\n' "$comparison_epoch"
  printf 'HASKELL deposits=%s treasury=%s reserves=%s\n' "$h_deposits" "$h_treasury" "$h_reserves"
  printf 'YANO    deposits=%s treasury=%s reserves=%s\n' "$y_deposits" "$y_treasury" "$y_reserves"
  printf 'STORE   deposits=%s treasury=%s reserves=%s\n' "$s_deposits" "$s_treasury" "$s_reserves"
  printf 'GENESIS_DEPOSITS expected=%s yano=%s store=%s\n\n' "$expected" "$y0_deposits" "$s0_deposits"

  local fail=0

  if [ "$h_deposits" = "$y_deposits" ] && [ "$h_deposits" = "$s_deposits" ]; then
    printf 'PASS deposits all == %s\n' "$h_deposits"
  else
    printf 'FAIL deposits mismatch\n'
    fail=1
  fi

  if [ "$h_treasury" = "$y_treasury" ] && [ "$h_treasury" = "$s_treasury" ]; then
    printf 'PASS treasury all == %s\n' "$h_treasury"
  else
    printf 'FAIL treasury mismatch\n'
    fail=1
  fi

  if [ "$h_reserves" = "$y_reserves" ] && [ "$h_reserves" = "$s_reserves" ]; then
    printf 'PASS reserves all == %s\n' "$h_reserves"
  else
    printf 'FAIL reserves mismatch\n'
    fail=1
  fi

  if [ "$y0_deposits" = "$expected" ] && [ "$s0_deposits" = "$expected" ]; then
    printf 'PASS genesis deposits == %s\n' "$expected"
  else
    printf 'FAIL genesis deposits mismatch\n'
    fail=1
  fi

  if [ "$fail" -ne 0 ]; then
    printf '\nLogs:\n  %s\n  %s\n  %s\n' "$LOG_DIR/yano.log" "$LOG_DIR/haskell-node.log" "$LOG_DIR/yaci-store-stdout.log" >&2
    exit 1
  fi
}

main() {
  preflight "${1:-}"
  log "YANO_REPO=$YANO_REPO"
  log "YACI_STORE_REPO=$YACI_STORE_REPO"
  log "HASKELL_NODE_DIR=$HASKELL_NODE_DIR"
  log "TEST_DIR=$TEST_DIR"
  log "Using yaci-store jar: $YACI_STORE_JAR"
  print_reward_version "Yano" "$YANO_JAR"
  print_store_reward_version "$YACI_STORE_JAR"

  prepare_fixture
  start_yano
  start_haskell_node
  start_yaci_store
  compare_adapot

  log "Comparison passed. Logs are in $LOG_DIR"
}

main "$@"
