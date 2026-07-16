#!/usr/bin/env bash
# ADR-013 Phase 1.5 launcher. Phase 1.6 owns public-network guards and cleanup.
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
    if [ -z "${!key+x}" ]; then
      printf -v "$key" '%s' "$value"
      export "$key"
    fi
  done < "$file"
}

load_defaults "$SCRIPT_DIR/config/common.env"
load_defaults "$SCRIPT_DIR/config/images.env"

COMMAND="${1:-help}"
[ "$#" -eq 0 ] || shift
MODE="${DEMO_MODE:-compose}"
INSTANCE="${DEMO_INSTANCE:-default}"
OBSERVABILITY="${DEMO_OBSERVABILITY:-false}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    --mode|--deployment) MODE="${2:-}"; shift 2;;
    --network) DEMO_NETWORK="${2:-}"; shift 2;;
    --instance) INSTANCE="${2:-}"; shift 2;;
    --observability) OBSERVABILITY=true; shift;;
    --no-observability) OBSERVABILITY=false; shift;;
    -h|--help) COMMAND=help; shift;;
    *) die "unknown option: $1";;
  esac
done

case "$MODE" in compose|host) ;; *) die "--mode must be compose or host";; esac
[[ "$INSTANCE" =~ ^[a-z0-9][a-z0-9-]{0,31}$ ]] \
  || die "--instance must match [a-z0-9][a-z0-9-]{0,31}"
[[ "$DEMO_CHAIN_ID" =~ ^[a-z][a-z0-9-]{0,62}$ ]] \
  || die "DEMO_CHAIN_ID must match [a-z][a-z0-9-]{0,62}"
[ "$DEMO_NETWORK" = devnet ] \
  || die "Phase 1.5 supports devnet only; public-network profiles and identity guards are Phase 1.6"
case "$OBSERVABILITY" in true|false) ;; *) die "DEMO_OBSERVABILITY must be true or false";; esac

DATA_ROOT="${DEMO_DATA_ROOT:-$SCRIPT_DIR/.demo-data}/$DEMO_NETWORK/$INSTANCE"
SECRET_ROOT="${DEMO_SECRET_ROOT:-$SCRIPT_DIR/.demo-secrets}/$DEMO_NETWORK/$INSTANCE"
RUNTIME_ROOT="${DEMO_RUNTIME_ROOT:-$SCRIPT_DIR/.demo-runtime}/$DEMO_NETWORK/$INSTANCE"
PLUGIN_DIR="$RUNTIME_ROOT/plugins"
REPORT_DIR="$DATA_ROOT/reports"
COMPOSE_ENV="$RUNTIME_ROOT/compose.env"
RUNNER_CONFIG="$RUNTIME_ROOT/runner-$MODE.properties"
UI_CONFIG="$RUNTIME_ROOT/ui-$MODE.properties"
NODE_CONFIG_DIR="$SECRET_ROOT/nodes-$MODE"
SHELLEY_GENESIS_FILE="$DATA_ROOT/l1/shared/shelley-genesis.json"
GENESIS_TIMESTAMP_FILE="$DATA_ROOT/l1/shared/genesis-timestamp"

API_KEY_FILE="$SECRET_ROOT/yano-api-key"
MINIO_ROOT_USER_FILE="$SECRET_ROOT/minio-root-user"
MINIO_ROOT_PASSWORD_FILE="$SECRET_ROOT/minio-root-password"
MINIO_RUNNER_ACCESS_FILE="${DEMO_HOST_S3_RUNNER_ACCESS_KEY_FILE:-$SECRET_ROOT/minio-runner-access-key}"
MINIO_RUNNER_SECRET_FILE="${DEMO_HOST_S3_RUNNER_SECRET_KEY_FILE:-$SECRET_ROOT/minio-runner-secret-key}"
MINIO_EXECUTOR_ACCESS_FILE="${DEMO_HOST_S3_EXECUTOR_ACCESS_KEY_FILE:-$SECRET_ROOT/minio-executor-access-key}"
MINIO_EXECUTOR_SECRET_FILE="${DEMO_HOST_S3_EXECUTOR_SECRET_KEY_FILE:-$SECRET_ROOT/minio-executor-secret-key}"
GRAFANA_PASSWORD_FILE="$SECRET_ROOT/grafana-admin-password"

HTTP0=$((10#$DEMO_HTTP_BASE))
HTTP1=$((HTTP0 + 1))
HTTP2=$((HTTP0 + 2))
SERVER_BASE="${DEMO_SERVER_BASE:-13337}"
PROJECT_NAME="yano-effects-${INSTANCE}"
INSTANCE_TARGET="$INSTANCE"
COMPOSE_S3_TARGET_ID="s3-compose-${DEMO_NETWORK}-${INSTANCE_TARGET}"
COMPOSE_IPFS_TARGET_ID="ipfs-compose-${DEMO_NETWORK}-${INSTANCE_TARGET}"
COMPOSE_KAFKA_TARGET_ID="kafka-compose-${DEMO_NETWORK}-${INSTANCE_TARGET}"
GIT_ID="$(git -C "$REPO_DIR" rev-parse --short=12 HEAD 2>/dev/null || printf local)"
DEMO_YANO_IMAGE="${DEMO_YANO_IMAGE:-yano-adr013-node:$GIT_ID}"
DEMO_RUNNER_IMAGE="${DEMO_RUNNER_IMAGE:-yano-adr013-runner:$GIT_ID}"

require() { command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"; }

validate_target_id() {
  local label="$1" value="$2"
  [[ "$value" =~ ^[a-z][a-z0-9-]{0,62}$ ]] \
    || die "$label must match [a-z][a-z0-9-]{0,62}"
}

resolve_host_target_ids() {
  require_host_target_for_override DEMO_HOST_S3_ENDPOINT DEMO_HOST_S3_TARGET_ID
  require_host_target_for_override DEMO_HOST_IPFS_API_URL DEMO_HOST_IPFS_TARGET_ID
  require_host_target_for_override DEMO_HOST_KAFKA_BOOTSTRAP_SERVERS DEMO_HOST_KAFKA_TARGET_ID
  HOST_S3_TARGET_ID="${DEMO_HOST_S3_TARGET_ID:-s3-host-${DEMO_NETWORK}-${INSTANCE_TARGET}}"
  HOST_IPFS_TARGET_ID="${DEMO_HOST_IPFS_TARGET_ID:-ipfs-host-${DEMO_NETWORK}-${INSTANCE_TARGET}}"
  HOST_KAFKA_TARGET_ID="${DEMO_HOST_KAFKA_TARGET_ID:-kafka-host-${DEMO_NETWORK}-${INSTANCE_TARGET}}"
  validate_target_id DEMO_HOST_S3_TARGET_ID "$HOST_S3_TARGET_ID"
  validate_target_id DEMO_HOST_IPFS_TARGET_ID "$HOST_IPFS_TARGET_ID"
  validate_target_id DEMO_HOST_KAFKA_TARGET_ID "$HOST_KAFKA_TARGET_ID"
}

require_host_target_for_override() {
  local locator="$1" target="$2" locator_value target_value
  locator_value="${!locator-__YANO_DEMO_UNSET__}"
  [ "$locator_value" != __YANO_DEMO_UNSET__ ] || return 0
  [ -n "$locator_value" ] || die "$locator must not be empty"
  target_value="${!target-}"
  [ -n "$target_value" ] || die "$target is required when $locator is overridden"
}

single_line() {
  local label="$1" value="$2"
  case "$value" in *$'\n'*|*$'\r'*) die "$label must not contain CR or LF";; esac
}

ensure_secret() {
  local file="$1" value="$2"
  if [ ! -e "$file" ]; then
    (umask 077; printf '%s\n' "$value" > "$file")
  fi
  [ -f "$file" ] && [ ! -L "$file" ] || die "secret must be a regular non-symlink file: $file"
  chmod 600 "$file"
}

read_secret() {
  local file="$1" value extra mode
  [ -f "$file" ] && [ ! -L "$file" ] || die "missing secret file: $file"
  if mode="$(stat -c '%a' "$file" 2>/dev/null)"; then :
  else mode="$(stat -f '%Lp' "$file" 2>/dev/null)" || die "cannot inspect secret mode: $file"
  fi
  (( (8#$mode & 077) == 0 )) || die "secret file must be owner-only (0400 or 0600): $file"
  exec 3< "$file"
  IFS= read -r value <&3 || [ -n "$value" ] || { exec 3<&-; die "empty secret file: $file"; }
  if IFS= read -r extra <&3; then
    exec 3<&-
    die "secret file must contain exactly one line: $file"
  fi
  exec 3<&-
  [ -n "$value" ] || die "empty secret file: $file"
  if LC_ALL=C printf '%s' "$value" | grep -q '[[:cntrl:]]'; then
    die "secret file contains control characters: $file"
  fi
  printf '%s' "$value"
}

repeat_hex_byte() {
  local byte="$1" out="" i
  [[ "$byte" =~ ^[0-9a-f]{2}$ ]] || die "invalid internal seed byte"
  for ((i=0; i<32; i++)); do out+="$byte"; done
  printf '%s' "$out"
}

prepare_directories() {
  local dir i
  mkdir -p "$SECRET_ROOT" "$RUNTIME_ROOT" "$PLUGIN_DIR" "$REPORT_DIR" \
    "$DATA_ROOT/connectors/kafka" "$DATA_ROOT/connectors/minio" \
    "$DATA_ROOT/connectors/ipfs" "$DATA_ROOT/observability/prometheus" \
    "$DATA_ROOT/observability/grafana" "$DATA_ROOT/l1/shared"
  chmod 700 "$SECRET_ROOT"
  for i in 0 1 2; do
    for dir in "$DATA_ROOT/l1/node$i" "$DATA_ROOT/app-chain/node$i" \
      "$DATA_ROOT/logs/node$i"; do
      mkdir -p "$dir"
      chmod u+rwx "$dir"
    done
  done
  chmod u+rwx "$DATA_ROOT/connectors/kafka" "$DATA_ROOT/connectors/minio" \
    "$DATA_ROOT/connectors/ipfs" "$DATA_ROOT/observability/prometheus" \
    "$DATA_ROOT/observability/grafana" "$REPORT_DIR"
}

prepare_secrets() {
  require openssl
  ensure_secret "$API_KEY_FILE" "$(openssl rand -hex 32)"
  ensure_secret "$MINIO_ROOT_USER_FILE" "yanoroot$(openssl rand -hex 8)"
  ensure_secret "$MINIO_ROOT_PASSWORD_FILE" "$(openssl rand -hex 32)"
  if [ "$MINIO_RUNNER_ACCESS_FILE" = "$SECRET_ROOT/minio-runner-access-key" ]; then
    ensure_secret "$MINIO_RUNNER_ACCESS_FILE" "yanorunner$(openssl rand -hex 8)"
    ensure_secret "$MINIO_RUNNER_SECRET_FILE" "$(openssl rand -hex 32)"
  fi
  if [ "$MINIO_EXECUTOR_ACCESS_FILE" = "$SECRET_ROOT/minio-executor-access-key" ]; then
    ensure_secret "$MINIO_EXECUTOR_ACCESS_FILE" "yanoexecutor$(openssl rand -hex 8)"
    ensure_secret "$MINIO_EXECUTOR_SECRET_FILE" "$(openssl rand -hex 32)"
  fi
  ensure_secret "$GRAFANA_PASSWORD_FILE" "$(openssl rand -hex 24)"
  read_secret "$MINIO_RUNNER_ACCESS_FILE" >/dev/null
  read_secret "$MINIO_RUNNER_SECRET_FILE" >/dev/null
  read_secret "$MINIO_EXECUTOR_ACCESS_FILE" >/dev/null
  read_secret "$MINIO_EXECUTOR_SECRET_FILE" >/dev/null
}

prepare_genesis() {
  local source="$APP_DIR/config/network/devnet/shelley-genesis.json" now start tmp
  require jq
  [ -f "$source" ] || die "devnet Shelley genesis not found: $source"
  if [ -e "$SHELLEY_GENESIS_FILE" ] || [ -e "$GENESIS_TIMESTAMP_FILE" ]; then
    [ -f "$SHELLEY_GENESIS_FILE" ] && [ -f "$GENESIS_TIMESTAMP_FILE" ] \
      || die "incomplete shared devnet genesis state under $DATA_ROOT/l1/shared"
    return
  fi
  now="$(date +%s)"
  if start="$(date -u -r "$now" '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null)"; then :
  else start="$(date -u -d "@$now" '+%Y-%m-%dT%H:%M:%SZ')"
  fi
  tmp="$SHELLEY_GENESIS_FILE.tmp.$$"
  jq --arg start "$start" '.epochLength = 500 | .systemStart = $start' "$source" > "$tmp"
  chmod 600 "$tmp"
  mv "$tmp" "$SHELLEY_GENESIS_FILE"
  (umask 077; printf '%s\n' "$((now * 1000))" > "$GENESIS_TIMESTAMP_FILE")
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
  local index="$1" seed="$2" peers="$3" extras="$4" base
  base="$RUNTIME_ROOT/node$index.base"
  render_template "$SCRIPT_DIR/config/templates/node-compose.properties.in" "$base" \
    API_KEY "$(read_secret "$API_KEY_FILE")" CHAIN_ID "$DEMO_CHAIN_ID" \
    SIGNING_KEY "$seed" APP_PEERS "$peers" \
    GENESIS_TIMESTAMP "$(read_secret "$GENESIS_TIMESTAMP_FILE")"
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
    MINIO_IP "$DEMO_MINIO_IP" KUBO_IP "$DEMO_KUBO_IP" \
    S3_TARGET_ID "$COMPOSE_S3_TARGET_ID" IPFS_TARGET_ID "$COMPOSE_IPFS_TARGET_ID" \
    KAFKA_TARGET_ID "$COMPOSE_KAFKA_TARGET_ID" \
    MINIO_EXECUTOR_ACCESS "$(read_secret "$MINIO_EXECUTOR_ACCESS_FILE")" \
    MINIO_EXECUTOR_SECRET "$(read_secret "$MINIO_EXECUTOR_SECRET_FILE")"
  cp "$executor" "$node0extra"
  anchor_key="$(repeat_hex_byte 30)"
  {
    printf '%s\n' 'yano.app-chain.chains[0].anchor.enabled=true'
    printf '%s\n' 'yano.app-chain.chains[0].anchor.mode=script'
    printf 'yano.app-chain.chains[0].anchor.signing-key=%s\n' "$anchor_key"
    # The READY continuation can be the last app block. Anchor every block so
    # the proof closes without manufacturing a dummy follow-up command.
    printf '%s\n' 'yano.app-chain.chains[0].anchor.every-blocks=1'
    printf '%s\n' 'yano.app-chain.chains[0].anchor.max-interval-minutes=1'
  } >> "$node0extra"
  cp "$SCRIPT_DIR/config/templates/follower-compose.properties.in" "$follower"
  compose_node_config 0 "$(repeat_hex_byte 01)" \
    'yano-1:13337,yano-2:13337' "$node0extra"
  compose_node_config 1 "$(repeat_hex_byte 02)" \
    'yano-0:13337,yano-2:13337' "$follower"
  compose_node_config 2 "$(repeat_hex_byte 03)" \
    'yano-0:13337,yano-1:13337' "$follower"
  render_template "$SCRIPT_DIR/config/templates/runner-compose.properties.in" "$RUNNER_CONFIG" \
    CHAIN_ID "$DEMO_CHAIN_ID" MINIO_IP "$DEMO_MINIO_IP" KUBO_IP "$DEMO_KUBO_IP" \
    S3_TARGET_ID "$COMPOSE_S3_TARGET_ID" IPFS_TARGET_ID "$COMPOSE_IPFS_TARGET_ID" \
    KAFKA_TARGET_ID "$COMPOSE_KAFKA_TARGET_ID" \
    TIMEOUT_SECONDS "$DEMO_SCENARIO_TIMEOUT_SECONDS" \
    POLL_INTERVAL_MILLIS "$DEMO_SCENARIO_POLL_INTERVAL_MILLIS"
  chmod 600 "$RUNNER_CONFIG"
  render_template "$SCRIPT_DIR/config/templates/runner-ui.properties.in" "$UI_CONFIG" \
    REPORT_DIRECTORY /var/lib/yano-demo/reports BIND_ADDRESS 0.0.0.0 UI_INTERNAL_PORT 7080
  chmod 600 "$UI_CONFIG"
  rm -f "$executor" "$follower" "$node0extra"
}

prepare_host_configs() {
  local executor follower base i access secret host_home
  resolve_host_target_ids
  mkdir -p "$NODE_CONFIG_DIR"
  chmod 700 "$NODE_CONFIG_DIR"
  executor="$RUNTIME_ROOT/executor-host.properties"
  follower="$RUNTIME_ROOT/follower-host.properties"
  access="$(read_secret "$MINIO_EXECUTOR_ACCESS_FILE")"
  secret="$(read_secret "$MINIO_EXECUTOR_SECRET_FILE")"
  render_template "$SCRIPT_DIR/config/templates/executor-host.properties.in" "$executor" \
    KAFKA_BOOTSTRAP_SERVERS "${DEMO_HOST_KAFKA_BOOTSTRAP_SERVERS:-127.0.0.1:$DEMO_KAFKA_PORT}" \
    S3_ENDPOINT "${DEMO_HOST_S3_ENDPOINT:-http://127.0.0.1:$DEMO_MINIO_PORT}" \
    IPFS_API_URL "${DEMO_HOST_IPFS_API_URL:-http://127.0.0.1:$DEMO_IPFS_PORT}" \
    S3_TARGET_ID "$HOST_S3_TARGET_ID" IPFS_TARGET_ID "$HOST_IPFS_TARGET_ID" \
    KAFKA_TARGET_ID "$HOST_KAFKA_TARGET_ID" \
    MINIO_EXECUTOR_ACCESS "$access" MINIO_EXECUTOR_SECRET "$secret"
  cp "$SCRIPT_DIR/config/templates/follower-host.properties.in" "$follower"
  for i in 0 1 2; do
    base="$RUNTIME_ROOT/node$i-host.base"
    render_template "$SCRIPT_DIR/config/templates/node-host.properties.in" "$base" \
      PLUGIN_DIR "$PLUGIN_DIR"
    insert_node_settings "$base" "$([ "$i" -eq 0 ] && printf '%s' "$executor" || printf '%s' "$follower")" \
      "$NODE_CONFIG_DIR/node$i.properties"
    rm -f "$base"
  done
  render_template "$SCRIPT_DIR/config/templates/runner-host.properties.in" "$RUNNER_CONFIG" \
    CHAIN_ID "$DEMO_CHAIN_ID" HTTP0 "$HTTP0" HTTP1 "$HTTP1" HTTP2 "$HTTP2" \
    SAMPLE_FILE "$SCRIPT_DIR/samples/inspection-certificate.json" \
    REPORT_DIRECTORY "$REPORT_DIR" \
    S3_ENDPOINT "${DEMO_HOST_S3_ENDPOINT:-http://127.0.0.1:$DEMO_MINIO_PORT}" \
    MINIO_RUNNER_ACCESS_FILE "$MINIO_RUNNER_ACCESS_FILE" \
    MINIO_RUNNER_SECRET_FILE "$MINIO_RUNNER_SECRET_FILE" \
    IPFS_API_URL "${DEMO_HOST_IPFS_API_URL:-http://127.0.0.1:$DEMO_IPFS_PORT}" \
    KAFKA_BOOTSTRAP_SERVERS "${DEMO_HOST_KAFKA_BOOTSTRAP_SERVERS:-127.0.0.1:$DEMO_KAFKA_PORT}" \
    S3_TARGET_ID "$HOST_S3_TARGET_ID" IPFS_TARGET_ID "$HOST_IPFS_TARGET_ID" \
    KAFKA_TARGET_ID "$HOST_KAFKA_TARGET_ID" \
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
    "$host_home/config/application-appchain.yml" CHAIN_ID "$DEMO_CHAIN_ID"
}

write_compose_env() {
  local i variable
  for variable in DEMO_KAFKA_IMAGE DEMO_MINIO_IMAGE DEMO_MINIO_MC_IMAGE \
    DEMO_KUBO_IMAGE DEMO_PROMETHEUS_IMAGE DEMO_GRAFANA_IMAGE \
    DEMO_YANO_IMAGE DEMO_RUNNER_IMAGE DEMO_CONNECTOR_SUBNET DEMO_MINIO_IP \
    DEMO_KUBO_IP DEMO_UI_PORT DEMO_KAFKA_PORT DEMO_MINIO_PORT DEMO_IPFS_PORT \
    DEMO_PROMETHEUS_PORT DEMO_GRAFANA_PORT; do
    single_line "$variable" "${!variable}"
  done
  for variable in "$RUNNER_CONFIG" "$UI_CONFIG" "$PLUGIN_DIR" "$REPORT_DIR" "$API_KEY_FILE" \
    "$MINIO_ROOT_USER_FILE" "$MINIO_ROOT_PASSWORD_FILE" \
    "$MINIO_RUNNER_ACCESS_FILE" "$MINIO_RUNNER_SECRET_FILE" \
    "$MINIO_EXECUTOR_ACCESS_FILE" "$MINIO_EXECUTOR_SECRET_FILE" \
    "$GRAFANA_PASSWORD_FILE" "$SHELLEY_GENESIS_FILE" "$NODE_CONFIG_DIR" \
    "$DATA_ROOT"; do
    single_line "generated path" "$variable"
  done
  (umask 077
    {
      printf 'DEMO_PROJECT_NAME=%s\n' "$PROJECT_NAME"
      printf 'DEMO_HOST_UID=%s\n' "$(id -u)"
      printf 'DEMO_HOST_GID=%s\n' "$(id -g)"
      printf 'DEMO_YANO_IMAGE=%s\n' "$DEMO_YANO_IMAGE"
      printf 'DEMO_RUNNER_IMAGE=%s\n' "$DEMO_RUNNER_IMAGE"
      for i in KAFKA MINIO MINIO_MC KUBO PROMETHEUS GRAFANA; do
        variable="DEMO_${i}_IMAGE"
        printf 'DEMO_%s_IMAGE=%s\n' "$i" "${!variable}"
      done
      printf 'DEMO_HTTP0=%s\nDEMO_HTTP1=%s\nDEMO_HTTP2=%s\n' "$HTTP0" "$HTTP1" "$HTTP2"
      printf 'DEMO_UI_PORT=%s\nDEMO_KAFKA_PORT=%s\nDEMO_MINIO_PORT=%s\nDEMO_IPFS_PORT=%s\n' \
        "$DEMO_UI_PORT" "$DEMO_KAFKA_PORT" "$DEMO_MINIO_PORT" "$DEMO_IPFS_PORT"
      printf 'DEMO_PROMETHEUS_PORT=%s\nDEMO_GRAFANA_PORT=%s\n' \
        "$DEMO_PROMETHEUS_PORT" "$DEMO_GRAFANA_PORT"
      printf 'DEMO_CONNECTOR_SUBNET=%s\nDEMO_MINIO_IP=%s\nDEMO_KUBO_IP=%s\n' \
        "$DEMO_CONNECTOR_SUBNET" "$DEMO_MINIO_IP" "$DEMO_KUBO_IP"
      printf 'DEMO_RUNNER_CONFIG=%s\nDEMO_PLUGIN_DIR=%s\nDEMO_REPORT_DIR=%s\n' \
        "$RUNNER_CONFIG" "$PLUGIN_DIR" "$REPORT_DIR"
      printf 'DEMO_UI_CONFIG=%s\n' "$UI_CONFIG"
      printf 'DEMO_API_KEY_FILE=%s\n' "$API_KEY_FILE"
      printf 'DEMO_MINIO_ROOT_USER_FILE=%s\nDEMO_MINIO_ROOT_PASSWORD_FILE=%s\n' \
        "$MINIO_ROOT_USER_FILE" "$MINIO_ROOT_PASSWORD_FILE"
      printf 'DEMO_MINIO_RUNNER_ACCESS_FILE=%s\nDEMO_MINIO_RUNNER_SECRET_FILE=%s\n' \
        "$MINIO_RUNNER_ACCESS_FILE" "$MINIO_RUNNER_SECRET_FILE"
      printf 'DEMO_MINIO_EXECUTOR_ACCESS_FILE=%s\nDEMO_MINIO_EXECUTOR_SECRET_FILE=%s\n' \
        "$MINIO_EXECUTOR_ACCESS_FILE" "$MINIO_EXECUTOR_SECRET_FILE"
      printf 'DEMO_GRAFANA_PASSWORD_FILE=%s\n' "$GRAFANA_PASSWORD_FILE"
      printf 'DEMO_SHELLEY_GENESIS_FILE=%s\n' "$SHELLEY_GENESIS_FILE"
      for i in 0 1 2; do
        printf 'DEMO_NODE%s_CONFIG=%s\n' "$i" "$NODE_CONFIG_DIR/node$i.properties"
        printf 'DEMO_YANO%s_DATA_DIR=%s\n' "$i" "$DATA_ROOT/l1/node$i"
        printf 'DEMO_YANO%s_APP_DATA_DIR=%s\n' "$i" "$DATA_ROOT/app-chain/node$i"
        printf 'DEMO_YANO%s_LOG_DIR=%s\n' "$i" "$DATA_ROOT/logs/node$i"
      done
      printf 'DEMO_KAFKA_DATA_DIR=%s\nDEMO_MINIO_DATA_DIR=%s\nDEMO_IPFS_DATA_DIR=%s\n' \
        "$DATA_ROOT/connectors/kafka" "$DATA_ROOT/connectors/minio" "$DATA_ROOT/connectors/ipfs"
      printf 'DEMO_PROMETHEUS_DATA_DIR=%s\nDEMO_GRAFANA_DATA_DIR=%s\n' \
        "$DATA_ROOT/observability/prometheus" "$DATA_ROOT/observability/grafana"
    } > "$COMPOSE_ENV"
  )
}

prepare_configuration() {
  require python3
  prepare_directories
  prepare_secrets
  prepare_genesis
  if [ "$MODE" = compose ]; then
    prepare_compose_configs
  else
    prepare_host_configs
  fi
  write_compose_env
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
    :appchain-evidence-registry:shadowJar \
    :appchain-evidence-demo-runner:shadowJar \
    :app:prepareYanoDockerJvmContext
  for name in appchain-kafka appchain-ipfs appchain-objectstore-s3; do
    source="$(unique_artifact "$REPO_DIR/appchain/extensions/$name/build/libs" \
      '*-bundle.jar' "$name bundle")"
    install -m 0644 "$source" "$PLUGIN_DIR/$name-bundle.jar"
  done
  source="$(unique_artifact "$REPO_DIR/appchain/examples/appchain-evidence-registry/build/libs" \
    '*-bundle.jar' 'evidence-registry bundle')"
  install -m 0644 "$source" "$PLUGIN_DIR/appchain-evidence-registry-bundle.jar"
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
  local yano_context="$1" runner_context
  require docker
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
  for name in appchain-kafka appchain-ipfs appchain-objectstore-s3 \
    appchain-evidence-registry; do
    [ -f "$PLUGIN_DIR/$name-bundle.jar" ] || die "missing staged plugin: $name"
  done
  [ -f "$RUNTIME_ROOT/runner.jar" ] || die "missing runner; run prepare"
  [ -f "$RUNTIME_ROOT/yano.jar" ] || die "missing Yano jar; run prepare"
}

dc() {
  local args=(docker compose --env-file "$COMPOSE_ENV" -f "$COMPOSE_FILE")
  [ "$OBSERVABILITY" = false ] || args+=(--profile observability)
  "${args[@]}" "$@"
}

runner_java() {
  java --add-modules=jdk.httpserver -jar "$RUNTIME_ROOT/runner.jar" "$@" \
    --config "$RUNNER_CONFIG"
}

api_curl() {
  local key
  key="$(read_secret "$API_KEY_FILE")"
  curl --config - "$@" <<EOF
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
  wait_for_loopback_http "MinIO" \
    "http://127.0.0.1:$DEMO_MINIO_PORT/minio/health/ready"
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

compose_anchor_bootstrap() {
  local base="http://127.0.0.1:$HTTP0/api/v1" status bootstrapped address deadline
  status="$(curl -fsS "$base/app-chain/chains/$DEMO_CHAIN_ID/status")"
  read -r bootstrapped address < <(printf '%s' "$status" | python3 -c '
import json,sys
a=json.load(sys.stdin).get("anchor", {})
print(str(bool(a.get("bootstrapped"))).lower(), a.get("walletAddress", ""))
')
  [ "$bootstrapped" = true ] && return
  [ -n "$address" ] || die "node 0 did not report a script-anchor wallet"
  curl -fsS -X POST "$base/devnet/fund" -H 'Content-Type: application/json' \
    -d "{\"address\":\"$address\",\"ada\":500}" >/dev/null
  sleep 6
  api_curl -fsS -X POST \
    "$base/app-chain/chains/$DEMO_CHAIN_ID/admin/anchor/bootstrap" >/dev/null
  deadline=$((SECONDS + 120))
  while [ "$SECONDS" -lt "$deadline" ]; do
    status="$(curl -fsS "$base/app-chain/chains/$DEMO_CHAIN_ID/status")"
    bootstrapped="$(printf '%s' "$status" | python3 -c \
      'import json,sys; print(str(bool(json.load(sys.stdin).get("anchor",{}).get("bootstrapped"))).lower())')"
    [ "$bootstrapped" = true ] && return
    sleep 2
  done
  die "script anchor did not confirm within 120 seconds"
}

host_cluster() {
  local cluster="$APP_DIR/appchain-cluster/cluster.sh" key
  [ -x "$cluster" ] || die "cluster launcher not executable: $cluster"
  key="$(read_secret "$API_KEY_FILE")"
  YANO_HOME="$RUNTIME_ROOT/host-home" \
  YANO_JAR="$RUNTIME_ROOT/yano.jar" \
  YANO_CLUSTER_DIR="$DATA_ROOT/l1/host-cluster" \
  YANO_CLUSTER_NODE_CONFIG_DIR="$NODE_CONFIG_DIR" \
  YANO_CLUSTER_API_KEY="$key" \
  "$cluster" "$@"
}

prepare_host_state_links() {
  local i root target link
  for i in 0 1 2; do
    root="$DATA_ROOT/l1/host-cluster/node$i/chainstate"
    target="$DATA_ROOT/app-chain/node$i"
    link="$root/app-chain"
    mkdir -p "$root" "$target"
    if [ -e "$link" ] && [ ! -L "$link" ]; then
      die "host app-chain path is not the managed symlink: $link"
    fi
    [ -L "$link" ] || ln -s "$target" "$link"
    [ "$(cd "$link" && pwd -P)" = "$(cd "$target" && pwd -P)" ] \
      || die "host app-chain link points to an unexpected directory"
  done
}

start_host_ui() {
  local pid_file="$RUNTIME_ROOT/host-ui.pid" log="$RUNTIME_ROOT/host-ui.log" pid deadline
  if [ -f "$pid_file" ]; then
    pid="$(cat "$pid_file")"
    if host_ui_process "$pid"; then return; fi
    rm -f "$pid_file"
  fi
  java --add-modules=jdk.httpserver -jar "$RUNTIME_ROOT/runner.jar" serve \
    --config "$UI_CONFIG" >"$log" 2>&1 &
  pid=$!
  printf '%s\n' "$pid" > "$pid_file"
  deadline=$((SECONDS + 30))
  until curl -fsS "http://127.0.0.1:$DEMO_UI_PORT/healthz" >/dev/null 2>&1; do
    host_ui_process "$pid" || die "host evidence UI exited during startup (see $log)"
    [ "$SECONDS" -lt "$deadline" ] || die "host evidence UI was not ready within 30 seconds"
    sleep 1
  done
}

host_ui_process() {
  local pid="${1:-}" command
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 1
  kill -0 "$pid" 2>/dev/null || return 1
  command="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  [[ "$command" == *"$RUNTIME_ROOT/runner.jar"* \
    && "$command" == *"serve"* && "$command" == *"$UI_CONFIG"* ]]
}

stop_host_ui() {
  local pid_file="$RUNTIME_ROOT/host-ui.pid" pid deadline
  [ -f "$pid_file" ] || return 0
  pid="$(cat "$pid_file")"
  if host_ui_process "$pid"; then
    kill "$pid"
    deadline=$((SECONDS + 15))
    while kill -0 "$pid" 2>/dev/null && [ "$SECONDS" -lt "$deadline" ]; do sleep 1; done
    kill -0 "$pid" 2>/dev/null && die "host evidence UI did not stop within 15 seconds"
  fi
  rm -f "$pid_file"
}

cmd_prepare() {
  prepare_configuration
  build_artifacts
  verify_artifacts
  if [ "$MODE" = compose ]; then
    dc config --quiet
  fi
  note "Prepared $MODE demo instance '$INSTANCE'."
  note "Secrets: $SECRET_ROOT (values are not printed)"
}

cmd_up() {
  cmd_prepare
  if [ "$MODE" = compose ]; then
    dc up -d --wait --wait-timeout 360
    verify_compose_loopback_surfaces
    compose_anchor_bootstrap
    dc --profile tools run --rm scenario probe --config /run/demo/runner.properties
  else
    require java
    runner_java init-connectors
    prepare_host_state_links
    host_cluster start 3 --anchor --anchor-every 1 --http-base "$HTTP0" \
      --server-base "$SERVER_BASE"
    host_cluster anchor-bootstrap "$DEMO_CHAIN_ID"
    runner_java probe
    start_host_ui
  fi
  note "Yano status: http://127.0.0.1:$HTTP0/ui/app-chain/ (nodes: $HTTP0, $HTTP1, $HTTP2)"
  note "Evidence UI: http://127.0.0.1:$DEMO_UI_PORT/"
  note "API key file: $API_KEY_FILE"
  if [ "$OBSERVABILITY" = true ] && [ "$MODE" = compose ]; then
    note "Prometheus: http://127.0.0.1:$DEMO_PROMETHEUS_PORT/"
    note "Grafana: http://127.0.0.1:$DEMO_GRAFANA_PORT/ (password file: $GRAFANA_PASSWORD_FILE)"
  fi
}

cmd_run() {
  prepare_configuration
  verify_artifacts
  if [ "$MODE" = compose ]; then
    dc --profile tools run --rm scenario run --config /run/demo/runner.properties
  else
    runner_java run
  fi
}

cmd_probe() {
  prepare_configuration
  verify_artifacts
  if [ "$MODE" = compose ]; then
    dc --profile tools run --rm scenario probe --config /run/demo/runner.properties
  else
    runner_java probe
  fi
}

cmd_status() {
  prepare_configuration
  if [ "$MODE" = compose ]; then dc ps; else host_cluster status; fi
}

cmd_stop() {
  if [ "$MODE" = compose ]; then
    [ -f "$COMPOSE_ENV" ] || die "instance is not prepared: $INSTANCE"
    dc down
  else
    stop_host_ui
    host_cluster stop
  fi
  note "Stopped instance '$INSTANCE'; data was preserved."
}

cmd_config() {
  [ "$MODE" = compose ] || die "config is a Compose-only command"
  prepare_configuration
  dc config
}

usage() {
  cat <<'EOF'
Usage: ./demo.sh <command> [options]

Commands:
  prepare   build/stage plugins, runner and images; generate private config
  up        start the three-node devnet, bootstrap its anchor, and probe it
  run       execute the evidence scenario with the same deployment-neutral runner
  probe     verify Yano, Kafka, S3 and IPFS readiness
  status    show cluster status
  stop      stop processes and preserve all data
  config    render the fully resolved Compose model

Options:
  --deployment compose|host default: compose (`--mode` remains an alias)
  --network devnet          Phase 1.5 intentionally supports only devnet
  --instance <name>         isolated name: [a-z0-9][a-z0-9-]{0,31}
  --observability           start pinned Prometheus and Grafana services

There is deliberately no clean command in Phase 1.5. Granular deletion,
network identity markers, and guarded public-network lifecycle are Phase 1.6.
EOF
}

case "$COMMAND" in
  prepare) cmd_prepare;;
  up) cmd_up;;
  run) cmd_run;;
  probe) cmd_probe;;
  status) cmd_status;;
  stop) cmd_stop;;
  config) cmd_config;;
  clean) die "clean is intentionally deferred to Phase 1.6; stop preserves data";;
  help|-h|--help) usage;;
  *) usage >&2; die "unknown command: $COMMAND";;
esac
