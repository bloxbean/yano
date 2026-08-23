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

# JAVA_OPTS is honoured here as it is in the JVM image. Keep these rules in
# sync with app/bin/yano.sh: a native image implements its own -XX: namespace
# and rejects unknown ones loudly, so -XX: is forwarded; it silently ignores an
# unknown -X, so anything beyond -Xmx/-Xms/-Xss is forwarded with a warning;
# JVM agents and module-system flags cannot exist at all and are dropped.
NATIVE_JAVA_OPTS=""
JAVA_OPTS_DROPPED=""
JAVA_OPTS_UNVERIFIED=""
for opt in ${JAVA_OPTS:-}; do
  case "$opt" in
    -javaagent*|-agentlib*|-agentpath*|--add-*|--enable-preview)
      JAVA_OPTS_DROPPED="${JAVA_OPTS_DROPPED} ${opt}"
      ;;
    -D*|-Xmx*|-Xms*|-Xss*|-XX:*|-verbose*)
      NATIVE_JAVA_OPTS="${NATIVE_JAVA_OPTS} ${opt}"
      ;;
    -X*)
      JAVA_OPTS_UNVERIFIED="${JAVA_OPTS_UNVERIFIED} ${opt}"
      NATIVE_JAVA_OPTS="${NATIVE_JAVA_OPTS} ${opt}"
      ;;
    *)
      JAVA_OPTS_DROPPED="${JAVA_OPTS_DROPPED} ${opt}"
      ;;
  esac
done

if [ -n "$JAVA_OPTS_DROPPED" ]; then
  echo "Warning: dropping JAVA_OPTS entries a native image cannot implement:${JAVA_OPTS_DROPPED}" >&2
  echo "         JVM agents and module-system flags have no native equivalent." >&2
fi
if [ -n "$JAVA_OPTS_UNVERIFIED" ]; then
  echo "Warning: forwarding JAVA_OPTS entries this image may ignore silently:${JAVA_OPTS_UNVERIFIED}" >&2
  echo "         Only -Xmx, -Xms and -Xss are verified on a native image; prefer" >&2
  echo "         the equivalent -XX: option ('/app/yano -XX:PrintFlags=' lists them)." >&2
fi

# shellcheck disable=SC2086
exec /app/yano ${NATIVE_JAVA_OPTS} -Dquarkus.profile="${PROFILE}" ${YANO_EXTRA_ARGS:-} "$@"
