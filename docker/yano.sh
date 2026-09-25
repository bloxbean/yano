#!/usr/bin/env sh
set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/compose/yano.yml"
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

# Default host path for one data folder of a network: data-<network>/<folder>.
# A folder from the earlier flat layout (<folder>-<network>/ beside compose/) stays in
# use, so an upgraded installation keeps its database instead of starting empty.
default_data_path() { # <folder> <profile>
  if [ -d "$COMPOSE_DIR/../$1-$2" ]; then
    printf '../%s-%s\n' "$1" "$2"
  else
    printf '../data-%s/%s\n' "$2" "$1"
  fi
}

# Export defaults only for data paths the user has not set. Values from the shell or
# compose/.env are left to Compose, which expands ${VAR} references inside them.
export_default_data_paths() { # <network>
  for pair in YANO_CHAINSTATE_PATH:chainstate YANO_RUNTIME_DATA_PATH:runtime-data \
      YANO_APPCHAIN_STATE_PATH:appchain-chainstate YANO_APPCHAIN_INDEXER_PATH:appchain-indexers; do
    var=${pair%%:*}
    folder=${pair#*:}
    eval "current=\${$var:-}"
    [ -n "$current" ] && continue
    [ -n "$(env_file_value "$var")" ] && continue
    eval "$var=\$(default_data_path \"\$folder\" \"\$1\")"
    export "$var"
  done
}

note_legacy_folders() { # <network>
  for pair in YANO_CHAINSTATE_PATH:chainstate YANO_RUNTIME_DATA_PATH:runtime-data \
      YANO_APPCHAIN_STATE_PATH:appchain-chainstate YANO_APPCHAIN_INDEXER_PATH:appchain-indexers; do
    var=${pair%%:*}
    folder=${pair#*:}
    eval "path=\${$var:-}"
    if [ "$path" = "../$folder-$1" ]; then
      echo "Using $folder-$1/ from the earlier folder layout (new installations use data-$1/$folder/)." >&2
    fi
  done
}

compose_for_network() { # <network> <compose arguments...>
  network_name=$1
  shift
  case "$network_name" in
    mainnet|preview|sanchonet|devnet)
      docker compose -f "$COMPOSE_FILE" -f "$COMPOSE_DIR/yano-$network_name.yml" --env-file "$ENV_FILE" "$@"
      ;;
    *)
      compose "$@"
      ;;
  esac
}

# Create the host data folders before Docker can create them as root, using the paths
# Compose resolves (after expanding .env references).
ensure_data_dirs() { # <network>
  compose_for_network "$1" config 2>/dev/null | awk '
    /^[[:space:]]*source:/ { src = $0; sub(/^[[:space:]]*source:[[:space:]]*/, "", src); gsub(/^"|"$/, "", src) }
    /^[[:space:]]*target:/ {
      t = $0; sub(/^[[:space:]]*target:[[:space:]]*/, "", t)
      if (t == "/app/data" || t == "/app/data/chainstate" || t == "/app/appchain-chainstate" || t == "/app/appchain-indexers") print src
    }' | while IFS= read -r dir; do
    [ -n "$dir" ] && mkdir -p "$dir"
  done
}

prepare_chainstate_for_profiles() {
  profile_list="$1"
  validate_profile_list "$profile_list"
  profile="$(primary_profile "$profile_list")"
  export_default_data_paths "$profile"
  ensure_data_dirs "$profile"
}

container_label() {
  docker container inspect --format "{{index .Config.Labels \"$2\"}}" "$1" 2>/dev/null
}

# The launcher manages only the container created from this directory's Compose file.
# "start" refuses any other container with the configured name. "replace" (stop and
# restart) also removes this directory's container when it belongs to an older project
# name, such as "compose" from bundles that did not name their Compose project.
ensure_container_ownership() {
  mode="$1"
  identity="$(compose config 2>/dev/null | sed -n -e 's/^name: *//p' -e 's/^ *container_name: *//p')"
  expected_project="$(printf '%s\n' "$identity" | sed -n 1p)"
  container="$(printf '%s\n' "$identity" | sed -n 2p)"
  if [ -z "$expected_project" ] || [ -z "$container" ]; then
    return 0
  fi

  # Compose acts on every container in the project, so a project shared with another
  # directory (the same COMPOSE_PROJECT_NAME) would let it stop or replace that container.
  for member in $(docker ps -aq --filter "label=com.docker.compose.project=$expected_project"); do
    member_files="$(container_label "$member" com.docker.compose.project.config_files)"
    case ",$member_files," in
      *",$COMPOSE_FILE,"*) ;;
      *)
        member_name="$(docker container inspect --format '{{.Name}}' "$member" 2>/dev/null)"
        echo "Compose project '$expected_project' already has container ${member_name#/} from another directory." >&2
        echo "Files: $member_files" >&2
        if [ -n "${COMPOSE_PROJECT_NAME:-}" ] || [ -n "$(env_file_value COMPOSE_PROJECT_NAME)" ]; then
          echo "Give each directory its own project: set a distinct COMPOSE_PROJECT_NAME in $ENV_FILE," >&2
          echo "or remove COMPOSE_PROJECT_NAME so the project follows INSTANCE_NAME." >&2
        else
          echo "Give each directory its own project: set a distinct INSTANCE_NAME in $ENV_FILE." >&2
        fi
        exit 1
        ;;
    esac
  done

  existing_project="$(container_label "$container" com.docker.compose.project)" || return 0
  existing_files="$(container_label "$container" com.docker.compose.project.config_files)"
  case ",$existing_files," in
    *",$COMPOSE_FILE,"*) ;;
    *)
      echo "Container $container is not managed by this directory." >&2
      echo "Compose project: $existing_project; files: $existing_files" >&2
      echo "Set a distinct INSTANCE_NAME in $ENV_FILE, or stop that instance from its own directory." >&2
      exit 1
      ;;
  esac

  if [ "$existing_project" = "$expected_project" ]; then
    return 0
  fi
  if [ "$mode" != "replace" ]; then
    echo "Container $container was started from this directory as Compose project '$existing_project'." >&2
    echo "Run '$0 stop' or '$0 restart' to replace it with project '$expected_project'." >&2
    exit 1
  fi
  echo "Removing container $container from Compose project '$existing_project'."
  docker stop "$container" >/dev/null
  docker rm "$container" >/dev/null
}

compose_network() {
  profile_list="$1"
  shift
  validate_profile_list "$profile_list"
  network="$(primary_profile "$profile_list")"
  validate_profile_name "$network"
  export_default_data_paths "$network"

  if [ "${1:-}" = "down" ]; then
    ensure_container_ownership replace
  fi

  if [ "${1:-}" = "up" ]; then
    ensure_container_ownership start
    note_legacy_folders "$network"
    ensure_data_dirs "$network"
  fi

  YANO_PROFILE="$profile_list" YANO_NETWORK="$network" compose_for_network "$network" "$@"
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
    ensure_container_ownership replace
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
