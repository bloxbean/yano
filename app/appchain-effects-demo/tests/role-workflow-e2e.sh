#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd -P)"
DEMO_DIR="$(cd "$SCRIPT_DIR/.." && pwd -P)"

[ "${YANO_RUN_ROLE_WORKFLOW_E2E:-false}" = true ] || {
  printf '%s\n' \
    'SKIP: set YANO_RUN_ROLE_WORKFLOW_E2E=true to run the destructive isolated E2E.'
  exit 0
}

fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }
note() { printf '%s\n' "$*"; }
require() { command -v "$1" >/dev/null 2>&1 || fail "missing command: $1"; }
mode() {
  if stat -c '%a' "$1" >/dev/null 2>&1; then stat -c '%a' "$1"; else stat -f '%Lp' "$1"; fi
}

for command in docker curl jq openssl lsof python3; do require "$command"; done
docker compose version >/dev/null 2>&1 || fail 'Docker Compose v2 is required'

INSTANCE="${YANO_ROLE_WORKFLOW_INSTANCE:-role-e2e}"
CHAIN_ID="${YANO_ROLE_WORKFLOW_CHAIN_ID:-evidence-chain-role-e2e}"
EVIDENCE_ID="${YANO_ROLE_WORKFLOW_EVIDENCE_ID:-role-$(openssl rand -hex 6)}"
[[ "$INSTANCE" =~ ^[a-z0-9][a-z0-9-]{0,31}$ ]] || fail 'instance id is malformed'
[[ "$CHAIN_ID" =~ ^[a-z][a-z0-9-]{0,62}$ ]] || fail 'chain id is malformed'
[[ "$EVIDENCE_ID" =~ ^[a-z][a-z0-9-]{0,62}$ ]] || fail 'evidence id is malformed'

export DEMO_HTTP_BASE="${YANO_ROLE_WORKFLOW_HTTP_BASE:-48070}"
export DEMO_UI_PORT="${YANO_ROLE_WORKFLOW_UI_PORT:-48080}"
export DEMO_SERVER_BASE="${YANO_ROLE_WORKFLOW_SERVER_BASE:-48337}"
export DEMO_KAFKA_PORT="${YANO_ROLE_WORKFLOW_KAFKA_PORT:-49092}"
export DEMO_S3_PORT="${YANO_ROLE_WORKFLOW_S3_PORT:-49000}"
export DEMO_IPFS_PORT="${YANO_ROLE_WORKFLOW_IPFS_PORT:-45001}"
export DEMO_PROMETHEUS_PORT="${YANO_ROLE_WORKFLOW_PROMETHEUS_PORT:-49090}"
export DEMO_GRAFANA_PORT="${YANO_ROLE_WORKFLOW_GRAFANA_PORT:-43000}"
export DEMO_CONNECTOR_SUBNET="${YANO_ROLE_WORKFLOW_SUBNET:-172.30.118.0/24}"
export DEMO_S3_IP="${YANO_ROLE_WORKFLOW_S3_IP:-172.30.118.10}"
export DEMO_KUBO_IP="${YANO_ROLE_WORKFLOW_KUBO_IP:-172.30.118.11}"
export DEMO_KAFKA_IP="${YANO_ROLE_WORKFLOW_KAFKA_IP:-172.30.118.12}"
export DEMO_OBSERVABILITY=false
export DEMO_SCENARIO_TIMEOUT_SECONDS="${YANO_ROLE_WORKFLOW_TIMEOUT_SECONDS:-600}"
export DEMO_SCENARIO_POLL_INTERVAL_MILLIS=500

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

ROOT_PARENT="$(cd "${YANO_ROLE_WORKFLOW_TMPDIR:-${TMPDIR:-/tmp}}" && pwd -P)"
ROOT="$(mktemp -d "$ROOT_PARENT/yano-role-workflow-e2e.XXXXXX")"
ROOT="$(cd "$ROOT" && pwd -P)"
SENTINEL="$ROOT/.yano-role-workflow-e2e-owned-v1"
(umask 077; printf 'yano-role-workflow-e2e-v1\n' > "$SENTINEL")
chmod 600 "$SENTINEL"
export DEMO_DATA_ROOT="$ROOT/data"
export DEMO_SECRET_ROOT="$ROOT/secrets"
export DEMO_RUNTIME_ROOT="$ROOT/runtime"

RUNTIME_ROOT="$DEMO_RUNTIME_ROOT/networks/devnet/$INSTANCE/compose"
SECRET_ROOT="$DEMO_SECRET_ROOT/networks/devnet/$INSTANCE/compose"
ENV_FILE="$RUNTIME_ROOT/compose.env"
API_KEY_FILE="$SECRET_ROOT/yano-api-key"
PROJECT_NAME=""
PREPARED=false
CLEANED=false

demo() {
  local command="$1"
  shift
  "$DEMO_DIR/demo.sh" "$command" --deployment compose --network devnet \
    --instance "$INSTANCE" --chain-id "$CHAIN_ID" --evidence-id "$EVIDENCE_ID" \
    --machine role --continuation direct "$@"
}

dc() {
  [ -n "$PROJECT_NAME" ] && [ -f "$ENV_FILE" ] || fail 'Compose identity is unavailable'
  docker compose -p "$PROJECT_NAME" --env-file "$ENV_FILE" \
    -f "$DEMO_DIR/compose.yaml" "$@"
}

remove_owned_root() {
  python3 - "$ROOT" "$SENTINEL" "$ROOT_PARENT" <<'PY'
from pathlib import Path
import shutil
import sys

root = Path(sys.argv[1])
sentinel = Path(sys.argv[2])
parent = Path(sys.argv[3]).resolve(strict=True)
resolved = root.resolve(strict=True)
if (root != resolved or root.is_symlink() or resolved.parent != parent
        or not resolved.name.startswith("yano-role-workflow-e2e.")):
    raise SystemExit(1)
if (sentinel.is_symlink()
        or sentinel.read_text(encoding="ascii") != "yano-role-workflow-e2e-v1\n"):
    raise SystemExit(1)
shutil.rmtree(resolved)
PY
}

cleanup() {
  local status="$?" uncertain=false remaining=""
  trap - EXIT INT TERM
  set +e
  if [ "$PREPARED" = true ]; then demo stop >/dev/null 2>&1 || uncertain=true; fi
  if [ -n "$PROJECT_NAME" ]; then
    remaining="$(docker ps -a --filter \
      "label=com.docker.compose.project=$PROJECT_NAME" -q 2>/dev/null)" || uncertain=true
    [ -z "$remaining" ] || uncertain=true
  fi
  if [ "$uncertain" = false ] && [ -f "$SENTINEL" ]; then
    remove_owned_root >/dev/null 2>&1 && CLEANED=true || uncertain=true
  fi
  if [ "$CLEANED" != true ]; then
    printf 'WARN: isolated role diagnostics retained: %s\n' "$ROOT" >&2
  fi
  if [ "$uncertain" = true ] && [ "$status" -eq 0 ]; then status=1; fi
  exit "$status"
}
trap cleanup EXIT INT TERM

for port in "$DEMO_HTTP_BASE" "$((DEMO_HTTP_BASE + 1))" "$((DEMO_HTTP_BASE + 2))" \
  "$DEMO_UI_PORT" "$DEMO_SERVER_BASE" "$((DEMO_SERVER_BASE + 1))" \
  "$((DEMO_SERVER_BASE + 2))" "$DEMO_KAFKA_PORT" "$DEMO_S3_PORT" \
  "$DEMO_IPFS_PORT" "$DEMO_PROMETHEUS_PORT" "$DEMO_GRAFANA_PORT"; do
  ! lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1 \
    || fail "isolated test port is already in use: $port"
done

bounded_get() {
  local url="$1" output="$2"
  curl --proto '=http' --connect-timeout 2 --max-time 10 --max-filesize 1048576 \
    -fsS "$url" > "$output.tmp" || { rm -f "$output.tmp"; return 1; }
  [ "$(wc -c < "$output.tmp" | tr -d ' ')" -le 1048576 ] \
    || { rm -f "$output.tmp"; return 1; }
  mv "$output.tmp" "$output"
}

authenticated_get() {
  local url="$1" output="$2" api_key
  [ -f "$API_KEY_FILE" ] && [ ! -L "$API_KEY_FILE" ] \
    && [ "$(mode "$API_KEY_FILE")" = 600 ] || return 1
  api_key="$(tr -d '\r\n' < "$API_KEY_FILE")"
  [[ "$api_key" =~ ^[0-9a-f]{64}$ ]] || return 1
  printf 'header = "X-API-Key: %s"\n' "$api_key" \
    | curl --proto '=http' --connect-timeout 2 --max-time 10 --max-filesize 1048576 \
        -fsS --config - "$url" > "$output.tmp" \
    || { rm -f "$output.tmp"; return 1; }
  unset api_key
  mv "$output.tmp" "$output"
}

wait_agreement() {
  local label="$1" output="$2" deadline=$((SECONDS + 240)) node port valid
  while [ "$SECONDS" -lt "$deadline" ]; do
    valid=true
    for node in 0 1 2; do
      port=$((DEMO_HTTP_BASE + node))
      bounded_get "http://127.0.0.1:$port/api/v1/app-chain/chains/$CHAIN_ID/status" \
        "$ROOT/$label-node$node.json" 2>/dev/null || valid=false
    done
    if [ "$valid" = true ] && jq -s -e --arg chain "$CHAIN_ID" '
        length == 3 and all(.[];
          .chainId == $chain and .running == true and .stateMachine == "role-evidence"
          and .members == 3 and .threshold == 2 and .tipHeight >= 1
          and (.stateRoot | test("^[0-9a-f]{64}$"))
          and .stateMachineStatus.mode == "governed"
          and .stateMachineStatus.currentEpoch == 0
          and .stateMachineStatus.catalogReady == true)
        and ([.[].tipHeight] | unique | length) == 1
        and ([.[].stateRoot] | unique | length) == 1
        and ([.[].memberKey] | unique | length) == 3' \
        "$ROOT/$label-node0.json" "$ROOT/$label-node1.json" \
        "$ROOT/$label-node2.json" >/dev/null 2>&1; then
      jq -S -c -s '[.[] | {chainId, tipHeight, stateRoot, memberKey,
        members, threshold, stateMachine, stateMachineStatus}]' \
        "$ROOT/$label-node0.json" "$ROOT/$label-node1.json" \
        "$ROOT/$label-node2.json" > "$output"
      return 0
    fi
    sleep 1
  done
  return 1
}

assert_role_api() {
  local label="$1" stats actor
  stats="$ROOT/$label-stats.json"
  actor="$ROOT/$label-actor.json"
  authenticated_get \
    "http://127.0.0.1:$DEMO_HTTP_BASE/api/v1/plugins/com.bloxbean.cardano.yano.appchain.role-workflow/stats?chain=$CHAIN_ID" \
    "$stats" || fail "$label role statistics API is unavailable"
  jq -e '.record.type == "approval-stats" and .record.created == 3
    and .record.pending == 0 and .record.approved == 1
    and .record.cancelled == 2 and .record.rejected == 0 and .record.expired == 0
    and (.proofKey | test("^[0-9a-f]+$"))
    and (.recordValue | test("^[0-9a-f]+$"))' "$stats" >/dev/null \
    || fail "$label authenticated proposal statistics are incorrect"
  authenticated_get \
    "http://127.0.0.1:$DEMO_HTTP_BASE/api/v1/plugins/com.bloxbean.cardano.yano.appchain.role-workflow/actors/recovery-probe?chain=$CHAIN_ID" \
    "$actor" || fail "$label current actor API is unavailable"
  jq -e '.record.actorId == "recovery-probe" and .record.revision == 3
    and .record.status == "REVOKED"
    and (.proofKey | test("^[0-9a-f]+$"))
    and (.recordValue | test("^[0-9a-f]+$"))
    and (.currentPointerProofKey | test("^[0-9a-f]+$"))
    and .currentPointerValue == "0000000000000003"' "$actor" >/dev/null \
    || fail "$label current actor response lacks its current-pointer proof material"
}

note "Starting isolated ADR-019 role E2E in $ROOT"
PREPARED=true
demo up
[ -f "$ENV_FILE" ] && [ ! -L "$ENV_FILE" ] || fail 'Compose environment is missing'
PROJECT_NAME="$(sed -n 's/^DEMO_PROJECT_NAME=//p' "$ENV_FILE")"
[[ "$PROJECT_NAME" =~ ^yano-effects-devnet-${INSTANCE}-[0-9a-f]{8}$ ]] \
  || fail 'unexpected Compose project identity'

PUBLISH_OUT="$ROOT/publish.out"
demo publish --sample-file "$DEMO_DIR/samples/inspection-certificate.json" > "$PUBLISH_OUT"
grep -q '^PASS command=publish scenario=' "$PUBLISH_OUT" \
  || fail 'role-gated evidence publication failed'
LIFECYCLE_OUT="$ROOT/lifecycle.out"
demo role-lifecycle > "$LIFECYCLE_OUT"
grep -q '^PASS command=role-lifecycle actor=recovery-probe revision=3 ' "$LIFECYCLE_OUT" \
  || fail 'actor rotation/revocation lifecycle failed'
VERIFY_OUT="$ROOT/verify.out"
demo verify > "$VERIFY_OUT"
grep -q '^PASS command=verify scenario=' "$VERIFY_OUT" \
  || fail 'read-only role evidence verification failed'
wait_agreement pre-restart "$ROOT/pre-restart.json" \
  || fail 'members do not agree before member restart'
assert_role_api pre-restart

note 'Restarting only role member yano-1 and waiting for retained catch-up'
dc restart yano-1 >/dev/null
wait_agreement post-restart "$ROOT/post-restart.json" \
  || fail 'restarted member did not catch up to the role chain'
[ "$(jq -r '.[0].stateRoot' "$ROOT/pre-restart.json")" \
    = "$(jq -r '.[0].stateRoot' "$ROOT/post-restart.json")" ] \
  || fail 'member restart changed authenticated role state'

demo verify > "$ROOT/post-restart-verify.out"
grep -q '^PASS command=verify scenario=' "$ROOT/post-restart-verify.out" \
  || fail 'verification failed after one-member catch-up'
demo role-lifecycle > "$ROOT/post-restart-lifecycle.out"
grep -q '^PASS command=role-lifecycle actor=recovery-probe revision=3 ' \
  "$ROOT/post-restart-lifecycle.out" || fail 'retained lifecycle rerun is not idempotent'
assert_role_api post-restart

demo stop >/dev/null
PREPARED=false
[ -z "$(docker ps -a --filter "label=com.docker.compose.project=$PROJECT_NAME" -q)" ] \
  || fail 'role E2E retained Compose containers'
remove_owned_root
CLEANED=true
trap - EXIT INT TERM
note 'PASS: ADR-019 role authorization, recovery, proofs, and one-member catch-up.'
