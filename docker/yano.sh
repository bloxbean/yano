#!/usr/bin/env sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/compose/yano.yml"
DEVNET_COMPOSE_FILE="$SCRIPT_DIR/compose/yano-devnet.yml"
MAINNET_COMPOSE_FILE="$SCRIPT_DIR/compose/yano-mainnet.yml"
PREVIEW_COMPOSE_FILE="$SCRIPT_DIR/compose/yano-preview.yml"
SANCHONET_COMPOSE_FILE="$SCRIPT_DIR/compose/yano-sanchonet.yml"
ENV_FILE="$SCRIPT_DIR/compose/.env"

usage() {
  echo "Usage: $0 [start|start:<profile>|stop|restart|restart:<profile>|logs|logs:yano|status|config|config:<profile>|pull]"
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

env_file_value() {
  key="$1"
  sed -n "s/^${key}=//p" "$ENV_FILE" | tail -n 1
}

chainstate_path_for_profile() {
  profile="$1"

  if [ "${YANO_CHAINSTATE_PATH+x}" ]; then
    printf '%s\n' "$YANO_CHAINSTATE_PATH"
    return
  fi

  configured_path="$(env_file_value YANO_CHAINSTATE_PATH)"
  if [ -n "$configured_path" ]; then
    printf '%s\n' "$configured_path"
    return
  fi

  printf '../chainstate-%s\n' "$profile"
}

compose_network() {
  network="$1"
  shift
  validate_profile_name "$network"

  case "$network" in
    preprod)
      compose "$@"
      ;;
    mainnet)
      docker compose -f "$COMPOSE_FILE" -f "$MAINNET_COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
      ;;
    preview)
      docker compose -f "$COMPOSE_FILE" -f "$PREVIEW_COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
      ;;
    sanchonet)
      docker compose -f "$COMPOSE_FILE" -f "$SANCHONET_COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
      ;;
    devnet)
      docker compose -f "$COMPOSE_FILE" -f "$DEVNET_COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
      ;;
    *)
      custom_chainstate_path="$(chainstate_path_for_profile "$network")"
      YANO_PROFILE="$network" \
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
  start|start:preprod)
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
  restart|restart:preprod)
    compose_network preprod down
    compose_network preprod up -d
    ;;
  restart:mainnet)
    compose_network mainnet down
    compose_network mainnet up -d
    ;;
  restart:preview)
    compose_network preview down
    compose_network preview up -d
    ;;
  restart:sanchonet)
    compose_network sanchonet down
    compose_network sanchonet up -d
    ;;
  restart:devnet)
    compose_network devnet down
    compose_network devnet up -d
    ;;
  restart:*)
    profile="${ACTION#restart:}"
    compose_network "$profile" down
    compose_network "$profile" up -d
    ;;
  logs|logs:yano)
    compose logs -f yano
    ;;
  status)
    compose ps
    ;;
  config|config:preprod)
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
