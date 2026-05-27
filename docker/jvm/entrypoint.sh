#!/usr/bin/env sh
set -eu

PROFILE="${YANO_PROFILE:-preprod}"
DEFAULT_NETWORK_CONFIG_DIR="/app/default-config/network"
NETWORK_CONFIG_DIR="/app/config/network"

seed_network_config() {
  if [ ! -d "$DEFAULT_NETWORK_CONFIG_DIR" ]; then
    return
  fi

  mkdir -p "$NETWORK_CONFIG_DIR"

  (
    cd "$DEFAULT_NETWORK_CONFIG_DIR"
    find . -type d | while IFS= read -r dir; do
      mkdir -p "$NETWORK_CONFIG_DIR/$dir"
    done
    find . -type f | while IFS= read -r file; do
      if [ ! -e "$NETWORK_CONFIG_DIR/$file" ]; then
        cp -p "$DEFAULT_NETWORK_CONFIG_DIR/$file" "$NETWORK_CONFIG_DIR/$file"
      fi
    done
  )
}

seed_network_config

# shellcheck disable=SC2086
exec java ${JAVA_OPTS:-} -Dquarkus.profile="${PROFILE}" -jar /app/yano.jar ${YANO_EXTRA_ARGS:-} "$@"
