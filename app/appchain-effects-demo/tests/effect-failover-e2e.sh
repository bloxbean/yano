#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DEMO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
FAULT_MODEL="$SCRIPT_DIR/support/compose-post-ack-fault.yaml"
FAILOVER_MODEL="$SCRIPT_DIR/support/compose-fenced-failover.yaml"
FAILOVER_TOOL="$DEMO_DIR/tools/effect_failover.py"

[ "${YANO_RUN_EFFECT_FAILOVER_E2E:-false}" = true ] || {
  printf '%s\n' 'SKIP: set YANO_RUN_EFFECT_FAILOVER_E2E=true to run the destructive isolated E2E.'
  exit 0
}

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
note() { printf '%s\n' "$*"; }
mode() {
  if stat -c '%a' "$1" >/dev/null 2>&1; then stat -c '%a' "$1"; else stat -f '%Lp' "$1"; fi
}
require() { command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"; }

for command in docker curl jq python3 openssl lsof; do require "$command"; done
docker compose version >/dev/null 2>&1 || fail 'Docker Compose v2 is required'

INSTANCE="${YANO_EFFECT_FAILOVER_INSTANCE:-p17-failover}"
CHAIN_ID="${YANO_EFFECT_FAILOVER_CHAIN_ID:-evidence-chain-p17-failover}"
EVIDENCE_ID="${YANO_EFFECT_FAILOVER_EVIDENCE_ID:-failover-$(openssl rand -hex 8)}"
[[ "$INSTANCE" =~ ^[a-z0-9][a-z0-9-]{0,31}$ ]] \
  || fail 'isolated instance id is malformed'
[[ "$CHAIN_ID" =~ ^[a-z][a-z0-9-]{0,62}$ ]] \
  || fail 'isolated chain id is malformed'
[[ "$EVIDENCE_ID" =~ ^[a-z][a-z0-9-]{0,62}$ ]] \
  || fail 'isolated evidence id is malformed'
export DEMO_HTTP_BASE="${YANO_EFFECT_FAILOVER_HTTP_BASE:-18070}"
export DEMO_UI_PORT="${YANO_EFFECT_FAILOVER_UI_PORT:-18080}"
export DEMO_KAFKA_PORT="${YANO_EFFECT_FAILOVER_KAFKA_PORT:-29092}"
export DEMO_S3_PORT="${YANO_EFFECT_FAILOVER_S3_PORT:-29000}"
export DEMO_IPFS_PORT="${YANO_EFFECT_FAILOVER_IPFS_PORT:-25001}"
export DEMO_PROMETHEUS_PORT="${YANO_EFFECT_FAILOVER_PROMETHEUS_PORT:-29090}"
export DEMO_GRAFANA_PORT="${YANO_EFFECT_FAILOVER_GRAFANA_PORT:-23000}"
export DEMO_SERVER_BASE="${YANO_EFFECT_FAILOVER_SERVER_BASE:-18337}"
export DEMO_OBSERVABILITY=false
export DEMO_CONNECTOR_SUBNET="${YANO_EFFECT_FAILOVER_SUBNET:-172.30.114.0/24}"
export DEMO_S3_IP="${YANO_EFFECT_FAILOVER_S3_IP:-172.30.114.10}"
export DEMO_KUBO_IP="${YANO_EFFECT_FAILOVER_KUBO_IP:-172.30.114.11}"
export DEMO_KAFKA_IP="${YANO_EFFECT_FAILOVER_KAFKA_IP:-172.30.114.12}"
export DEMO_SCENARIO_TIMEOUT_SECONDS="${YANO_EFFECT_FAILOVER_TIMEOUT_SECONDS:-600}"
export DEMO_SCENARIO_POLL_INTERVAL_MILLIS=500

# Bash arithmetic recursively evaluates variable contents. Validate every
# environment-controlled number before the first $((...)) expansion, reject
# leading zeros, and bound digit count before converting explicitly as base 10.
require_decimal_range() {
  local name="$1" value="$2" minimum="$3" maximum="$4" number
  if ! [[ "$value" =~ ^(0|[1-9][0-9]*)$ ]] \
      || [ "${#value}" -gt "${#maximum}" ]; then
    fail "$name must be a canonical bounded decimal integer"
  fi
  number=$((10#$value))
  if [ "$number" -lt "$minimum" ] || [ "$number" -gt "$maximum" ]; then
    fail "$name is outside its supported range"
  fi
}

require_decimal_range DEMO_HTTP_BASE "$DEMO_HTTP_BASE" 1 65533
require_decimal_range DEMO_SERVER_BASE "$DEMO_SERVER_BASE" 1 65533
require_decimal_range DEMO_UI_PORT "$DEMO_UI_PORT" 1 65535
require_decimal_range DEMO_KAFKA_PORT "$DEMO_KAFKA_PORT" 1 65535
require_decimal_range DEMO_S3_PORT "$DEMO_S3_PORT" 1 65535
require_decimal_range DEMO_IPFS_PORT "$DEMO_IPFS_PORT" 1 65535
require_decimal_range DEMO_PROMETHEUS_PORT "$DEMO_PROMETHEUS_PORT" 1 65535
require_decimal_range DEMO_GRAFANA_PORT "$DEMO_GRAFANA_PORT" 1 65535
require_decimal_range DEMO_SCENARIO_TIMEOUT_SECONDS \
  "$DEMO_SCENARIO_TIMEOUT_SECONDS" 10 3600

ROOT_PARENT="$(cd "${YANO_EFFECT_FAILOVER_TMPDIR:-${TMPDIR:-/tmp}}" && pwd -P)"
ROOT="$(mktemp -d "$ROOT_PARENT/yano-effect-failover-e2e.XXXXXX")"
ROOT="$(cd "$ROOT" && pwd -P)"
SENTINEL="$ROOT/.yano-effect-failover-owned-v1"
(umask 077; printf 'yano-effect-failover-e2e-v1\n' > "$SENTINEL")
chmod 600 "$SENTINEL"
export DEMO_DATA_ROOT="$ROOT/data"
export DEMO_SECRET_ROOT="$ROOT/secrets"
export DEMO_RUNTIME_ROOT="$ROOT/runtime"

RUNTIME_ROOT="$DEMO_RUNTIME_ROOT/networks/devnet/$INSTANCE/compose"
SECRET_ROOT="$DEMO_SECRET_ROOT/networks/devnet/$INSTANCE/compose"
DATA_ROOT="$DEMO_DATA_ROOT/networks/devnet/instances/$INSTANCE/compose"
ENV_FILE="$RUNTIME_ROOT/compose.env"
NODE_DIR="$SECRET_ROOT/nodes-compose"
REPORT_DIR="$DATA_ROOT/reports"
FAULT_DIR="$SECRET_ROOT/effect-fault"
DERIVED_DIR="$SECRET_ROOT/failover"
API_KEY_FILE="$SECRET_ROOT/yano-api-key"
RUN_OUTPUT="$ROOT/scenario.out"
PROJECT_NAME=""
RUN_PID=""
CLEANED=false

port_free() {
  ! lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
}

for port in "$DEMO_HTTP_BASE" "$((DEMO_HTTP_BASE + 1))" "$((DEMO_HTTP_BASE + 2))" \
  "$DEMO_UI_PORT" "$DEMO_KAFKA_PORT" "$DEMO_S3_PORT" "$DEMO_IPFS_PORT" \
  "$DEMO_PROMETHEUS_PORT" "$DEMO_GRAFANA_PORT"; do
  port_free "$port" || fail "isolated test port is already in use: $port"
done

EXISTING_DOCKER_SUBNETS="$(docker network inspect $(docker network ls -q) \
  --format '{{range .IPAM.Config}}{{.Subnet}}{{"\n"}}{{end}}' 2>/dev/null)" \
  || fail 'could not inspect existing Docker networks'
[ "${#EXISTING_DOCKER_SUBNETS}" -le 65536 ] \
  || fail 'Docker subnet inventory exceeds the bounded preflight input'
subnet_check=(python3 "$FAILOVER_TOOL" subnet-check --candidate "$DEMO_CONNECTOR_SUBNET")
while IFS= read -r existing; do
  [ -z "$existing" ] || subnet_check+=(--existing "$existing")
done <<< "$EXISTING_DOCKER_SUBNETS"
"${subnet_check[@]}" >/dev/null \
  || fail "isolated connector subnet overlaps an existing Docker network: $DEMO_CONNECTOR_SUBNET"

dc_base() {
  [ -n "$PROJECT_NAME" ] || fail 'Compose project is not resolved'
  docker compose -p "$PROJECT_NAME" --env-file "$ENV_FILE" \
    -f "$DEMO_DIR/compose.yaml" "$@"
}

dc_fault() {
  [ -n "$PROJECT_NAME" ] || fail 'Compose project is not resolved'
  docker compose -p "$PROJECT_NAME" --env-file "$ENV_FILE" \
    -f "$DEMO_DIR/compose.yaml" -f "$FAULT_MODEL" "$@"
}

dc_failover() {
  [ -n "$PROJECT_NAME" ] || fail 'Compose project is not resolved'
  docker compose -p "$PROJECT_NAME" --env-file "$ENV_FILE" \
    -f "$DEMO_DIR/compose.yaml" -f "$FAILOVER_MODEL" "$@"
}

remove_owned_root() {
  python3 - "$ROOT" "$SENTINEL" "$ROOT_PARENT" <<'PY'
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1])
sentinel = Path(sys.argv[2])
resolved = root.resolve(strict=True)
allowed_parent = Path(sys.argv[3]).resolve(strict=True)
if root != resolved or root.is_symlink():
    raise SystemExit("refusing non-canonical cleanup root")
if resolved.parent != allowed_parent:
    raise SystemExit("refusing cleanup outside the temporary root")
if not resolved.name.startswith("yano-effect-failover-e2e."):
    raise SystemExit("refusing unexpected cleanup root")
if sentinel.is_symlink() or sentinel.read_text(encoding="ascii") != "yano-effect-failover-e2e-v1\n":
    raise SystemExit("refusing cleanup without exact ownership sentinel")
shutil.rmtree(resolved)
PY
}

cleanup() {
  local status="$?" remaining=""
  trap - EXIT INT TERM
  set +e
  if [ -n "$RUN_PID" ] && kill -0 "$RUN_PID" >/dev/null 2>&1; then
    kill "$RUN_PID" >/dev/null 2>&1
    wait "$RUN_PID" >/dev/null 2>&1
  fi
  if [ -n "$PROJECT_NAME" ] && [ -f "$ENV_FILE" ]; then
    docker compose -p "$PROJECT_NAME" --env-file "$ENV_FILE" \
      -f "$DEMO_DIR/compose.yaml" down --remove-orphans >/dev/null 2>&1
    remaining="$(docker ps -a --filter "label=com.docker.compose.project=$PROJECT_NAME" -q 2>/dev/null)"
  fi
  if [ -z "$remaining" ] && [ -f "$SENTINEL" ]; then
    remove_owned_root >/dev/null 2>&1 && CLEANED=true
  fi
  if [ "$CLEANED" != true ]; then
    printf 'WARN: isolated test state retained for inspection: %s\n' "$ROOT" >&2
  fi
  exit "$status"
}
trap cleanup EXIT INT TERM

bounded_get() {
  local url="$1" output="$2" max_bytes="$3" temporary bytes
  temporary="$output.tmp"
  rm -f "$temporary"
  if ! (umask 077; ulimit -f 8192; curl --proto '=http' --connect-timeout 2 \
      --max-time 10 --max-filesize "$max_bytes" -fsS "$url" > "$temporary"); then
    rm -f "$temporary"
    return 1
  fi
  [ -f "$temporary" ] && [ ! -L "$temporary" ] || { rm -f "$temporary"; return 1; }
  bytes="$(wc -c < "$temporary" | tr -d ' ')"
  [[ "$bytes" =~ ^[0-9]+$ ]] && [ "$bytes" -gt 0 ] && [ "$bytes" -le "$max_bytes" ] \
    || { rm -f "$temporary"; return 1; }
  mv "$temporary" "$output"
}

bounded_authenticated_get() {
  local url="$1" output="$2" max_bytes="$3" temporary bytes api_key
  [ -f "$API_KEY_FILE" ] && [ ! -L "$API_KEY_FILE" ] \
    && [ "$(mode "$API_KEY_FILE")" = 600 ] || return 1
  api_key="$(tr -d '\r\n' < "$API_KEY_FILE")"
  [[ "$api_key" =~ ^[0-9a-f]{64}$ ]] || { unset api_key; return 1; }
  temporary="$output.tmp"
  rm -f "$temporary"
  if ! (umask 077; ulimit -f 8192; \
      printf 'header = "X-API-Key: %s"\n' "$api_key" \
      | curl --proto '=http' --connect-timeout 2 --max-time 10 \
          --max-filesize "$max_bytes" -fsS --config - "$url" > "$temporary"); then
    unset api_key
    rm -f "$temporary"
    return 1
  fi
  unset api_key
  [ -f "$temporary" ] && [ ! -L "$temporary" ] || { rm -f "$temporary"; return 1; }
  bytes="$(wc -c < "$temporary" | tr -d ' ')"
  [[ "$bytes" =~ ^[0-9]+$ ]] && [ "$bytes" -gt 0 ] && [ "$bytes" -le "$max_bytes" ] \
    || { rm -f "$temporary"; return 1; }
  mv "$temporary" "$output"
}

wait_json() {
  local url="$1" expression="$2" output="$3" deadline=$((SECONDS + 180))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if bounded_get "$url" "$output" 1048576 2>/dev/null \
        && jq -e "$expression" "$output" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  printf 'Cluster agreement timeout (%s):\n' "$phase" >&2
  for node in 0 1 2; do
    if [ -f "$ROOT/$phase-node$node-status.json" ]; then
      jq -c --argjson node "$node" '{node: $node, chainId, running, tipHeight,
          stateRoot, memberKey, members, threshold, stateMachine,
          profileMode: .stateMachineStatus.mode,
          profileEpoch: .stateMachineStatus.currentEpoch,
          activeProfileDigest: .stateMachineStatus.activeProfileDigest,
          catalogReady: .stateMachineStatus.catalogReady,
          currentMembershipDigest: .stateMachineStatus.currentMembershipDigest}' \
        "$ROOT/$phase-node$node-status.json" >&2 || true
    else
      printf '{"node":%s,"status":"unavailable"}\n' "$node" >&2
    fi
  done
  return 1
}

wait_authenticated_json() {
  local url="$1" expression="$2" output="$3" deadline=$((SECONDS + 180))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if bounded_authenticated_get "$url" "$output" 1048576 2>/dev/null \
        && jq -e "$expression" "$output" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

assert_initial_effect_topology() {
  local phase="$1" node port output
  [ "$(grep -Fxc \
      'yano.app-chain.chains[0].effects.executor.enabled=true' \
      "$NODE_DIR/node0.properties")" -eq 1 ] \
    && [ "$(grep -Fxc \
      'yano.app-chain.chains[0].effects.executor.types=object.put,ipfs.pin,kafka.publish' \
      "$NODE_DIR/node0.properties")" -eq 1 ] \
    || fail 'node 0 does not have the exact initial effect partition'
  for node in 1 2; do
    [ "$(grep -Fxc \
        'yano.app-chain.chains[0].effects.executor.enabled=false' \
        "$NODE_DIR/node$node.properties")" -eq 1 ] \
      || fail "node $node is not configured as an executor-disabled member"
  done

  output="$ROOT/$phase-node0-effect-stats.json"
  wait_json \
    "http://127.0.0.1:$DEMO_HTTP_BASE/api/v1/app-chain/chains/$CHAIN_ID/effects/stats" \
    '.stats.enabled == true
      and (.stats | has("runtimeEnabled") | not)
      and (.stats.owner | test("^v1:fp1:[0-9a-f]{64}$"))
      and .stats.executors == ["ipfs-pin", "kafka-publish", "objectstore-s3-object-put"]' \
    "$output" || fail 'node 0 is not the sole initial owner of the exact executor partition'
  for node in 1 2; do
    port=$((DEMO_HTTP_BASE + node))
    output="$ROOT/$phase-node$node-effect-stats.json"
    wait_json \
      "http://127.0.0.1:$port/api/v1/app-chain/chains/$CHAIN_ID/effects/stats" \
      '.stats.enabled == true
        and .stats.runtimeEnabled == false
        and .stats.metricsGeneration == "inactive"
        and (.stats | has("owner") | not)
        and (.stats | has("executors") | not)' \
      "$output" || fail "node $node unexpectedly owns an effect runtime"
  done
}

assert_failover_effect_topology() {
  local phase="$1" node port output
  [ "$(grep -Fxc \
      'yano.app-chain.chains[0].effects.executor.enabled=false' \
      "$FENCED_CONFIG")" -eq 1 ] \
    || fail 'retained node 0 fencing config no longer disables execution'
  [ "$(grep -Fxc \
      'yano.app-chain.chains[0].effects.executor.enabled=true' \
      "$REPLACEMENT_CONFIG")" -eq 1 ] \
    && [ "$(grep -Fxc \
      'yano.app-chain.chains[0].effects.executor.identity=evidence-executor-1' \
      "$REPLACEMENT_CONFIG")" -eq 1 ] \
    && [ "$(grep -Fxc \
      'yano.app-chain.chains[0].effects.executor.types=object.put,ipfs.pin,kafka.publish' \
      "$REPLACEMENT_CONFIG")" -eq 1 ] \
    || fail 'retained node 1 replacement config does not own the exact effect partition'
  [ "$(grep -Fxc \
      'yano.app-chain.chains[0].effects.executor.enabled=false' \
      "$NODE_DIR/node2.properties")" -eq 1 ] \
    || fail 'retained node 2 config no longer disables execution'

  for node in 0 2; do
    port=$((DEMO_HTTP_BASE + node))
    output="$ROOT/$phase-node$node-effect-stats.json"
    wait_json \
      "http://127.0.0.1:$port/api/v1/app-chain/chains/$CHAIN_ID/effects/stats" \
      '.stats.enabled == true
        and .stats.runtimeEnabled == false
        and .stats.metricsGeneration == "inactive"
        and (.stats | has("owner") | not)
        and (.stats | has("executors") | not)' \
      "$output" || fail "node $node unexpectedly overlaps the replacement effect owner"
  done
  output="$ROOT/$phase-node1-effect-stats.json"
  wait_json \
    "http://127.0.0.1:$((DEMO_HTTP_BASE + 1))/api/v1/app-chain/chains/$CHAIN_ID/effects/stats" \
    '.stats.enabled == true
      and (.stats | has("runtimeEnabled") | not)
      and (.stats.owner | test("^v1:fp1:[0-9a-f]{64}$"))
      and .stats.executors == ["ipfs-pin", "kafka-publish", "objectstore-s3-object-put"]' \
    "$output" || fail 'node 1 did not remain sole owner of the exact executor partition'
}

capture_failover_container_ownership() {
  local output="$1" raw id entry
  raw="$output.raw"
  : > "$output.tmp"
  dc_failover ps -aq > "$raw"
  [ -s "$raw" ] || fail 'isolated failover project has no owned containers'
  while IFS= read -r id; do
    [[ "$id" =~ ^[0-9a-f]{12,64}$ ]] \
      || fail 'Compose returned a malformed owned container identity'
    entry="$(docker inspect "$id" | jq -er \
      --arg project "$PROJECT_NAME" --arg id "$id" '
      if length == 1
          and (.[0].Id | test("^[0-9a-f]{64}$") and startswith($id))
          and .[0].Config.Labels["com.docker.compose.project"] == $project
          and (.[0].Config.Labels["com.docker.compose.service"]
            | test("^[a-z0-9][a-z0-9-]*$"))
      then [.[0].Config.Labels["com.docker.compose.service"], .[0].Id] | @tsv
      else error("container ownership mismatch")
      end
    ')" || fail 'container escaped the isolated Compose project ownership'
    printf '%s\n' "$entry" >> "$output.tmp"
  done < "$raw"
  rm -f "$raw"
  LC_ALL=C sort -o "$output.tmp" "$output.tmp"
  [ "$(cut -f1 "$output.tmp" | uniq -d | wc -l | tr -d ' ')" -eq 0 ] \
    || fail 'isolated Compose project has duplicate service ownership'
  [ "$(cut -f1 "$output.tmp")" = "$(printf '%s\n' \
      connector-init evidence-ui kafka kubo leader-warmup rustfs \
      s3-bootstrap yano-0 yano-1 yano-2 | LC_ALL=C sort)" ] \
    || fail 'isolated Compose project does not own the exact full-stack service set'
  mv "$output.tmp" "$output"
}

assert_owned_containers_stopped() {
  local ownership="$1" service id
  while IFS=$'\t' read -r service id; do
    [ -n "$service" ] && [[ "$id" =~ ^[0-9a-f]{64}$ ]] \
      || fail 'retained container ownership record is malformed'
    docker inspect "$id" | jq -e --arg project "$PROJECT_NAME" '
      length == 1
      and .[0].Config.Labels["com.docker.compose.project"] == $project
      and .[0].State.Running == false
    ' >/dev/null || fail "owned service $service did not fully stop"
  done < "$ownership"
}

assert_plugin_operations_all_nodes() {
  local phase="$1" node port summary bundles fingerprint expected_fingerprint=""
  for node in 0 1 2; do
    port=$((DEMO_HTTP_BASE + node))
    summary="$ROOT/$phase-node$node-plugin-operations.json"
    bundles="$ROOT/$phase-node$node-plugin-bundles.json"
    wait_authenticated_json \
      "http://127.0.0.1:$port/api/v1/plugin-operations" \
      '.catalogFingerprint | test("^sha256:[0-9a-f]{64}$")' "$summary" \
      || fail "node $node plugin operations summary is unavailable"
    wait_authenticated_json \
      "http://127.0.0.1:$port/api/v1/plugin-operations/bundles?limit=100" \
      '(.items | map(select(.selected)) | map(.id)) == [
          "com.bloxbean.cardano.yano.appchain.evidence-profile",
          "com.bloxbean.cardano.yano.appchain.evidence-registry",
          "com.bloxbean.cardano.yano.appchain.ipfs",
          "com.bloxbean.cardano.yano.appchain.kafka",
          "com.bloxbean.cardano.yano.appchain.objectstore.s3",
          "com.bloxbean.cardano.yano.appchain.stdlib"]
        and (.items | map(select(.selected)) | all(
          .selectionStatus == "SELECTED"
          and (.lifecycle == "VALIDATED" or .lifecycle == "ACTIVE")
          and (.health == "UNKNOWN" or .health == "UP")
          and .failure.code == "NONE"
          and .metricsStale == false))
        and ([.items[] | select(.selected) | .contributionCount] | add) == 13
        and .nextAfter == null' "$bundles" \
      || fail "node $node plugin inventory is not the exact selected demo catalog"
    fingerprint="$(jq -r '.catalogFingerprint' "$summary")"
    jq -e --arg fingerprint "$fingerprint" '
      .catalogFingerprint == $fingerprint
      and (.generation | type == "number" and . >= 1)
      and (.capturedAtEpochMillis | type == "number" and . > 0)
      and .pluginApiMajor == 1
      and .pluginApiLevel >= 1
      and .totals.selectedBundles == 7
      and .totals.failedBundles == 0
      and .totals.degradedBundles == 0
      and .totals.staleSources == 0
    ' "$summary" >/dev/null || fail "node $node plugin operations snapshot is unhealthy"
    jq -e --arg fingerprint "$fingerprint" '.catalogFingerprint == $fingerprint' \
      "$bundles" >/dev/null || fail "node $node plugin inventory is from another catalog"
    if [ -z "$expected_fingerprint" ]; then
      expected_fingerprint="$fingerprint"
    else
      [ "$fingerprint" = "$expected_fingerprint" ] \
        || fail 'plugin catalog fingerprints differ across cluster members'
    fi
  done
}

assert_metrics_all_nodes() {
  local phase="$1" node port output
  for node in 0 1 2; do
    port=$((DEMO_HTTP_BASE + node))
    output="$ROOT/$phase-node$node-metrics.prom"
    bounded_get "http://127.0.0.1:$port/q/metrics" "$output" 4194304 \
      || fail "node $node metrics endpoint is unavailable or exceeds its bound"
    python3 - "$output" "$CHAIN_ID" <<'PY' \
      || fail "node $node is missing app-chain, effect, or plugin metrics evidence"
import math
from pathlib import Path
import sys

path = Path(sys.argv[1])
chain = sys.argv[2]
text = path.read_text(encoding="utf-8")


def sample(name, required_label):
    matches = []
    for line in text.splitlines():
        if not line.startswith(name + "{") or required_label not in line:
            continue
        try:
            value = float(line.rsplit(None, 1)[1])
        except (IndexError, ValueError):
            raise SystemExit(1)
        if not math.isfinite(value) or value < 0:
            raise SystemExit(1)
        matches.append(value)
    if len(matches) != 1:
        raise SystemExit(1)
    return matches[0]


sample("yano_appchain_tip_height", f'chain="{chain}"')
sample("yano_appchain_effects_open", f'chain="{chain}"')
if sample("yano_plugin_bundles", 'state="selected"') != 7:
    raise SystemExit(1)
PY
  done
}

wait_cluster_agreement() {
  local phase="$1" output="$2" deadline=$((SECONDS + 180)) node port valid
  local status0="$ROOT/$phase-node0-status.json"
  local status1="$ROOT/$phase-node1-status.json"
  local status2="$ROOT/$phase-node2-status.json"
  while [ "$SECONDS" -lt "$deadline" ]; do
    valid=true
    for node in 0 1 2; do
      port=$((DEMO_HTTP_BASE + node))
      bounded_get \
        "http://127.0.0.1:$port/api/v1/app-chain/chains/$CHAIN_ID/status" \
        "$ROOT/$phase-node$node-status.json" 1048576 2>/dev/null || valid=false
    done
    if [ "$valid" = true ] && jq -s -e --arg chain "$CHAIN_ID" '
        length == 3
        and all(.[];
          .chainId == $chain and .running == true
          and (.tipHeight | type == "number" and . >= 1)
          and (.stateRoot | test("^[0-9a-f]{64}$"))
          and (.memberKey | test("^[0-9a-f]{64}$"))
          and .members == 3 and .threshold == 2
          and .stateMachine == "composite"
          and .stateMachineStatus.mode == "governed"
          and .stateMachineStatus.currentEpoch == 0
          and (.stateMachineStatus.activeProfileDigest
            | test("^[0-9a-f]{64}$"))
          and (.stateMachineStatus as $machineStatus
            | ($machineStatus.catalogDigests
              | index($machineStatus.activeProfileDigest)) != null)
          and .stateMachineStatus.catalogReady == true
          and (.stateMachineStatus.currentMembershipDigest
            | test("^[0-9a-f]{64}$")))
        and ([.[].tipHeight] | unique | length) == 1
        and ([.[].stateRoot] | unique | length) == 1
        and ([.[].memberKey] | unique | length) == 3
      ' "$status0" "$status1" "$status2" >/dev/null 2>&1; then
      jq -S -c -s '[.[] | {chainId, running, tipHeight, stateRoot, memberKey,
          members, threshold, stateMachine, stateMachineStatus: {
            mode: .stateMachineStatus.mode,
            currentEpoch: .stateMachineStatus.currentEpoch,
            activeProfileDigest: .stateMachineStatus.activeProfileDigest,
            catalogDigests: .stateMachineStatus.catalogDigests,
            catalogReady: .stateMachineStatus.catalogReady,
            currentMembershipDigest: .stateMachineStatus.currentMembershipDigest}}]' \
        "$status0" "$status1" "$status2" \
        > "$output.tmp"
      mv "$output.tmp" "$output"
      return 0
    fi
    sleep 1
  done
  return 1
}

assert_evidence_ui_report() {
  local phase="$1" expected="$2" output
  output="$ROOT/$phase-evidence-ui-latest.json"
  bounded_get "http://127.0.0.1:$DEMO_UI_PORT/api/v1/reports/latest" \
    "$output" 1048576 || fail 'evidence UI latest-report API is unavailable or unbounded'
  cmp -s "$LATEST_REPORT" "$output" \
    || fail 'evidence UI report bytes differ from retained latest.json'
  [ -z "$expected" ] || cmp -s "$expected" "$output" \
    || fail 'evidence UI report changed across the retained-stack restart'
  jq -e --arg evidence "$EVIDENCE_ID" --arg chain "$CHAIN_ID" '
    .schemaVersion == 1
    and .evidenceId == $evidence
    and .outcome == "PASS" and .failureCode == null
    and .chain.chainId == $chain
    and .chain.businessStatus == "READY"
    and .chain.membersVerified == 3
    and .chain.finalityThreshold == 2
    and .chain.effectProofsVerified == 3
    and .storage.objectStateVerified == true
    and .storage.ipfsPinVerified == true
    and .kafka.topic == "evidence.available.v1"
    and .kafka.partition == 0
    and .kafka.eventVerified == true
  ' "$output" >/dev/null || fail 'evidence UI report is not semantically bound to this scenario'
}

audit_kafka_exact() {
  local expected="$1" effect_id="$2" output="$3" stdout_tmp stderr_tmp
  [[ "$expected" =~ ^[0-9]+$ ]] && [ "$expected" -le 16 ] || return 1
  [[ "$effect_id" =~ ^[0-9a-f]{64}$ ]] || return 1
  stdout_tmp="$output.tmp"
  stderr_tmp="$output.stderr"
  rm -f "$stdout_tmp" "$stderr_tmp"
  if ! (ulimit -f 128; dc_base run --rm --no-deps -T connector-init \
      audit-kafka --config /run/demo/runner.properties \
      --expected-records "$expected" --expected-effect-id "$effect_id") \
      >"$stdout_tmp" 2>"$stderr_tmp"; then
    tail -c 8192 "$stderr_tmp" >&2 || true
    return 1
  fi
  [ -f "$stdout_tmp" ] && [ ! -L "$stdout_tmp" ] \
    && [ "$(wc -c < "$stdout_tmp" | tr -d ' ')" -le 8192 ] \
    && [ "$(wc -l < "$stdout_tmp" | tr -d ' ')" -eq 1 ] \
    || return 1
  jq -e --arg id "$effect_id" --argjson expected "$expected" '
    type == "object"
    and ((keys | sort) == (["beginningOffset", "endOffset", "partition",
      "recordCount", "records", "schemaVersion", "topic"] | sort))
    and .schemaVersion == 1
    and .topic == "evidence.available.v1"
    and .partition == 0
    and .beginningOffset == 0
    and .endOffset == $expected
    and .recordCount == $expected
    and (.records | type == "array" and length == $expected)
    and (.records | all(.[];
      (keys | sort) == ["effectId", "offset", "recordDigest"]
      and .effectId == $id
      and (.recordDigest | test("^[0-9a-f]{64}$"))))
    and ((.records | map(.recordDigest) | unique | length) == 1)
    and ((.records | [.[].offset]) == [range(0; $expected)])
  ' "$stdout_tmp" >/dev/null || return 1
  rm -f "$stderr_tmp"
  mv "$stdout_tmp" "$output"
}

note "Starting isolated failover E2E in $ROOT"
"$DEMO_DIR/demo.sh" config --deployment compose --network devnet \
  --instance "$INSTANCE" --chain-id "$CHAIN_ID" --evidence-id "$EVIDENCE_ID" \
  --continuation explicit --machine composite \
  > "$ROOT/preflight-compose.yml"
python3 - "$ENV_FILE" <<'PY' \
  || fail 'preflight Compose environment is not a private regular owner file'
import os
from pathlib import Path
import stat
import sys

path = Path(sys.argv[1])
info = path.lstat()
if (path.is_symlink() or not stat.S_ISREG(info.st_mode)
        or info.st_uid != os.geteuid() or info.st_nlink != 1
        or stat.S_IMODE(info.st_mode) != 0o600 or not 1 <= info.st_size <= 65_536):
    raise SystemExit(1)
PY
[ "$(grep -c '^DEMO_PROJECT_NAME=' "$ENV_FILE")" -eq 1 ] \
  || fail 'preflight Compose environment has no unique project name'
PROJECT_NAME="$(sed -n 's/^DEMO_PROJECT_NAME=//p' "$ENV_FILE")"
[[ "$PROJECT_NAME" =~ ^yano-effects-devnet-${INSTANCE}-[0-9a-f]{8}$ ]] \
  || fail "unexpected isolated Compose project: $PROJECT_NAME"

"$DEMO_DIR/demo.sh" up --deployment compose --network devnet \
  --instance "$INSTANCE" --chain-id "$CHAIN_ID" --evidence-id "$EVIDENCE_ID" \
  --continuation explicit --machine composite

[ -f "$ENV_FILE" ] && [ "$(mode "$ENV_FILE")" = 600 ] \
  || fail 'generated Compose environment is not private mode 0600'
[ "$(sed -n 's/^DEMO_PROJECT_NAME=//p' "$ENV_FILE")" = "$PROJECT_NAME" ] \
  || fail 'Compose project changed between preflight and startup'
[ "$(sed -n 's/^demo.evidence-id=//p' "$RUNTIME_ROOT/runner-compose.properties")" \
    = "$EVIDENCE_ID" ] || fail 'fresh evidence id was not rendered'

assert_initial_effect_topology initial
assert_plugin_operations_all_nodes initial
assert_metrics_all_nodes initial

mkdir -p "$FAULT_DIR" "$DERIVED_DIR"
chmod 700 "$FAULT_DIR" "$DERIVED_DIR"
export DEMO_EFFECT_FAULT_DIRECTORY="$FAULT_DIR"
dc_fault config --quiet >/dev/null
dc_fault up --detach --no-deps --force-recreate --wait --wait-timeout 180 yano-0
FAULT_NODE0_ID="$(dc_fault ps -q yano-0)"
[ -n "$FAULT_NODE0_ID" ] || fail 'fault-armed node 0 container is missing'
docker inspect "$FAULT_NODE0_ID" | jq -e '
  .[0].Config.Env | any(startswith("JAVA_OPTS=")
    and contains("yano.test.effect-runtime.post-confirmed-pause=v1"))
' >/dev/null || fail 'node 0 did not receive the explicit test-only fault opt-in'

note "Running fresh scenario $EVIDENCE_ID and waiting at the post-ack boundary"
"$DEMO_DIR/demo.sh" run --deployment compose --network devnet \
  --instance "$INSTANCE" --chain-id "$CHAIN_ID" --evidence-id "$EVIDENCE_ID" \
  --continuation explicit --machine composite \
  >"$RUN_OUTPUT" 2>&1 &
RUN_PID="$!"

SIGNAL="$FAULT_DIR/acknowledged-before-result-v1"
deadline=$((SECONDS + DEMO_SCENARIO_TIMEOUT_SECONDS))
while [ ! -f "$SIGNAL" ] && [ "$SECONDS" -lt "$deadline" ]; do
  kill -0 "$RUN_PID" >/dev/null 2>&1 \
    || { sed -n '1,240p' "$RUN_OUTPUT" >&2; fail 'scenario exited before the fault marker'; }
  sleep 1
done
[ -f "$SIGNAL" ] || fail 'post-ack test marker was not published'
[ "$(mode "$SIGNAL")" = 600 ] || fail 'post-ack marker is not mode 0600'

EFFECT_ID="$(sed -n 's/^effectIdHash=//p' "$SIGNAL")"
EFFECT_HEIGHT="$(sed -n 's/^height=//p' "$SIGNAL")"
EFFECT_ORDINAL="$(sed -n 's/^ordinal=//p' "$SIGNAL")"
[[ "$EFFECT_ID" =~ ^[0-9a-f]{64}$ ]] || fail 'fault marker effect id is malformed'
[[ "$EFFECT_HEIGHT" =~ ^[1-9][0-9]*$ ]] || fail 'fault marker height is malformed'
[[ "$EFFECT_ORDINAL" =~ ^[0-9]+$ ]] || fail 'fault marker ordinal is malformed'
[ "$(grep -c '^type=kafka.publish$' "$SIGNAL")" -eq 1 ] \
  || fail 'fault marker does not identify kafka.publish'

ACK_EFFECT="$ROOT/effect-at-ack.json"
bounded_get \
  "http://127.0.0.1:$DEMO_HTTP_BASE/api/v1/app-chain/chains/$CHAIN_ID/effects/$EFFECT_HEIGHT/$EFFECT_ORDINAL" \
  "$ACK_EFFECT" 1048576 || fail 'could not read the bounded effect state at acknowledgement'
jq -e --arg id "$EFFECT_ID" '
  .record.type == "kafka.publish"
  and .record.effectIdHashHex == $id
  and ((.execution.status // "PENDING") != "DONE")
' "$ACK_EFFECT" >/dev/null || fail 'effect result was persisted before the fault fence'
audit_kafka_exact 1 "$EFFECT_ID" "$ROOT/kafka-at-ack.json" \
  || fail 'broker does not contain exactly one effect-id-bound pre-crash record'

note 'Fencing the old owner after acknowledgement and before result persistence'
OLD_OWNER_ID="$FAULT_NODE0_ID"
dc_fault stop --timeout 2 yano-0 >/dev/null
[ "$(docker inspect -f '{{.State.Running}}' "$OLD_OWNER_ID")" = false ] \
  || fail 'old executor process is still running after the fence'
audit_kafka_exact 1 "$EFFECT_ID" "$ROOT/kafka-after-fence.json" \
  || fail 'old owner made an external call after it was fenced'

python3 "$FAILOVER_TOOL" derive \
  --node0 "$NODE_DIR/node0.properties" --node1 "$NODE_DIR/node1.properties" \
  --output-directory "$DERIVED_DIR" > "$ROOT/derived.env"
FENCED_CONFIG="$(sed -n 's/^FENCED_CONFIG=//p' "$ROOT/derived.env")"
REPLACEMENT_CONFIG="$(sed -n 's/^REPLACEMENT_CONFIG=//p' "$ROOT/derived.env")"
[ "$(mode "$FENCED_CONFIG")" = 600 ] \
  && [ "$(mode "$REPLACEMENT_CONFIG")" = 600 ] \
  || fail 'derived failover configuration is not mode 0600'
python3 "$FAILOVER_TOOL" verify --fenced "$FENCED_CONFIG" \
  --replacement "$REPLACEMENT_CONFIG" >/dev/null
export DEMO_FENCED_NODE0_CONFIG="$FENCED_CONFIG"
export DEMO_REPLACEMENT_NODE1_CONFIG="$REPLACEMENT_CONFIG"

dc_failover config --quiet >/dev/null
dc_failover up --detach --no-deps --force-recreate --wait --wait-timeout 180 yano-0 yano-1
NEW_NODE0_ID="$(dc_failover ps -q yano-0)"
REPLACEMENT_ID="$(dc_failover ps -q yano-1)"
[ -n "$NEW_NODE0_ID" ] && [ -n "$REPLACEMENT_ID" ] \
  || fail 'fenced proposer or replacement executor did not start'
[ "$NEW_NODE0_ID" != "$OLD_OWNER_ID" ] \
  || fail 'old executor container identity was reused'
if docker inspect "$OLD_OWNER_ID" >/dev/null 2>&1; then
  fail 'old executor container still exists after fenced recreation'
fi

docker inspect "$NEW_NODE0_ID" | jq -e --arg source "$FENCED_CONFIG" '
  (.[0].Mounts | any(.Destination == "/run/demo/node.properties" and .Source == $source))
  and (.[0].Config.Env | all(contains("yano.test.effect-runtime") | not))
' >/dev/null || fail 'restarted proposer is not bound to the disabled private config'
docker inspect "$REPLACEMENT_ID" | jq -e \
  --arg source "$REPLACEMENT_CONFIG" --arg network "${PROJECT_NAME}_connectors" '
  (.[0].Mounts | any(.Destination == "/run/demo/node.properties" and .Source == $source))
  and (.[0].NetworkSettings.Networks | has($network))
' >/dev/null || fail 'replacement executor lacks its private config or connector network'

NODE0_STATS="$ROOT/node0-stats.json"
NODE1_EFFECT="$ROOT/node1-quarantined.json"
wait_json \
  "http://127.0.0.1:$DEMO_HTTP_BASE/api/v1/app-chain/chains/$CHAIN_ID/effects/stats" \
  '.stats.runtimeEnabled == false' "$NODE0_STATS" \
  || fail 'restarted proposer did not report its executor as disabled'
wait_json \
  "http://127.0.0.1:$((DEMO_HTTP_BASE + 1))/api/v1/app-chain/chains/$CHAIN_ID/effects/$EFFECT_HEIGHT/$EFFECT_ORDINAL" \
  '.record.type == "kafka.publish" and .execution.status == "QUARANTINED"' \
  "$NODE1_EFFECT" || fail 'replacement did not quarantine the historical open effect'

note "Replacement is sole owner; explicitly requeueing $EFFECT_HEIGHT/$EFFECT_ORDINAL"
[ -f "$API_KEY_FILE" ] && [ ! -L "$API_KEY_FILE" ] \
  && [ "$(mode "$API_KEY_FILE")" = 600 ] || fail 'API key file is unsafe'
API_KEY="$(tr -d '\r\n' < "$API_KEY_FILE")"
[[ "$API_KEY" =~ ^[0-9a-f]{64}$ ]] || fail 'API key file is malformed'
REQUEUE_RESPONSE="$ROOT/requeue.json"
REQUEUE_STATUS="$(curl --connect-timeout 3 --max-time 10 -sS -o "$REQUEUE_RESPONSE" \
  --proto '=http' --max-filesize 1048576 -w '%{http_code}' --config - -X POST \
  "http://127.0.0.1:$((DEMO_HTTP_BASE + 1))/api/v1/app-chain/chains/$CHAIN_ID/effects/$EFFECT_HEIGHT/$EFFECT_ORDINAL/requeue" <<EOF
header = "X-API-Key: $API_KEY"
EOF
)"
unset API_KEY
[ -f "$REQUEUE_RESPONSE" ] && [ ! -L "$REQUEUE_RESPONSE" ] \
  && [ "$(wc -c < "$REQUEUE_RESPONSE" | tr -d ' ')" -le 1048576 ] \
  || fail 'operator requeue response is missing, unsafe, or oversized'
[ "$REQUEUE_STATUS" = 200 ] && jq -e '.requeued == true' "$REQUEUE_RESPONSE" >/dev/null \
  || fail 'operator requeue was rejected'

set +e
wait "$RUN_PID"
RUN_STATUS="$?"
set -e
RUN_PID=""
if [ "$RUN_STATUS" -ne 0 ]; then
  sed -n '1,300p' "$RUN_OUTPUT" >&2
  fail "scenario failed after fenced handoff (status $RUN_STATUS)"
fi

audit_kafka_exact 2 "$EFFECT_ID" "$ROOT/kafka-after-retry.json" \
  || fail 'expected exactly two physical records bound to the same logical effect id'

FINAL_EFFECTS="$ROOT/final-effects.json"
bounded_get \
  "http://127.0.0.1:$((DEMO_HTTP_BASE + 1))/api/v1/app-chain/chains/$CHAIN_ID/effects?fromHeight=0&limit=100" \
  "$FINAL_EFFECTS" 1048576 || fail 'could not read the bounded final effect inventory'
jq -e --arg id "$EFFECT_ID" '
  [.effects[] | select(.type == "kafka.publish" and .effectIdHashHex == $id)] | length == 1
' "$FINAL_EFFECTS" >/dev/null \
  || fail 'app-chain contains more than one logical Kafka effect'

REPORT_COUNT="$(find "$REPORT_DIR" -maxdepth 1 -type f -name 'report-*.json' | wc -l | tr -d ' ')"
[ "$REPORT_COUNT" -eq 1 ] \
  || fail 'scenario did not produce exactly one primary report for the fresh data root'
PRIMARY_REPORT="$(find "$REPORT_DIR" -maxdepth 1 -type f -name 'report-*.json' -print)"
LATEST_REPORT="$REPORT_DIR/latest.json"
[ -f "$LATEST_REPORT" ] && [ ! -L "$LATEST_REPORT" ] \
  || fail 'scenario latest report is missing or unsafe'
cmp -s "$PRIMARY_REPORT" "$LATEST_REPORT" \
  || fail 'latest report does not identify the same scenario outcome as the primary report'
jq -e --arg id "$EVIDENCE_ID" '
  def passed($name):
    ([.checks[] | select(.name == $name and .status == "PASS")] | length) == 1;
  .evidenceId == $id
  and .outcome == "PASS"
  and .failureCode == null
  and passed("AUTHENTICATED_STORAGE_RESULTS")
  and passed("THREE_NODE_STATE_AGREEMENT")
  and passed("COMPOSED_EFFECT_PROOFS")
  and passed("KAFKA_ACKNOWLEDGEMENT_AND_EVENT")
  and passed("APP_CHAIN_MEMBER_ANCHOR_OBSERVATION")
' "$PRIMARY_REPORT" >/dev/null \
  || fail 'scenario report did not prove the expected failover closure checks'

assert_failover_effect_topology pre-restart
RETAINED_EFFECT_OWNER="$(jq -r '.stats.owner' \
  "$ROOT/pre-restart-node1-effect-stats.json")"
[[ "$RETAINED_EFFECT_OWNER" =~ ^v1:fp1:[0-9a-f]{64}$ ]] \
  || fail 'pre-restart replacement owner fingerprint is malformed'
assert_evidence_ui_report pre-restart ""
RETAINED_REPORT="$ROOT/retained-latest-before-restart.json"
cp "$LATEST_REPORT" "$RETAINED_REPORT"
chmod 600 "$RETAINED_REPORT"
[ "$(wc -c < "$RETAINED_REPORT" | tr -d ' ')" -le 1048576 ] \
  || fail 'retained report snapshot exceeds its bound'

PRE_RESTART_STATE="$ROOT/pre-restart-cluster-state.json"
wait_cluster_agreement pre-restart "$PRE_RESTART_STATE" \
  || fail 'all three nodes did not converge before the retained-stack restart'
audit_kafka_exact 2 "$EFFECT_ID" "$ROOT/kafka-before-restart.json" \
  || fail 'Kafka changed before the retained-stack restart'

note 'Stopping and restarting the complete isolated stack with retained data'
"$DEMO_DIR/demo.sh" probe --deployment compose --network devnet \
  --instance "$INSTANCE" --chain-id "$CHAIN_ID" --evidence-id "$EVIDENCE_ID" \
  --continuation explicit --machine composite
# Keep the authoritative demo lease/identity markers live while every owned
# container is stopped. This fences concurrent use of the shared L1 state; the
# final demo.sh stop remains the sole operation that releases that lease.
RESTART_OWNERSHIP_BEFORE="$ROOT/restart-container-ownership-before.tsv"
RESTART_OWNERSHIP_AFTER="$ROOT/restart-container-ownership-after.tsv"
dc_failover config --quiet >/dev/null
capture_failover_container_ownership "$RESTART_OWNERSHIP_BEFORE"
dc_failover stop --timeout 30 >/dev/null
assert_owned_containers_stopped "$RESTART_OWNERSHIP_BEFORE"

# Restart through the same fenced overlay and the already verified private
# configs. A base demo.sh up here would silently fall ownership back to node 0.
dc_failover up --detach --wait --wait-timeout 360
"$DEMO_DIR/demo.sh" probe --deployment compose --network devnet \
  --instance "$INSTANCE" --chain-id "$CHAIN_ID" --evidence-id "$EVIDENCE_ID" \
  --continuation explicit --machine composite
capture_failover_container_ownership "$RESTART_OWNERSHIP_AFTER"
cmp -s "$RESTART_OWNERSHIP_BEFORE" "$RESTART_OWNERSHIP_AFTER" \
  || fail 'retained restart recreated or reassigned an owned Compose container'

for node in 0 1 2; do
  wait_json "http://127.0.0.1:$((DEMO_HTTP_BASE + node))/q/health/ready" \
    '.status == "UP"' "$ROOT/restarted-node$node-readiness.json" \
    || fail "node $node did not recover readiness after retained-stack restart"
done
assert_failover_effect_topology restarted
[ "$(jq -r '.stats.owner' "$ROOT/restarted-node1-effect-stats.json")" \
    = "$RETAINED_EFFECT_OWNER" ] \
  || fail 'replacement effect owner fingerprint changed across restart'
assert_plugin_operations_all_nodes restarted
assert_metrics_all_nodes restarted

POST_RESTART_STATE="$ROOT/post-restart-cluster-state.json"
wait_cluster_agreement post-restart "$POST_RESTART_STATE" \
  || fail 'all three nodes did not recover the retained app-chain state'
cmp -s "$PRE_RESTART_STATE" "$POST_RESTART_STATE" \
  || fail 'app-chain height, state root, or member identity changed across restart'
[ -f "$LATEST_REPORT" ] && [ ! -L "$LATEST_REPORT" ] \
  && cmp -s "$RETAINED_REPORT" "$LATEST_REPORT" \
  || fail 'retained latest.json changed across restart'
[ "$(find "$REPORT_DIR" -maxdepth 1 -type f -name 'report-*.json' \
    | wc -l | tr -d ' ')" -eq 1 ] \
  || fail 'retained-stack restart changed the primary report inventory'
assert_evidence_ui_report post-restart "$RETAINED_REPORT"
audit_kafka_exact 2 "$EFFECT_ID" "$ROOT/kafka-after-restart.json" \
  || fail 'Kafka physical record count changed across retained-stack restart'

FINAL_EFFECTS_AFTER_RESTART="$ROOT/final-effects-after-restart.json"
bounded_get \
  "http://127.0.0.1:$((DEMO_HTTP_BASE + 1))/api/v1/app-chain/chains/$CHAIN_ID/effects?fromHeight=0&limit=100" \
  "$FINAL_EFFECTS_AFTER_RESTART" 1048576 \
  || fail 'restarted owner could not read retained effects'
jq -e --arg id "$EFFECT_ID" '
  [.effects[] | select(.type == "kafka.publish" and .effectIdHashHex == $id)] | length == 1
' "$FINAL_EFFECTS_AFTER_RESTART" >/dev/null \
  || fail 'restarted cluster changed the logical Kafka effect inventory'

note 'PASS: fenced failover, plugin/metrics/UI evidence, and retained-stack restart are stable.'
"$DEMO_DIR/demo.sh" stop --deployment compose --network devnet \
  --instance "$INSTANCE" --chain-id "$CHAIN_ID" --evidence-id "$EVIDENCE_ID" \
  --continuation explicit --machine composite
PROJECT_NAME=""
remove_owned_root
CLEANED=true
trap - EXIT INT TERM
