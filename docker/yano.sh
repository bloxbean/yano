#!/usr/bin/env sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/compose/yano.yml"
DEVNET_COMPOSE_FILE="$SCRIPT_DIR/compose/yano-devnet.yml"
ENV_FILE="$SCRIPT_DIR/compose/.env"

usage() {
  echo "Usage: $0 [start|start:devnet|stop|restart|restart:devnet|logs|logs:yano|status|config|config:devnet|pull]"
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

compose_devnet() {
  docker compose -f "$COMPOSE_FILE" -f "$DEVNET_COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
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
    compose up -d
    ;;
  start:devnet)
    compose_devnet up -d
    ;;
  stop)
    compose down
    ;;
  restart)
    compose down
    compose up -d
    ;;
  restart:devnet)
    compose_devnet down
    compose_devnet up -d
    ;;
  logs|logs:yano)
    compose logs -f yano
    ;;
  status)
    compose ps
    ;;
  config)
    compose config
    ;;
  config:devnet)
    compose_devnet config
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
