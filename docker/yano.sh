#!/usr/bin/env sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/compose/yano.yml"
DEVNET_COMPOSE_FILE="$SCRIPT_DIR/compose/yano-devnet.yml"
MAINNET_COMPOSE_FILE="$SCRIPT_DIR/compose/yano-mainnet.yml"
PREVIEW_COMPOSE_FILE="$SCRIPT_DIR/compose/yano-preview.yml"
SANCHONET_COMPOSE_FILE="$SCRIPT_DIR/compose/yano-sanchonet.yml"
ENV_FILE="$SCRIPT_DIR/compose/.env"
COMPOSE_DIR="$SCRIPT_DIR/compose"

usage() {
  echo "Usage: $0 [start|start:<profiles>|stop|restart|restart:<profiles>|logs|logs:yano|status|config|config:<profiles>|pull]"
}

require_docker() {
  if ! command -v docker >/dev/null 2>&1; then
    echo "Docker is not installed or not found in PATH." >&2
    exit 1
  fi

  if ! docker compose version >/dev/null 2>&1; then
    echo "'docker compose' is not available. Install Docker with the Compose plugin." >&2
    exit 1
  fi
}

compose() {
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
}

validate_profile_name() {
  profile="$1"
  case "$profile" in
    ''|*/*|*..*|*[!abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.-]*)
      echo "Invalid profile name: $profile" >&2
      echo "Use letters, numbers, dot, underscore, and dash only." >&2
      exit 1
      ;;
  esac
}

validate_profile_list() {
  profile_list="$1"
  case "$profile_list" in
    ''|*,|,*|*,,*)
      echo "Invalid profile list: $profile_list" >&2
      echo "Use comma-separated profile names without empty segments." >&2
      exit 1
      ;;
  esac

  rest="$profile_list"
  while :; do
    case "$rest" in
      *,*)
        profile="${rest%%,*}"
        rest="${rest#*,}"
        ;;
      *)
        profile="$rest"
        rest=""
        ;;
    esac
    validate_profile_name "$profile"
    [ -z "$rest" ] && break
  done
}

primary_profile() {
  profile_list="$1"
  printf '%s\n' "${profile_list%%,*}"
}

default_profiles() {
  if [ -n "${YANO_PROFILE:-}" ]; then
    printf '%s\n' "$YANO_PROFILE"
    return
  fi

  configured_profile="$(strip_optional_quotes "$(env_file_value YANO_PROFILE)")"
  if [ -n "$configured_profile" ]; then
    printf '%s\n' "$configured_profile"
    return
  fi

  printf '%s\n' preprod
}

env_file_value() {
  key="$1"
  sed -n "s/^${key}=//p" "$ENV_FILE" | tail -n 1
}

strip_optional_quotes() {
  value="$1"
  case "$value" in
    \"*\")
      value="${value#\"}"
      value="${value%\"}"
      ;;
    \'*\')
      value="${value#\'}"
      value="${value%\'}"
      ;;
  esac
  printf '%s\n' "$value"
}

chainstate_path_for_profile() {
  profile="$1"

  if [ -n "${YANO_CHAINSTATE_PATH:-}" ]; then
    printf '%s\n' "$YANO_CHAINSTATE_PATH"
    return
  fi

  configured_path="$(strip_optional_quotes "$(env_file_value YANO_CHAINSTATE_PATH)")"
  if [ -n "$configured_path" ]; then
    printf '%s\n' "$configured_path"
    return
  fi

  printf '../chainstate-%s\n' "$profile"
}

ensure_chainstate_dir() {
  profile="$1"
  chainstate_path="$(chainstate_path_for_profile "$profile")"

  case "$chainstate_path" in
    /*)
      chainstate_dir="$chainstate_path"
      ;;
    *)
      chainstate_dir="$COMPOSE_DIR/$chainstate_path"
      ;;
  esac

  mkdir -p "$chainstate_dir"
}

prepare_chainstate_for_profiles() {
  profile_list="$1"
  validate_profile_list "$profile_list"
  ensure_chainstate_dir "$(primary_profile "$profile_list")"
}

compose_network() {
  profile_list="$1"
  shift
  validate_profile_list "$profile_list"
  network="$(primary_profile "$profile_list")"
  validate_profile_name "$network"

  if [ "${1:-}" = "up" ]; then
    ensure_chainstate_dir "$network"
  fi

  case "$network" in
    preprod)
      YANO_PROFILE="$profile_list" \
        YANO_NETWORK="$network" \
        compose "$@"
      ;;
    mainnet)
      YANO_PROFILE="$profile_list" \
        YANO_NETWORK="$network" \
        docker compose -f "$COMPOSE_FILE" -f "$MAINNET_COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
      ;;
    preview)
      YANO_PROFILE="$profile_list" \
        YANO_NETWORK="$network" \
        docker compose -f "$COMPOSE_FILE" -f "$PREVIEW_COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
      ;;
    sanchonet)
      YANO_PROFILE="$profile_list" \
        YANO_NETWORK="$network" \
        docker compose -f "$COMPOSE_FILE" -f "$SANCHONET_COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
      ;;
    devnet)
      YANO_PROFILE="$profile_list" \
        YANO_NETWORK="$network" \
        docker compose -f "$COMPOSE_FILE" -f "$DEVNET_COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
      ;;
    *)
      custom_chainstate_path="$(chainstate_path_for_profile "$network")"
      YANO_PROFILE="$profile_list" \
        YANO_NETWORK="$network" \
        YANO_CHAINSTATE_PATH="$custom_chainstate_path" \
        docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
      ;;
  esac
}

ACTION="${1:-}"
if [ -z "$ACTION" ]; then
  usage
  exit 1
fi

if [ ! -f "$ENV_FILE" ]; then
  echo "Compose env file not found: $ENV_FILE" >&2
  exit 1
fi

require_docker

case "$ACTION" in
  start)
    compose_network "$(default_profiles)" up -d
    ;;
  start:preprod)
    compose_network preprod up -d
    ;;
  start:mainnet)
    compose_network mainnet up -d
    ;;
  start:preview)
    compose_network preview up -d
    ;;
  start:sanchonet)
    compose_network sanchonet up -d
    ;;
  start:devnet)
    compose_network devnet up -d
    ;;
  start:*)
    compose_network "${ACTION#start:}" up -d
    ;;
  stop)
    compose down
    ;;
  restart)
    profiles="$(default_profiles)"
    prepare_chainstate_for_profiles "$profiles"
    compose_network "$profiles" down
    compose_network "$profiles" up -d
    ;;
  restart:preprod)
    prepare_chainstate_for_profiles preprod
    compose_network preprod down
    compose_network preprod up -d
    ;;
  restart:mainnet)
    prepare_chainstate_for_profiles mainnet
    compose_network mainnet down
    compose_network mainnet up -d
    ;;
  restart:preview)
    prepare_chainstate_for_profiles preview
    compose_network preview down
    compose_network preview up -d
    ;;
  restart:sanchonet)
    prepare_chainstate_for_profiles sanchonet
    compose_network sanchonet down
    compose_network sanchonet up -d
    ;;
  restart:devnet)
    prepare_chainstate_for_profiles devnet
    compose_network devnet down
    compose_network devnet up -d
    ;;
  restart:*)
    profiles="${ACTION#restart:}"
    prepare_chainstate_for_profiles "$profiles"
    compose_network "$profiles" down
    compose_network "$profiles" up -d
    ;;
  logs|logs:yano)
    compose logs -f yano
    ;;
  status)
    compose ps
    ;;
  config)
    compose_network "$(default_profiles)" config
    ;;
  config:preprod)
    compose_network preprod config
    ;;
  config:mainnet)
    compose_network mainnet config
    ;;
  config:preview)
    compose_network preview config
    ;;
  config:sanchonet)
    compose_network sanchonet config
    ;;
  config:devnet)
    compose_network devnet config
    ;;
  config:*)
    compose_network "${ACTION#config:}" config
    ;;
  pull)
    compose pull
    ;;
  *)
    echo "Invalid action: $ACTION" >&2
    usage
    exit 1
    ;;
esac
