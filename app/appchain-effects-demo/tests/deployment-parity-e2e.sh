#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
DEMO_DIR="$(cd "$SCRIPT_DIR/.." && pwd -P)"
SUBNET_TOOL="$DEMO_DIR/tools/effect_failover.py"
# shellcheck disable=SC1091
. "$DEMO_DIR/config/images.env"

[ "${YANO_RUN_DEPLOYMENT_PARITY_E2E:-false}" = true ] || {
  printf '%s\n' \
    'SKIP: set YANO_RUN_DEPLOYMENT_PARITY_E2E=true to run the destructive isolated E2E.'
  exit 0
}

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
note() { printf '%s\n' "$*"; }
require() { command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"; }
mode() {
  if stat -c '%a' "$1" >/dev/null 2>&1; then stat -c '%a' "$1"; else stat -f '%Lp' "$1"; fi
}

for command in docker curl jq python3 openssl lsof java; do require "$command"; done
docker compose version >/dev/null 2>&1 || fail 'Docker Compose v2 is required'

COMPOSE_INSTANCE="${YANO_DEPLOYMENT_PARITY_COMPOSE_INSTANCE:-p17-parity-compose}"
HOST_INSTANCE="${YANO_DEPLOYMENT_PARITY_HOST_INSTANCE:-p17-parity-host}"
COMPOSE_CHAIN="${YANO_DEPLOYMENT_PARITY_COMPOSE_CHAIN:-evidence-chain-p17-parity-compose}"
HOST_CHAIN="${YANO_DEPLOYMENT_PARITY_HOST_CHAIN:-evidence-chain-p17-parity-host}"
RUN_TOKEN="$(openssl rand -hex 6)"
COMPOSE_EVIDENCE="${YANO_DEPLOYMENT_PARITY_COMPOSE_EVIDENCE:-parity-c-$RUN_TOKEN}"
HOST_EVIDENCE="${YANO_DEPLOYMENT_PARITY_HOST_EVIDENCE:-parity-h-$RUN_TOKEN}"
CONTINUATION_MODE="${YANO_DEPLOYMENT_PARITY_CONTINUATION_MODE:-explicit}"
MACHINE_MODE="${YANO_DEPLOYMENT_PARITY_MACHINE:-standalone}"

for instance in "$COMPOSE_INSTANCE" "$HOST_INSTANCE"; do
  [[ "$instance" =~ ^[a-z0-9][a-z0-9-]{0,31}$ ]] \
    || fail 'isolated instance id is malformed'
done
[ "$COMPOSE_INSTANCE" != "$HOST_INSTANCE" ] || fail 'deployment instances must be distinct'
for chain in "$COMPOSE_CHAIN" "$HOST_CHAIN"; do
  [[ "$chain" =~ ^[a-z][a-z0-9-]{0,62}$ ]] \
    || fail 'isolated chain id is malformed'
done
# Deployment-local chain identities stay distinct so every API/report assertion
# also proves that host and Compose evidence was not accidentally cross-wired.
[ "$COMPOSE_CHAIN" != "$HOST_CHAIN" ] \
  || fail 'coexisting deployment parity requires distinct local chain ids'
for evidence in "$COMPOSE_EVIDENCE" "$HOST_EVIDENCE"; do
  [[ "$evidence" =~ ^[a-z][a-z0-9-]{0,62}$ ]] \
    || fail 'isolated evidence id is malformed'
done
# Both deployments process the same immutable sample and workflow. They need
# distinct business ids only because they intentionally coexist on one backend:
# the object connector binds target id and effect id into immutable destination
# metadata, so reusing its key under the required distinct target aliases must
# correctly be rejected as a destination conflict rather than called parity.
[ "$COMPOSE_EVIDENCE" != "$HOST_EVIDENCE" ] \
  || fail 'coexisting deployment parity requires distinct external object keys'
case "$CONTINUATION_MODE" in
  explicit)
    EXPECTED_FINALITY_BUNDLES=2
    EXPECTED_CONTINUATION_CHECK=EXPLICIT_NOTIFY_CONTINUATION
    ;;
  direct)
    EXPECTED_FINALITY_BUNDLES=1
    EXPECTED_CONTINUATION_CHECK=DIRECT_RESULT_CONTINUATION
    ;;
  *) fail 'continuation mode must be exactly explicit or direct';;
esac
case "$MACHINE_MODE" in
  standalone)
    EXPECTED_STATE_MACHINE=evidence-registry
    EXPECTED_WORKFLOW_CHECK=DIRECT_EVIDENCE_SUBMISSION
    ;;
  composite)
    EXPECTED_STATE_MACHINE=composite
    EXPECTED_WORKFLOW_CHECK=COMPOSITE_EVIDENCE_RELEASE_WORKFLOW
    ;;
  role)
    EXPECTED_STATE_MACHINE=role-evidence
    EXPECTED_WORKFLOW_CHECK=ROLE_GATED_EVIDENCE_RELEASE_WORKFLOW
    ;;
  *) fail 'machine mode must be exactly standalone, composite, or role';;
esac

COMPOSE_HTTP_BASE="${YANO_DEPLOYMENT_PARITY_COMPOSE_HTTP_BASE:-28070}"
COMPOSE_UI_PORT="${YANO_DEPLOYMENT_PARITY_COMPOSE_UI_PORT:-28080}"
COMPOSE_SERVER_BASE="${YANO_DEPLOYMENT_PARITY_COMPOSE_SERVER_BASE:-28337}"
HOST_HTTP_BASE="${YANO_DEPLOYMENT_PARITY_HOST_HTTP_BASE:-28170}"
HOST_UI_PORT="${YANO_DEPLOYMENT_PARITY_HOST_UI_PORT:-28180}"
HOST_SERVER_BASE="${YANO_DEPLOYMENT_PARITY_HOST_SERVER_BASE:-28437}"
CONNECTOR_KAFKA_PORT="${YANO_DEPLOYMENT_PARITY_KAFKA_PORT:-39092}"
CONNECTOR_S3_PORT="${YANO_DEPLOYMENT_PARITY_S3_PORT:-39000}"
CONNECTOR_IPFS_PORT="${YANO_DEPLOYMENT_PARITY_IPFS_PORT:-35001}"
CONNECTOR_PROMETHEUS_PORT="${YANO_DEPLOYMENT_PARITY_PROMETHEUS_PORT:-39090}"
CONNECTOR_GRAFANA_PORT="${YANO_DEPLOYMENT_PARITY_GRAFANA_PORT:-33000}"
CONNECTOR_SUBNET="${YANO_DEPLOYMENT_PARITY_SUBNET:-172.30.116.0/24}"
CONNECTOR_S3_IP="${YANO_DEPLOYMENT_PARITY_S3_IP:-172.30.116.10}"
CONNECTOR_KUBO_IP="${YANO_DEPLOYMENT_PARITY_KUBO_IP:-172.30.116.11}"
CONNECTOR_KAFKA_IP="${YANO_DEPLOYMENT_PARITY_KAFKA_IP:-172.30.116.12}"
SCENARIO_TIMEOUT="${YANO_DEPLOYMENT_PARITY_TIMEOUT_SECONDS:-600}"

# Validate every environment-controlled number before Bash arithmetic. Bash
# arithmetic recursively evaluates input and therefore must never see raw text.
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

require_decimal_range COMPOSE_HTTP_BASE "$COMPOSE_HTTP_BASE" 1 65533
require_decimal_range COMPOSE_SERVER_BASE "$COMPOSE_SERVER_BASE" 1 65533
require_decimal_range HOST_HTTP_BASE "$HOST_HTTP_BASE" 1 65533
require_decimal_range HOST_SERVER_BASE "$HOST_SERVER_BASE" 1 65533
require_decimal_range COMPOSE_UI_PORT "$COMPOSE_UI_PORT" 1 65535
require_decimal_range HOST_UI_PORT "$HOST_UI_PORT" 1 65535
require_decimal_range CONNECTOR_KAFKA_PORT "$CONNECTOR_KAFKA_PORT" 1 65535
require_decimal_range CONNECTOR_S3_PORT "$CONNECTOR_S3_PORT" 1 65535
require_decimal_range CONNECTOR_IPFS_PORT "$CONNECTOR_IPFS_PORT" 1 65535
require_decimal_range CONNECTOR_PROMETHEUS_PORT "$CONNECTOR_PROMETHEUS_PORT" 1 65535
require_decimal_range CONNECTOR_GRAFANA_PORT "$CONNECTOR_GRAFANA_PORT" 1 65535
require_decimal_range SCENARIO_TIMEOUT "$SCENARIO_TIMEOUT" 10 3600

ROOT_PARENT="$(cd "${YANO_DEPLOYMENT_PARITY_TMPDIR:-${TMPDIR:-/tmp}}" && pwd -P)"
ROOT="$(mktemp -d "$ROOT_PARENT/yano-deployment-parity-e2e.XXXXXX")"
ROOT="$(cd "$ROOT" && pwd -P)"
SENTINEL="$ROOT/.yano-deployment-parity-owned-v1"
(umask 077; printf 'yano-deployment-parity-e2e-v1\n' > "$SENTINEL")
chmod 600 "$SENTINEL"

remove_owned_root() {
  python3 - "$ROOT" "$SENTINEL" "$ROOT_PARENT" <<'PY'
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1])
sentinel = Path(sys.argv[2])
resolved = root.resolve(strict=True)
parent = Path(sys.argv[3]).resolve(strict=True)
if (root != resolved or root.is_symlink() or resolved.parent != parent
        or not resolved.name.startswith("yano-deployment-parity-e2e.")):
    raise SystemExit("refusing unexpected cleanup root")
if (sentinel.is_symlink()
        or sentinel.read_text(encoding="ascii") != "yano-deployment-parity-e2e-v1\n"):
    raise SystemExit("refusing cleanup without exact ownership sentinel")
shutil.rmtree(resolved)
PY
}

remove_owned_image() {
  local image="$1" claimed="$2" expected_id="$3" actual_id
  [ "$claimed" = true ] || return 0
  [ -n "$image" ] || return 1
  if docker image inspect "$image" >/dev/null 2>&1; then
    actual_id="$(docker image inspect --format '{{.Id}}' -- "$image")" || return 1
    if [ -n "$expected_id" ] && [ "$actual_id" != "$expected_id" ]; then
      return 1
    fi
    docker image rm "$image" >/dev/null 2>&1 || return 1
  fi
  ! docker image inspect "$image" >/dev/null 2>&1
}

remove_owned_images() {
  remove_owned_image "${NODE_IMAGE:-}" "${NODE_IMAGE_CLAIMED:-false}" \
    "${NODE_IMAGE_EXPECTED_ID:-}" || return 1
  remove_owned_image "${RUNNER_IMAGE:-}" "${RUNNER_IMAGE_CLAIMED:-false}" \
    "${RUNNER_IMAGE_EXPECTED_ID:-}" || return 1
  remove_owned_image "${KUBO_IMAGE:-}" "${KUBO_IMAGE_CLAIMED:-false}" \
    "${KUBO_IMAGE_EXPECTED_ID:-}" || return 1
  remove_owned_image "${RUSTFS_IMAGE:-}" "${RUSTFS_IMAGE_CLAIMED:-false}" \
    "${RUSTFS_IMAGE_EXPECTED_ID:-}" || return 1
}

early_cleanup() {
  local status="$?" uncertain=false cleaned=false
  trap - EXIT INT TERM
  set +e
  remove_owned_images || uncertain=true
  if [ "$uncertain" = false ] && [ -f "$SENTINEL" ]; then
    remove_owned_root >/dev/null 2>&1 && cleaned=true || uncertain=true
  fi
  if [ "$cleaned" != true ]; then
    printf 'WARN: isolated preflight diagnostics retained after uncertain cleanup: %s\n' \
      "$ROOT" >&2
  fi
  if [ "$uncertain" = true ] && [ "$status" -eq 0 ]; then status=1; fi
  exit "$status"
}
trap early_cleanup EXIT INT TERM

COMPOSE_DATA_BASE="$ROOT/compose-data"
COMPOSE_SECRET_BASE="$ROOT/compose-secrets"
COMPOSE_RUNTIME_BASE="$ROOT/compose-runtime"
HOST_DATA_BASE="$ROOT/host-data"
HOST_SECRET_BASE="$ROOT/host-secrets"
HOST_RUNTIME_BASE="$ROOT/host-runtime"
mkdir -m 700 "$COMPOSE_DATA_BASE" "$COMPOSE_SECRET_BASE" "$COMPOSE_RUNTIME_BASE" \
  "$HOST_DATA_BASE" "$HOST_SECRET_BASE" "$HOST_RUNTIME_BASE"
python3 - "$ROOT" "$COMPOSE_DATA_BASE" "$COMPOSE_SECRET_BASE" "$COMPOSE_RUNTIME_BASE" \
  "$HOST_DATA_BASE" "$HOST_SECRET_BASE" "$HOST_RUNTIME_BASE" <<'PY' \
  || fail 'the six deployment roots are not canonical pairwise-disjoint owned directories'
import os
from pathlib import Path
import stat
import sys

root = Path(sys.argv[1]).resolve(strict=True)
paths = [Path(raw) for raw in sys.argv[2:]]
resolved = []
for path in paths:
    info = path.lstat()
    actual = path.resolve(strict=True)
    if (path.is_symlink() or not stat.S_ISDIR(info.st_mode)
            or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o700
            or actual.parent != root):
        raise SystemExit(1)
    resolved.append(actual)
if len(set(resolved)) != 6:
    raise SystemExit(1)
for index, left in enumerate(resolved):
    for right in resolved[index + 1:]:
        if left in right.parents or right in left.parents:
            raise SystemExit(1)
PY

COMPOSE_RUNTIME="$COMPOSE_RUNTIME_BASE/networks/devnet/$COMPOSE_INSTANCE/compose"
COMPOSE_SECRETS="$COMPOSE_SECRET_BASE/networks/devnet/$COMPOSE_INSTANCE/compose"
COMPOSE_DATA="$COMPOSE_DATA_BASE/networks/devnet/instances/$COMPOSE_INSTANCE/compose"
COMPOSE_ENV="$COMPOSE_RUNTIME/compose.env"
COMPOSE_REPORTS="$COMPOSE_DATA/reports"
COMPOSE_API_KEY="$COMPOSE_SECRETS/yano-api-key"
HOST_RUNTIME="$HOST_RUNTIME_BASE/networks/devnet/$HOST_INSTANCE/host"
HOST_SECRETS="$HOST_SECRET_BASE/networks/devnet/$HOST_INSTANCE/host"
HOST_DATA="$HOST_DATA_BASE/networks/devnet/instances/$HOST_INSTANCE/host"
HOST_REPORTS="$HOST_DATA/reports"
HOST_API_KEY="$HOST_SECRETS/yano-api-key"
COMPOSE_PROJECT=""
COMPOSE_PREPARED=false
COMPOSE_RUNNING=false
HOST_PREPARED=false
HOST_RUNNING=false
CLEANED=false

NODE_IMAGE="yano-adr013-parity-node:$RUN_TOKEN"
RUNNER_IMAGE="yano-adr013-parity-runner:$RUN_TOKEN"
KUBO_IMAGE="${DEMO_KUBO_IMAGE:?missing pinned Kubo demo image}"
RUSTFS_IMAGE="${DEMO_RUSTFS_IMAGE:?missing pinned RustFS demo image}"
NODE_IMAGE_CLAIMED=false
RUNNER_IMAGE_CLAIMED=false
KUBO_IMAGE_CLAIMED=false
RUSTFS_IMAGE_CLAIMED=false
NODE_IMAGE_EXPECTED_ID=""
RUNNER_IMAGE_EXPECTED_ID=""
KUBO_IMAGE_EXPECTED_ID=""
RUSTFS_IMAGE_EXPECTED_ID=""
for image in "$NODE_IMAGE" "$RUNNER_IMAGE"; do
  docker image inspect "$image" >/dev/null 2>&1 \
    && fail "random isolated image tag already exists: $image"
done
NODE_IMAGE_CLAIMED=true
RUNNER_IMAGE_CLAIMED=true

port_free() { ! lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1; }
PORTS="$COMPOSE_HTTP_BASE $((COMPOSE_HTTP_BASE + 1)) $((COMPOSE_HTTP_BASE + 2)) \
$COMPOSE_UI_PORT $COMPOSE_SERVER_BASE $((COMPOSE_SERVER_BASE + 1)) $((COMPOSE_SERVER_BASE + 2)) \
$HOST_HTTP_BASE $((HOST_HTTP_BASE + 1)) $((HOST_HTTP_BASE + 2)) \
$HOST_UI_PORT $HOST_SERVER_BASE $((HOST_SERVER_BASE + 1)) $((HOST_SERVER_BASE + 2)) \
$CONNECTOR_KAFKA_PORT $CONNECTOR_S3_PORT $CONNECTOR_IPFS_PORT \
$CONNECTOR_PROMETHEUS_PORT $CONNECTOR_GRAFANA_PORT"
python3 - $PORTS <<'PY' || fail 'isolated deployment ports overlap'
import sys
ports = [int(raw) for raw in sys.argv[1:]]
if len(ports) != len(set(ports)):
    raise SystemExit(1)
PY
for port in $PORTS; do
  port_free "$port" || fail "isolated test port is already in use: $port"
done

EXISTING_DOCKER_SUBNETS="$(docker network inspect $(docker network ls -q) \
  --format '{{range .IPAM.Config}}{{.Subnet}}{{"\n"}}{{end}}' 2>/dev/null)" \
  || fail 'could not inspect existing Docker networks'
[ "${#EXISTING_DOCKER_SUBNETS}" -le 65536 ] \
  || fail 'Docker subnet inventory exceeds the bounded preflight input'
subnet_check=(python3 "$SUBNET_TOOL" subnet-check --candidate "$CONNECTOR_SUBNET")
while IFS= read -r existing; do
  [ -z "$existing" ] || subnet_check+=(--existing "$existing")
done <<< "$EXISTING_DOCKER_SUBNETS"
"${subnet_check[@]}" >/dev/null \
  || fail "isolated connector subnet overlaps an existing Docker network: $CONNECTOR_SUBNET"

activate_compose() {
  export DEMO_DATA_ROOT="$COMPOSE_DATA_BASE"
  export DEMO_SECRET_ROOT="$COMPOSE_SECRET_BASE"
  export DEMO_RUNTIME_ROOT="$COMPOSE_RUNTIME_BASE"
  export DEMO_HTTP_BASE="$COMPOSE_HTTP_BASE"
  export DEMO_UI_PORT="$COMPOSE_UI_PORT"
  export DEMO_SERVER_BASE="$COMPOSE_SERVER_BASE"
  export DEMO_KAFKA_PORT="$CONNECTOR_KAFKA_PORT"
  export DEMO_S3_PORT="$CONNECTOR_S3_PORT"
  export DEMO_IPFS_PORT="$CONNECTOR_IPFS_PORT"
  export DEMO_PROMETHEUS_PORT="$CONNECTOR_PROMETHEUS_PORT"
  export DEMO_GRAFANA_PORT="$CONNECTOR_GRAFANA_PORT"
  export DEMO_CONNECTOR_SUBNET="$CONNECTOR_SUBNET"
  export DEMO_S3_IP="$CONNECTOR_S3_IP"
  export DEMO_KUBO_IP="$CONNECTOR_KUBO_IP"
  export DEMO_KAFKA_IP="$CONNECTOR_KAFKA_IP"
  export DEMO_SCENARIO_TIMEOUT_SECONDS="$SCENARIO_TIMEOUT"
  export DEMO_SCENARIO_POLL_INTERVAL_MILLIS=500
  export DEMO_SKIP_BUILD=false
  export DEMO_YANO_IMAGE="$NODE_IMAGE"
  export DEMO_RUNNER_IMAGE="$RUNNER_IMAGE"
  export DEMO_KUBO_IMAGE="$KUBO_IMAGE"
  export DEMO_RUSTFS_IMAGE="$RUSTFS_IMAGE"
  unset DEMO_HOST_S3_ENDPOINT DEMO_HOST_IPFS_API_URL DEMO_HOST_KAFKA_BOOTSTRAP_SERVERS
  unset DEMO_HOST_S3_TARGET_ID DEMO_HOST_IPFS_TARGET_ID DEMO_HOST_KAFKA_TARGET_ID
  unset DEMO_HOST_S3_RUNNER_ACCESS_KEY_FILE DEMO_HOST_S3_RUNNER_SECRET_KEY_FILE
  unset DEMO_HOST_S3_EXECUTOR_ACCESS_KEY_FILE DEMO_HOST_S3_EXECUTOR_SECRET_KEY_FILE
}

activate_host() {
  export DEMO_DATA_ROOT="$HOST_DATA_BASE"
  export DEMO_SECRET_ROOT="$HOST_SECRET_BASE"
  export DEMO_RUNTIME_ROOT="$HOST_RUNTIME_BASE"
  export DEMO_HTTP_BASE="$HOST_HTTP_BASE"
  export DEMO_UI_PORT="$HOST_UI_PORT"
  export DEMO_SERVER_BASE="$HOST_SERVER_BASE"
  export DEMO_KAFKA_PORT="$CONNECTOR_KAFKA_PORT"
  export DEMO_S3_PORT="$CONNECTOR_S3_PORT"
  export DEMO_IPFS_PORT="$CONNECTOR_IPFS_PORT"
  export DEMO_PROMETHEUS_PORT="$CONNECTOR_PROMETHEUS_PORT"
  export DEMO_GRAFANA_PORT="$CONNECTOR_GRAFANA_PORT"
  export DEMO_CONNECTOR_SUBNET="$CONNECTOR_SUBNET"
  export DEMO_S3_IP="$CONNECTOR_S3_IP"
  export DEMO_KUBO_IP="$CONNECTOR_KUBO_IP"
  export DEMO_KAFKA_IP="$CONNECTOR_KAFKA_IP"
  export DEMO_SCENARIO_TIMEOUT_SECONDS="$SCENARIO_TIMEOUT"
  export DEMO_SCENARIO_POLL_INTERVAL_MILLIS=500
  export DEMO_SKIP_BUILD=false
  export DEMO_HOST_S3_ENDPOINT="http://127.0.0.1:$CONNECTOR_S3_PORT"
  export DEMO_HOST_IPFS_API_URL="http://127.0.0.1:$CONNECTOR_IPFS_PORT"
  export DEMO_HOST_KAFKA_BOOTSTRAP_SERVERS="127.0.0.1:$CONNECTOR_KAFKA_PORT"
  export DEMO_HOST_S3_TARGET_ID="s3-host-parity-$RUN_TOKEN"
  export DEMO_HOST_IPFS_TARGET_ID="ipfs-host-parity-$RUN_TOKEN"
  export DEMO_HOST_KAFKA_TARGET_ID="kafka-host-parity-$RUN_TOKEN"
  export DEMO_HOST_S3_RUNNER_ACCESS_KEY_FILE="$COMPOSE_SECRETS/s3-runner-access-key"
  export DEMO_HOST_S3_RUNNER_SECRET_KEY_FILE="$COMPOSE_SECRETS/s3-runner-secret-key"
  export DEMO_HOST_S3_EXECUTOR_ACCESS_KEY_FILE="$COMPOSE_SECRETS/s3-executor-access-key"
  export DEMO_HOST_S3_EXECUTOR_SECRET_KEY_FILE="$COMPOSE_SECRETS/s3-executor-secret-key"
}

demo_compose() {
  "$DEMO_DIR/demo.sh" "$1" --deployment compose --network devnet \
    --instance "$COMPOSE_INSTANCE" --chain-id "$COMPOSE_CHAIN" \
    --evidence-id "$COMPOSE_EVIDENCE" --continuation "$CONTINUATION_MODE" \
    --machine "$MACHINE_MODE"
}

demo_host() {
  "$DEMO_DIR/demo.sh" "$1" --deployment host --network devnet \
    --instance "$HOST_INSTANCE" --chain-id "$HOST_CHAIN" \
    --evidence-id "$HOST_EVIDENCE" --continuation "$CONTINUATION_MODE" \
    --machine "$MACHINE_MODE"
}

dc_compose() {
  [ -n "$COMPOSE_PROJECT" ] && [ -f "$COMPOSE_ENV" ] \
    || fail 'Compose project is not resolved'
  docker compose -p "$COMPOSE_PROJECT" --env-file "$COMPOSE_ENV" \
    -f "$DEMO_DIR/compose.yaml" "$@"
}

capture_owned_image_ids() {
  local actual_rustfs_id
  NODE_IMAGE_EXPECTED_ID="$(docker image inspect --format '{{.Id}}' -- "$NODE_IMAGE")" \
    || return 1
  RUNNER_IMAGE_EXPECTED_ID="$(docker image inspect --format '{{.Id}}' -- "$RUNNER_IMAGE")" \
    || return 1
  KUBO_IMAGE_EXPECTED_ID="$(docker image inspect --format '{{.Id}}' -- "$KUBO_IMAGE")" \
    || return 1
  actual_rustfs_id="$(docker image inspect --format '{{.Id}}' -- "$RUSTFS_IMAGE")" \
    || return 1
  [[ "$NODE_IMAGE_EXPECTED_ID" =~ ^sha256:[0-9a-f]{64}$ ]] \
    && [[ "$RUNNER_IMAGE_EXPECTED_ID" =~ ^sha256:[0-9a-f]{64}$ ]] \
    && [[ "$KUBO_IMAGE_EXPECTED_ID" =~ ^sha256:[0-9a-f]{64}$ ]] \
    && [[ "$actual_rustfs_id" =~ ^sha256:[0-9a-f]{64}$ ]] \
    || return 1
  if [ -n "$RUSTFS_IMAGE_EXPECTED_ID" ] \
      && [ "$actual_rustfs_id" != "$RUSTFS_IMAGE_EXPECTED_ID" ]; then
    return 1
  fi
  RUSTFS_IMAGE_EXPECTED_ID="$actual_rustfs_id"
}

cleanup() {
  local status="$?" uncertain=false remaining=""
  trap - EXIT INT TERM
  set +e
  if [ "$HOST_PREPARED" = true ]; then
    activate_host
    demo_host stop >/dev/null 2>&1 || uncertain=true
    HOST_RUNNING=false
  fi
  if [ "$COMPOSE_PREPARED" = true ]; then
    activate_compose
    demo_compose stop >/dev/null 2>&1 || uncertain=true
    COMPOSE_RUNNING=false
  fi
  if [ -n "$COMPOSE_PROJECT" ]; then
    remaining="$(docker ps -a --filter \
      "label=com.docker.compose.project=$COMPOSE_PROJECT" -q 2>/dev/null)" \
      || uncertain=true
    [ -z "$remaining" ] || uncertain=true
  fi
  if [ "$uncertain" = false ] && [ -f "$SENTINEL" ]; then
    if remove_owned_images && remove_owned_root >/dev/null 2>&1; then
      CLEANED=true
    else
      uncertain=true
    fi
  fi
  if [ "$CLEANED" != true ]; then
    printf 'WARN: isolated test diagnostics retained after uncertain cleanup: %s\n' \
      "$ROOT" >&2
  fi
  if [ "$uncertain" = true ] && [ "$status" -eq 0 ]; then status=1; fi
  exit "$status"
}
trap cleanup EXIT INT TERM

bounded_get() {
  local url="$1" output="$2" max_bytes="$3" temporary
  temporary="$output.tmp"
  rm -f "$temporary"
  if ! (umask 077; ulimit -f 8192; curl --proto '=http' --connect-timeout 2 \
      --max-time 10 --max-filesize "$max_bytes" -fsS "$url" > "$temporary"); then
    rm -f "$temporary"
    return 1
  fi
  install_bounded_response "$temporary" "$output" "$max_bytes"
}

bounded_authenticated_get() {
  local url="$1" output="$2" max_bytes="$3" api_key_file="$4"
  local temporary api_key
  [ -f "$api_key_file" ] && [ ! -L "$api_key_file" ] \
    && [ "$(mode "$api_key_file")" = 600 ] || return 1
  api_key="$(tr -d '\r\n' < "$api_key_file")"
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
  install_bounded_response "$temporary" "$output" "$max_bytes"
}

install_bounded_response() {
  local temporary="$1" output="$2" max_bytes="$3" bytes
  [ -f "$temporary" ] && [ ! -L "$temporary" ] \
    || { rm -f "$temporary"; return 1; }
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
  return 1
}

wait_authenticated_json() {
  local url="$1" expression="$2" output="$3" key_file="$4"
  local deadline=$((SECONDS + 180))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if bounded_authenticated_get "$url" "$output" 1048576 "$key_file" 2>/dev/null \
        && jq -e "$expression" "$output" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

assert_effect_ownership() {
  local phase="$1" base="$2" chain="$3" node_dir="$4" node port output
  grep -Fxq 'yano.app-chain.chains[0].effects.executor.enabled=true' \
    "$node_dir/node0.properties" \
    && grep -Fxq \
      'yano.app-chain.chains[0].effects.executor.types=object.put,ipfs.pin,kafka.publish' \
      "$node_dir/node0.properties" \
    || fail "$phase node 0 lacks the exact executor partition"
  for node in 1 2; do
    grep -Fxq 'yano.app-chain.chains[0].effects.executor.enabled=false' \
      "$node_dir/node$node.properties" \
      || fail "$phase node $node is not executor-disabled"
  done
  output="$ROOT/$phase-node0-effects.json"
  wait_json "http://127.0.0.1:$base/api/v1/app-chain/chains/$chain/effects/stats" \
    '.stats.enabled == true and (.stats | has("runtimeEnabled") | not)
      and (.stats.owner | test("^v1:fp1:[0-9a-f]{64}$"))
      and .stats.executors == ["ipfs-pin", "kafka-publish", "objectstore-s3-object-put"]' \
    "$output" || fail "$phase node 0 is not the sole exact effect owner"
  for node in 1 2; do
    port=$((base + node))
    output="$ROOT/$phase-node$node-effects.json"
    wait_json "http://127.0.0.1:$port/api/v1/app-chain/chains/$chain/effects/stats" \
      '.stats.enabled == true and .stats.runtimeEnabled == false
        and .stats.metricsGeneration == "inactive"
        and (.stats | has("owner") | not) and (.stats | has("executors") | not)
        and (.stats | has("executorOperations") | not)' \
      "$output" || fail "$phase node $node unexpectedly owns effects"
  done
}

assert_executor_activity() {
  local phase="$1" base="$2" chain="$3" node port output
  output="$ROOT/$phase-node0-executor-activity.json"
  wait_json "http://127.0.0.1:$base/api/v1/app-chain/chains/$chain/effects/stats" \
    '.stats.executorOperations as $operations
      | ($operations | length) == 3
      and (($operations | sort_by(.id) | map(.id)) == [
        "ipfs-pin", "kafka-publish", "objectstore-s3-object-put"])
      and all($operations[];
        (keys == ["attempts", "bundleId", "failureCode", "id", "inFlight",
          "lastFailureAge", "lastSuccessAge", "readiness", "retryableFailures",
          "sampleState", "scheme", "successes", "terminalFailures", "types"])
        and .sampleState == "FRESH" and .readiness == "READY"
        and .attempts >= 1 and .successes >= 1 and .inFlight == 0
        and .retryableFailures == 0 and .terminalFailures == 0
        and .lastSuccessAge != "NEVER" and .failureCode == "NONE")' \
    "$output" || fail "$phase executor activity snapshot is incomplete or unsafe"

  for node in 0 1 2; do
    port=$((base + node))
    output="$ROOT/$phase-node$node-executor-metrics.prom"
    bounded_get "http://127.0.0.1:$port/q/metrics" "$output" 4194304 \
      || fail "$phase node $node executor metrics are unavailable or oversized"
    python3 - "$output" "$chain" "$node" <<'PY' \
      || fail "$phase node $node executor metric inventory is incorrect"
import math
from pathlib import Path
import re
import sys

text = Path(sys.argv[1]).read_text(encoding="utf-8")
chain = sys.argv[2]
node = int(sys.argv[3])
prefix = "yano_appchain_effects_executor_"
expected = {"ipfs-pin", "kafka-publish", "objectstore-s3-object-put"}
samples = []
for line in text.splitlines():
    if not line.startswith(prefix) or line.startswith("#"):
        continue
    try:
        metric, raw_value = line.rsplit(None, 1)
        value = float(raw_value)
    except ValueError:
        raise SystemExit(1)
    if not math.isfinite(value) or value < 0:
        raise SystemExit(1)
    name, _, raw_labels = metric.partition("{")
    labels = dict(re.findall(r'([A-Za-z_][A-Za-z0-9_]*)="([^"\\]*)"', raw_labels))
    samples.append((name, labels, value))

def reject(reason):
    inventory = {}
    for name, labels, value in samples:
        inventory.setdefault(name, []).append({
            "executor": labels.get("executor"),
            "slot": labels.get("slot"),
            "state": labels.get("state"),
            "value": value,
        })
    print(f"executor metrics rejected: {reason}; inventory={inventory}", file=sys.stderr)
    raise SystemExit(1)

if node != 0:
    if samples:
        reject("executor-disabled member exported executor series")
    raise SystemExit(0)

# The exact exporter spelling of base units is not contractual. Cardinality is:
# three fixed executor slots, four readiness states, and a bounded fixed set of
# counters/gauges. Require every operationally essential series and reject any
# dynamic executor label, while leaving room for exporter base-unit variants.
if not 21 <= len(samples) <= 48:
    reject(f"unexpected bounded series count {len(samples)}")
if {labels.get("executor") for _, labels, _ in samples} != expected:
    reject("executor labels differ from the startup-fixed inventory")
if any(labels.get("chain") != chain for _, labels, _ in samples):
    reject("chain label mismatch")

for executor in expected:
    owned = [(name, labels, value) for name, labels, value in samples
             if labels.get("executor") == executor]
    slots = {labels.get("slot") for _, labels, _ in owned}
    if len(slots) != 1 or None in slots:
        reject(f"{executor} does not own one fixed slot")
    readiness = [(labels.get("state"), value) for name, labels, value in owned
                 if name == prefix + "readiness"]
    if sorted(state for state, _ in readiness) != [
            "degraded", "ready", "unavailable", "unknown"]:
        reject(f"{executor} readiness is not one fixed four-state vector")
    if dict(readiness) != {"ready": 1.0, "degraded": 0.0,
                           "unavailable": 0.0, "unknown": 0.0}:
        reject(f"{executor} is not one-hot ready")
    attempts = [value for name, _, value in owned if "attempts" in name]
    successes = [value for name, _, value in owned if "successes" in name]
    in_flight = [value for name, _, value in owned if "in_flight" in name]
    if len(attempts) != 1 or attempts[0] < 1:
        reject(f"{executor} has no positive attempts counter")
    if len(successes) != 1 or successes[0] < 1:
        reject(f"{executor} has no positive successes counter")
    if in_flight != [0.0]:
        reject(f"{executor} in-flight gauge is absent, duplicated, or nonzero")
PY
  done
}

assert_plugin_inventory() {
  local phase="$1" base="$2" key_file="$3" node port summary bundles fingerprint expected=""
  for node in 0 1 2; do
    port=$((base + node))
    summary="$ROOT/$phase-node$node-plugin-summary.json"
    bundles="$ROOT/$phase-node$node-plugin-bundles.json"
    wait_authenticated_json "http://127.0.0.1:$port/api/v1/plugin-operations" \
      '.catalogFingerprint | test("^sha256:[0-9a-f]{64}$")' "$summary" "$key_file" \
      || fail "$phase node $node plugin operations are unavailable"
    wait_authenticated_json \
      "http://127.0.0.1:$port/api/v1/plugin-operations/bundles?limit=100" \
      '(.items | map(select(.selected)) | map(.id)) == [
          "com.bloxbean.cardano.yano.appchain.evidence-profile",
          "com.bloxbean.cardano.yano.appchain.evidence-registry",
          "com.bloxbean.cardano.yano.appchain.ipfs",
          "com.bloxbean.cardano.yano.appchain.kafka",
          "com.bloxbean.cardano.yano.appchain.objectstore.s3",
          "com.bloxbean.cardano.yano.appchain.role-workflow",
          "com.bloxbean.cardano.yano.appchain.stdlib"]
        and (.items | map(select(.selected)) | all(
          .selectionStatus == "SELECTED"
          and (.lifecycle == "VALIDATED" or .lifecycle == "ACTIVE")
          and (.health == "UNKNOWN" or .health == "UP")
          and .failure.code == "NONE" and .metricsStale == false))
        and ([.items[] | select(.selected) | .contributionCount] | add) == 15
        and .nextAfter == null' "$bundles" "$key_file" \
      || fail "$phase node $node plugin inventory differs from the demo catalog"
    fingerprint="$(jq -r '.catalogFingerprint' "$summary")"
    jq -e --arg fingerprint "$fingerprint" '
      .catalogFingerprint == $fingerprint and .pluginApiMajor == 1
      and .pluginApiLevel >= 1 and .totals.selectedBundles == 8
      and .totals.failedBundles == 0 and .totals.degradedBundles == 0
      and .totals.staleSources == 0' "$summary" >/dev/null \
      || fail "$phase node $node plugin summary is unhealthy"
    jq -e --arg fingerprint "$fingerprint" '.catalogFingerprint == $fingerprint' \
      "$bundles" >/dev/null || fail "$phase node $node plugin snapshots disagree"
    if [ -z "$expected" ]; then expected="$fingerprint"; else
      [ "$expected" = "$fingerprint" ] \
        || fail "$phase plugin fingerprints differ across members"
    fi
  done
}

assert_metrics() {
  local phase="$1" base="$2" chain="$3" node port output
  for node in 0 1 2; do
    port=$((base + node))
    output="$ROOT/$phase-node$node-metrics.prom"
    bounded_get "http://127.0.0.1:$port/q/metrics" "$output" 4194304 \
      || fail "$phase node $node metrics are unavailable or oversized"
    python3 - "$output" "$chain" <<'PY' \
      || fail "$phase node $node lacks app-chain/effect/plugin metrics"
import math
from pathlib import Path
import sys

text = Path(sys.argv[1]).read_text(encoding="utf-8")
chain = sys.argv[2]

def sample(name, label):
    matches = []
    for line in text.splitlines():
        if not line.startswith(name + "{") or label not in line:
            continue
        value = float(line.rsplit(None, 1)[1])
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

capture_cluster_state() {
  local phase="$1" base="$2" chain="$3" output="$4" node valid port
  local deadline=$((SECONDS + 180))
  while [ "$SECONDS" -lt "$deadline" ]; do
    valid=true
    for node in 0 1 2; do
      port=$((base + node))
      bounded_get "http://127.0.0.1:$port/api/v1/app-chain/chains/$chain/status" \
        "$ROOT/$phase-node$node-status.json" 1048576 2>/dev/null || valid=false
    done
    if [ "$valid" = true ] && jq -s -e --arg chain "$chain" \
        --arg machine "$EXPECTED_STATE_MACHINE" '
        length == 3 and all(.[];
          .chainId == $chain and .running == true and .tipHeight >= 1
          and (.stateRoot | test("^[0-9a-f]{64}$"))
          and (.memberKey | test("^[0-9a-f]{64}$"))
          and .members == 3 and .threshold == 2
          and .stateMachine == $machine
          and (($machine != "composite" and $machine != "role-evidence") or (
            .stateMachineStatus.mode == "governed"
            and .stateMachineStatus.currentEpoch == 0
            and (.stateMachineStatus.activeProfileDigest
              | test("^[0-9a-f]{64}$"))
            and (.stateMachineStatus as $machineStatus
              | ($machineStatus.catalogDigests
                | index($machineStatus.activeProfileDigest)) != null)
            and .stateMachineStatus.catalogReady == true
            and (.stateMachineStatus.currentMembershipDigest
              | test("^[0-9a-f]{64}$")))))
        and ([.[].tipHeight] | unique | length) == 1
        and ([.[].stateRoot] | unique | length) == 1
        and ([.[].memberKey] | unique | length) == 3' \
        "$ROOT/$phase-node0-status.json" "$ROOT/$phase-node1-status.json" \
        "$ROOT/$phase-node2-status.json" >/dev/null 2>&1; then
      jq -S -c -s '[.[] | {chainId, running, tipHeight, stateRoot, memberKey,
        members, threshold, stateMachine, stateMachineStatus}]' \
        "$ROOT/$phase-node0-status.json" \
        "$ROOT/$phase-node1-status.json" "$ROOT/$phase-node2-status.json" \
        > "$output.tmp"
      mv "$output.tmp" "$output"
      return 0
    fi
    sleep 1
  done
  return 1
}

cluster_state_signature() {
  jq -S -c 'map(del(.tipHeight))' "$1"
}

cluster_tip() {
  jq -e -r '
    ([.[].tipHeight] | unique) as $tips
    | if ($tips | length) == 1 then $tips[0]
      else error("cluster members do not share one tip") end
  ' "$1"
}

assert_replay_advanced_only_tip() {
  local before="$1" after="$2" label="$3" base="$4" chain="$5"
  local before_tip after_tip node port valid deadline=$((SECONDS + 180))
  before_tip="$(cluster_tip "$before")"
  while [ "$SECONDS" -lt "$deadline" ]; do
    [ "$(cluster_state_signature "$before")" = "$(cluster_state_signature "$after")" ] \
      || fail "$label changed app-chain state or membership"
    after_tip="$(cluster_tip "$after")"
    if [ "$after_tip" -gt "$before_tip" ]; then
      return 0
    fi
    sleep 1
    valid=true
    for node in 0 1 2; do
      port=$((base + node))
      bounded_get "http://127.0.0.1:$port/api/v1/app-chain/chains/$chain/status" \
        "$ROOT/replay-wait-$base-node$node-status.json" 1048576 2>/dev/null \
        || valid=false
    done
    if [ "$valid" = true ] && jq -s -e --arg chain "$chain" \
        --arg machine "$EXPECTED_STATE_MACHINE" '
        length == 3 and all(.[];
          .chainId == $chain and .running == true and .tipHeight >= 1
          and (.stateRoot | test("^[0-9a-f]{64}$"))
          and (.memberKey | test("^[0-9a-f]{64}$"))
          and .members == 3 and .threshold == 2
          and .stateMachine == $machine
          and (($machine != "composite" and $machine != "role-evidence") or (
            .stateMachineStatus.mode == "governed"
            and .stateMachineStatus.currentEpoch == 0
            and (.stateMachineStatus.activeProfileDigest
              | test("^[0-9a-f]{64}$"))
            and (.stateMachineStatus as $machineStatus
              | ($machineStatus.catalogDigests
                | index($machineStatus.activeProfileDigest)) != null)
            and .stateMachineStatus.catalogReady == true
            and (.stateMachineStatus.currentMembershipDigest
              | test("^[0-9a-f]{64}$")))))
        and ([.[].tipHeight] | unique | length) == 1
        and ([.[].stateRoot] | unique | length) == 1
        and ([.[].memberKey] | unique | length) == 3' \
        "$ROOT/replay-wait-$base-node0-status.json" \
        "$ROOT/replay-wait-$base-node1-status.json" \
        "$ROOT/replay-wait-$base-node2-status.json" >/dev/null 2>&1; then
      jq -S -c -s '[.[] | {chainId, running, tipHeight, stateRoot, memberKey,
        members, threshold, stateMachine, stateMachineStatus}]' \
        "$ROOT/replay-wait-$base-node0-status.json" \
        "$ROOT/replay-wait-$base-node1-status.json" \
        "$ROOT/replay-wait-$base-node2-status.json" > "$after.tmp"
      mv "$after.tmp" "$after"
    fi
  done
  fail "$label was not recorded at a strictly later consensus tip"
}

assert_report() {
  local report="$1" evidence="$2" chain="$3"
  [ -f "$report" ] && [ ! -L "$report" ] \
    && [ "$(wc -c < "$report" | tr -d ' ')" -le 1048576 ] \
    || fail 'scenario report is missing, unsafe, or oversized'
  jq -e --arg evidence "$evidence" --arg chain "$chain" \
    --arg continuationCheck "$EXPECTED_CONTINUATION_CHECK" \
    --arg workflowCheck "$EXPECTED_WORKFLOW_CHECK" \
    --argjson finalityBundles "$EXPECTED_FINALITY_BUNDLES" '
    def passed($name):
      ([.checks[] | select(.name == $name and .status == "PASS")] | length) == 1;
    .schemaVersion == 1 and .evidenceId == $evidence
    and .outcome == "PASS" and .failureCode == null
    and .chain.chainId == $chain and .chain.businessStatus == "READY"
    and .chain.membersVerified == 3 and .chain.finalityThreshold == 2
    and .chain.stateProofsVerified == 6 and .chain.effectProofsVerified == 3
    and .chain.finalityBundlesVerified == $finalityBundles
    and .storage.objectStateVerified == true and .storage.ipfsPinVerified == true
    and (.storage.objectVersionFingerprint | test("^[0-9a-f]{64}$"))
    and (.storage.cid | startswith("baf"))
    and .kafka.topic == "evidence.available.v1" and .kafka.partition == 0
    and .kafka.offset >= 0 and .kafka.eventVerified == true
    and .anchor.required == true and .anchor.portableLinkageVerified == true
    and .anchor.portableTransactionsVisibleOnAllMembers == true
    and .anchor.portableDatumCommitmentsVerified == true
    and .anchor.memberObservationCovered == true
    and .anchor.memberObservedTransactionVisibleOnAllMembers == true
    and .anchor.memberObservedDatumCommitmentVerified == true
    and ([.checks[].name] | length) == ([.checks[].name] | unique | length)
    and passed("AUTHENTICATED_STORAGE_RESULTS")
    and passed($workflowCheck)
    and passed($continuationCheck)
    and passed("THREE_NODE_STATE_AGREEMENT")
    and passed("COMPOSED_EFFECT_PROOFS")
    and passed("KAFKA_ACKNOWLEDGEMENT_AND_EVENT")
    and passed("PORTABLE_ANCHOR_LINKAGE")
    and passed("PORTABLE_ANCHOR_TXS_VISIBLE_ON_ALL_MEMBERS")
    and passed("PORTABLE_ANCHOR_DATUM_COMMITMENTS_VERIFIED")
    and passed("APP_CHAIN_MEMBER_ANCHOR_OBSERVATION")
    and passed("MEMBER_OBSERVED_ANCHOR_TX_VISIBLE_ON_ALL_MEMBERS")
    and passed("MEMBER_OBSERVED_ANCHOR_DATUM_COMMITMENT_VERIFIED")' "$report" >/dev/null \
    || fail 'scenario report does not close the deployment-neutral acceptance contract'
}

LAST_REPORT=""
run_and_capture() {
  local deployment="$1" report_dir="$2" evidence="$3" chain="$4" label="$5"
  local output scenario report
  output="$ROOT/$label-run.out"
  if [ "$deployment" = compose ]; then
    if ! demo_compose run >"$output" 2>&1; then
      sed -n '1,200p' "$output" >&2
      if [ -f "$report_dir/latest.json" ] && [ ! -L "$report_dir/latest.json" ] \
          && [ "$(wc -c < "$report_dir/latest.json" | tr -d ' ')" -le 1048576 ]; then
        jq -c '{outcome, failureCode, checks}' \
          "$report_dir/latest.json" >&2 || true
      fi
      fail "$label scenario command failed"
    fi
  else
    if ! demo_host run >"$output" 2>&1; then
      sed -n '1,200p' "$output" >&2
      if [ -f "$report_dir/latest.json" ] && [ ! -L "$report_dir/latest.json" ] \
          && [ "$(wc -c < "$report_dir/latest.json" | tr -d ' ')" -le 1048576 ]; then
        jq -c '{outcome, failureCode, checks}' \
          "$report_dir/latest.json" >&2 || true
      fi
      fail "$label scenario command failed"
    fi
  fi
  [ "$(wc -c < "$output" | tr -d ' ')" -le 1048576 ] \
    || fail "$label scenario output exceeds its bound"
  [ "$(grep -c '^PASS command=run scenario=' "$output")" -eq 1 ] \
    || { sed -n '1,200p' "$output" >&2; fail "$label scenario did not return one PASS"; }
  scenario="$(sed -n 's/^PASS command=run scenario=//p' "$output")"
  [[ "$scenario" =~ ^[a-z0-9][a-z0-9-]{0,63}$ ]] \
    || fail "$label scenario id is malformed"
  report="$report_dir/report-$scenario.json"
  assert_report "$report" "$evidence" "$chain"
  [ -f "$report_dir/latest.json" ] && [ ! -L "$report_dir/latest.json" ] \
    && cmp -s "$report" "$report_dir/latest.json" \
    || fail "$label latest report does not identify the completed attempt"
  LAST_REPORT="$report"
}

stable_receipt_signature() {
  jq -S -c '{
    evidenceId,
    chain: (.chain | {chainId, businessStatus}),
    storage: (.storage | {sha256, size, objectVersionFingerprint, cid,
      objectStateVerified, ipfsPinVerified}),
    kafka,
    anchor: (.anchor | {required, portableLinkageVerified,
      portableTransactionsVisibleOnAllMembers, portableDatumCommitmentsVerified,
      memberObservationCovered,
      memberObservedTransactionVisibleOnAllMembers,
      memberObservedDatumCommitmentVerified})
  }' "$1"
}

capture_effect_inventory() {
  local phase="$1" base="$2" chain="$3" output="$4" raw
  raw="$output.raw"
  bounded_get \
    "http://127.0.0.1:$base/api/v1/app-chain/chains/$chain/effects?fromHeight=0&limit=100" \
    "$raw" 1048576 || fail "$phase effect inventory is unavailable or oversized"
  jq -S -c --arg chain "$chain" '
    if (.chainId == $chain
        and (.effects | length) == 3
        and ([.effects[].type] | sort)
          == ["ipfs.pin", "kafka.publish", "object.put"]
        and ([.effects[].effectIdHashHex] | unique | length) == 3
        and all(.effects[];
          .chainId == $chain
          and ((.height | type) == "number" and .height >= 1)
          and ((.ordinal | type) == "number" and .ordinal >= 0)
          and (.effectHashHex | test("^[0-9a-f]{64}$"))
          and (.effectIdHashHex | test("^[0-9a-f]{64}$"))))
    then [.effects[] | {
      chainId, height, ordinal, type, scope, gate, resultPolicy, expiryHeight,
      effectHashHex, effectIdHashHex
    }] | sort_by(.height, .ordinal)
    else error("unexpected logical effect inventory")
    end
  ' "$raw" > "$output.tmp" \
    || fail "$phase does not contain exactly one logical effect of each required type"
  mv "$output.tmp" "$output"
}

semantic_signature() {
  jq -S -c '{
    schemaVersion, outcome, failureCode,
    chain: (.chain | {businessStatus, membersVerified, finalityThreshold,
      stateProofsVerified, effectProofsVerified, finalityBundlesVerified}),
    storage: (.storage | {sha256, size, cid, objectStateVerified, ipfsPinVerified}),
    kafka: (.kafka | {topic, partition, eventVerified}),
    anchor: (.anchor | {required, portableLinkageVerified,
      portableTransactionsVisibleOnAllMembers, portableDatumCommitmentsVerified,
      memberObservationCovered, memberObservedTransactionVisibleOnAllMembers,
      memberObservedDatumCommitmentVerified}),
    checks: ([.checks[] | {name, status}] | sort_by(.name))
  }' "$1"
}

assert_ui_latest() {
  local phase="$1" port="$2" latest="$3" output
  output="$ROOT/$phase-ui-latest.json"
  bounded_get "http://127.0.0.1:$port/api/v1/reports/latest" "$output" 1048576 \
    || fail "$phase evidence UI is unavailable or oversized"
  cmp -s "$latest" "$output" || fail "$phase evidence UI does not serve retained latest.json"
}

artifact_manifest() {
  local runtime="$1" output="$2"
  python3 - "$runtime" "$output" <<'PY'
import hashlib
import json
import os
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve(strict=True)
max_file_bytes = 512 * 1024 * 1024
max_total_bytes = 1024 * 1024 * 1024
expected = [
    "plugins/appchain-ipfs-bundle.jar",
    "plugins/appchain-kafka-bundle.jar",
    "plugins/appchain-objectstore-s3-bundle.jar",
    "runner.jar",
    "yano.jar",
]
result = {}
total = 0
for relative in expected:
    path = root / relative
    info = path.lstat()
    resolved = path.resolve(strict=True)
    if (path.is_symlink() or resolved != path or not path.is_file()
            or info.st_uid != os.geteuid() or info.st_nlink != 1
            or not 1 <= info.st_size <= max_file_bytes):
        raise SystemExit(1)
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
                         | getattr(os, "O_NOFOLLOW", 0))
    try:
        before = os.fstat(descriptor)
        identity = lambda value: (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns)
        if identity(before) != identity(info):
            raise SystemExit(1)
        total += before.st_size
        if total > max_total_bytes:
            raise SystemExit(1)
        digest = hashlib.sha256()
        consumed = 0
        while chunk := os.read(descriptor, 1024 * 1024):
            consumed += len(chunk)
            if consumed > before.st_size:
                raise SystemExit(1)
            digest.update(chunk)
        after = os.fstat(descriptor)
        if consumed != before.st_size or identity(after) != identity(before):
            raise SystemExit(1)
    finally:
        os.close(descriptor)
    result[relative] = {"bytes": before.st_size, "sha256": digest.hexdigest()}
Path(sys.argv[2]).write_text(json.dumps(result, sort_keys=True, separators=(",", ":")) + "\n",
                             encoding="utf-8")
PY
}

verify_live_generation() {
  local phase="$1" base="$2" chain="$3" node_dir="$4" key_file="$5"
  assert_effect_ownership "$phase" "$base" "$chain" "$node_dir"
  assert_plugin_inventory "$phase" "$base" "$key_file"
  assert_metrics "$phase" "$base" "$chain"
}

note "Starting isolated Compose/host parity E2E in $ROOT"
activate_compose
demo_compose prepare
capture_owned_image_ids || fail 'prepared Compose image tags are missing or changed identity'
COMPOSE_PREPARED=true
demo_compose config > "$ROOT/compose-preflight.yaml"
python3 - "$COMPOSE_ENV" <<'PY' \
  || fail 'Compose environment is not a private regular owner file'
import os
from pathlib import Path
import stat
import sys
path = Path(sys.argv[1])
info = path.lstat()
if (path.is_symlink() or not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid()
        or info.st_nlink != 1 or stat.S_IMODE(info.st_mode) != 0o600
        or not 1 <= info.st_size <= 65_536):
    raise SystemExit(1)
PY
[ "$(grep -c '^DEMO_PROJECT_NAME=' "$COMPOSE_ENV")" -eq 1 ] \
  || fail 'Compose environment has no unique project name'
COMPOSE_PROJECT="$(sed -n 's/^DEMO_PROJECT_NAME=//p' "$COMPOSE_ENV")"
[[ "$COMPOSE_PROJECT" =~ ^yano-effects-devnet-${COMPOSE_INSTANCE}-[0-9a-f]{8}$ ]] \
  || fail "unexpected isolated Compose project: $COMPOSE_PROJECT"
[ -z "$(docker ps -a --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" -q)" ] \
  || fail 'random isolated Compose project already owns containers'

demo_compose up
COMPOSE_RUNNING=true
capture_owned_image_ids || fail 'Compose startup changed or lost an owned image tag'
demo_compose probe
verify_live_generation compose-initial "$COMPOSE_HTTP_BASE" "$COMPOSE_CHAIN" \
  "$COMPOSE_SECRETS/nodes-compose" "$COMPOSE_API_KEY"
artifact_manifest "$COMPOSE_RUNTIME" "$ROOT/compose-artifacts.json"

run_and_capture compose "$COMPOSE_REPORTS" "$COMPOSE_EVIDENCE" "$COMPOSE_CHAIN" compose-first
COMPOSE_FIRST="$LAST_REPORT"
assert_executor_activity compose-first "$COMPOSE_HTTP_BASE" "$COMPOSE_CHAIN"
capture_effect_inventory compose-first "$COMPOSE_HTTP_BASE" "$COMPOSE_CHAIN" \
  "$ROOT/compose-first-effects.json"
capture_cluster_state compose-first "$COMPOSE_HTTP_BASE" "$COMPOSE_CHAIN" \
  "$ROOT/compose-first-state.json" \
  || fail 'Compose members did not agree after the first scenario'
run_and_capture compose "$COMPOSE_REPORTS" "$COMPOSE_EVIDENCE" "$COMPOSE_CHAIN" compose-replay
COMPOSE_REPLAY="$LAST_REPORT"
capture_effect_inventory compose-replay "$COMPOSE_HTTP_BASE" "$COMPOSE_CHAIN" \
  "$ROOT/compose-replay-effects.json"
cmp -s "$ROOT/compose-first-effects.json" "$ROOT/compose-replay-effects.json" \
  || fail 'Compose immediate replay changed the logical effect inventory'
capture_cluster_state compose-replay "$COMPOSE_HTTP_BASE" "$COMPOSE_CHAIN" \
  "$ROOT/compose-replay-state.json" \
  || fail 'Compose members did not agree after immediate replay'
assert_replay_advanced_only_tip "$ROOT/compose-first-state.json" \
  "$ROOT/compose-replay-state.json" 'Compose immediate replay' \
  "$COMPOSE_HTTP_BASE" "$COMPOSE_CHAIN"
[ "$(find "$COMPOSE_REPORTS" -maxdepth 1 -type f -name 'report-*.json' \
    | wc -l | tr -d ' ')" -eq 2 ] || fail 'Compose replay did not create exactly two attempt reports'
[ "$(stable_receipt_signature "$COMPOSE_FIRST")" \
    = "$(stable_receipt_signature "$COMPOSE_REPLAY")" ] \
  || fail 'Compose immediate replay changed state or external receipts'
[ "$(jq -r '.kafka.offset' "$COMPOSE_FIRST")" -eq 0 ] \
  || fail 'fresh isolated Compose broker did not assign the first exact Kafka offset'
assert_ui_latest compose-pre-restart "$COMPOSE_UI_PORT" "$COMPOSE_REPLAY"
capture_cluster_state compose-pre-restart "$COMPOSE_HTTP_BASE" "$COMPOSE_CHAIN" \
  "$ROOT/compose-pre-restart-state.json" \
  || fail 'Compose members did not agree before retained restart'

note 'Restarting the exact Compose stack from retained data'
demo_compose stop
COMPOSE_RUNNING=false
[ -z "$(docker ps -a --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" -q)" ] \
  || fail 'Compose stop retained project containers'
demo_compose up
COMPOSE_RUNNING=true
capture_owned_image_ids || fail 'Compose restart changed or lost an owned image tag'
demo_compose probe
verify_live_generation compose-restarted "$COMPOSE_HTTP_BASE" "$COMPOSE_CHAIN" \
  "$COMPOSE_SECRETS/nodes-compose" "$COMPOSE_API_KEY"
artifact_manifest "$COMPOSE_RUNTIME" "$ROOT/compose-restarted-artifacts.json"
cmp -s "$ROOT/compose-artifacts.json" "$ROOT/compose-restarted-artifacts.json" \
  || fail 'Compose retained restart changed its staged binary artifacts'
capture_cluster_state compose-restarted "$COMPOSE_HTTP_BASE" "$COMPOSE_CHAIN" \
  "$ROOT/compose-restarted-state.json" \
  || fail 'Compose members did not recover retained agreement'
cmp -s "$ROOT/compose-pre-restart-state.json" "$ROOT/compose-restarted-state.json" \
  || fail 'Compose app-chain state or membership changed across retained restart'
run_and_capture compose "$COMPOSE_REPORTS" "$COMPOSE_EVIDENCE" "$COMPOSE_CHAIN" \
  compose-post-restart-replay
COMPOSE_POST_RESTART="$LAST_REPORT"
capture_effect_inventory compose-post-restart "$COMPOSE_HTTP_BASE" "$COMPOSE_CHAIN" \
  "$ROOT/compose-post-restart-effects.json"
cmp -s "$ROOT/compose-first-effects.json" "$ROOT/compose-post-restart-effects.json" \
  || fail 'Compose retained restart/replay changed the logical effect inventory'
capture_cluster_state compose-post-restart-replay "$COMPOSE_HTTP_BASE" "$COMPOSE_CHAIN" \
  "$ROOT/compose-post-restart-replay-state.json" \
  || fail 'Compose members did not agree after retained restart/replay'
assert_replay_advanced_only_tip "$ROOT/compose-restarted-state.json" \
  "$ROOT/compose-post-restart-replay-state.json" 'Compose retained restart/replay' \
  "$COMPOSE_HTTP_BASE" "$COMPOSE_CHAIN"
[ "$(cluster_state_signature "$ROOT/compose-first-state.json")" \
    = "$(cluster_state_signature "$ROOT/compose-post-restart-replay-state.json")" ] \
  || fail 'Compose retained restart/replay changed app-chain state or membership'
[ "$(stable_receipt_signature "$COMPOSE_FIRST")" \
    = "$(stable_receipt_signature "$COMPOSE_POST_RESTART")" ] \
  || fail 'Compose retained restart/replay changed state or external receipts'
assert_ui_latest compose-post-restart "$COMPOSE_UI_PORT" "$COMPOSE_POST_RESTART"
[ "$(find "$COMPOSE_REPORTS" -maxdepth 1 -type f -name 'report-*.json' \
    | wc -l | tr -d ' ')" -eq 3 ] \
  || fail 'Compose retained replay did not produce exactly three attempt reports'

# The Compose stack deliberately remains live here. Host mode reaches the same
# connector instances only through their loopback-published normal endpoints.
for port in "$CONNECTOR_KAFKA_PORT" "$CONNECTOR_S3_PORT" "$CONNECTOR_IPFS_PORT"; do
  port_free "$port" && fail "Compose connector stopped before host parity: $port"
done
for service in kafka rustfs kubo; do
  container="$(dc_compose ps -q "$service")"
  [[ "$container" =~ ^[0-9a-f]{64}$ ]] \
    || fail "Compose connector container is missing before host parity: $service"
  [ "$(docker inspect -f '{{.State.Running}}' "$container")" = true ] \
    || fail "Compose connector is not running before host parity: $service"
  printf '%s=%s\n' "$service" "$container" >> "$ROOT/compose-connector-identities"
done

note 'Starting normal/host deployment against the isolated Compose connectors'
activate_host
demo_host prepare
HOST_PREPARED=true
artifact_manifest "$HOST_RUNTIME" "$ROOT/host-artifacts.json"
cmp -s "$ROOT/compose-artifacts.json" "$ROOT/host-artifacts.json" \
  || fail 'Compose and host staged binary artifacts are not byte-identical'
grep -Fxq "s3.target-id=s3-host-parity-$RUN_TOKEN" "$HOST_RUNTIME/runner-host.properties" \
  && grep -Fxq "ipfs.target-id=ipfs-host-parity-$RUN_TOKEN" \
    "$HOST_RUNTIME/runner-host.properties" \
  && grep -Fxq "kafka.target-id=kafka-host-parity-$RUN_TOKEN" \
    "$HOST_RUNTIME/runner-host.properties" \
  || fail 'host deployment did not bind the three explicit distinct target ids'
python3 - \
  "$COMPOSE_DATA/appchain-identity.json" "$HOST_DATA/appchain-identity.json" <<'PY' \
  || fail 'Compose and host connector target identities are not six distinct aliases'
import json
from pathlib import Path
import sys

documents = [json.loads(Path(path).read_text(encoding="utf-8")) for path in sys.argv[1:]]
identities = []
for document in documents:
    connectors = document.get("connectors", {})
    identities.extend(connectors.get(name, {}).get("targetId") for name in ("s3", "ipfs", "kafka"))
if len(identities) != 6 or any(not isinstance(value, str) for value in identities):
    raise SystemExit(1)
if len(set(identities)) != 6:
    raise SystemExit(1)
PY
demo_host up
HOST_RUNNING=true
demo_host probe
artifact_manifest "$HOST_RUNTIME" "$ROOT/host-live-artifacts.json"
cmp -s "$ROOT/compose-artifacts.json" "$ROOT/host-live-artifacts.json" \
  || fail 'running host deployment changed the staged binary artifacts'
verify_live_generation host-initial "$HOST_HTTP_BASE" "$HOST_CHAIN" \
  "$HOST_SECRETS/nodes-host" "$HOST_API_KEY"

run_and_capture host "$HOST_REPORTS" "$HOST_EVIDENCE" "$HOST_CHAIN" host-first
HOST_FIRST="$LAST_REPORT"
assert_executor_activity host-first "$HOST_HTTP_BASE" "$HOST_CHAIN"
capture_effect_inventory host-first "$HOST_HTTP_BASE" "$HOST_CHAIN" \
  "$ROOT/host-first-effects.json"
capture_cluster_state host-first "$HOST_HTTP_BASE" "$HOST_CHAIN" \
  "$ROOT/host-first-state.json" \
  || fail 'host members did not agree after the first scenario'
run_and_capture host "$HOST_REPORTS" "$HOST_EVIDENCE" "$HOST_CHAIN" host-replay
HOST_REPLAY="$LAST_REPORT"
capture_effect_inventory host-replay "$HOST_HTTP_BASE" "$HOST_CHAIN" \
  "$ROOT/host-replay-effects.json"
cmp -s "$ROOT/host-first-effects.json" "$ROOT/host-replay-effects.json" \
  || fail 'host immediate replay changed the logical effect inventory'
capture_cluster_state host-replay "$HOST_HTTP_BASE" "$HOST_CHAIN" \
  "$ROOT/host-replay-state.json" \
  || fail 'host members did not agree after immediate replay'
assert_replay_advanced_only_tip "$ROOT/host-first-state.json" \
  "$ROOT/host-replay-state.json" 'host immediate replay' \
  "$HOST_HTTP_BASE" "$HOST_CHAIN"
[ "$(find "$HOST_REPORTS" -maxdepth 1 -type f -name 'report-*.json' \
    | wc -l | tr -d ' ')" -eq 2 ] || fail 'host replay did not create exactly two attempt reports'
[ "$(stable_receipt_signature "$HOST_FIRST")" = "$(stable_receipt_signature "$HOST_REPLAY")" ] \
  || fail 'host immediate replay changed state or external receipts'
[ "$(semantic_signature "$COMPOSE_FIRST")" = "$(semantic_signature "$HOST_FIRST")" ] \
  || fail 'Compose and host reports are not semantically equivalent PASS outcomes'
jq -e -n --argjson compose "$(jq -c '.kafka.offset' "$COMPOSE_FIRST")" \
  --argjson host "$(jq -c '.kafka.offset' "$HOST_FIRST")" \
  '$host == ($compose + 1)' >/dev/null \
  || fail 'host Kafka receipt is not the next exact record on the shared broker'
assert_ui_latest host-pre-restart "$HOST_UI_PORT" "$HOST_REPLAY"
capture_cluster_state host-pre-restart "$HOST_HTTP_BASE" "$HOST_CHAIN" \
  "$ROOT/host-pre-restart-state.json" \
  || fail 'host members did not agree before retained restart'

note 'Restarting the exact host deployment from retained data'
demo_host stop
HOST_RUNNING=false
demo_host up
HOST_RUNNING=true
demo_host probe
verify_live_generation host-restarted "$HOST_HTTP_BASE" "$HOST_CHAIN" \
  "$HOST_SECRETS/nodes-host" "$HOST_API_KEY"
artifact_manifest "$HOST_RUNTIME" "$ROOT/host-restarted-artifacts.json"
cmp -s "$ROOT/compose-artifacts.json" "$ROOT/host-restarted-artifacts.json" \
  || fail 'host retained restart changed its staged binary artifacts'
capture_cluster_state host-restarted "$HOST_HTTP_BASE" "$HOST_CHAIN" \
  "$ROOT/host-restarted-state.json" \
  || fail 'host members did not recover retained agreement'
cmp -s "$ROOT/host-pre-restart-state.json" "$ROOT/host-restarted-state.json" \
  || fail 'host app-chain state or membership changed across retained restart'
run_and_capture host "$HOST_REPORTS" "$HOST_EVIDENCE" "$HOST_CHAIN" host-post-restart-replay
HOST_POST_RESTART="$LAST_REPORT"
capture_effect_inventory host-post-restart "$HOST_HTTP_BASE" "$HOST_CHAIN" \
  "$ROOT/host-post-restart-effects.json"
cmp -s "$ROOT/host-first-effects.json" "$ROOT/host-post-restart-effects.json" \
  || fail 'host retained restart/replay changed the logical effect inventory'
capture_cluster_state host-post-restart-replay "$HOST_HTTP_BASE" "$HOST_CHAIN" \
  "$ROOT/host-post-restart-replay-state.json" \
  || fail 'host members did not agree after retained restart/replay'
assert_replay_advanced_only_tip "$ROOT/host-restarted-state.json" \
  "$ROOT/host-post-restart-replay-state.json" 'host retained restart/replay' \
  "$HOST_HTTP_BASE" "$HOST_CHAIN"
[ "$(cluster_state_signature "$ROOT/host-first-state.json")" \
    = "$(cluster_state_signature "$ROOT/host-post-restart-replay-state.json")" ] \
  || fail 'host retained restart/replay changed app-chain state or membership'
[ "$(stable_receipt_signature "$HOST_FIRST")" \
    = "$(stable_receipt_signature "$HOST_POST_RESTART")" ] \
  || fail 'host retained restart/replay changed state or external receipts'
[ "$(semantic_signature "$COMPOSE_POST_RESTART")" \
    = "$(semantic_signature "$HOST_POST_RESTART")" ] \
  || fail 'deployment parity changed after retained restarts'
assert_ui_latest host-post-restart "$HOST_UI_PORT" "$HOST_POST_RESTART"
[ "$(find "$HOST_REPORTS" -maxdepth 1 -type f -name 'report-*.json' \
    | wc -l | tr -d ' ')" -eq 3 ] \
  || fail 'host retained replay did not produce exactly three attempt reports'
for service in kafka rustfs kubo; do
  expected="$(sed -n "s/^$service=//p" "$ROOT/compose-connector-identities")"
  actual="$(dc_compose ps -q "$service")"
  [ -n "$expected" ] && [ "$actual" = "$expected" ] \
    && [ "$(docker inspect -f '{{.State.Running}}' "$actual")" = true ] \
    || fail "host workflow restarted or stopped the isolated Compose connector: $service"
done

note 'Stopping isolated host first, then the exact Compose project'
demo_host stop
HOST_RUNNING=false
activate_compose
demo_compose stop
COMPOSE_RUNNING=false
[ -z "$(docker ps -a --filter "label=com.docker.compose.project=$COMPOSE_PROJECT" -q)" ] \
  || fail 'final Compose stop retained project containers'
remove_owned_images
remove_owned_root
CLEANED=true
trap - EXIT INT TERM
note 'PASS: Compose and normal deployment have identical artifacts, semantics, and retained replay behavior.'
