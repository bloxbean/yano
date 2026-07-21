#!/usr/bin/env bash
# ADR-013 Milestone 1 launcher: guarded profiles, identity markers, and cleanup.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$APP_DIR/.." && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/compose.yaml"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }
note() { printf '%s\n' "$*"; }

load_defaults() {
  local file="$1" line key value
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue;; esac
    key="${line%%=*}"
    value="${line#*=}"
    [[ "$key" =~ ^[A-Z][A-Z0-9_]*$ ]] \
      || die "invalid default key in $file: $key"
    if [ -z "${!key+x}" ]; then
      printf -v "$key" '%s' "$value"
      export "$key"
    fi
  done < "$file"
}

profile_digest_for() {
  local wanted="$1" file="$SCRIPT_DIR/config/composite-profile-digests.properties"
  local line key value found=""
  [ -f "$file" ] || die "composite profile trust-root manifest is missing"
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue;; esac
    case "$line" in *=*) ;;
      *) die "invalid composite profile trust-root manifest";;
    esac
    key="${line%%=*}"
    value="${line#*=}"
    case "$key" in
      app-final:explicit|app-final:direct|l1-anchored:explicit|l1-anchored:direct) ;;
      *) die "unknown composite profile trust-root key";;
    esac
    [ "${#value}" -eq 64 ] && [[ "$value" != *[!0-9a-f]* ]] \
      || die "invalid composite profile trust-root digest"
    if [ "$key" = "$wanted" ]; then
      [ -z "$found" ] || die "duplicate composite profile trust-root key"
      found="$value"
    fi
  done < "$file"
  [ -n "$found" ] || die "composite profile trust root is not declared"
  printf '%s\n' "$found"
}

CHAIN_ID_EXPLICIT=false
[ -z "${DEMO_CHAIN_ID+x}" ] || CHAIN_ID_EXPLICIT=true
load_defaults "$SCRIPT_DIR/config/common.env"
load_defaults "$SCRIPT_DIR/config/images.env"

ORIGINAL_ARGS=("$@")
COMMAND="${1:-help}"
[ "$#" -eq 0 ] || shift
MODE="${DEMO_MODE:-compose}"
INSTANCE="${DEMO_INSTANCE:-default}"
OBSERVABILITY="${DEMO_OBSERVABILITY:-false}"
CLEAN_SCOPE=""
CLEAN_CONFIRMED=false
ENABLE_MAINNET=false
ANCHOR_KEY_FILE="${DEMO_ANCHOR_KEY_FILE:-}"
ANCHOR_KEY_FILE_EXPLICIT=false
PUBLIC_ANCHOR_CONFIRMATION=""
PUBLIC_L1_DELETE_CONFIRMATION=""
NEW_INSTANCE=""
NEW_CHAIN_ID=""
EVIDENCE_ID_EXPLICIT=false
SCENARIO_SAMPLE_FILE=""
BUSINESS_VERSION=""
LOAD_COUNT=""
LOAD_CONCURRENCY="1"
LOAD_CONCURRENCY_EXPLICIT=false
LOAD_ID_PREFIX=""
LOAD_MODE="lifecycle"
LOAD_MODE_EXPLICIT=false
LOAD_MAX_IN_FLIGHT=""
EVIDENCE_CAPACITY_PER_BLOCK=8
TEMP_FILES=()
MEMBER_SEED_0=""
MEMBER_SEED_1=""
MEMBER_SEED_2=""
RESULT_SIGNERS=""
ANCHOR_KEY_VALUE=""
STAGED_SHELLEY=""
STAGED_GENESIS_TIMESTAMP=""
STAGED_INSTANCE_IDENTITY=""
STAGED_LEASE_IDENTITY=""

cleanup_temporary_files() {
  local file
  for file in "${TEMP_FILES[@]-}"; do
    [ -z "$file" ] || rm -f "$file"
  done
}
trap cleanup_temporary_files EXIT

temporary_file() {
  local created
  created="$(mktemp "${TMPDIR:-/tmp}/yano-effects-demo.XXXXXX")" \
    || die "cannot create a private temporary file"
  LAST_TEMP_FILE="$(cd "$(dirname "$created")" && pwd -P)/$(basename "$created")"
  chmod 600 "$LAST_TEMP_FILE"
  TEMP_FILES+=("$LAST_TEMP_FILE")
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --mode|--deployment) MODE="${2:-}"; shift 2;;
    --network) DEMO_NETWORK="${2:-}"; shift 2;;
    --chain-id) DEMO_CHAIN_ID="${2:-}"; CHAIN_ID_EXPLICIT=true; shift 2;;
    --evidence-id) DEMO_EVIDENCE_ID="${2:-}"; EVIDENCE_ID_EXPLICIT=true; shift 2;;
    --sample-file) SCENARIO_SAMPLE_FILE="${2:-}"; shift 2;;
    --business-version) BUSINESS_VERSION="${2:-}"; shift 2;;
    --count) LOAD_COUNT="${2:-}"; shift 2;;
    --concurrency)
      LOAD_CONCURRENCY="${2:-}"; LOAD_CONCURRENCY_EXPLICIT=true; shift 2;;
    --id-prefix) LOAD_ID_PREFIX="${2:-}"; shift 2;;
    --load-mode) LOAD_MODE="${2:-}"; LOAD_MODE_EXPLICIT=true; shift 2;;
    --max-in-flight) LOAD_MAX_IN_FLIGHT="${2:-}"; shift 2;;
    --continuation) DEMO_CONTINUATION_MODE="${2:-}"; shift 2;;
    --machine) DEMO_MACHINE_MODE="${2:-}"; shift 2;;
    --data-dir) DEMO_DATA_ROOT="${2:-}"; shift 2;;
    --instance) INSTANCE="${2:-}"; shift 2;;
    --observability) OBSERVABILITY=true; shift;;
    --no-observability) OBSERVABILITY=false; shift;;
    --anchor-key-file) ANCHOR_KEY_FILE="${2:-}"; ANCHOR_KEY_FILE_EXPLICIT=true; shift 2;;
    --confirm-public-anchor) PUBLIC_ANCHOR_CONFIRMATION="${2:-}"; shift 2;;
    --confirm-public-l1-delete) PUBLIC_L1_DELETE_CONFIRMATION="${2:-}"; shift 2;;
    --enable-mainnet) ENABLE_MAINNET=true; shift;;
    --scope) CLEAN_SCOPE="${2:-}"; shift 2;;
    --new-instance) NEW_INSTANCE="${2:-}"; shift 2;;
    --new-chain-id) NEW_CHAIN_ID="${2:-}"; shift 2;;
    --yes) CLEAN_CONFIRMED=true; shift;;
    -h|--help) COMMAND=help; shift;;
    *) die "unknown option: $1";;
  esac
done

case "$MODE" in compose|host) ;; *) die "--mode must be compose or host";; esac
case "$DEMO_NETWORK" in
  devnet|preview|preprod|mainnet) ;;
  *) die "--network must be exactly devnet, preview, preprod, or mainnet";;
esac
[[ "$INSTANCE" =~ ^[a-z0-9][a-z0-9-]{0,31}$ ]] \
  || die "--instance must match [a-z0-9][a-z0-9-]{0,31}"
if [ -n "$NEW_INSTANCE" ]; then
  [[ "$NEW_INSTANCE" =~ ^[a-z0-9][a-z0-9-]{0,31}$ ]] \
    || die "--new-instance must match [a-z0-9][a-z0-9-]{0,31}"
  [ "$NEW_INSTANCE" != "$INSTANCE" ] \
    || die "--new-instance must differ from the retired instance"
fi
if [ -n "$NEW_CHAIN_ID" ]; then
  [[ "$NEW_CHAIN_ID" =~ ^[a-z][a-z0-9-]{0,62}$ ]] \
    || die "--new-chain-id must match [a-z][a-z0-9-]{0,62}"
fi
if [ "$CHAIN_ID_EXPLICIT" = false ] && [ "$INSTANCE" != default ]; then
  DEMO_CHAIN_ID="evidence-chain-$INSTANCE"
fi
[[ "$DEMO_CHAIN_ID" =~ ^[a-z][a-z0-9-]{0,62}$ ]] \
  || die "DEMO_CHAIN_ID must match [a-z][a-z0-9-]{0,62}"
[[ "$DEMO_EVIDENCE_ID" =~ ^[a-z][a-z0-9-]{0,62}$ ]] \
  || die "DEMO_EVIDENCE_ID must match [a-z][a-z0-9-]{0,62}"
if [ -n "$BUSINESS_VERSION" ]; then
  [[ "$BUSINESS_VERSION" =~ ^[1-9][0-9]{0,18}$ ]] \
    || die "--business-version must be a positive 64-bit integer"
fi
case "$COMMAND" in
  publish)
    [ "$EVIDENCE_ID_EXPLICIT" = true ] \
      || die "publish requires --evidence-id"
    [ -n "$SCENARIO_SAMPLE_FILE" ] || die "publish requires --sample-file"
    [ -z "$BUSINESS_VERSION" ] \
      || die "publish always creates version 1; omit --business-version"
    ;;
  republish|replay)
    [ "$EVIDENCE_ID_EXPLICIT" = true ] \
      || die "$COMMAND requires --evidence-id"
    [ -n "$SCENARIO_SAMPLE_FILE" ] || die "$COMMAND requires --sample-file"
    [ -n "$BUSINESS_VERSION" ] || die "$COMMAND requires --business-version"
    ;;
  verify)
    [ "$EVIDENCE_ID_EXPLICIT" = true ] || die "verify requires --evidence-id"
    [ -z "$SCENARIO_SAMPLE_FILE" ] || die "verify does not accept --sample-file"
    ;;
  load)
    [ "$EVIDENCE_ID_EXPLICIT" = false ] || die "load does not accept --evidence-id"
    [ -z "$BUSINESS_VERSION" ] || die "load does not accept --business-version"
    [ -n "$SCENARIO_SAMPLE_FILE" ] || die "load requires --sample-file"
    [ -n "$LOAD_COUNT" ] || die "load requires --count"
    [ -n "$LOAD_ID_PREFIX" ] || die "load requires --id-prefix"
    ;;
  run) [ -z "$BUSINESS_VERSION" ] || die "run does not accept --business-version";;
  *)
    [ -z "$SCENARIO_SAMPLE_FILE" ] \
      || die "--sample-file is valid only for scenario commands"
    [ -z "$BUSINESS_VERSION" ] \
      || die "--business-version is valid only for scenario commands"
    ;;
esac
if [ "$COMMAND" != load ]; then
  [ -z "$LOAD_COUNT" ] && [ "$LOAD_CONCURRENCY_EXPLICIT" = false ] \
    && [ -z "$LOAD_ID_PREFIX" ] && [ "$LOAD_MODE_EXPLICIT" = false ] \
    && [ -z "$LOAD_MAX_IN_FLIGHT" ] \
    || die "--count, --concurrency, --id-prefix, --load-mode and --max-in-flight are valid only for load"
fi
case "$OBSERVABILITY" in true|false) ;; *) die "DEMO_OBSERVABILITY must be true or false";; esac
case "$DEMO_CONTINUATION_MODE" in
  explicit|direct) ;;
  *) die "--continuation must be explicit or direct";;
esac
case "$DEMO_MACHINE_MODE" in
  standalone) STATE_MACHINE_ID="evidence-registry";;
  composite) STATE_MACHINE_ID="composite";;
  *) die "--machine must be standalone or composite";;
esac
DIRECT_RESULT_ACTIVATION_SETTING="# direct result emission is not activated"
if [ "$DEMO_CONTINUATION_MODE" = direct ]; then
  DIRECT_RESULT_ACTIVATION_SETTING="yano.app-chain.chains[0].machines.evidence-registry.activations.direct-result-emission=1"
fi

validate_decimal() {
  local name="$1" value="$2" minimum="$3" maximum="$4" number
  [[ "$value" =~ ^[0-9]+$ ]] || die "$name must be a decimal integer"
  number=$((10#$value))
  [ "$number" -ge "$minimum" ] && [ "$number" -le "$maximum" ] \
    || die "$name must be between $minimum and $maximum"
}

validate_decimal DEMO_HTTP_BASE "$DEMO_HTTP_BASE" 1 65533
validate_decimal DEMO_SERVER_BASE "${DEMO_SERVER_BASE:-13337}" 1 65533
validate_decimal DEMO_UI_PORT "$DEMO_UI_PORT" 1 65535
validate_decimal DEMO_KAFKA_PORT "$DEMO_KAFKA_PORT" 1 65535
validate_decimal DEMO_S3_PORT "$DEMO_S3_PORT" 1 65535
validate_decimal DEMO_IPFS_PORT "$DEMO_IPFS_PORT" 1 65535
validate_decimal DEMO_PROMETHEUS_PORT "$DEMO_PROMETHEUS_PORT" 1 65535
validate_decimal DEMO_GRAFANA_PORT "$DEMO_GRAFANA_PORT" 1 65535
if [ "$COMMAND" = load ]; then
  validate_decimal --count "$LOAD_COUNT" 1 50000
  validate_decimal --concurrency "$LOAD_CONCURRENCY" 1 16
  [ "$((10#$LOAD_CONCURRENCY))" -le "$((10#$LOAD_COUNT))" ] \
    || die "--concurrency must not exceed --count"
  [[ "$LOAD_ID_PREFIX" =~ ^[a-z][a-z0-9-]{0,55}$ ]] \
    || die "--id-prefix must match [a-z][a-z0-9-]{0,55}"
  case "$LOAD_MODE" in lifecycle|pipeline) ;;
    *) die "--load-mode must be lifecycle or pipeline";;
  esac
  if [ -n "$LOAD_MAX_IN_FLIGHT" ]; then
    [ "$LOAD_MODE" = pipeline ] \
      || die "--max-in-flight is valid only with --load-mode pipeline"
    validate_decimal --max-in-flight "$LOAD_MAX_IN_FLIGHT" 1 5000
    [ "$((10#$LOAD_MAX_IN_FLIGHT))" -ge "$((10#$LOAD_CONCURRENCY))" ] \
      || die "--max-in-flight must not be less than --concurrency"
    [ "$((10#$LOAD_MAX_IN_FLIGHT))" -le "$((10#$LOAD_COUNT))" ] \
      || die "--max-in-flight must not exceed --count"
  fi
fi
validate_decimal DEMO_SCENARIO_TIMEOUT_SECONDS "$DEMO_SCENARIO_TIMEOUT_SECONDS" 1 86400
validate_decimal DEMO_SCENARIO_POLL_INTERVAL_MILLIS "$DEMO_SCENARIO_POLL_INTERVAL_MILLIS" 1 60000
validate_decimal DEMO_ANCHOR_FUND_TIMEOUT_SECONDS "$DEMO_ANCHOR_FUND_TIMEOUT_SECONDS" 60 86400

load_network_profile() {
  local file="$SCRIPT_DIR/config/networks/$DEMO_NETWORK.env" line key value seen="|"
  PROFILE_NETWORK=""; PROFILE_PUBLIC=""; PROFILE_MAINNET=""
  PROFILE_AUTO_FAUCET=""; PROFILE_DEFAULT_ANCHOR=""; PROFILE_ALLOW_ANCHOR=""
  PROFILE_L1_MODE=""; PROFILE_WARMUP_SECONDS=""; PROFILE_PROTOCOL_MAGIC=""
  PROFILE_ANCHOR_EVERY_BLOCKS=""; PROFILE_ANCHOR_MAX_INTERVAL_MINUTES=""
  PROFILE_SYNC_TIMEOUT_SECONDS=""
  [ -f "$file" ] || die "unsupported network profile: $DEMO_NETWORK"
  while IFS= read -r line || [ -n "$line" ]; do
    case "$line" in ''|'#'*) continue;; esac
    key="${line%%=*}"; value="${line#*=}"
    [ "$line" != "$key" ] || die "invalid network profile line in $file"
    case "$seen" in *"|$key|"*) die "duplicate network profile key in $file: $key";; esac
    seen="$seen$key|"
    case "$key" in
      network) PROFILE_NETWORK="$value";;
      protocol_magic) PROFILE_PROTOCOL_MAGIC="$value";;
      public) PROFILE_PUBLIC="$value";;
      mainnet) PROFILE_MAINNET="$value";;
      auto_faucet) PROFILE_AUTO_FAUCET="$value";;
      default_anchor) PROFILE_DEFAULT_ANCHOR="$value";;
      allow_anchor) PROFILE_ALLOW_ANCHOR="$value";;
      l1_mode) PROFILE_L1_MODE="$value";;
      warmup_seconds) PROFILE_WARMUP_SECONDS="$value";;
      anchor_every_blocks) PROFILE_ANCHOR_EVERY_BLOCKS="$value";;
      anchor_max_interval_minutes) PROFILE_ANCHOR_MAX_INTERVAL_MINUTES="$value";;
      sync_timeout_seconds) PROFILE_SYNC_TIMEOUT_SECONDS="$value";;
      *) die "unknown network profile key in $file: $key";;
    esac
  done < "$file"
  [ "$PROFILE_NETWORK" = "$DEMO_NETWORK" ] \
    || die "network profile identity does not match $DEMO_NETWORK"
  for value in "$PROFILE_PUBLIC" "$PROFILE_MAINNET" "$PROFILE_AUTO_FAUCET" \
    "$PROFILE_DEFAULT_ANCHOR" "$PROFILE_ALLOW_ANCHOR"; do
    case "$value" in true|false) ;; *) die "invalid boolean in network profile: $file";; esac
  done
  case "$PROFILE_L1_MODE" in producer|relay) ;; *) die "invalid l1_mode in $file";; esac
  validate_decimal protocol_magic "$PROFILE_PROTOCOL_MAGIC" 1 4294967295
  validate_decimal warmup_seconds "$PROFILE_WARMUP_SECONDS" 0 300
  validate_decimal anchor_every_blocks "$PROFILE_ANCHOR_EVERY_BLOCKS" 1 1000000
  validate_decimal anchor_max_interval_minutes "$PROFILE_ANCHOR_MAX_INTERVAL_MINUTES" 1 1440
  validate_decimal sync_timeout_seconds "$PROFILE_SYNC_TIMEOUT_SECONDS" 60 86400
  case "$DEMO_NETWORK:$PROFILE_PROTOCOL_MAGIC" in
    devnet:42|preview:2|preprod:1|mainnet:764824073) ;;
    *) die "protocol_magic does not match the selected built-in network";;
  esac
  if [ "$PROFILE_PUBLIC" = true ]; then
    [ "$PROFILE_AUTO_FAUCET" = false ] && [ "$PROFILE_L1_MODE" = relay ] \
      || die "public profiles must use relay mode and must never auto-fund"
  fi
  [ "$PROFILE_DEFAULT_ANCHOR" = false ] || [ "$PROFILE_ALLOW_ANCHOR" = true ] \
    || die "a default anchor requires allow_anchor=true"
  if [ "$PROFILE_MAINNET" = true ]; then
    [ "$DEMO_NETWORK" = mainnet ] && [ "$PROFILE_PUBLIC" = true ] \
      && [ "$PROFILE_ALLOW_ANCHOR" = false ] && [ "$PROFILE_DEFAULT_ANCHOR" = false ] \
      || die "the mainnet profile must be public and forbid automatic anchoring"
  fi
}

load_network_profile
[ "$ANCHOR_KEY_FILE_EXPLICIT" = false ] || [ -n "$ANCHOR_KEY_FILE" ] \
  || die "--anchor-key-file requires a non-empty path"
case "$COMMAND" in help|status|stop) SAFE_WITHOUT_MAINNET_GUARD=true;; *) SAFE_WITHOUT_MAINNET_GUARD=false;; esac
[ "$PROFILE_MAINNET" = false ] || [ "$ENABLE_MAINNET" = true ] \
  || [ "$SAFE_WITHOUT_MAINNET_GUARD" = true ] \
  || die "mainnet requires the explicit --enable-mainnet guard"
ANCHOR_ENABLED="$PROFILE_DEFAULT_ANCHOR"
if [ "$PROFILE_PUBLIC" = true ]; then
  if [ -n "$PUBLIC_ANCHOR_CONFIRMATION" ]; then
    [ "$PUBLIC_ANCHOR_CONFIRMATION" = "$DEMO_NETWORK" ] \
      || die "--confirm-public-anchor must exactly name the selected network"
    [ "$PROFILE_ALLOW_ANCHOR" = true ] \
      || die "the $DEMO_NETWORK profile forbids automatic anchor/value operations"
    [ -n "$ANCHOR_KEY_FILE" ] \
      || die "public anchoring also requires --anchor-key-file with an operator-funded wallet"
    ANCHOR_ENABLED=true
  elif [ -n "$ANCHOR_KEY_FILE" ]; then
    die "a public anchor key does not authorize spending; add --confirm-public-anchor $DEMO_NETWORK"
  fi
else
  [ -z "$PUBLIC_ANCHOR_CONFIRMATION" ] \
    || die "--confirm-public-anchor is only valid for a public network"
  [ -z "$ANCHOR_KEY_FILE" ] || ANCHOR_ENABLED=true
fi
STORAGE_GATE=app-final
REQUIRE_ANCHOR=false
if [ "$ANCHOR_ENABLED" = true ]; then
  STORAGE_GATE=l1-anchored
  REQUIRE_ANCHOR=true
fi
COMPOSITE_PROFILE_DIGEST_SETTING="# standalone machine has no composite profile trust root"
if [ "$DEMO_MACHINE_MODE" = composite ]; then
  COMPOSITE_PROFILE_DIGEST="$(profile_digest_for \
    "$STORAGE_GATE:$DEMO_CONTINUATION_MODE")"
  COMPOSITE_PROFILE_DIGEST_SETTING="demo.composite-profile-digest=$COMPOSITE_PROFILE_DIGEST"
fi

command -v python3 >/dev/null 2>&1 || die "required command not found: python3"
normalize_base_path() {
  local label="$1" value="$2"
  [ -n "$value" ] || die "$label must not be empty"
  case "$value" in *$'\n'*|*$'\r'*|*'$'*) die "$label contains an unsafe character";; esac
  python3 - "$value" <<'PY'
import os, sys
value = os.path.expanduser(sys.argv[1])
if ".." in value.split(os.sep):
    raise SystemExit(1)
print(os.path.realpath(os.path.abspath(value)))
PY
}
normalize_file_path() {
  local label="$1" value="$2"
  [ -n "$value" ] || die "$label must not be empty"
  case "$value" in *$'\n'*|*$'\r'*|*'$'*) die "$label contains an unsafe character";; esac
  python3 - "$value" <<'PY'
import os, sys
value = os.path.expanduser(sys.argv[1])
if ".." in value.split(os.sep):
    raise SystemExit(1)
print(os.path.abspath(value))
PY
}
DATA_BASE="$(normalize_base_path DEMO_DATA_ROOT "${DEMO_DATA_ROOT:-$SCRIPT_DIR/.demo-data}")" \
  || die "DEMO_DATA_ROOT must be a safe path without '..'"
SECRET_BASE="$(normalize_base_path DEMO_SECRET_ROOT "${DEMO_SECRET_ROOT:-$SCRIPT_DIR/.demo-secrets}")" \
  || die "DEMO_SECRET_ROOT must be a safe path without '..'"
RUNTIME_BASE="$(normalize_base_path DEMO_RUNTIME_ROOT "${DEMO_RUNTIME_ROOT:-$SCRIPT_DIR/.demo-runtime}")" \
  || die "DEMO_RUNTIME_ROOT must be a safe path without '..'"
python3 - "$DATA_BASE" "$SECRET_BASE" "$RUNTIME_BASE" <<'PY' \
  || die "DEMO_DATA_ROOT, DEMO_SECRET_ROOT and DEMO_RUNTIME_ROOT must be pairwise disjoint"
from pathlib import Path
import sys

roots = [("data", Path(value).resolve(strict=False)) for value in sys.argv[1:2]]
roots += [("secrets", Path(value).resolve(strict=False)) for value in sys.argv[2:3]]
roots += [("runtime", Path(value).resolve(strict=False)) for value in sys.argv[3:4]]
for index, (left_name, left) in enumerate(roots):
    for right_name, right in roots[index + 1:]:
        if left == right or left in right.parents or right in left.parents:
            raise SystemExit(f"{left_name} and {right_name} roots overlap")
PY
NETWORK_ROOT="$DATA_BASE/networks/$DEMO_NETWORK"
DATA_ROOT="$NETWORK_ROOT/instances/$INSTANCE/$MODE"
L1_ROOT="$NETWORK_ROOT/l1/$MODE"
SECRET_ROOT="$SECRET_BASE/networks/$DEMO_NETWORK/$INSTANCE/$MODE"
RUNTIME_INSTANCE_ROOT="$RUNTIME_BASE/networks/$DEMO_NETWORK/$INSTANCE"
RUNTIME_ROOT="$RUNTIME_INSTANCE_ROOT/$MODE"

PLUGIN_DIR="$RUNTIME_ROOT/plugins"
REPORT_DIR="$DATA_ROOT/reports"
COMPOSE_ENV="$RUNTIME_ROOT/compose.env"
RUNNER_CONFIG="$RUNTIME_ROOT/runner-$MODE.properties"
S3_BOOTSTRAP_CONFIG="$RUNTIME_ROOT/s3-bootstrap-$MODE.properties"
UI_CONFIG="$RUNTIME_ROOT/ui-$MODE.properties"
KAFKA_PASSWD_FILE="$RUNTIME_ROOT/kafka-passwd"
KAFKA_GROUP_FILE="$RUNTIME_ROOT/kafka-group"
NODE_CONFIG_DIR="$SECRET_ROOT/nodes-$MODE"
MEMBER_KEY_DIR="$SECRET_ROOT/member-keys"
NETWORK_MARKER="$NETWORK_ROOT/network-identity.json"
INSTANCE_MARKER="$DATA_ROOT/appchain-identity.json"
ANCHOR_BINDING="$DATA_ROOT/anchor-binding.json"
L1_LEASE="$L1_ROOT/demo-owner.json"
RETIRE_MARKER="$NETWORK_ROOT/retired/$MODE/$INSTANCE.json"
if [ "$DEMO_NETWORK" = devnet ]; then
  SHELLEY_GENESIS_FILE="$NETWORK_ROOT/l1/shared/shelley-genesis.json"
  GENESIS_TIMESTAMP_FILE="$NETWORK_ROOT/l1/shared/genesis-timestamp"
else
  SHELLEY_GENESIS_FILE="$APP_DIR/config/network/$DEMO_NETWORK/shelley-genesis.json"
  GENESIS_TIMESTAMP_FILE=""
fi

API_KEY_FILE="$SECRET_ROOT/yano-api-key"
S3_BOOTSTRAP_ACCESS_FILE="$SECRET_ROOT/s3-bootstrap-access-key"
S3_BOOTSTRAP_SECRET_FILE="$SECRET_ROOT/s3-bootstrap-secret-key"
S3_IAM_MASTER_KEY_FILE="$SECRET_ROOT/s3-iam-master-key"
S3_RUNNER_ACCESS_FILE="$(normalize_file_path DEMO_HOST_S3_RUNNER_ACCESS_KEY_FILE \
  "${DEMO_HOST_S3_RUNNER_ACCESS_KEY_FILE:-$SECRET_ROOT/s3-runner-access-key}")" \
  || die "DEMO_HOST_S3_RUNNER_ACCESS_KEY_FILE must be a safe path without '..'"
S3_RUNNER_SECRET_FILE="$(normalize_file_path DEMO_HOST_S3_RUNNER_SECRET_KEY_FILE \
  "${DEMO_HOST_S3_RUNNER_SECRET_KEY_FILE:-$SECRET_ROOT/s3-runner-secret-key}")" \
  || die "DEMO_HOST_S3_RUNNER_SECRET_KEY_FILE must be a safe path without '..'"
S3_EXECUTOR_ACCESS_FILE="$(normalize_file_path DEMO_HOST_S3_EXECUTOR_ACCESS_KEY_FILE \
  "${DEMO_HOST_S3_EXECUTOR_ACCESS_KEY_FILE:-$SECRET_ROOT/s3-executor-access-key}")" \
  || die "DEMO_HOST_S3_EXECUTOR_ACCESS_KEY_FILE must be a safe path without '..'"
S3_EXECUTOR_SECRET_FILE="$(normalize_file_path DEMO_HOST_S3_EXECUTOR_SECRET_KEY_FILE \
  "${DEMO_HOST_S3_EXECUTOR_SECRET_KEY_FILE:-$SECRET_ROOT/s3-executor-secret-key}")" \
  || die "DEMO_HOST_S3_EXECUTOR_SECRET_KEY_FILE must be a safe path without '..'"
RUSTFS_IAM_SPEC_FILE="$SECRET_ROOT/rustfs-iam-spec.json"
GRAFANA_PASSWORD_FILE="$SECRET_ROOT/grafana-admin-password"
if [ -n "$ANCHOR_KEY_FILE" ]; then
  ANCHOR_KEY_FILE="$(normalize_file_path anchor-key-file "$ANCHOR_KEY_FILE")" \
    || die "--anchor-key-file must be a safe path without '..'"
fi
python3 - "$DATA_BASE" "$RUNTIME_BASE" "$API_KEY_FILE" "$S3_BOOTSTRAP_ACCESS_FILE" \
  "$S3_BOOTSTRAP_SECRET_FILE" "$S3_IAM_MASTER_KEY_FILE" \
  "$S3_RUNNER_ACCESS_FILE" "$S3_RUNNER_SECRET_FILE" \
  "$S3_EXECUTOR_ACCESS_FILE" "$S3_EXECUTOR_SECRET_FILE" "$GRAFANA_PASSWORD_FILE" \
  "${ANCHOR_KEY_FILE:-}" <<'PY' \
  || die "secret material must not be stored below a cleanup-managed data or runtime root"
from pathlib import Path
import sys

managed = [Path(value).resolve(strict=False) for value in sys.argv[1:3]]
for raw in sys.argv[3:]:
    if not raw:
        continue
    path = Path(raw).resolve(strict=False)
    if any(path == root or root in path.parents for root in managed):
        raise SystemExit(1)
PY

HTTP0=$((10#$DEMO_HTTP_BASE))
HTTP1=$((HTTP0 + 1))
HTTP2=$((HTTP0 + 2))
SERVER_BASE="${DEMO_SERVER_BASE:-13337}"
python3 - "$DEMO_CONNECTOR_SUBNET" "$DEMO_S3_IP" "$DEMO_KUBO_IP" "$DEMO_KAFKA_IP" <<'PY' \
  || die "connector subnet/IP values must be distinct canonical private IPv4 addresses"
import ipaddress, sys
network = ipaddress.ip_network(sys.argv[1], strict=True)
addresses = [ipaddress.ip_address(value) for value in sys.argv[2:]]
if network.version != 4 or not network.is_private or len(set(addresses)) != len(addresses):
    raise SystemExit(1)
if any(address.version != 4 or address not in network for address in addresses):
    raise SystemExit(1)
if str(network) != sys.argv[1] or any(str(address) != raw for address, raw in zip(addresses, sys.argv[2:])):
    raise SystemExit(1)
PY
python3 - "$MODE" "$HTTP0" "$HTTP1" "$HTTP2" "$DEMO_UI_PORT" "$DEMO_KAFKA_PORT" \
  "$DEMO_S3_PORT" "$DEMO_IPFS_PORT" "$DEMO_PROMETHEUS_PORT" "$DEMO_GRAFANA_PORT" \
  "$SERVER_BASE" "$((10#$SERVER_BASE + 1))" "$((10#$SERVER_BASE + 2))" <<'PY' \
  || die "published HTTP/service/server ports must not overlap"
import sys
mode, *raw = sys.argv[1:]
ports = list(map(int, raw[:9]))
if mode == "host":
    ports.extend(map(int, raw[9:]))
if len(ports) != len(set(ports)):
    raise SystemExit(1)
PY
PROJECT_SCOPE_ID="$(python3 - "$DATA_ROOT" <<'PY'
import hashlib
import os
import sys
print(hashlib.sha256(os.path.abspath(sys.argv[1]).encode("utf-8")).hexdigest()[:8])
PY
)"
PROJECT_NAME="yano-effects-${DEMO_NETWORK}-${INSTANCE}-${PROJECT_SCOPE_ID}"
DEMO_HOST_UID="$(id -u)"
DEMO_HOST_GID="$(id -g)"
[[ "$DEMO_HOST_UID" =~ ^[1-9][0-9]*$ ]] \
  || die "the Compose demo must run as a non-root host user"
[[ "$DEMO_HOST_GID" =~ ^[1-9][0-9]*$ ]] \
  || die "the Compose demo requires a non-root primary host group"
INSTANCE_TARGET="$INSTANCE"
COMPOSE_S3_TARGET_ID="s3-compose-${DEMO_NETWORK}-${INSTANCE_TARGET}-${PROJECT_SCOPE_ID}"
COMPOSE_IPFS_TARGET_ID="ipfs-compose-${DEMO_NETWORK}-${INSTANCE_TARGET}-${PROJECT_SCOPE_ID}"
COMPOSE_KAFKA_TARGET_ID="kafka-compose-${DEMO_NETWORK}-${INSTANCE_TARGET}-${PROJECT_SCOPE_ID}"
S3_PROVIDER="rustfs"
S3_PROVIDER_VERSION="1.0.0-beta.9"
S3_LAYOUT_VERSION="2"
S3_CONFIG_HASH=""
GIT_ID="$(git -C "$REPO_DIR" rev-parse --short=12 HEAD 2>/dev/null || printf local)"
DEMO_YANO_IMAGE="${DEMO_YANO_IMAGE:-yano-adr013-node:$GIT_ID}"
DEMO_RUNNER_IMAGE="${DEMO_RUNNER_IMAGE:-yano-adr013-runner:$GIT_ID}"
: "${DEMO_RUSTFS_IMAGE:?config/images.env must pin the RustFS demo image}"
: "${DEMO_KUBO_IMAGE:?config/images.env must pin the Kubo demo image}"

require() { command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"; }

validate_target_id() {
  local label="$1" value="$2"
  [[ "$value" =~ ^[a-z][a-z0-9-]{0,62}$ ]] \
    || die "$label must match [a-z][a-z0-9-]{0,62}"
}

resolve_host_target_ids() {
  validate_host_connector_locators
  require_host_target_for_override DEMO_HOST_S3_ENDPOINT DEMO_HOST_S3_TARGET_ID
  require_host_target_for_override DEMO_HOST_IPFS_API_URL DEMO_HOST_IPFS_TARGET_ID
  require_host_target_for_override DEMO_HOST_KAFKA_BOOTSTRAP_SERVERS DEMO_HOST_KAFKA_TARGET_ID
  HOST_S3_TARGET_ID="${DEMO_HOST_S3_TARGET_ID:-s3-host-${DEMO_NETWORK}-${INSTANCE_TARGET}-${PROJECT_SCOPE_ID}}"
  HOST_IPFS_TARGET_ID="${DEMO_HOST_IPFS_TARGET_ID:-ipfs-host-${DEMO_NETWORK}-${INSTANCE_TARGET}-${PROJECT_SCOPE_ID}}"
  HOST_KAFKA_TARGET_ID="${DEMO_HOST_KAFKA_TARGET_ID:-kafka-host-${DEMO_NETWORK}-${INSTANCE_TARGET}-${PROJECT_SCOPE_ID}}"
  validate_target_id DEMO_HOST_S3_TARGET_ID "$HOST_S3_TARGET_ID"
  validate_target_id DEMO_HOST_IPFS_TARGET_ID "$HOST_IPFS_TARGET_ID"
  validate_target_id DEMO_HOST_KAFKA_TARGET_ID "$HOST_KAFKA_TARGET_ID"
}

validate_host_connector_locators() {
  local s3="${DEMO_HOST_S3_ENDPOINT:-http://127.0.0.1:$DEMO_S3_PORT}"
  local ipfs="${DEMO_HOST_IPFS_API_URL:-http://127.0.0.1:$DEMO_IPFS_PORT}"
  local kafka="${DEMO_HOST_KAFKA_BOOTSTRAP_SERVERS:-127.0.0.1:$DEMO_KAFKA_PORT}"
  python3 - "$s3" "$ipfs" "$kafka" <<'PY' \
    || die "host connector locators must be bounded credential-free HTTP endpoints and host:port Kafka servers"
import ipaddress
import re
import sys
from urllib.parse import urlsplit


def printable_ascii(value, label):
    if not 1 <= len(value) <= 2048 or any(ord(char) < 0x21 or ord(char) > 0x7e for char in value):
        raise ValueError(f"invalid {label}")


def endpoint(value, label):
    printable_ascii(value, label)
    parsed = urlsplit(value)
    if (parsed.scheme not in ("http", "https") or not parsed.hostname
            or parsed.username is not None or parsed.password is not None
            or parsed.query or parsed.fragment or parsed.path not in ("", "/")):
        raise ValueError(f"invalid {label}")
    try:
        port = parsed.port
    except ValueError as error:
        raise ValueError(f"invalid {label}") from error
    if port is None or not 1 <= port <= 65535:
        raise ValueError(f"invalid {label}")
    if parsed.hostname != parsed.hostname.lower():
        raise ValueError(f"invalid {label}")


def kafka_servers(value):
    printable_ascii(value, "Kafka bootstrap servers")
    if any(char in value for char in "@/?#;=\\"):
        raise ValueError("invalid Kafka bootstrap servers")
    entries = value.split(",")
    if not 1 <= len(entries) <= 32 or any(not entry for entry in entries):
        raise ValueError("invalid Kafka bootstrap servers")
    hostname = re.compile(r"(?=.{1,253}\Z)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?:\.(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?))*\Z")
    for entry in entries:
        parsed = urlsplit("kafka://" + entry)
        try:
            port = parsed.port
        except ValueError as error:
            raise ValueError("invalid Kafka bootstrap servers") from error
        if (not parsed.hostname or port is None or not 1 <= port <= 65535
                or parsed.path or parsed.query or parsed.fragment
                or parsed.username is not None or parsed.password is not None):
            raise ValueError("invalid Kafka bootstrap servers")
        host = parsed.hostname
        try:
            ipaddress.ip_address(host)
        except ValueError:
            if not hostname.fullmatch(host):
                raise ValueError("invalid Kafka bootstrap servers")


endpoint(sys.argv[1], "S3 endpoint")
endpoint(sys.argv[2], "IPFS API endpoint")
kafka_servers(sys.argv[3])
PY
}

require_host_target_for_override() {
  local locator="$1" target="$2" locator_value target_value
  locator_value="${!locator-__YANO_DEMO_UNSET__}"
  [ "$locator_value" != __YANO_DEMO_UNSET__ ] || return 0
  [ -n "$locator_value" ] || die "$locator must not be empty"
  target_value="${!target-}"
  [ -n "$target_value" ] || die "$target is required when $locator is overridden"
}

# Complete every pure option/path/locator check before the operation wrapper
# creates its private lock directory. Invalid input must leave no managed
# filesystem artifacts behind.
if [ "$MODE" = host ]; then
  resolve_host_target_ids
fi

OPERATION_LOCK_REQUIRED=false
case "$COMMAND" in
  prepare|config|up) OPERATION_LOCK_REQUIRED=true;;
  run|publish|republish|verify|replay|load|probe|stop|clean)
    if [ -e "$NETWORK_ROOT" ] || [ -L "$NETWORK_ROOT" ]; then
      OPERATION_LOCK_REQUIRED=true
    fi
    ;;
esac
if [ "$OPERATION_LOCK_REQUIRED" = true ]; then
  EXPECTED_OPERATION_LOCK_ROOT="$DATA_BASE/.yano-operation-locks/$DEMO_NETWORK/$MODE"
  if [ -n "${YANO_DEMO_OPERATION_LOCK_FD:-}" ] \
      || [ -n "${YANO_DEMO_OPERATION_LOCK_ROOT:-}" ] \
      || [ -n "${YANO_DEMO_OPERATION_WATCH_FD:-}" ] \
      || [ -n "${YANO_DEMO_OPERATION_READY_READ_FD:-}" ] \
      || [ -n "${YANO_DEMO_OPERATION_READY_WRITE_FD:-}" ]; then
    [ "${YANO_DEMO_OPERATION_LOCK_ROOT:-}" = "$EXPECTED_OPERATION_LOCK_ROOT" ] \
      || die "inherited operation lock does not match this network/deployment"
    [[ "${YANO_DEMO_OPERATION_LOCK_FD:-}" =~ ^[0-9]+$ ]] \
      || die "inherited operation lock descriptor is invalid"
    [[ "${YANO_DEMO_OPERATION_WATCH_FD:-}" =~ ^[0-9]+$ ]] \
      || die "inherited operation watch descriptor is invalid"
    [[ "${YANO_DEMO_OPERATION_READY_READ_FD:-}" =~ ^[0-9]+$ ]] \
      || die "inherited operation ready-read descriptor is invalid"
    [[ "${YANO_DEMO_OPERATION_READY_WRITE_FD:-}" =~ ^[0-9]+$ ]] \
      || die "inherited operation ready-write descriptor is invalid"
    python3 "$SCRIPT_DIR/tools/lifecycle.py" operation-validate \
      --data-root "$DATA_BASE" --network "$DEMO_NETWORK" --deployment "$MODE" \
      --fd "${YANO_DEMO_OPERATION_LOCK_FD:-}" >/dev/null \
      || die "deployment command does not hold its exact operation lock"

    # The detached watchdog inherits the same locked open-file-description.
    # Do not release this shell's copy until the watcher has validated both
    # pipes, detached from our process group, and explicitly acknowledged it.
    OPERATION_LOCK_FD="$YANO_DEMO_OPERATION_LOCK_FD"
    OPERATION_WATCH_FD="$YANO_DEMO_OPERATION_WATCH_FD"
    OPERATION_READY_READ_FD="$YANO_DEMO_OPERATION_READY_READ_FD"
    OPERATION_READY_WRITE_FD="$YANO_DEMO_OPERATION_READY_WRITE_FD"
    python3 "$SCRIPT_DIR/tools/lifecycle.py" operation-watch \
      --data-root "$DATA_BASE" --network "$DEMO_NETWORK" --deployment "$MODE" \
      --fd "$OPERATION_WATCH_FD" --lock-fd "$OPERATION_LOCK_FD" \
      --ready-fd "$OPERATION_READY_WRITE_FD" \
      --unused-ready-read-fd "$OPERATION_READY_READ_FD" --parent-pid "$$" &
    OPERATION_WATCH_PID=$!
    eval "exec ${OPERATION_READY_WRITE_FD}>&-"
    if ! IFS= read -r -n 1 OPERATION_READY_BYTE <&"$OPERATION_READY_READ_FD" \
        || [ "$OPERATION_READY_BYTE" != R ]; then
      die "deployment operation watchdog did not become ready"
    fi
    eval "exec ${OPERATION_READY_READ_FD}<&-"
    eval "exec ${OPERATION_WATCH_FD}<&-"
    eval "exec ${OPERATION_LOCK_FD}>&-"
    unset YANO_DEMO_OPERATION_LOCK_ROOT YANO_DEMO_OPERATION_LOCK_FD \
      YANO_DEMO_OPERATION_WATCH_FD YANO_DEMO_OPERATION_READY_READ_FD \
      YANO_DEMO_OPERATION_READY_WRITE_FD OPERATION_LOCK_FD OPERATION_WATCH_FD \
      OPERATION_READY_READ_FD OPERATION_READY_WRITE_FD OPERATION_READY_BYTE
  else
    exec python3 "$SCRIPT_DIR/tools/lifecycle.py" operation-run \
      --data-root "$DATA_BASE" --network "$DEMO_NETWORK" --deployment "$MODE" -- \
      "$SCRIPT_DIR/demo.sh" "${ORIGINAL_ARGS[@]}"
  fi
fi

single_line() {
  local label="$1" value="$2"
  case "$value" in *$'\n'*|*$'\r'*) die "$label must not contain CR or LF";; esac
}

compose_env_value() {
  local label="$1" value="$2"
  single_line "$label" "$value"
  case "$value" in *'$'*|*'\'*) die "$label contains an unsafe Compose env character";; esac
}

ensure_secret() {
  local file="$1" value="$2"
  printf '%s\n' "$value" \
    | python3 "$SCRIPT_DIR/tools/secret_file.py" --path "$file" \
    || die "could not securely create or validate secret: $file"
}

read_secret() {
  local file="$1"
  python3 - "$file" <<'PY' || exit 1
import errno
import os
import stat
import sys

path = os.path.abspath(os.path.expanduser(sys.argv[1]))
flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
try:
    descriptor = os.open(path, flags)
except OSError as error:
    print(f"error: cannot safely open secret file {path}: {error.strerror}", file=sys.stderr)
    raise SystemExit(1)
try:
    before = os.fstat(descriptor)
    if not stat.S_ISREG(before.st_mode) or before.st_uid != os.geteuid():
        raise ValueError("must be a regular file owned by the launcher user")
    if before.st_nlink != 1:
        raise ValueError("must have exactly one hard link")
    mode = stat.S_IMODE(before.st_mode)
    if mode & 0o077 or not mode & 0o400 or mode & 0o111:
        raise ValueError("must be owner-only, readable, and non-executable (0400 or 0600)")
    if before.st_size < 1 or before.st_size > 4096:
        raise ValueError("must contain one bounded line (1..4096 bytes)")
    data = b""
    while len(data) <= 4096:
        chunk = os.read(descriptor, 4097 - len(data))
        if not chunk:
            break
        data += chunk
    after = os.fstat(descriptor)
    if (before.st_dev, before.st_ino, before.st_size) != (after.st_dev, after.st_ino, after.st_size):
        raise ValueError("changed while it was being read")
    if data.endswith(b"\n"):
        data = data[:-1]
    if not data or b"\n" in data or b"\r" in data:
        raise ValueError("must contain exactly one non-empty line")
    if any(byte < 0x21 or byte > 0x7e for byte in data):
        raise ValueError("must contain printable ASCII without spaces or controls")
    sys.stdout.buffer.write(data)
except (OSError, ValueError) as error:
    print(f"error: invalid secret file {path}: {error}", file=sys.stderr)
    raise SystemExit(1)
finally:
    os.close(descriptor)
PY
}

ensure_private_directory() {
  local directory="$1"
  if [ ! -e "$directory" ]; then
    (umask 077; mkdir -p "$directory")
  fi
  python3 - "$directory" <<'PY' >/dev/null || die "private directory must be launcher-owned 0700: $directory"
import os
import stat
import sys
path = sys.argv[1]
info = os.lstat(path)
if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o700:
    raise SystemExit(1)
PY
}

prepare_private_state_directory() {
  local directory="$1"
  python3 - "$directory" <<'PY' >/dev/null \
    || die "private state directory must be a non-symlink launcher-owned 0700 directory: $directory"
import os
import stat
import sys

target = os.path.abspath(sys.argv[1])
parts = [part for part in target.split(os.sep) if part]
flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_CLOEXEC", 0)
no_follow = getattr(os, "O_NOFOLLOW", 0)
descriptor = os.open(os.sep, flags)
try:
    for index, part in enumerate(parts):
        try:
            child = os.open(part, flags | no_follow, dir_fd=descriptor)
        except FileNotFoundError:
            os.mkdir(part, mode=0o700, dir_fd=descriptor)
            child = os.open(part, flags | no_follow, dir_fd=descriptor)
        info = os.fstat(child)
        if not stat.S_ISDIR(info.st_mode):
            os.close(child)
            raise ValueError("path component is not a directory")
        os.close(descriptor)
        descriptor = child
        if index == len(parts) - 1:
            if info.st_uid != os.geteuid():
                raise ValueError("directory has the wrong owner")
            os.fchmod(descriptor, 0o700)
            final = os.fstat(descriptor)
            if stat.S_IMODE(final.st_mode) != 0o700:
                raise ValueError("directory mode is not private")
finally:
    os.close(descriptor)
PY
}

prepare_directories() {
  local dir i
  [ ! -e "$DATA_ROOT/connectors/minio" ] && [ ! -L "$DATA_ROOT/connectors/minio" ] \
    || die "legacy MinIO connector state is incompatible; retire/clean this preview instance or choose a new instance"
  [ ! -e "$DATA_ROOT/connectors/seaweedfs-v1" ] && [ ! -L "$DATA_ROOT/connectors/seaweedfs-v1" ] \
    || die "legacy SeaweedFS connector state is incompatible; retire/clean this preview instance or choose a new instance"
  mkdir -p "$RUNTIME_ROOT" "$PLUGIN_DIR" \
    "$DATA_ROOT/observability/prometheus" \
    "$DATA_ROOT/observability/grafana" "$DATA_ROOT/logs" "$L1_ROOT"
  chmod 700 "$RUNTIME_ROOT" "$PLUGIN_DIR"
  prepare_private_state_directory "$DATA_ROOT/connectors"
  prepare_private_state_directory "$DATA_ROOT/connectors/kafka"
  prepare_private_state_directory "$DATA_ROOT/connectors/rustfs-v1"
  prepare_private_state_directory "$DATA_ROOT/connectors/ipfs"
  prepare_private_state_directory "$REPORT_DIR"
  for i in 0 1 2; do
    for dir in "$L1_ROOT/node$i" "$DATA_ROOT/app-chain/node$i" \
      "$DATA_ROOT/logs/node$i"; do
      mkdir -p "$dir"
      chmod u+rwx "$dir"
    done
  done
  chmod u+rwx "$DATA_ROOT/observability/prometheus" \
    "$DATA_ROOT/observability/grafana" "$REPORT_DIR"
}

prepare_secrets() {
  local member_mode anchor_value runner_access_default runner_secret_default
  local executor_access_default executor_secret_default runner_external executor_external
  require openssl
  ensure_private_directory "$SECRET_ROOT"
  member_mode=generated
  MEMBER_KEYS="$(python3 "$SCRIPT_DIR/tools/ed25519_keys.py" \
    --directory "$MEMBER_KEY_DIR" --mode "$member_mode" --count 3)"
  [[ "$MEMBER_KEYS" =~ ^[0-9a-f]{64},[0-9a-f]{64},[0-9a-f]{64}$ ]] \
    || die "member key helper returned an invalid three-member identity"
  MEMBER_SEED_0="$(read_secret "$MEMBER_KEY_DIR/node0.seed")"
  MEMBER_SEED_1="$(read_secret "$MEMBER_KEY_DIR/node1.seed")"
  MEMBER_SEED_2="$(read_secret "$MEMBER_KEY_DIR/node2.seed")"
  [[ "$MEMBER_SEED_0,$MEMBER_SEED_1,$MEMBER_SEED_2" \
      =~ ^[0-9a-fA-F]{64},[0-9a-fA-F]{64},[0-9a-fA-F]{64}$ ]] \
    || die "member key files must each contain one 32-byte hexadecimal seed"
  PROPOSER_KEY="${MEMBER_KEYS%%,*}"
  RESULT_SIGNERS="${MEMBER_KEYS%,*}"
  [[ "$RESULT_SIGNERS" =~ ^[0-9a-f]{64},[0-9a-f]{64}$ ]] \
    || die "result signer policy must pre-authorize the primary and failover members"
  ensure_secret "$API_KEY_FILE" "$(openssl rand -hex 32)"
  ensure_secret "$S3_BOOTSTRAP_ACCESS_FILE" "yanobootstrap$(openssl rand -hex 8)"
  ensure_secret "$S3_BOOTSTRAP_SECRET_FILE" "$(openssl rand -hex 32)"
  ensure_secret "$S3_IAM_MASTER_KEY_FILE" "$(openssl rand -hex 32)"
  runner_access_default="$SECRET_ROOT/s3-runner-access-key"
  runner_secret_default="$SECRET_ROOT/s3-runner-secret-key"
  executor_access_default="$SECRET_ROOT/s3-executor-access-key"
  executor_secret_default="$SECRET_ROOT/s3-executor-secret-key"
  runner_external=false
  executor_external=false
  if [ "$S3_RUNNER_ACCESS_FILE" != "$runner_access_default" ] \
      || [ "$S3_RUNNER_SECRET_FILE" != "$runner_secret_default" ]; then
    [ "$S3_RUNNER_ACCESS_FILE" != "$runner_access_default" ] \
      && [ "$S3_RUNNER_SECRET_FILE" != "$runner_secret_default" ] \
      || die "runner S3 access-key and secret-key file overrides must be supplied together"
    runner_external=true
  fi
  if [ "$S3_EXECUTOR_ACCESS_FILE" != "$executor_access_default" ] \
      || [ "$S3_EXECUTOR_SECRET_FILE" != "$executor_secret_default" ]; then
    [ "$S3_EXECUTOR_ACCESS_FILE" != "$executor_access_default" ] \
      && [ "$S3_EXECUTOR_SECRET_FILE" != "$executor_secret_default" ] \
      || die "executor S3 access-key and secret-key file overrides must be supplied together"
    executor_external=true
  fi
  if [ "$runner_external" = false ]; then
    ensure_secret "$S3_RUNNER_ACCESS_FILE" "yanorunner$(openssl rand -hex 8)"
    ensure_secret "$S3_RUNNER_SECRET_FILE" "$(openssl rand -hex 32)"
  fi
  if [ "$executor_external" = false ]; then
    ensure_secret "$S3_EXECUTOR_ACCESS_FILE" "yanoexecutor$(openssl rand -hex 8)"
    ensure_secret "$S3_EXECUTOR_SECRET_FILE" "$(openssl rand -hex 32)"
  fi
  ensure_secret "$GRAFANA_PASSWORD_FILE" "$(openssl rand -hex 24)"
  if [ "$ANCHOR_ENABLED" = true ]; then
    if [ -z "$ANCHOR_KEY_FILE" ]; then
      [ "$DEMO_NETWORK" = devnet ] \
        || die "public anchoring requires --anchor-key-file for a funded owner-controlled wallet"
      ANCHOR_KEY_FILE="$SECRET_ROOT/anchor.seed"
      ensure_secret "$ANCHOR_KEY_FILE" "$(openssl rand -hex 32)"
    fi
    anchor_value="$(read_secret "$ANCHOR_KEY_FILE")"
    [[ "$anchor_value" =~ ^[0-9a-fA-F]{64}$ ]] \
      || die "anchor key file must contain one 32-byte hexadecimal seed"
    ANCHOR_KEY_VALUE="$(printf '%s' "$anchor_value" | tr 'A-F' 'a-f')"
  else
    ANCHOR_KEY_VALUE=""
  fi
  read_secret "$S3_RUNNER_ACCESS_FILE" >/dev/null
  read_secret "$S3_RUNNER_SECRET_FILE" >/dev/null
  read_secret "$S3_EXECUTOR_ACCESS_FILE" >/dev/null
  read_secret "$S3_EXECUTOR_SECRET_FILE" >/dev/null
  S3_CONFIG_HASH="$(python3 "$SCRIPT_DIR/tools/rustfs_iam_spec.py" \
    --output "$RUSTFS_IAM_SPEC_FILE" \
    --root-access "$S3_BOOTSTRAP_ACCESS_FILE" \
    --runner-access "$S3_RUNNER_ACCESS_FILE" \
    --executor-access "$S3_EXECUTOR_ACCESS_FILE")" \
    || die "could not securely create or validate the RustFS S3 IAM specification"
  [[ "$S3_CONFIG_HASH" =~ ^[0-9a-f]{64}$ ]] \
    || die "RustFS S3 IAM specification helper returned an invalid digest"
}

load_cached_key_material() {
  local anchor_value
  MEMBER_KEYS="$(python3 "$SCRIPT_DIR/tools/ed25519_keys.py" \
    --directory "$MEMBER_KEY_DIR" --mode generated --count 3 --existing-only)" \
    || die "persisted member key material is missing or unsafe"
  [[ "$MEMBER_KEYS" =~ ^[0-9a-f]{64},[0-9a-f]{64},[0-9a-f]{64}$ ]] \
    || die "persisted member key helper returned an invalid three-member identity"
  MEMBER_SEED_0="$(read_secret "$MEMBER_KEY_DIR/node0.seed")"
  MEMBER_SEED_1="$(read_secret "$MEMBER_KEY_DIR/node1.seed")"
  MEMBER_SEED_2="$(read_secret "$MEMBER_KEY_DIR/node2.seed")"
  [[ "$MEMBER_SEED_0,$MEMBER_SEED_1,$MEMBER_SEED_2" \
      =~ ^[0-9a-f]{64},[0-9a-f]{64},[0-9a-f]{64}$ ]] \
    || die "persisted member key files must contain canonical 32-byte seeds"
  PROPOSER_KEY="${MEMBER_KEYS%%,*}"
  RESULT_SIGNERS="${MEMBER_KEYS%,*}"
  [[ "$RESULT_SIGNERS" =~ ^[0-9a-f]{64},[0-9a-f]{64}$ ]] \
    || die "persisted result signer policy is malformed"
  if [ "$ANCHOR_ENABLED" = true ]; then
    if [ -z "$ANCHOR_KEY_FILE" ]; then
      [ "$DEMO_NETWORK" = devnet ] \
        || die "public anchored commands require the original --anchor-key-file"
      ANCHOR_KEY_FILE="$SECRET_ROOT/anchor.seed"
    fi
    anchor_value="$(read_secret "$ANCHOR_KEY_FILE")"
    [[ "$anchor_value" =~ ^[0-9a-fA-F]{64}$ ]] \
      || die "persisted anchor key file must contain one 32-byte hexadecimal seed"
    ANCHOR_KEY_VALUE="$(printf '%s' "$anchor_value" | tr 'A-F' 'a-f')"
  else
    ANCHOR_KEY_VALUE=""
  fi
}

verify_loaded_key_identity() {
  python3 - "$INSTANCE_MARKER" "$MEMBER_KEYS" "$PROPOSER_KEY" \
    "$RESULT_SIGNERS" "$ANCHOR_ENABLED" "$ANCHOR_KEY_VALUE" <<'PY' \
    || die "persisted signing keys do not match the immutable app-chain identity"
import hashlib
import json
import os
import stat
import sys


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate key")
        result[key] = value
    return result


path, members_csv, proposer, result_signers_csv, anchor_enabled, anchor_key = sys.argv[1:]
flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
descriptor = os.open(path, flags)
try:
    before = os.fstat(descriptor)
    if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.geteuid()
            or before.st_nlink != 1 or stat.S_IMODE(before.st_mode) != 0o600
            or not 1 <= before.st_size <= 65536):
        raise ValueError("unsafe app-chain identity marker")
    raw = b""
    while len(raw) <= 65536:
        chunk = os.read(descriptor, 65537 - len(raw))
        if not chunk:
            break
        raw += chunk
    after = os.fstat(descriptor)
    if ((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
            != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
            or len(raw) != before.st_size):
        raise ValueError("app-chain identity marker changed while being read")
finally:
    os.close(descriptor)
document = json.loads(raw.decode("utf-8"), object_pairs_hook=unique)
canonical = (json.dumps(document, ensure_ascii=False, allow_nan=False,
                        sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
if raw != canonical:
    raise ValueError("app-chain identity marker is not canonical")
members = members_csv.split(",")
result_signers = result_signers_csv.split(",")
membership = document.get("membership", {})
if (membership.get("members") != members or membership.get("proposer") != proposer
        or result_signers != members[:2]
        or membership.get("resultSigners") != result_signers):
    raise ValueError("member signing identity changed")
anchor = document.get("anchor", {})
enabled = anchor_enabled == "true"
fingerprint = (hashlib.sha256(b"yano-demo-anchor-signer-v1\0"
                              + anchor_key.encode("ascii")).hexdigest()
               if enabled else None)
if anchor.get("enabled") is not enabled or anchor.get("signerFingerprint") != fingerprint:
    raise ValueError("anchor signing identity changed")
PY
}

verify_cached_key_material() {
  [ "$(read_secret "$MEMBER_KEY_DIR/node0.seed")" = "$MEMBER_SEED_0" ] \
    && [ "$(read_secret "$MEMBER_KEY_DIR/node1.seed")" = "$MEMBER_SEED_1" ] \
    && [ "$(read_secret "$MEMBER_KEY_DIR/node2.seed")" = "$MEMBER_SEED_2" ] \
    || die "member key material changed after the immutable instance identity was prepared"
  if [ "$ANCHOR_ENABLED" = true ]; then
    [ "$(read_secret "$ANCHOR_KEY_FILE" | tr 'A-F' 'a-f')" = "$ANCHOR_KEY_VALUE" ] \
      || die "anchor key material changed after the immutable instance identity was prepared"
  fi
}

write_network_identity() {
  local output="$1" selected_shelley="$2"
  python3 - "$output" "$DEMO_NETWORK" "$PROFILE_PROTOCOL_MAGIC" \
    "$APP_DIR/config/network/$DEMO_NETWORK/byron-genesis.json" \
    "$selected_shelley" \
    "$APP_DIR/config/network/$DEMO_NETWORK/alonzo-genesis.json" \
    "$APP_DIR/config/network/$DEMO_NETWORK/conway-genesis.json" \
    "$APP_DIR/config/network/$DEMO_NETWORK/protocol-param.json" <<'PY'
import hashlib
import json
from pathlib import Path
import sys

output, network, magic, *files = sys.argv[1:]
names = ("byron", "shelley", "alonzo", "conway", "protocolParameters")
fingerprints = {}
for name, raw_path in zip(names, files):
    path = Path(raw_path)
    data = path.read_bytes()
    fingerprints[name] = {"sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
document = {
    "schemaVersion": 1,
    "kind": "yano.demo.network-identity",
    "layoutVersion": 1,
    "networkName": network,
    "protocolMagic": int(magic),
    "genesis": fingerprints,
}
if network == "devnet":
    selected = json.loads(Path(files[1]).read_text(encoding="utf-8"))
    document["generatedSystemStart"] = selected.get("systemStart")
Path(output).write_text(
    json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n",
    encoding="utf-8",
)
PY
}

validate_generated_genesis_pair() {
  python3 - "$1" "$2" <<'PY' || return 1
import datetime
import json
from pathlib import Path
import sys

genesis = json.loads(Path(sys.argv[1]).read_text(encoding="utf-8"))
millis = int(Path(sys.argv[2]).read_text(encoding="ascii").strip())
expected = datetime.datetime.fromtimestamp(
    millis / 1000, datetime.timezone.utc
).strftime("%Y-%m-%dT%H:%M:%SZ")
if genesis.get("systemStart") != expected or genesis.get("epochLength") != 500:
    raise SystemExit(1)
PY
}

prepare_network_identity() {
  local source="$APP_DIR/config/network/devnet/shelley-genesis.json"
  local identity now start
  require jq
  temporary_file; STAGED_SHELLEY="$LAST_TEMP_FILE"
  temporary_file; STAGED_GENESIS_TIMESTAMP="$LAST_TEMP_FILE"
  temporary_file; identity="$LAST_TEMP_FILE"

  if [ "$DEMO_NETWORK" = devnet ]; then
    [ -f "$source" ] || die "devnet Shelley genesis not found: $source"
    if [ -e "$SHELLEY_GENESIS_FILE" ] && [ -e "$GENESIS_TIMESTAMP_FILE" ]; then
      [ -f "$SHELLEY_GENESIS_FILE" ] && [ ! -L "$SHELLEY_GENESIS_FILE" ] \
        && [ -f "$GENESIS_TIMESTAMP_FILE" ] && [ ! -L "$GENESIS_TIMESTAMP_FILE" ] \
        || die "incomplete or unsafe shared devnet genesis state under $NETWORK_ROOT/l1/shared"
      validate_generated_genesis_pair "$SHELLEY_GENESIS_FILE" "$GENESIS_TIMESTAMP_FILE" \
        || die "retained devnet genesis and timestamp do not describe the same network identity"
      cp "$SHELLEY_GENESIS_FILE" "$STAGED_SHELLEY"
      cp "$GENESIS_TIMESTAMP_FILE" "$STAGED_GENESIS_TIMESTAMP"
    elif [ -e "$NETWORK_MARKER" ]; then
      [ -f "$NETWORK_MARKER" ] && [ ! -L "$NETWORK_MARKER" ] \
        || die "network identity marker is not a regular file"
      start="$(python3 - "$NETWORK_MARKER" <<'PY'
import json,sys
document=json.load(open(sys.argv[1], encoding="utf-8"))
value=document.get("generatedSystemStart")
if document.get("kind") != "yano.demo.network-identity" or not isinstance(value, str):
    raise SystemExit(1)
print(value)
PY
)" || die "retained network marker cannot reconstruct its generated devnet genesis"
      now="$(python3 - "$start" <<'PY'
from datetime import datetime
import sys
value=sys.argv[1]
if value.endswith("Z"):
    value=value[:-1]+"+00:00"
print(int(datetime.fromisoformat(value).timestamp()))
PY
)" || die "retained network marker has an invalid generated systemStart"
      jq --arg start "$start" '.epochLength = 500 | .systemStart = $start' \
        "$source" > "$STAGED_SHELLEY"
      printf '%s\n' "$((now * 1000))" > "$STAGED_GENESIS_TIMESTAMP"
    else
      now="$(date +%s)"
      if start="$(date -u -r "$now" '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null)"; then :
      else start="$(date -u -d "@$now" '+%Y-%m-%dT%H:%M:%SZ')"
      fi
      jq --arg start "$start" '.epochLength = 500 | .systemStart = $start' \
        "$source" > "$STAGED_SHELLEY"
      printf '%s\n' "$((now * 1000))" > "$STAGED_GENESIS_TIMESTAMP"
    fi
  else
    [ -f "$SHELLEY_GENESIS_FILE" ] && [ ! -L "$SHELLEY_GENESIS_FILE" ] \
      || die "$DEMO_NETWORK Shelley genesis not found or unsafe: $SHELLEY_GENESIS_FILE"
    cp "$SHELLEY_GENESIS_FILE" "$STAGED_SHELLEY"
  fi

  write_network_identity "$identity" "$STAGED_SHELLEY"
  if ! python3 "$SCRIPT_DIR/tools/lifecycle.py" ensure-network \
      --allowed-root "$DATA_BASE" --directory "$NETWORK_ROOT" \
      --identity-file "$identity" >/dev/null; then
    die "network identity mismatch; use a different --data-dir or restore the exact selected genesis"
  fi

}

install_staged_network_material() {
  local target_dir install_tmp
  [ "$DEMO_NETWORK" = devnet ] || return 0
  [ -n "$STAGED_SHELLEY" ] && [ -n "$STAGED_GENESIS_TIMESTAMP" ] \
    || die "devnet genesis was not staged before deployment acquisition"
  target_dir="$(dirname "$SHELLEY_GENESIS_FILE")"
  mkdir -p "$target_dir"
  chmod 700 "$target_dir"
  if [ ! -e "$SHELLEY_GENESIS_FILE" ]; then
    install_tmp="$target_dir/.shelley-genesis.tmp.$$"
    (umask 077; cp "$STAGED_SHELLEY" "$install_tmp")
    mv "$install_tmp" "$SHELLEY_GENESIS_FILE"
  fi
  if [ ! -e "$GENESIS_TIMESTAMP_FILE" ]; then
    install_tmp="$target_dir/.genesis-timestamp.tmp.$$"
    (umask 077; cp "$STAGED_GENESIS_TIMESTAMP" "$install_tmp")
    mv "$install_tmp" "$GENESIS_TIMESTAMP_FILE"
  fi
  cmp -s "$STAGED_SHELLEY" "$SHELLEY_GENESIS_FILE" \
    && cmp -s "$STAGED_GENESIS_TIMESTAMP" "$GENESIS_TIMESTAMP_FILE" \
    || die "shared devnet genesis changed during identity installation"
  validate_generated_genesis_pair "$SHELLEY_GENESIS_FILE" "$GENESIS_TIMESTAMP_FILE" \
    || die "installed devnet genesis pair is inconsistent"
}

write_instance_identity() {
  local output="$1" anchor_fingerprint="none" network_digest s3_id ipfs_id kafka_id
  local s3_locator ipfs_locator kafka_locator s3_provider s3_provider_version
  local s3_layout_version s3_config_hash
  network_digest="$(python3 - "$NETWORK_MARKER" <<'PY'
import hashlib, sys
print(hashlib.sha256(open(sys.argv[1], "rb").read()).hexdigest())
PY
)"
  if [ "$ANCHOR_ENABLED" = true ]; then
    anchor_fingerprint="$(printf '%s' "$ANCHOR_KEY_VALUE" | python3 -c \
      'import hashlib,sys; print(hashlib.sha256(b"yano-demo-anchor-signer-v1\0"+sys.stdin.buffer.read()).hexdigest())')"
  fi
  if [ "$MODE" = compose ]; then
    s3_id="$COMPOSE_S3_TARGET_ID"; ipfs_id="$COMPOSE_IPFS_TARGET_ID"; kafka_id="$COMPOSE_KAFKA_TARGET_ID"
    s3_locator="http://$DEMO_S3_IP:9000"
    s3_provider="$S3_PROVIDER"; s3_provider_version="$S3_PROVIDER_VERSION"
    s3_layout_version="$S3_LAYOUT_VERSION"; s3_config_hash="$S3_CONFIG_HASH"
    ipfs_locator="http://$DEMO_KUBO_IP:5001"
    kafka_locator="$DEMO_KAFKA_IP:19092"
  else
    s3_id="$HOST_S3_TARGET_ID"; ipfs_id="$HOST_IPFS_TARGET_ID"; kafka_id="$HOST_KAFKA_TARGET_ID"
    s3_locator="${DEMO_HOST_S3_ENDPOINT:-http://127.0.0.1:$DEMO_S3_PORT}"
    s3_provider="external-s3-compatible"; s3_provider_version=""
    s3_layout_version="1"; s3_config_hash=""
    ipfs_locator="${DEMO_HOST_IPFS_API_URL:-http://127.0.0.1:$DEMO_IPFS_PORT}"
    kafka_locator="${DEMO_HOST_KAFKA_BOOTSTRAP_SERVERS:-127.0.0.1:$DEMO_KAFKA_PORT}"
  fi
  python3 - "$output" "$DEMO_NETWORK" "$network_digest" "$INSTANCE" "$MODE" \
    "$DEMO_CHAIN_ID" "$MEMBER_KEYS" "$PROPOSER_KEY" "$RESULT_SIGNERS" \
    "$STORAGE_GATE" "$REQUIRE_ANCHOR" "$DEMO_CONTINUATION_MODE" \
    "$STATE_MACHINE_ID" "$EVIDENCE_CAPACITY_PER_BLOCK" \
    "$ANCHOR_ENABLED" "$PROFILE_ANCHOR_EVERY_BLOCKS" \
    "$PROFILE_ANCHOR_MAX_INTERVAL_MINUTES" "$anchor_fingerprint" "$PROJECT_NAME" \
    "$s3_id" "$s3_locator" "$ipfs_id" "$ipfs_locator" "$kafka_id" "$kafka_locator" \
    "$s3_provider" "$s3_provider_version" "$s3_layout_version" "$s3_config_hash" <<'PY'
import hashlib
import json
from pathlib import Path
import sys

(
    output, network, network_digest, instance, deployment, chain_id, members_csv,
    proposer, result_signers_csv, storage_gate, require_anchor, continuation_mode,
    state_machine, evidence_capacity,
    anchor_enabled, anchor_every,
    anchor_max_interval, anchor_fingerprint, project, s3_id, s3_locator, ipfs_id, ipfs_locator,
    kafka_id, kafka_locator, s3_provider, s3_provider_version, s3_layout_version,
    s3_config_hash,
) = sys.argv[1:]
members = members_csv.split(",")
result_signers = result_signers_csv.split(",")
if len(members) != 3 or len(set(members)) != 3 or result_signers != members[:2]:
    raise SystemExit("invalid immutable result signer policy")
document = {
    "schemaVersion": 1,
    "kind": "yano.demo.appchain-identity",
    "layoutVersion": 4,
    "networkName": network,
    "networkIdentitySha256": network_digest,
    "instanceId": instance,
    "deployment": deployment,
    "composeProject": project if deployment == "compose" else None,
    "chainIds": [chain_id],
    "stateMachine": {
        "provider": state_machine,
        "profileVersion": 2 if continuation_mode == "direct" else 1,
        "effectEmissionVersion": 1,
        "evidenceCapacityPerBlock": int(evidence_capacity),
    },
    "membership": {
        "members": members,
        "threshold": 2,
        "proposer": proposer,
        "resultSigners": result_signers,
    },
    "effects": {
        "storageGate": storage_gate,
        "requireAnchor": require_anchor == "true",
        "continuationMode": continuation_mode,
        "directResultEmissionActivationHeight": 1 if continuation_mode == "direct" else None,
    },
    "anchor": {
        "enabled": anchor_enabled == "true",
        "mode": "script" if anchor_enabled == "true" else "none",
        "everyBlocks": int(anchor_every) if anchor_enabled == "true" else None,
        "maxIntervalMinutes": int(anchor_max_interval) if anchor_enabled == "true" else None,
        "signerFingerprint": anchor_fingerprint if anchor_enabled == "true" else None,
    },
    "connectors": {
        "s3": {
            "targetId": s3_id,
            "locator": s3_locator,
            "profile": "local-demo-v2" if deployment == "compose" else "operator-managed-v1",
            "provider": s3_provider,
            "providerVersion": s3_provider_version or None,
            "dataLayoutVersion": int(s3_layout_version),
            "iamConfigSha256": s3_config_hash or None,
        },
        "ipfs": {"targetId": ipfs_id, "locator": ipfs_locator, "profile": "kubo-v1"},
        "kafka": {"targetId": kafka_id, "locator": kafka_locator, "profile": "acknowledged-v1"},
    },
}
if state_machine == "composite":
    document["stateMachine"]["preset"] = "evidence-v1-gated"
Path(output).write_text(
    json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n",
    encoding="utf-8",
)
PY
}

validate_anchor_binding_preflight() {
  local i history=false
  for i in 0 1 2; do
    [ ! -e "$DATA_ROOT/app-chain/node$i/$DEMO_CHAIN_ID/CURRENT" ] || history=true
  done
  if [ ! -e "$ANCHOR_BINDING" ]; then
    [ "$ANCHOR_ENABLED" = false ] || [ "$history" = false ] \
      || die "retained anchored history has no immutable anchor binding; restore it or retire this instance"
    return 0
  fi
  [ "$ANCHOR_ENABLED" = true ] \
    || die "an anchor binding exists but the selected immutable profile does not enable anchoring"
  [ -f "$ANCHOR_BINDING" ] && [ ! -L "$ANCHOR_BINDING" ] \
    || die "anchor binding must be a regular non-symlink file: $ANCHOR_BINDING"
  validate_private_runtime_file "$ANCHOR_BINDING" \
    || die "anchor binding must be a bounded launcher-owned private file"
  for i in 0 1 2; do
    [ -f "$DATA_ROOT/app-chain/node$i/$DEMO_CHAIN_ID/CURRENT" ] \
      || die "anchored instance is missing node $i app-chain history; restore it or retire this instance"
  done
  python3 "$SCRIPT_DIR/tools/anchor_binding.py" validate \
    --binding "$ANCHOR_BINDING" --network "$DEMO_NETWORK" \
    --instance "$INSTANCE" --deployment "$MODE" --chain-id "$DEMO_CHAIN_ID" \
    --state-machine "$STATE_MACHINE_ID" \
    >/dev/null \
    || die "anchor binding identity is invalid or does not match the selected immutable instance"
}

prepare_instance_identity() {
  temporary_file; STAGED_INSTANCE_IDENTITY="$LAST_TEMP_FILE"
  write_instance_identity "$STAGED_INSTANCE_IDENTITY"
  temporary_file; STAGED_LEASE_IDENTITY="$LAST_TEMP_FILE"
  write_l1_lease_identity "$STAGED_LEASE_IDENTITY" "$STAGED_INSTANCE_IDENTITY"
  if ! python3 "$SCRIPT_DIR/tools/lifecycle.py" deployment-acquire \
      --network-root "$NETWORK_ROOT" --data-root "$DATA_ROOT" --l1-root "$L1_ROOT" \
      --identity-file "$STAGED_INSTANCE_IDENTITY" \
      --lease-identity-file "$STAGED_LEASE_IDENTITY" >/dev/null; then
    die "app-chain identity mismatch, instance is retired and cannot be reused, permanent chain claim, or shared L1 lease conflict; stop its owner, resume cleanup, or choose a new instance/chain"
  fi
  STARTUP_LEASE_ACQUIRED=true
  trap 'demo_exit_handler $?' EXIT
  validate_anchor_binding_preflight
}

render_template() {
  local input="$1" output="$2" tmp key value
  shift 2
  [ $(( $# % 2 )) -eq 0 ] || die "template substitutions must be key/value pairs"
  tmp="$output.tmp.$$"
  rm -f "$tmp"
  if ! {
    while [ "$#" -gt 0 ]; do
      key="$1"; value="$2"; shift 2
      single_line "template value $key" "$value"
      if [[ "$value" =~ @[A-Z0-9_]+@ ]]; then
        die "template value $key must not contain placeholder syntax"
      fi
      printf '%s\0%s\0' "$key" "$value"
    done
  } | (umask 077; python3 "$SCRIPT_DIR/tools/render_template.py" "$input" "$tmp"); then
    rm -f "$tmp"
    die "failed to render template: $input"
  fi
  mv "$tmp" "$output"
}

insert_node_settings() {
  local base="$1" extras="$2" output="$3"
  (umask 077; awk -v extras="$extras" '
    $0 == "@NODE_SETTINGS@" {
      while ((getline line < extras) > 0) print line
      close(extras)
      next
    }
    { print }
  ' "$base" > "$output")
  if grep -Eq '@[A-Z0-9_]+@' "$output"; then
    die "unresolved placeholder in generated node configuration"
  fi
  chmod 600 "$output"
}

compose_node_config() {
  local index="$1" seed="$2" peers="$3" extras="$4" base genesis_setting
  base="$RUNTIME_ROOT/node$index.base"
  genesis_setting="# public-network systemStart comes from the selected genesis"
  if [ "$DEMO_NETWORK" = devnet ]; then
    genesis_setting="yano.block-producer.genesis-timestamp=$(read_secret "$GENESIS_TIMESTAMP_FILE")"
  fi
  render_template "$SCRIPT_DIR/config/templates/node-compose.properties.in" "$base" \
    API_KEY "$(read_secret "$API_KEY_FILE")" CHAIN_ID "$DEMO_CHAIN_ID" \
    STATE_MACHINE "$STATE_MACHINE_ID" \
    SIGNING_KEY "$seed" APP_PEERS "$peers" MEMBER_KEYS "$MEMBER_KEYS" \
    PROPOSER_KEY "$PROPOSER_KEY" RESULT_SIGNERS "$RESULT_SIGNERS" \
    STORAGE_GATE "$STORAGE_GATE" \
    EVIDENCE_CAPACITY_PER_BLOCK "$EVIDENCE_CAPACITY_PER_BLOCK" \
    DIRECT_RESULT_ACTIVATION_SETTING "$DIRECT_RESULT_ACTIVATION_SETTING" \
    GENESIS_TIMESTAMP_SETTING "$genesis_setting"
  insert_node_settings "$base" "$extras" "$NODE_CONFIG_DIR/node$index.properties"
  rm -f "$base"
}

prepare_compose_configs() {
  local executor follower node0extra anchor_key
  validate_target_id compose-S3-target-id "$COMPOSE_S3_TARGET_ID"
  validate_target_id compose-IPFS-target-id "$COMPOSE_IPFS_TARGET_ID"
  validate_target_id compose-Kafka-target-id "$COMPOSE_KAFKA_TARGET_ID"
  mkdir -p "$NODE_CONFIG_DIR"
  chmod 700 "$NODE_CONFIG_DIR"
  executor="$RUNTIME_ROOT/executor-compose.properties"
  follower="$RUNTIME_ROOT/follower-compose.properties"
  node0extra="$RUNTIME_ROOT/node0-compose.properties"
  render_template "$SCRIPT_DIR/config/templates/executor-compose.properties.in" "$executor" \
    S3_IP "$DEMO_S3_IP" KUBO_IP "$DEMO_KUBO_IP" KAFKA_IP "$DEMO_KAFKA_IP" \
    S3_TARGET_ID "$COMPOSE_S3_TARGET_ID" IPFS_TARGET_ID "$COMPOSE_IPFS_TARGET_ID" \
    KAFKA_TARGET_ID "$COMPOSE_KAFKA_TARGET_ID" \
    S3_EXECUTOR_ACCESS "$(read_secret "$S3_EXECUTOR_ACCESS_FILE")" \
    S3_EXECUTOR_SECRET "$(read_secret "$S3_EXECUTOR_SECRET_FILE")"
  cp "$executor" "$node0extra"
  if [ "$ANCHOR_ENABLED" = true ]; then
    anchor_key="$ANCHOR_KEY_VALUE"
    {
      printf '%s\n' 'yano.app-chain.chains[0].anchor.enabled=true'
      printf '%s\n' 'yano.app-chain.chains[0].anchor.mode=script'
      printf 'yano.app-chain.chains[0].anchor.signing-key=%s\n' "$anchor_key"
      # Devnet closes the proof quickly; public profiles use a fee-conscious
      # cadence fixed by the reviewed network profile.
      printf 'yano.app-chain.chains[0].anchor.every-blocks=%s\n' "$PROFILE_ANCHOR_EVERY_BLOCKS"
      printf 'yano.app-chain.chains[0].anchor.max-interval-minutes=%s\n' \
        "$PROFILE_ANCHOR_MAX_INTERVAL_MINUTES"
    } >> "$node0extra"
  fi
  if [ "$PROFILE_L1_MODE" = producer ]; then
    cp "$SCRIPT_DIR/config/templates/follower-compose.properties.in" "$follower"
  else
    cp "$SCRIPT_DIR/config/templates/follower-public-compose.properties.in" "$follower"
  fi
  compose_node_config 0 "$MEMBER_SEED_0" \
    'yano-1:13337,yano-2:13337' "$node0extra"
  compose_node_config 1 "$MEMBER_SEED_1" \
    'yano-0:13337,yano-2:13337' "$follower"
  compose_node_config 2 "$MEMBER_SEED_2" \
    'yano-0:13337,yano-1:13337' "$follower"
  render_template "$SCRIPT_DIR/config/templates/runner-compose.properties.in" "$RUNNER_CONFIG" \
    CHAIN_ID "$DEMO_CHAIN_ID" S3_IP "$DEMO_S3_IP" KUBO_IP "$DEMO_KUBO_IP" \
    KAFKA_IP "$DEMO_KAFKA_IP" \
    STATE_MACHINE "$STATE_MACHINE_ID" \
    COMPOSITE_PROFILE_DIGEST_SETTING "$COMPOSITE_PROFILE_DIGEST_SETTING" \
    EVIDENCE_ID "$DEMO_EVIDENCE_ID" \
    EVIDENCE_CAPACITY_PER_BLOCK "$EVIDENCE_CAPACITY_PER_BLOCK" \
    S3_TARGET_ID "$COMPOSE_S3_TARGET_ID" IPFS_TARGET_ID "$COMPOSE_IPFS_TARGET_ID" \
    KAFKA_TARGET_ID "$COMPOSE_KAFKA_TARGET_ID" \
    MEMBER_KEYS "$MEMBER_KEYS" REQUIRE_ANCHOR "$REQUIRE_ANCHOR" \
    TIMEOUT_SECONDS "$DEMO_SCENARIO_TIMEOUT_SECONDS" \
    POLL_INTERVAL_MILLIS "$DEMO_SCENARIO_POLL_INTERVAL_MILLIS"
  chmod 600 "$RUNNER_CONFIG"
  render_template "$SCRIPT_DIR/config/templates/s3-bootstrap-compose.properties.in" \
    "$S3_BOOTSTRAP_CONFIG" S3_IP "$DEMO_S3_IP"
  chmod 600 "$S3_BOOTSTRAP_CONFIG"
  render_template "$SCRIPT_DIR/config/templates/kafka-passwd.in" "$KAFKA_PASSWD_FILE" \
    HOST_UID "$DEMO_HOST_UID" HOST_GID "$DEMO_HOST_GID"
  render_template "$SCRIPT_DIR/config/templates/kafka-group.in" "$KAFKA_GROUP_FILE" \
    HOST_GID "$DEMO_HOST_GID"
  chmod 600 "$KAFKA_PASSWD_FILE" "$KAFKA_GROUP_FILE"
  python3 - "$KAFKA_PASSWD_FILE" "$KAFKA_GROUP_FILE" \
    "$DEMO_HOST_UID" "$DEMO_HOST_GID" <<'PY' \
    || die "generated Kafka NSS compatibility files are unsafe or inconsistent"
import os
import stat
import sys

passwd, group, uid, gid = sys.argv[1:]
expected = {
    passwd: f"root:!:0:0:root:/root:/sbin/nologin\nyano-kafka:!:{uid}:{gid}:Yano Kafka:/nonexistent:/sbin/nologin\n",
    group: f"root:!:0:\nyano-kafka:!:{gid}:\n",
}
flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
for path, content in expected.items():
    descriptor = os.open(path, flags)
    try:
        info = os.fstat(descriptor)
        actual = os.read(descriptor, 4097)
    finally:
        os.close(descriptor)
    if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid()
            or info.st_nlink != 1 or stat.S_IMODE(info.st_mode) != 0o600
            or actual != content.encode("ascii")):
        raise SystemExit(1)
PY
  render_template "$SCRIPT_DIR/config/templates/runner-ui.properties.in" "$UI_CONFIG" \
    REPORT_DIRECTORY /var/lib/yano-demo/reports BIND_ADDRESS 0.0.0.0 UI_INTERNAL_PORT 7080
  chmod 600 "$UI_CONFIG"
  rm -f "$executor" "$follower" "$node0extra"
}

prepare_host_configs() {
  local executor follower base i access secret host_home genesis_setting
  resolve_host_target_ids
  mkdir -p "$NODE_CONFIG_DIR"
  chmod 700 "$NODE_CONFIG_DIR"
  executor="$RUNTIME_ROOT/executor-host.properties"
  follower="$RUNTIME_ROOT/follower-host.properties"
  access="$(read_secret "$S3_EXECUTOR_ACCESS_FILE")"
  secret="$(read_secret "$S3_EXECUTOR_SECRET_FILE")"
  render_template "$SCRIPT_DIR/config/templates/executor-host.properties.in" "$executor" \
    KAFKA_BOOTSTRAP_SERVERS "${DEMO_HOST_KAFKA_BOOTSTRAP_SERVERS:-127.0.0.1:$DEMO_KAFKA_PORT}" \
    S3_ENDPOINT "${DEMO_HOST_S3_ENDPOINT:-http://127.0.0.1:$DEMO_S3_PORT}" \
    IPFS_API_URL "${DEMO_HOST_IPFS_API_URL:-http://127.0.0.1:$DEMO_IPFS_PORT}" \
    S3_TARGET_ID "$HOST_S3_TARGET_ID" IPFS_TARGET_ID "$HOST_IPFS_TARGET_ID" \
    KAFKA_TARGET_ID "$HOST_KAFKA_TARGET_ID" \
    S3_EXECUTOR_ACCESS "$access" S3_EXECUTOR_SECRET "$secret"
  cp "$SCRIPT_DIR/config/templates/follower-host.properties.in" "$follower"
  genesis_setting="# public-network systemStart comes from the selected genesis"
  if [ "$DEMO_NETWORK" = devnet ]; then
    genesis_setting="yano.block-producer.genesis-timestamp=$(read_secret "$GENESIS_TIMESTAMP_FILE")"
  fi
  for i in 0 1 2; do
    base="$RUNTIME_ROOT/node$i-host.base"
    render_template "$SCRIPT_DIR/config/templates/node-host.properties.in" "$base" \
      PLUGIN_DIR "$PLUGIN_DIR" PROPOSER_KEY "$PROPOSER_KEY" \
      STATE_MACHINE "$STATE_MACHINE_ID" \
      RESULT_SIGNERS "$RESULT_SIGNERS" STORAGE_GATE "$STORAGE_GATE" \
      EVIDENCE_CAPACITY_PER_BLOCK "$EVIDENCE_CAPACITY_PER_BLOCK" \
      DIRECT_RESULT_ACTIVATION_SETTING "$DIRECT_RESULT_ACTIVATION_SETTING" \
      GENESIS_TIMESTAMP_SETTING "$genesis_setting" \
      ANCHOR_MAX_INTERVAL_MINUTES "$PROFILE_ANCHOR_MAX_INTERVAL_MINUTES"
    insert_node_settings "$base" "$([ "$i" -eq 0 ] && printf '%s' "$executor" || printf '%s' "$follower")" \
      "$NODE_CONFIG_DIR/node$i.properties"
    rm -f "$base"
  done
  render_template "$SCRIPT_DIR/config/templates/runner-host.properties.in" "$RUNNER_CONFIG" \
    CHAIN_ID "$DEMO_CHAIN_ID" HTTP0 "$HTTP0" HTTP1 "$HTTP1" HTTP2 "$HTTP2" \
    STATE_MACHINE "$STATE_MACHINE_ID" \
    COMPOSITE_PROFILE_DIGEST_SETTING "$COMPOSITE_PROFILE_DIGEST_SETTING" \
    EVIDENCE_ID "$DEMO_EVIDENCE_ID" \
    EVIDENCE_CAPACITY_PER_BLOCK "$EVIDENCE_CAPACITY_PER_BLOCK" \
    SAMPLE_FILE "$SCRIPT_DIR/samples/inspection-certificate.json" \
    REPORT_DIRECTORY "$REPORT_DIR" \
    S3_ENDPOINT "${DEMO_HOST_S3_ENDPOINT:-http://127.0.0.1:$DEMO_S3_PORT}" \
    S3_RUNNER_ACCESS_FILE "$S3_RUNNER_ACCESS_FILE" \
    S3_RUNNER_SECRET_FILE "$S3_RUNNER_SECRET_FILE" \
    IPFS_API_URL "${DEMO_HOST_IPFS_API_URL:-http://127.0.0.1:$DEMO_IPFS_PORT}" \
    KAFKA_BOOTSTRAP_SERVERS "${DEMO_HOST_KAFKA_BOOTSTRAP_SERVERS:-127.0.0.1:$DEMO_KAFKA_PORT}" \
    S3_TARGET_ID "$HOST_S3_TARGET_ID" IPFS_TARGET_ID "$HOST_IPFS_TARGET_ID" \
    KAFKA_TARGET_ID "$HOST_KAFKA_TARGET_ID" \
    MEMBER_KEYS "$MEMBER_KEYS" REQUIRE_ANCHOR "$REQUIRE_ANCHOR" \
    TIMEOUT_SECONDS "$DEMO_SCENARIO_TIMEOUT_SECONDS" \
    POLL_INTERVAL_MILLIS "$DEMO_SCENARIO_POLL_INTERVAL_MILLIS" UI_PORT "$DEMO_UI_PORT"
  chmod 600 "$RUNNER_CONFIG"
  render_template "$SCRIPT_DIR/config/templates/runner-ui.properties.in" "$UI_CONFIG" \
    REPORT_DIRECTORY "$REPORT_DIR" BIND_ADDRESS 127.0.0.1 UI_INTERNAL_PORT "$DEMO_UI_PORT"
  chmod 600 "$UI_CONFIG"
  rm -f "$executor" "$follower"
  host_home="$RUNTIME_ROOT/host-home"
  mkdir -p "$host_home/config"
  if [ -d "$APP_DIR/config" ]; then
    cp -R "$APP_DIR/config/." "$host_home/config/"
  fi
  render_template "$SCRIPT_DIR/config/templates/application-appchain.yml.in" \
    "$host_home/config/application-appchain.yml" CHAIN_ID "$DEMO_CHAIN_ID" \
    STATE_MACHINE "$STATE_MACHINE_ID"
}

write_compose_env() {
  local i variable default_scenario_input
  default_scenario_input="$SCRIPT_DIR/samples/inspection-certificate.json"
  for variable in DEMO_KAFKA_IMAGE DEMO_RUSTFS_IMAGE \
    DEMO_KUBO_IMAGE DEMO_PROMETHEUS_IMAGE DEMO_GRAFANA_IMAGE \
    DEMO_YANO_IMAGE DEMO_RUNNER_IMAGE DEMO_CONNECTOR_SUBNET DEMO_S3_IP \
    DEMO_KUBO_IP DEMO_KAFKA_IP DEMO_UI_PORT DEMO_KAFKA_PORT DEMO_S3_PORT DEMO_IPFS_PORT \
    DEMO_PROMETHEUS_PORT DEMO_GRAFANA_PORT; do
    compose_env_value "$variable" "${!variable}"
  done
  compose_env_value DEMO_NETWORK "$DEMO_NETWORK"
  compose_env_value PROFILE_WARMUP_SECONDS "$PROFILE_WARMUP_SECONDS"
  for variable in "$RUNNER_CONFIG" "$S3_BOOTSTRAP_CONFIG" "$UI_CONFIG" \
    "$KAFKA_PASSWD_FILE" "$KAFKA_GROUP_FILE" "$PLUGIN_DIR" \
    "$REPORT_DIR" "$API_KEY_FILE" "$S3_BOOTSTRAP_ACCESS_FILE" \
    "$S3_BOOTSTRAP_SECRET_FILE" "$S3_IAM_MASTER_KEY_FILE" \
    "$S3_RUNNER_ACCESS_FILE" "$S3_RUNNER_SECRET_FILE" \
    "$S3_EXECUTOR_ACCESS_FILE" "$S3_EXECUTOR_SECRET_FILE" "$RUSTFS_IAM_SPEC_FILE" \
    "$GRAFANA_PASSWORD_FILE" "$SHELLEY_GENESIS_FILE" "$NODE_CONFIG_DIR" \
    "$DATA_ROOT" "$default_scenario_input"; do
    compose_env_value "generated path" "$variable"
  done
  (umask 077
    {
      printf 'DEMO_PROJECT_NAME=%s\n' "$PROJECT_NAME"
      printf 'DEMO_YANO_PROFILE=%s\n' "$DEMO_NETWORK"
      printf 'DEMO_LEADER_WARMUP_SECONDS=%s\n' "$PROFILE_WARMUP_SECONDS"
      printf 'DEMO_HOST_UID=%s\n' "$DEMO_HOST_UID"
      printf 'DEMO_HOST_GID=%s\n' "$DEMO_HOST_GID"
      printf 'DEMO_YANO_IMAGE=%s\n' "$DEMO_YANO_IMAGE"
      printf 'DEMO_RUNNER_IMAGE=%s\n' "$DEMO_RUNNER_IMAGE"
      printf 'DEMO_RUSTFS_IMAGE=%s\n' "$DEMO_RUSTFS_IMAGE"
      for i in KAFKA KUBO PROMETHEUS GRAFANA; do
        variable="DEMO_${i}_IMAGE"
        printf 'DEMO_%s_IMAGE=%s\n' "$i" "${!variable}"
      done
      printf 'DEMO_HTTP0=%s\nDEMO_HTTP1=%s\nDEMO_HTTP2=%s\n' "$HTTP0" "$HTTP1" "$HTTP2"
      printf 'DEMO_UI_PORT=%s\nDEMO_KAFKA_PORT=%s\nDEMO_S3_PORT=%s\nDEMO_IPFS_PORT=%s\n' \
        "$DEMO_UI_PORT" "$DEMO_KAFKA_PORT" "$DEMO_S3_PORT" "$DEMO_IPFS_PORT"
      printf 'DEMO_PROMETHEUS_PORT=%s\nDEMO_GRAFANA_PORT=%s\n' \
        "$DEMO_PROMETHEUS_PORT" "$DEMO_GRAFANA_PORT"
      printf 'DEMO_CONNECTOR_SUBNET=%s\nDEMO_S3_IP=%s\nDEMO_KUBO_IP=%s\nDEMO_KAFKA_IP=%s\n' \
        "$DEMO_CONNECTOR_SUBNET" "$DEMO_S3_IP" "$DEMO_KUBO_IP" "$DEMO_KAFKA_IP"
      printf 'DEMO_RUNNER_CONFIG=%s\nDEMO_PLUGIN_DIR=%s\nDEMO_REPORT_DIR=%s\n' \
        "$RUNNER_CONFIG" "$PLUGIN_DIR" "$REPORT_DIR"
      printf 'DEMO_SCENARIO_INPUT_FILE=%s\n' "$default_scenario_input"
      printf 'DEMO_KAFKA_PASSWD_FILE=%s\nDEMO_KAFKA_GROUP_FILE=%s\n' \
        "$KAFKA_PASSWD_FILE" "$KAFKA_GROUP_FILE"
      printf 'DEMO_S3_BOOTSTRAP_CONFIG=%s\n' "$S3_BOOTSTRAP_CONFIG"
      printf 'DEMO_UI_CONFIG=%s\n' "$UI_CONFIG"
      printf 'DEMO_API_KEY_FILE=%s\n' "$API_KEY_FILE"
      printf 'DEMO_S3_BOOTSTRAP_ACCESS_FILE=%s\nDEMO_S3_BOOTSTRAP_SECRET_FILE=%s\n' \
        "$S3_BOOTSTRAP_ACCESS_FILE" "$S3_BOOTSTRAP_SECRET_FILE"
      printf 'DEMO_S3_IAM_MASTER_KEY_FILE=%s\n' "$S3_IAM_MASTER_KEY_FILE"
      printf 'DEMO_S3_RUNNER_ACCESS_FILE=%s\nDEMO_S3_RUNNER_SECRET_FILE=%s\n' \
        "$S3_RUNNER_ACCESS_FILE" "$S3_RUNNER_SECRET_FILE"
      printf 'DEMO_S3_EXECUTOR_ACCESS_FILE=%s\nDEMO_S3_EXECUTOR_SECRET_FILE=%s\n' \
        "$S3_EXECUTOR_ACCESS_FILE" "$S3_EXECUTOR_SECRET_FILE"
      printf 'DEMO_RUSTFS_IAM_SPEC_FILE=%s\n' "$RUSTFS_IAM_SPEC_FILE"
      printf 'DEMO_GRAFANA_PASSWORD_FILE=%s\n' "$GRAFANA_PASSWORD_FILE"
      printf 'DEMO_SHELLEY_GENESIS_FILE=%s\n' "$SHELLEY_GENESIS_FILE"
      for i in 0 1 2; do
        printf 'DEMO_NODE%s_CONFIG=%s\n' "$i" "$NODE_CONFIG_DIR/node$i.properties"
        printf 'DEMO_YANO%s_DATA_DIR=%s\n' "$i" "$L1_ROOT/node$i"
        printf 'DEMO_YANO%s_APP_DATA_DIR=%s\n' "$i" "$DATA_ROOT/app-chain/node$i"
        printf 'DEMO_YANO%s_LOG_DIR=%s\n' "$i" "$DATA_ROOT/logs/node$i"
      done
      printf 'DEMO_KAFKA_DATA_DIR=%s\nDEMO_S3_DATA_DIR=%s\nDEMO_IPFS_DATA_DIR=%s\n' \
        "$DATA_ROOT/connectors/kafka" "$DATA_ROOT/connectors/rustfs-v1" \
        "$DATA_ROOT/connectors/ipfs"
      printf 'DEMO_PROMETHEUS_DATA_DIR=%s\nDEMO_GRAFANA_DATA_DIR=%s\n' \
        "$DATA_ROOT/observability/prometheus" "$DATA_ROOT/observability/grafana"
    } > "$COMPOSE_ENV"
  )
}

prepare_configuration() {
  require python3
  prepare_network_identity
  prepare_secrets
  if [ "$MODE" = host ]; then
    resolve_host_target_ids
  fi
  prepare_instance_identity
  install_staged_network_material
  prepare_directories
  if [ "$MODE" = compose ]; then
    prepare_compose_configs
  else
    prepare_host_configs
  fi
  write_compose_env
}

write_l1_lease_identity() {
  local output="$1" instance_identity="${2:-$INSTANCE_MARKER}"
  python3 - "$output" "$DEMO_NETWORK" "$INSTANCE" "$MODE" "$DEMO_CHAIN_ID" \
    "$PROJECT_NAME" "$NETWORK_MARKER" "$instance_identity" <<'PY'
import hashlib
import json
from pathlib import Path
import sys

output, network, instance, deployment, chain_id, project, network_marker, instance_marker = sys.argv[1:]
digest = lambda path: hashlib.sha256(Path(path).read_bytes()).hexdigest()
document = {
    "schemaVersion": 1,
    "kind": "yano.demo.l1-lease",
    "networkName": network,
    "instanceId": instance,
    "deployment": deployment,
    "chainIds": [chain_id],
    "project": project,
    "networkIdentitySha256": digest(network_marker),
    "appchainIdentitySha256": digest(instance_marker),
}
Path(output).write_text(
    json.dumps(document, sort_keys=True, separators=(",", ":")) + "\n",
    encoding="utf-8",
)
PY
}

release_l1_lease() {
  local identity
  [ ! -L "$L1_LEASE" ] \
    || die "L1 lease must not be a symlink: $L1_LEASE"
  [ ! -e "$L1_LEASE" ] || [ -f "$L1_LEASE" ] \
    || die "L1 lease is not a regular file: $L1_LEASE"
  [ -e "$L1_LEASE" ] || return 0
  temporary_file; identity="$LAST_TEMP_FILE"
  write_l1_lease_identity "$identity"
  python3 "$SCRIPT_DIR/tools/lifecycle.py" lease-release \
    --allowed-root "$NETWORK_ROOT" --directory "$L1_ROOT" \
    --identity-file "$identity"
}

validate_l1_lease_owner() {
  local identity
  [ -e "$L1_LEASE" ] || return 1
  [ -f "$L1_LEASE" ] && [ ! -L "$L1_LEASE" ] \
    || die "L1 lease is not a regular non-symlink file: $L1_LEASE"
  [ -f "$NETWORK_MARKER" ] && [ -f "$INSTANCE_MARKER" ] \
    || die "cannot validate L1 ownership without both immutable identity markers"
  temporary_file; identity="$LAST_TEMP_FILE"
  write_l1_lease_identity "$identity"
  python3 "$SCRIPT_DIR/tools/lifecycle.py" lease-validate \
    --allowed-root "$NETWORK_ROOT" --directory "$L1_ROOT" \
    --identity-file "$identity" >/dev/null
}

unique_artifact() {
  local dir="$1" pattern="$2" description="$3" matches=() file
  while IFS= read -r file; do matches+=("$file"); done \
    < <(find "$dir" -maxdepth 1 -type f -name "$pattern" -print | sort)
  [ "${#matches[@]}" -eq 1 ] \
    || die "expected one $description in $dir, found ${#matches[@]}"
  printf '%s' "${matches[0]}"
}

build_artifacts() {
  local gradle runner source name yano_context
  if [ "${DEMO_SKIP_BUILD:-false}" = true ]; then
    return 0
  fi
  gradle="$REPO_DIR/gradlew"
  "$gradle" -p "$REPO_DIR" --no-daemon \
    :appchain-kafka:shadowJar \
    :appchain-ipfs:shadowJar \
    :appchain-objectstore-s3:shadowJar \
    :appchain-evidence-demo-runner:shadowJar \
    :app:prepareYanoDockerJvmContext
  for name in appchain-kafka appchain-ipfs appchain-objectstore-s3; do
    source="$(unique_artifact "$REPO_DIR/appchain/extensions/$name/build/libs" \
      '*-bundle.jar' "$name bundle")"
    install -m 0644 "$source" "$PLUGIN_DIR/$name-bundle.jar"
  done
  runner="$(unique_artifact "$REPO_DIR/appchain/examples/appchain-evidence-demo-runner/build/libs" \
    '*-all.jar' 'evidence demo runner')"
  install -m 0644 "$runner" "$RUNTIME_ROOT/runner.jar"
  yano_context="$APP_DIR/build/docker/jvm/context"
  [ -f "$yano_context/yano/yano.jar" ] || die "prepared Yano Docker context has no yano.jar"
  install -m 0644 "$yano_context/yano/yano.jar" "$RUNTIME_ROOT/yano.jar"
  if [ "$MODE" = compose ]; then
    build_compose_images "$yano_context"
  fi
}

build_compose_images() {
  local yano_context="$1" runner_context image image_platform server_platform
  require docker
  require curl
  server_platform="$(docker version --format '{{.Server.Os}}/{{.Server.Arch}}')"
  for image in "$DEMO_RUSTFS_IMAGE" "$DEMO_KUBO_IMAGE"; do
    docker pull "$image" \
      || die "could not pull a pinned multi-architecture demo dependency: $image"
    image_platform="$(docker image inspect --format '{{.Os}}/{{.Architecture}}' "$image")"
    case "$image_platform:$server_platform" in
      linux/amd64:linux/amd64|linux/amd64:linux/x86_64|\
      linux/arm64:linux/arm64|linux/arm64:linux/aarch64) ;;
      *) die "the pulled dependency image is not native to the Docker server: $image" ;;
    esac
  done
  docker build --pull=false --build-arg "YANO_BASE_IMAGE=$DEMO_RUNNER_BASE_IMAGE" \
    -f "$SCRIPT_DIR/Dockerfile.yano" -t "$DEMO_YANO_IMAGE" "$yano_context"
  runner_context="$RUNTIME_ROOT/runner-image"
  mkdir -p "$runner_context"
  install -m 0644 "$RUNTIME_ROOT/runner.jar" "$runner_context/runner.jar"
  docker build --pull=false --build-arg "RUNNER_BASE_IMAGE=$DEMO_RUNNER_BASE_IMAGE" \
    -f "$SCRIPT_DIR/Dockerfile.runner" -t "$DEMO_RUNNER_IMAGE" "$runner_context"
}

verify_artifacts() {
  local name
  for name in appchain-kafka appchain-ipfs appchain-objectstore-s3; do
    [ -f "$PLUGIN_DIR/$name-bundle.jar" ] || die "missing staged plugin: $name"
  done
  [ -f "$RUNTIME_ROOT/runner.jar" ] || die "missing runner; run prepare"
  [ -f "$RUNTIME_ROOT/yano.jar" ] || die "missing Yano jar; run prepare"
}

validate_private_runtime_file() {
  local path="$1"
  python3 - "$path" <<'PY' >/dev/null || return 1
import os, stat, sys
path = sys.argv[1]
if os.path.islink(path):
    raise SystemExit(1)
info = os.stat(path, follow_symlinks=False)
if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_nlink != 1
        or stat.S_IMODE(info.st_mode) & 0o077 or info.st_size > 1_048_576):
    raise SystemExit(1)
PY
}

dc() {
  validate_private_runtime_file "$COMPOSE_ENV" \
    || die "Compose environment must be a bounded launcher-owned private file: $COMPOSE_ENV"
  local args=(docker compose -p "$PROJECT_NAME" --env-file "$COMPOSE_ENV" -f "$COMPOSE_FILE")
  [ "$OBSERVABILITY" = false ] || args+=(--profile observability)
  "${args[@]}" "$@"
}

compose_project_container_ids() {
  docker ps -a --filter "label=com.docker.compose.project=$PROJECT_NAME" -q
}

# `docker compose down` returning zero is not proof that a changed-model orphan
# disappeared. Release shared L1 ownership only after the Docker daemon reports
# no container (running or stopped) with this exact Compose project label.
compose_down_confirmed() {
  local containers down_status=0
  validate_private_runtime_file "$COMPOSE_ENV" || return 1
  docker compose -p "$PROJECT_NAME" --env-file "$COMPOSE_ENV" \
    -f "$COMPOSE_FILE" down --remove-orphans "$@" || down_status=$?
  containers="$(compose_project_container_ids)" || return 1
  if [ -n "$containers" ]; then
    note "Compose shutdown left project-labelled containers; shared L1 ownership is preserved." >&2
    return 1
  fi
  [ "$down_status" -eq 0 ] \
    || note "Compose down returned $down_status, but the daemon confirms the project has no containers." >&2
  return 0
}

runner_java() {
  java --add-modules=jdk.httpserver -jar "$RUNTIME_ROOT/runner.jar" "$@" \
    --config "$RUNNER_CONFIG"
}

prepare_scenario_input() {
  local source="${SCENARIO_SAMPLE_FILE:-$SCRIPT_DIR/samples/inspection-certificate.json}"
  local target="$RUNTIME_ROOT/scenario-input.bin"
  mkdir -p "$RUNTIME_ROOT"
  chmod 700 "$RUNTIME_ROOT"
  python3 - "$source" "$target" <<'PY' \
    || die "scenario input must be a stable, non-symlink regular file of 1..16777216 bytes"
import os
from pathlib import Path
import stat
import sys
import tempfile

source = os.path.abspath(os.path.expanduser(sys.argv[1]))
target = Path(sys.argv[2])
flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
descriptor = os.open(source, flags)
temporary = None
try:
    before = os.fstat(descriptor)
    if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.geteuid()
            or before.st_size < 1 or before.st_size > 16_777_216):
        raise ValueError("invalid input")
    data = b""
    while len(data) <= 16_777_216:
        chunk = os.read(descriptor, min(1_048_576, 16_777_217 - len(data)))
        if not chunk:
            break
        data += chunk
    after = os.fstat(descriptor)
    if ((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
            != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
            or len(data) != before.st_size):
        raise ValueError("input changed")
    file_descriptor, temporary = tempfile.mkstemp(prefix=".scenario-input-", dir=target.parent)
    try:
        os.fchmod(file_descriptor, 0o600)
        with os.fdopen(file_descriptor, "wb", closefd=True) as output:
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        os.replace(temporary, target)
        temporary = None
    finally:
        if temporary is not None:
            os.unlink(temporary)
finally:
    os.close(descriptor)
PY
  SCENARIO_INPUT_COPY="$target"
  TEMP_FILES+=("$target")
}

api_curl() {
  local key
  key="$(read_secret "$API_KEY_FILE")"
  curl --connect-timeout 5 --max-time 30 --config - "$@" <<EOF
header = "X-API-Key: $key"
EOF
}

wait_for_loopback_http() {
  local label="$1" url="$2" method="${3:-GET}" deadline
  deadline=$((SECONDS + 30))
  until curl --connect-timeout 2 --max-time 4 -fsS -X "$method" "$url" \
      >/dev/null 2>&1; do
    [ "$SECONDS" -lt "$deadline" ] \
      || die "$label is healthy in Compose but unreachable through $url"
    sleep 1
  done
}

wait_for_loopback_tcp() {
  local label="$1" port="$2" deadline
  deadline=$((SECONDS + 30))
  until python3 - "$port" >/dev/null 2>&1 <<'PY'
import socket
import sys

with socket.create_connection(("127.0.0.1", int(sys.argv[1])), timeout=2):
    pass
PY
  do
    [ "$SECONDS" -lt "$deadline" ] \
      || die "$label is healthy in Compose but unreachable through 127.0.0.1:$port"
    sleep 1
  done
}

verify_compose_loopback_surfaces() {
  wait_for_loopback_http "Yano node 0" "http://127.0.0.1:$HTTP0/q/health/ready"
  wait_for_loopback_http "evidence UI" "http://127.0.0.1:$DEMO_UI_PORT/healthz"
  wait_for_loopback_tcp "RustFS S3" "$DEMO_S3_PORT"
  wait_for_loopback_http "Kubo RPC" \
    "http://127.0.0.1:$DEMO_IPFS_PORT/api/v0/version" POST
  wait_for_loopback_tcp "Kafka" "$DEMO_KAFKA_PORT"
  if [ "$OBSERVABILITY" = true ]; then
    wait_for_loopback_http "Prometheus" \
      "http://127.0.0.1:$DEMO_PROMETHEUS_PORT/-/ready"
    wait_for_loopback_http "Grafana" \
      "http://127.0.0.1:$DEMO_GRAFANA_PORT/api/health"
  fi
  note "Verified all published Compose surfaces through 127.0.0.1."
}

wait_for_public_l1_sync() {
  local deadline next_report i ready status summary
  [ "$PROFILE_PUBLIC" = true ] || return 0
  deadline=$((SECONDS + 10#$PROFILE_SYNC_TIMEOUT_SECONDS))
  next_report=$SECONDS
  note "WAIT_L1_SYNC: waiting for all three $DEMO_NETWORK members to report initialSyncComplete=true."
  while [ "$SECONDS" -lt "$deadline" ]; do
    ready=true
    summary=""
    for i in 0 1 2; do
      status="$(curl -fsS --connect-timeout 3 --max-time 10 \
        "http://127.0.0.1:$((HTTP0 + i))/api/v1/node/status" 2>/dev/null || true)"
      if [ -z "$status" ]; then
        ready=false
        summary="$summary node$i=unreachable"
        continue
      fi
      read -r is_complete local_tip remote_tip < <(printf '%s' "$status" | python3 -c '
import json,sys
d=json.load(sys.stdin)
print(str(d.get("initialSyncComplete") is True).lower(), d.get("localTipBlockNumber", "?"), d.get("remoteTipBlockNumber", "?"))
' 2>/dev/null || printf 'false ? ?\n')
      [ "$is_complete" = true ] || ready=false
      summary="$summary node$i=$local_tip/$remote_tip"
    done
    [ "$ready" = true ] && { note "WAIT_L1_SYNC complete:$summary"; return 0; }
    if [ "$SECONDS" -ge "$next_report" ]; then
      note "WAIT_L1_SYNC progress:$summary"
      next_report=$((SECONDS + 60))
    fi
    sleep 10
  done
  die "$DEMO_NETWORK L1 sync did not complete within $PROFILE_SYNC_TIMEOUT_SECONDS seconds"
}

reconcile_anchor_binding() {
  local require_adopted="${1:-false}" deadline status_file error_file i result
  local binding_state binding_result binding_error
  local -a statuses=()
  local -a binding_command
  [ "$ANCHOR_ENABLED" = true ] || return 0
  [ "$require_adopted" = true ] || [ "$require_adopted" = false ] \
    || die "internal anchor reconciliation mode is invalid"
  for i in 0 1 2; do
    temporary_file
    statuses+=("$LAST_TEMP_FILE")
  done
  temporary_file
  error_file="$LAST_TEMP_FILE"
  binding_command=(python3 "$SCRIPT_DIR/tools/anchor_binding.py" reconcile \
    --binding "$ANCHOR_BINDING" --network "$DEMO_NETWORK" \
    --instance "$INSTANCE" --deployment "$MODE" --chain-id "$DEMO_CHAIN_ID" \
    --state-machine "$STATE_MACHINE_ID" \
    --member-keys "$MEMBER_KEYS")
  [ "$require_adopted" = true ] || binding_command+=(--allow-pristine-pending)
  for status_file in "${statuses[@]}"; do
    binding_command+=(--status "$status_file")
  done
  deadline=$((SECONDS + 180))
  if [ "$require_adopted" = true ]; then
    note "WAIT_ANCHOR_ADOPTION: requiring one adopted script identity and height on all members."
  else
    note "WAIT_ANCHOR_ADOPTION: verifying all members; a pristine height-0 identity may remain pending until first activity."
  fi
  while [ "$SECONDS" -lt "$deadline" ]; do
    result=0
    for i in 0 1 2; do
      curl --connect-timeout 3 --max-time 10 -fsS \
        "http://127.0.0.1:$((HTTP0 + i))/api/v1/app-chain/chains/$DEMO_CHAIN_ID/status" \
        > "${statuses[$i]}" || result=1
    done
    if [ "$result" -eq 0 ]; then
      if binding_state="$("${binding_command[@]}" 2>"$error_file")"; then
        if [ "$binding_state" = pending-genesis ]; then
          note "Anchor binding recorded as pending-genesis; the first app-chain activity will require three-member adoption."
        else
          note "Anchor binding verified and atomically recorded as member-adopted at $ANCHOR_BINDING."
        fi
        return 0
      else
        binding_result=$?
        if [ "$binding_result" -eq 2 ]; then
          binding_error="$(tr '\n' ' ' < "$error_file")"
          die "anchor binding reconciliation failed closed: ${binding_error:-invalid binding or member status}"
        fi
        [ "$binding_result" -eq 3 ] \
          || die "anchor binding reconciliation returned unexpected status $binding_result"
      fi
    fi
    sleep 2
  done
  if [ "$require_adopted" = true ]; then
    die "members did not converge on one adopted anchor identity/height within 180 seconds"
  fi
  die "members were neither pristine-pending nor converged on one adopted anchor within 180 seconds"
}

compose_anchor_bootstrap() {
  local base="http://127.0.0.1:$HTTP0/api/v1" status bootstrapped address deadline
  status="$(curl --connect-timeout 3 --max-time 10 -fsS \
    "$base/app-chain/chains/$DEMO_CHAIN_ID/status")"
  read -r bootstrapped address < <(printf '%s' "$status" | python3 -c '
import json,sys
a=json.load(sys.stdin).get("anchor", {})
print(str(bool(a.get("bootstrapped"))).lower(), a.get("walletAddress", ""))
  ')
  if [ "$bootstrapped" = true ]; then
    return
  fi
  [ -n "$address" ] || die "node 0 did not report a script-anchor wallet"
  if [ "$PROFILE_AUTO_FAUCET" = true ]; then
    curl --connect-timeout 3 --max-time 30 -fsS -X POST \
      "$base/devnet/fund" -H 'Content-Type: application/json' \
      -d "{\"address\":\"$address\",\"ada\":500}" >/dev/null
  else
    note "Using the explicitly enabled $DEMO_NETWORK anchor wallet: $address"
  fi
  wait_for_anchor_wallet_funds "$base" "$address"
  api_curl -fsS -X POST \
    "$base/app-chain/chains/$DEMO_CHAIN_ID/admin/anchor/bootstrap" >/dev/null
  deadline=$((SECONDS + 120))
  while [ "$SECONDS" -lt "$deadline" ]; do
    status="$(curl --connect-timeout 3 --max-time 10 -fsS \
      "$base/app-chain/chains/$DEMO_CHAIN_ID/status")"
    bootstrapped="$(printf '%s' "$status" | python3 -c \
      'import json,sys; print(str(bool(json.load(sys.stdin).get("anchor",{}).get("bootstrapped"))).lower())')"
    if [ "$bootstrapped" = true ]; then
      return
    fi
    sleep 2
  done
  die "script anchor did not confirm within 120 seconds"
}

wait_for_anchor_wallet_funds() {
  local base="$1" address="$2" deadline next_report response usable timeout
  if [ "$PROFILE_PUBLIC" = true ]; then
    timeout=$((10#$DEMO_ANCHOR_FUND_TIMEOUT_SECONDS))
    note "WAIT_ANCHOR_FUNDS: waiting for transaction-ready pure-ADA funding at $address."
    note "Required: at least two distinct UTxOs (one >= 5000000, another >= 10000000 lovelace) and >= 20000000 total."
  else
    timeout=120
    note "WAIT_ANCHOR_FUNDS: waiting for a pure-ADA UTxO of at least 1000000 lovelace at $address."
  fi
  deadline=$((SECONDS + timeout))
  next_report=$SECONDS
  while [ "$SECONDS" -lt "$deadline" ]; do
    response="$(curl --connect-timeout 3 --max-time 10 -fsS \
      "$base/addresses/$address/utxos?count=50" 2>/dev/null || true)"
    # macOS still ships Bash 3.2, where expanding an empty local array under
    # `set -u` raises "unbound variable". Keep the no-argument devnet path
    # explicit instead of relying on an empty-array expansion.
    if { [ "$PROFILE_PUBLIC" = true ] \
          && printf '%s' "$response" | python3 "$SCRIPT_DIR/tools/anchor_funding.py" \
            --public 2>/dev/null; } \
        || { [ "$PROFILE_PUBLIC" = false ] \
          && printf '%s' "$response" | python3 "$SCRIPT_DIR/tools/anchor_funding.py" \
            2>/dev/null; }; then
      usable=true
    else
      usable=false
    fi
    [ "$usable" != true ] || { note "WAIT_ANCHOR_FUNDS complete: usable wallet UTxO observed."; return 0; }
    if [ "$SECONDS" -ge "$next_report" ]; then
      note "WAIT_ANCHOR_FUNDS pending: fund $address; the launcher will continue after the UTxO is visible."
      next_report=$((SECONDS + 60))
    fi
    sleep 5
  done
  die "anchor wallet funding was not observed within $timeout seconds; fund $address and run up again"
}

host_anchor_bootstrap() {
  local base="http://127.0.0.1:$HTTP0/api/v1" status bootstrapped address
  status="$(curl --connect-timeout 3 --max-time 10 -fsS \
    "$base/app-chain/chains/$DEMO_CHAIN_ID/status")"
  read -r bootstrapped address < <(printf '%s' "$status" | python3 -c '
import json,sys
a=json.load(sys.stdin).get("anchor", {})
print(str(bool(a.get("bootstrapped"))).lower(), a.get("walletAddress", ""))
')
  [ "$bootstrapped" != true ] || return 0
  [ -n "$address" ] || die "node 0 did not report a script-anchor wallet"
  if [ "$PROFILE_PUBLIC" = true ]; then
    note "Using the explicitly enabled $DEMO_NETWORK anchor wallet: $address"
    wait_for_anchor_wallet_funds "$base" "$address"
  fi
  host_cluster anchor-bootstrap "$DEMO_CHAIN_ID"
}

host_cluster() {
  local cluster="$APP_DIR/appchain-cluster/cluster.sh" key="" devnet_genesis=""
  [ -x "$cluster" ] || die "cluster launcher not executable: $cluster"
  case "${1:-}" in
    status|stop) ;;
    *) key="$(read_secret "$API_KEY_FILE")";;
  esac
  [ "$DEMO_NETWORK" != devnet ] || devnet_genesis="$SHELLEY_GENESIS_FILE"
  YANO_HOME="$RUNTIME_ROOT/host-home" \
  YANO_JAR="$RUNTIME_ROOT/yano.jar" \
  YANO_CLUSTER_DIR="$L1_ROOT/host-cluster" \
  YANO_CLUSTER_NODE_CONFIG_DIR="$NODE_CONFIG_DIR" \
  YANO_CLUSTER_MEMBER_KEY_DIR="$MEMBER_KEY_DIR" \
  YANO_CLUSTER_ANCHOR_KEY_FILE="$ANCHOR_KEY_FILE" \
  YANO_CLUSTER_PRIVATE_CONFIG_DIR="$SECRET_ROOT/cluster-private-config" \
  YANO_CLUSTER_DEVNET_GENESIS_FILE="$devnet_genesis" \
  YANO_CLUSTER_APPCHAIN_IDENTITY_MARKER="$INSTANCE_MARKER" \
  YANO_CLUSTER_API_KEY="$key" \
  "$cluster" "$@"
}

prepare_host_state_links() {
  local i root target link status
  for i in 0 1 2; do
    root="$L1_ROOT/host-cluster/node$i/chainstate"
    target="$DATA_ROOT/app-chain/node$i"
    link="$root/app-chain"
    mkdir -p "$root" "$target"
    if [ -e "$link" ] && [ ! -L "$link" ]; then
      die "host app-chain path is not the managed symlink: $link"
    fi
    if [ -L "$link" ]; then
      if python3 - "$link" "$target" "$NETWORK_ROOT/instances" <<'PY'
import os, sys
link, target, allowed = map(os.path.abspath, sys.argv[1:])
actual = os.path.realpath(link)
expected = os.path.realpath(target)
allowed = os.path.realpath(allowed)
if os.path.commonpath((actual, allowed)) != allowed:
    raise SystemExit(2)
raise SystemExit(0 if actual == expected else 1)
PY
      then
        status=0
      else
        status=$?
      fi
      if [ "$status" -ne 0 ]; then
        [ "$status" -eq 1 ] \
          || die "host app-chain link points outside the managed instances root: $link"
        rm "$link"
      fi
    fi
    [ -L "$link" ] || ln -s "$target" "$link"
    [ "$(cd "$link" && pwd -P)" = "$(cd "$target" && pwd -P)" ] \
      || die "host app-chain link points to an unexpected directory"
  done
}

start_host_ui() {
  local pid deadline
  resolve_host_ui_command
  pid="$(python3 "$SCRIPT_DIR/tools/managed_process.py" start \
    --runtime-root "$RUNTIME_ROOT" --name host-ui --log-file host-ui.log -- \
    "${HOST_UI_COMMAND[@]}")" \
    || die "host evidence UI could not be started or recovered safely"
  deadline=$((SECONDS + 30))
  until curl -fsS "http://127.0.0.1:$DEMO_UI_PORT/healthz" >/dev/null 2>&1; do
    host_ui_process || die "host evidence UI exited during startup (see $RUNTIME_ROOT/host-ui.log)"
    [ "$SECONDS" -lt "$deadline" ] || die "host evidence UI was not ready within 30 seconds"
    sleep 1
  done
}

HOST_UI_COMMAND=()
resolve_host_ui_command() {
  local java_executable
  java_executable="$(command -v java 2>/dev/null)" \
    || die "required command not found: java"
  [ -n "$java_executable" ] || die "required command not found: java"
  HOST_UI_COMMAND=("$java_executable" --add-modules=jdk.httpserver \
    -jar "$RUNTIME_ROOT/runner.jar" serve --config "$UI_CONFIG")
}

host_ui_process() {
  resolve_host_ui_command
  python3 "$SCRIPT_DIR/tools/managed_process.py" status \
    --runtime-root "$RUNTIME_ROOT" --name host-ui -- \
    "${HOST_UI_COMMAND[@]}" >/dev/null
}

HOST_UI_RUNNING=false
report_host_ui_status() {
  local output status=0
  HOST_UI_RUNNING=false
  resolve_host_ui_command
  output="$(python3 "$SCRIPT_DIR/tools/managed_process.py" status \
    --runtime-root "$RUNTIME_ROOT" --name host-ui -- \
    "${HOST_UI_COMMAND[@]}")" || status=$?
  case "$status" in
    0) HOST_UI_RUNNING=true; note "Host evidence UI: $output";;
    3) note "Host evidence UI: stopped";;
    *) die "host evidence UI lifecycle artifacts are invalid or untrusted";;
  esac
}

stop_host_ui() {
  resolve_host_ui_command
  python3 "$SCRIPT_DIR/tools/managed_process.py" stop \
    --runtime-root "$RUNTIME_ROOT" --name host-ui \
    --term-timeout 15 --kill-timeout 5 -- "${HOST_UI_COMMAND[@]}" \
    || die "host evidence UI could not be proven stopped; lifecycle record was preserved"
}

host_ui_lifecycle_artifacts() {
  local artifact
  for artifact in \
    "$RUNTIME_ROOT/host-ui.process.json" \
    "$RUNTIME_ROOT/.host-ui.launch.json" \
    "$RUNTIME_ROOT/.host-ui.process.tmp" \
    "$RUNTIME_ROOT/.host-ui.process-update.tmp" \
    "$RUNTIME_ROOT/.host-ui.launch.tmp"; do
    [ ! -e "$artifact" ] && [ ! -L "$artifact" ] || return 0
  done
  return 1
}

cmd_prepare() {
  prepare_configuration
  build_artifacts
  verify_artifacts
  if [ "$MODE" = compose ]; then
    dc config --quiet
  fi
  release_command_lease
  trap cleanup_temporary_files EXIT
  note "Prepared $MODE demo instance '$INSTANCE'."
  note "Secrets: $SECRET_ROOT (values are not printed)"
}

STARTUP_LEASE_ACQUIRED=false
STARTUP_MAY_HAVE_SERVICES=false

rollback_host_ui() {
  host_ui_lifecycle_artifacts || return 0
  resolve_host_ui_command || return 1
  python3 "$SCRIPT_DIR/tools/managed_process.py" stop \
    --runtime-root "$RUNTIME_ROOT" --name host-ui \
    --term-timeout 15 --kill-timeout 5 -- "${HOST_UI_COMMAND[@]}" >/dev/null 2>&1
}

rollback_failed_startup() {
  local stopped=false containers=""
  [ "$STARTUP_LEASE_ACQUIRED" = true ] || return 0
  note "ROLLBACK_STARTUP: stopping partial services before deciding whether the shared L1 lease can be released." >&2
  if [ "$MODE" = compose ]; then
    if command -v docker >/dev/null 2>&1 && [ -f "$COMPOSE_ENV" ] \
        && validate_private_runtime_file "$COMPOSE_ENV"; then
      compose_down_confirmed >/dev/null 2>&1 && stopped=true
    fi
    if [ "$stopped" = false ] && command -v docker >/dev/null 2>&1; then
      containers="$(docker ps -a --filter "label=com.docker.compose.project=$PROJECT_NAME" \
        -q 2>/dev/null || printf __unknown__)"
      [ -z "$containers" ] && stopped=true
    fi
  else
    if rollback_host_ui; then
      if [ -d "$L1_ROOT/host-cluster" ]; then
        host_cluster stop >/dev/null 2>&1 && stopped=true
      else
        stopped=true
      fi
    fi
  fi
  if [ "$stopped" = true ] && release_l1_lease >/dev/null 2>&1; then
    STARTUP_LEASE_ACQUIRED=false
    note "ROLLBACK_STARTUP complete: partial services stopped and L1 lease released." >&2
    return 0
  fi
  note "ROLLBACK_STARTUP incomplete: L1 lease preserved because a partial service could not be proven stopped; run ./demo.sh stop with the same profile." >&2
  return 1
}

release_command_lease() {
  [ "$STARTUP_LEASE_ACQUIRED" = true ] || return 0
  release_l1_lease >/dev/null
  STARTUP_LEASE_ACQUIRED=false
}

demo_exit_handler() {
  local status="$1"
  trap - EXIT
  set +e
  if [ "$STARTUP_LEASE_ACQUIRED" = true ]; then
    if [ "$STARTUP_MAY_HAVE_SERVICES" = true ]; then
      rollback_failed_startup
    else
      release_command_lease
    fi
  fi
  cleanup_temporary_files
  exit "$status"
}

cmd_up() {
  local -a start_args
  prepare_configuration
  build_artifacts
  verify_artifacts
  if [ "$MODE" = compose ]; then
    dc config --quiet
  fi
  if [ "$MODE" = host ]; then
    require java
    runner_java init-connectors
  fi
  verify_cached_key_material
  if [ "$MODE" = compose ]; then
    STARTUP_MAY_HAVE_SERVICES=true
    dc up -d --wait --wait-timeout 360
    verify_compose_loopback_surfaces
    wait_for_public_l1_sync
    [ "$ANCHOR_ENABLED" = false ] || compose_anchor_bootstrap
    dc --profile tools run --rm scenario probe --config /run/demo/runner.properties
  else
    prepare_host_state_links
    start_args=(start 3 --network "$DEMO_NETWORK" --http-base "$HTTP0" \
      --server-base "$SERVER_BASE")
    if [ "$ANCHOR_ENABLED" = true ]; then
      start_args+=(--anchor --anchor-every "$PROFILE_ANCHOR_EVERY_BLOCKS")
    fi
    STARTUP_MAY_HAVE_SERVICES=true
    host_cluster "${start_args[@]}"
    wait_for_public_l1_sync
    [ "$ANCHOR_ENABLED" = false ] || host_anchor_bootstrap
    runner_java probe
    start_host_ui
  fi
  [ "$ANCHOR_ENABLED" = false ] || reconcile_anchor_binding
  STARTUP_MAY_HAVE_SERVICES=false
  STARTUP_LEASE_ACQUIRED=false
  trap cleanup_temporary_files EXIT
  note "Yano status: http://127.0.0.1:$HTTP0/ui/app-chain/ (nodes: $HTTP0, $HTTP1, $HTTP2)"
  note "Evidence UI: http://127.0.0.1:$DEMO_UI_PORT/"
  note "API key file: $API_KEY_FILE"
  if [ "$OBSERVABILITY" = true ] && [ "$MODE" = compose ]; then
    note "Prometheus: http://127.0.0.1:$DEMO_PROMETHEUS_PORT/"
    note "Grafana: http://127.0.0.1:$DEMO_GRAFANA_PORT/ (password file: $GRAFANA_PASSWORD_FILE)"
  fi
}

validate_running_configuration() {
  [ -e "$L1_LEASE" ] \
    || die "instance '$INSTANCE' is not running (shared L1 lease is absent)"
  validate_persisted_lifecycle_identity true
  validate_l1_lease_owner \
    || die "shared L1 state is leased by another instance"
  load_cached_key_material
  verify_loaded_key_identity
  verify_cached_key_material
  [ "$ANCHOR_ENABLED" = false ] || validate_anchor_binding_preflight
}

cmd_scenario() {
  local -a runner_args
  local runner_sample=""
  validate_running_configuration
  verify_artifacts
  runner_args=("$COMMAND" --evidence-id "$DEMO_EVIDENCE_ID")
  if [ "$COMMAND" != verify ]; then
    prepare_scenario_input
    runner_sample="$SCENARIO_INPUT_COPY"
    [ "$MODE" != compose ] || runner_sample=/run/demo/scenario-input
    runner_args+=(--sample-file "$runner_sample")
  fi
  if [ -n "$BUSINESS_VERSION" ]; then
    runner_args+=(--business-version "$BUSINESS_VERSION")
  elif [ "$COMMAND" = verify ]; then
    runner_args+=(--business-version latest)
  fi
  if [ "$MODE" = compose ]; then
    if [ "$COMMAND" = verify ]; then
      dc --profile tools run --rm scenario "${runner_args[@]}" \
        --config /run/demo/runner.properties
    else
      export DEMO_SCENARIO_INPUT_FILE="$SCENARIO_INPUT_COPY"
      dc --profile tools run --rm scenario "${runner_args[@]}" \
        --config /run/demo/runner.properties
      unset DEMO_SCENARIO_INPUT_FILE
    fi
  else
    runner_java "${runner_args[@]}"
  fi
  [ "$ANCHOR_ENABLED" = false ] || reconcile_anchor_binding true
}

cmd_load() {
  local -a runner_args
  local runner_sample
  [ "$ANCHOR_ENABLED" = false ] || [ "$PROFILE_PUBLIC" = false ] \
    || die "load is disabled for public anchor-enabled profiles; use APP_FINAL load testing"
  validate_running_configuration
  verify_artifacts
  prepare_scenario_input
  runner_sample="$SCENARIO_INPUT_COPY"
  [ "$MODE" != compose ] || runner_sample=/run/demo/scenario-input
  runner_args=(load --count "$LOAD_COUNT" --concurrency "$LOAD_CONCURRENCY"
    --load-mode "$LOAD_MODE" --id-prefix "$LOAD_ID_PREFIX" --sample-file "$runner_sample")
  if [ -n "$LOAD_MAX_IN_FLIGHT" ]; then
    runner_args+=(--max-in-flight "$LOAD_MAX_IN_FLIGHT")
  fi
  if [ "$MODE" = compose ]; then
    export DEMO_SCENARIO_INPUT_FILE="$SCENARIO_INPUT_COPY"
    dc --profile tools run --rm scenario "${runner_args[@]}" \
      --config /run/demo/runner.properties
    unset DEMO_SCENARIO_INPUT_FILE
  else
    runner_java "${runner_args[@]}"
  fi
}

cmd_probe() {
  validate_running_configuration
  verify_artifacts
  if [ "$MODE" = compose ]; then
    dc --profile tools run --rm scenario probe --config /run/demo/runner.properties
  else
    runner_java probe
  fi
  [ "$ANCHOR_ENABLED" = false ] || reconcile_anchor_binding false
}

cmd_status() {
  if [ -e "$L1_LEASE" ]; then
    validate_l1_lease_owner \
      || die "shared L1 state is leased by another instance"
  fi
  if [ "$MODE" = compose ]; then
    [ -f "$COMPOSE_ENV" ] || { note "Instance '$INSTANCE' is not prepared."; return; }
    dc ps
  else
    [ -f "$RUNNER_CONFIG" ] && [ -d "$L1_ROOT/host-cluster" ] \
      || { note "Instance '$INSTANCE' is not prepared."; return; }
    if host_ui_lifecycle_artifacts; then report_host_ui_status; fi
    [ -e "$L1_LEASE" ] \
      || {
        [ "$HOST_UI_RUNNING" = false ] \
          || die "host evidence UI is running without its required shared L1 lease"
        note "Instance '$INSTANCE' is stopped (no active L1 lease)."
        return
      }
    host_cluster status
  fi
}

cmd_stop() {
  local containers
  if [ -e "$L1_LEASE" ]; then
    validate_l1_lease_owner \
      || die "shared L1 state is leased by another instance"
  fi
  if [ "$MODE" = compose ]; then
    if [ -f "$COMPOSE_ENV" ]; then
      compose_down_confirmed \
        || die "Compose services could not be proven absent; L1 lease was preserved"
    elif [ -e "$L1_LEASE" ]; then
      require docker
      containers="$(docker ps -a --filter "label=com.docker.compose.project=$PROJECT_NAME" -q 2>/dev/null)" \
        || die "cannot inspect the partial Compose deployment; L1 lease was preserved"
      [ -z "$containers" ] \
        || die "Compose runtime metadata is missing while containers remain; L1 lease was preserved"
    else
      note "Instance '$INSTANCE' is not prepared; nothing to stop."
      return
    fi
  else
    if [ ! -e "$L1_LEASE" ]; then
      if host_ui_lifecycle_artifacts; then stop_host_ui; fi
      # A killed launcher can leave a gated child or exact PID metadata before
      # the deployment lease is visible. Reconcile the cluster lifecycle even
      # without a lease; cluster.sh fails closed on malformed/uncertain state.
      [ ! -d "$L1_ROOT/host-cluster" ] || host_cluster stop
      if [ -f "$RUNNER_CONFIG" ] || [ -d "$L1_ROOT/host-cluster" ]; then
        note "Instance '$INSTANCE' is already stopped; data was preserved."
      else
        note "Instance '$INSTANCE' is not prepared; nothing to stop."
      fi
      return
    fi
    if host_ui_lifecycle_artifacts; then stop_host_ui; fi
    [ ! -d "$L1_ROOT/host-cluster" ] || host_cluster stop
  fi
  release_l1_lease
  note "Stopped instance '$INSTANCE'; data was preserved."
}

managed_services_running() {
  local artifact containers
  if [ -L "$L1_LEASE" ]; then
    die "L1 lease must not be a symlink: $L1_LEASE"
  fi
  [ ! -e "$L1_LEASE" ] || return 0
  if [ "$MODE" = compose ]; then
    command -v docker >/dev/null 2>&1 \
      || die "cannot verify Compose is stopped because Docker is unavailable"
    containers="$(docker ps -a \
      --filter "label=com.docker.compose.project=$PROJECT_NAME" -q 2>/dev/null)" \
      || die "cannot verify Compose is stopped because the Docker daemon is unavailable"
    [ -z "$containers" ] || return 0
  else
    # Any durable UI record/fence/temp is active or uncertain until `stop`
    # reconciles it through the exact PID/start-token/argv manager.
    host_ui_lifecycle_artifacts && return 0
    # Cluster stop is the only operation allowed to reconcile these durable
    # launch/PID records. Even a malformed, stale, symlinked, or temporary
    # artifact is active/uncertain from cleanup's perspective.
    for artifact in \
      "$L1_ROOT/host-cluster"/node*.pid \
      "$L1_ROOT/host-cluster"/node*.pid.meta \
      "$L1_ROOT/host-cluster"/node*.launch \
      "$L1_ROOT/host-cluster"/node*.pid.tmp.* \
      "$L1_ROOT/host-cluster"/node*.pid.meta.tmp.*; do
      [ ! -e "$artifact" ] && [ ! -L "$artifact" ] || return 0
    done
  fi
  return 1
}

validate_persisted_lifecycle_identity() {
  local require_instance="${1:-true}"
  [ -f "$NETWORK_MARKER" ] && [ ! -L "$NETWORK_MARKER" ] \
    || die "cleanup requires the retained immutable network identity marker: $NETWORK_MARKER"
  if [ "$require_instance" = true ]; then
    [ -f "$INSTANCE_MARKER" ] && [ ! -L "$INSTANCE_MARKER" ] \
      || die "cleanup requires the retained immutable app-chain identity marker: $INSTANCE_MARKER"
  fi
  python3 - "$NETWORK_MARKER" "$INSTANCE_MARKER" "$require_instance" \
    "$DEMO_NETWORK" "$PROFILE_PROTOCOL_MAGIC" "$INSTANCE" "$MODE" "$DEMO_CHAIN_ID" <<'PY' \
    || die "persisted lifecycle identity is invalid or does not match the requested cleanup target"
import hashlib
import json
import os
import stat
import sys
from pathlib import Path


def unique(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate key")
        result[key] = value
    return result


def read_marker(raw_path):
    path = Path(raw_path)
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    try:
        before = os.fstat(descriptor)
        if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.geteuid()
                or before.st_nlink != 1 or stat.S_IMODE(before.st_mode) != 0o600
                or not 1 <= before.st_size <= 65536):
            raise ValueError("unsafe marker")
        raw = b""
        while len(raw) <= 65536:
            chunk = os.read(descriptor, 65537 - len(raw))
            if not chunk:
                break
            raw += chunk
        after = os.fstat(descriptor)
        if ((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
                != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
                or len(raw) != before.st_size):
            raise ValueError("marker changed while being read")
    finally:
        os.close(descriptor)
    document = json.loads(raw.decode("utf-8"), object_pairs_hook=unique)
    if not isinstance(document, dict):
        raise ValueError("marker must be an object")
    canonical = (json.dumps(document, ensure_ascii=False, allow_nan=False,
                            sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
    if raw != canonical:
        raise ValueError("marker is not canonical")
    return document, raw


network_path, instance_path, require_instance, network_name, magic, instance, deployment, chain_id = sys.argv[1:]
network, network_raw = read_marker(network_path)
if (network.get("schemaVersion") != 1 or network.get("kind") != "yano.demo.network-identity"
        or network.get("networkName") != network_name
        or network.get("protocolMagic") != int(magic)):
    raise SystemExit(1)
if require_instance == "true":
    appchain, _ = read_marker(instance_path)
    if (appchain.get("schemaVersion") != 1
            or appchain.get("kind") != "yano.demo.appchain-identity"
            or appchain.get("networkName") != network_name
            or appchain.get("networkIdentitySha256") != hashlib.sha256(network_raw).hexdigest()
            or appchain.get("instanceId") != instance
            or appchain.get("deployment") != deployment
            or appchain.get("chainIds") != [chain_id]):
        raise SystemExit(1)
    anchor = appchain.get("anchor")
    if not isinstance(anchor, dict) or not isinstance(anchor.get("enabled"), bool):
        raise SystemExit(1)
PY
}

prepare_cleanup_replacement() {
  if [ "$CLEAN_SCOPE" = instance ] || [ "$CLEAN_SCOPE" = all ]; then
    [ -n "$NEW_INSTANCE" ] \
      || die "--scope $CLEAN_SCOPE requires --new-instance with a distinct replacement identity"
    [ -n "$NEW_CHAIN_ID" ] || NEW_CHAIN_ID="evidence-chain-$NEW_INSTANCE"
    [[ "$NEW_CHAIN_ID" =~ ^[a-z][a-z0-9-]{0,62}$ ]] \
      || die "replacement chain id must match [a-z][a-z0-9-]{0,62}"
    [ "$NEW_CHAIN_ID" != "$DEMO_CHAIN_ID" ] \
      || die "replacement chain id must differ from the retired chain id"
  elif [ -n "$NEW_INSTANCE" ] || [ -n "$NEW_CHAIN_ID" ]; then
    die "--new-instance/--new-chain-id are only valid with --scope instance or --scope all"
  fi
}

cleanup_identity_digest() {
  if [ -f "$INSTANCE_MARKER" ] && [ ! -L "$INSTANCE_MARKER" ]; then
    validate_persisted_lifecycle_identity true
    python3 - "$INSTANCE_MARKER" <<'PY'
import hashlib
from pathlib import Path
import sys

print(hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest())
PY
    return
  fi
  [ "$CLEAN_SCOPE" = instance ] || [ "$CLEAN_SCOPE" = all ] \
    || die "cleanup requires the retained immutable app-chain identity marker: $INSTANCE_MARKER"
  [ ! -e "$DATA_ROOT" ] && [ ! -L "$DATA_ROOT" ] \
    || die "instance state exists without its immutable identity marker; restore it before cleanup"
  python3 - "$RETIRE_MARKER" "$DEMO_NETWORK" "$INSTANCE" "$MODE" \
    "$DEMO_CHAIN_ID" "$NEW_INSTANCE" "$NEW_CHAIN_ID" <<'PY' \
    || die "durable retirement record does not match the requested cleanup plan"
import json
import os
from pathlib import Path
import re
import stat
import sys

path = Path(sys.argv[1])
flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
descriptor = os.open(path, flags)
try:
    before = os.fstat(descriptor)
    if (not stat.S_ISREG(before.st_mode) or before.st_uid != os.geteuid()
            or before.st_nlink != 1 or stat.S_IMODE(before.st_mode) != 0o600
            or not 1 <= before.st_size <= 65536):
        raise SystemExit(1)
    raw = b""
    while len(raw) <= 65536:
        chunk = os.read(descriptor, 65537 - len(raw))
        if not chunk:
            break
        raw += chunk
    after = os.fstat(descriptor)
    if ((before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns)
            != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns)
            or len(raw) != before.st_size):
        raise SystemExit(1)
finally:
    os.close(descriptor)

document = json.loads(raw.decode("utf-8"))
expected_fields = {
    "schemaVersion", "kind", "networkName", "instanceId", "deployment", "chainId",
    "appchainIdentitySha256", "replacementInstanceId", "replacementChainId", "status",
    "updatedAtMillis",
}
expected_values = {
    "schemaVersion": 1,
    "kind": "yano.demo.retired-instance",
    "networkName": sys.argv[2],
    "instanceId": sys.argv[3],
    "deployment": sys.argv[4],
    "chainId": sys.argv[5],
    "replacementInstanceId": sys.argv[6],
    "replacementChainId": sys.argv[7],
}
if set(document) != expected_fields or any(
        document.get(key) != value for key, value in expected_values.items()):
    raise SystemExit(1)
digest = document.get("appchainIdentitySha256")
updated = document.get("updatedAtMillis")
if (not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest)
        or document.get("status") not in {"retiring", "retired"}
        or not isinstance(updated, int) or isinstance(updated, bool) or updated < 0):
    raise SystemExit(1)
canonical = (json.dumps(document, ensure_ascii=False, allow_nan=False,
                        sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8")
if raw != canonical:
    raise SystemExit(1)
print(digest)
PY
}

write_cleanup_plan() {
  local output="$1" digest="$2"
  python3 - "$output" "$DEMO_NETWORK" "$INSTANCE" "$MODE" "$DEMO_CHAIN_ID" \
    "$digest" "$CLEAN_SCOPE" "$DATA_ROOT" "$L1_ROOT" "$RUNTIME_ROOT" \
    "$NEW_INSTANCE" "$NEW_CHAIN_ID" <<'PY'
import json
from pathlib import Path
import sys

(
    output, network, instance, deployment, chain, digest, scope,
    data_root, l1_root, runtime_root, replacement_instance, replacement_chain,
) = sys.argv[1:]
document = {
    "schemaVersion": 1,
    "kind": "yano.demo.cleanup-plan",
    "networkName": network,
    "instanceId": instance,
    "deployment": deployment,
    "chainId": chain,
    "appchainIdentitySha256": None if digest == "-" else digest,
    "scope": scope,
    "dataRoot": data_root,
    "l1Root": l1_root,
    "runtimeRoot": runtime_root,
    "replacementInstanceId": replacement_instance or None,
    "replacementChainId": replacement_chain or None,
}
Path(output).write_text(
    json.dumps(document, ensure_ascii=False, allow_nan=False,
               sort_keys=True, separators=(",", ":")) + "\n",
    encoding="utf-8",
)
PY
}

require_public_l1_delete_confirmation() {
  [ "$PROFILE_PUBLIC" = false ] || [ "$PUBLIC_L1_DELETE_CONFIRMATION" = "$DEMO_NETWORK" ] \
    || die "public L1 deletion requires --confirm-public-l1-delete $DEMO_NETWORK"
}

cmd_clean() {
  local digest="-" plan
  case "$CLEAN_SCOPE" in
    observability|reports|runtime|instance|l1|all) ;;
    appchain|connectors)
      die "--scope $CLEAN_SCOPE is unsafe in isolation; retire the whole effect instance with --scope instance --new-instance"
      ;;
    '') die "clean requires one explicit --scope";;
    *) die "unsupported clean scope: $CLEAN_SCOPE";;
  esac
  prepare_cleanup_replacement
  [ "$CLEAN_CONFIRMED" = true ] || die "clean requires --yes; nothing was changed"
  if managed_services_running; then
    die "managed services or an L1 lease are active; run stop before clean"
  fi
  if [ "$CLEAN_SCOPE" = l1 ] || [ "$CLEAN_SCOPE" = all ]; then
    require_public_l1_delete_confirmation
  fi
  if [ "$CLEAN_SCOPE" != l1 ]; then
    digest="$(cleanup_identity_digest)"
  fi
  temporary_file; plan="$LAST_TEMP_FILE"
  write_cleanup_plan "$plan" "$digest"
  python3 "$SCRIPT_DIR/tools/lifecycle.py" cleanup-execute \
    --network-root "$NETWORK_ROOT" --runtime-allowed-root "$RUNTIME_INSTANCE_ROOT" \
    --plan-file "$plan" --yes
  note "Cleanup complete. Secrets were preserved under $SECRET_ROOT."
  if [ -n "$NEW_INSTANCE" ]; then
    note "Replacement: ./demo.sh up --network $DEMO_NETWORK --deployment $MODE --instance $NEW_INSTANCE --chain-id $NEW_CHAIN_ID"
  fi
}

cmd_config() {
  [ "$MODE" = compose ] || die "config is a Compose-only command"
  prepare_configuration
  dc config
  release_command_lease
  trap cleanup_temporary_files EXIT
}

usage() {
  cat <<'EOF'
Usage: ./demo.sh <command> [options]

Commands:
  prepare   build/stage plugins, runner and images; generate private config
  up        start the selected three-node profile and probe it
  run       safely publish an absent default id, or verify matching retained bytes
  publish   create version 1 for one new evidence id
  republish create the exact next immutable version of an existing evidence id
  verify    read-only verification of latest or one historical evidence version
  replay    explicitly finalize the accepted command as a deterministic no-op
  load      publish many unique evidence records with bounded concurrency
  probe     verify Yano, Kafka, S3 and IPFS readiness
  status    show cluster status
  stop      stop processes and preserve all data
  config    render the fully resolved Compose model
  clean     delete one explicit stopped-instance category

Options:
  --deployment compose|host default: compose (`--mode` remains an alias)
  --network <profile>       devnet (default), preview, preprod, or mainnet
  --instance <name>         isolated name: [a-z0-9][a-z0-9-]{0,31}
  --chain-id <id>           explicit app-chain id (non-default instances derive one)
  --evidence-id <id>        scenario business id (use a fresh id for fault tests)
  --sample-file <path>      bounded input for run/publish/republish/replay
  --business-version <n>    required by republish/replay; optional for verify
  --count <n>               load publications, 1..50000
  --concurrency <n>         load workers, 1..16 and no greater than count
  --id-prefix <prefix>      required load identity prefix; IDs append -000001
  --load-mode lifecycle|pipeline
                            full per-item workers (default) or bounded staged pipeline
  --max-in-flight <n>       pipeline workflow bound, concurrency..min(count,5000)
  --continuation explicit|direct
                            explicit notify (legacy/default) or activated direct result emission
  --machine standalone|composite
                            evidence-v1-gated composite (default) or standalone regression profile
  --data-dir <base>         bind-data base; network isolation is added below it
  --observability           start pinned Prometheus and Grafana services
  --anchor-key-file <path>  owner-only funded anchor seed (preview/preprod)
  --confirm-public-anchor <network>
                            separately acknowledge preview/preprod anchor spending
  --enable-mainnet          required for mainnet prepare/config/up/run/probe/clean

Cleanup options:
  --scope <category>        observability, reports, runtime, instance, l1, or all
  --yes                     mandatory deletion confirmation
  --new-instance <name>     mandatory for instance/all retirement
  --new-chain-id <id>       replacement id (default: evidence-chain-<new-instance>)
  --confirm-public-l1-delete <network>
                            second exact acknowledgement for public L1 deletion

App-chain journals and connector durability are one effect-instance boundary;
they cannot be cleaned independently. Public anchor keys never imply consent
to spend. Mainnet forbids demo anchoring and automatic value movement. Cleanup
always preserves secret material.
EOF
}

case "$COMMAND" in
  prepare) cmd_prepare;;
  up) cmd_up;;
  run|publish|republish|verify|replay) cmd_scenario;;
  load) cmd_load;;
  probe) cmd_probe;;
  status) cmd_status;;
  stop) cmd_stop;;
  config) cmd_config;;
  clean) cmd_clean;;
  help|-h|--help) usage;;
  *) usage >&2; die "unknown command: $COMMAND";;
esac
