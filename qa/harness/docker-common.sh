#!/bin/bash
# Helpers for the Docker Compose distribution tests (docker-dist.sh, docker-public.sh).
# The image is built from the working tree as bloxbean/yano:qa-local-jvm, so developer
# images such as local-jvm are never replaced. Containers, networks and folders use
# qa-docker-* names; cleanup touches only those.
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

QA_IMAGE_TAG=qa-local
QA_IMAGE=bloxbean/yano:$QA_IMAGE_TAG-jvm
DOCKER_CACHE=$QA_BIN/docker
DOCKER_ZIP=$DOCKER_CACHE/yano-docker.zip
FAILS=()
check() { # <label> <condition-exit-code>
  if [ "$2" = 0 ]; then echo "  PASS  $1"; else echo "  FAIL  $1"; FAILS+=("$1"); fi
}

# Builds the JVM image and the Compose ZIP unless both exist for the current commit and
# uncommitted tracked changes. -Dquarkus.native.container-build=true bundles the Linux
# DuckDB extensions the container needs.
ensure_docker_build() {
  local key; key="$(cd "$REPO" && git rev-parse HEAD)-$(cd "$REPO" && git diff HEAD | shasum | cut -c1-12)"
  mkdir -p "$DOCKER_CACHE"
  if [ "$(cat "$DOCKER_CACHE/key" 2>/dev/null)" = "$key" ] && [ -f "$DOCKER_ZIP" ] \
      && docker image inspect "$QA_IMAGE" >/dev/null 2>&1; then
    log "reusing $QA_IMAGE and Compose ZIP ($key)"; return 0
  fi
  log "building $QA_IMAGE and the Compose ZIP"
  (cd "$REPO" && ./gradlew :app:prepareYanoDockerJvmContext :app:yanoDockerDistZip \
      -PyanoDockerImageTag=$QA_IMAGE_TAG -PskipSigning=true -Dquarkus.native.container-build=true) \
      > "$SP/runs/docker-build.log" 2>&1 || { log "gradle build failed (runs/docker-build.log)"; return 1; }
  docker build -q --build-arg JVM_BASE_IMAGE=eclipse-temurin:25-jre -t "$QA_IMAGE" \
      "$REPO/app/build/docker/jvm/context" >> "$SP/runs/docker-build.log" 2>&1 \
      || { log "docker build failed (runs/docker-build.log)"; return 1; }
  cp "$(ls -t "$REPO"/app/build/distributions/yano-docker-*.zip | head -1)" "$DOCKER_ZIP" || return 1
  echo "$key" > "$DOCKER_CACHE/key"
  # The context build rebuilt app/build/yano.jar with Linux extensions; restore the
  # orchestrator's jar, which scripts/sparse-backfill reads from app/build.
  [ -f "$QA_BIN/yano.jar" ] && cp "$QA_BIN/yano.jar" "$REPO/app/build/yano.jar"
  return 0
}

# extract_bundle <dir> <instance> <http> <n2n>: a fresh Compose bundle on harness ports.
extract_bundle() {
  local dir=$1 inst=$2 http=$3 n2n=$4 tmp
  rm -rf "$dir"; mkdir -p "$dir"; tmp=$(mktemp -d)
  unzip -q "$DOCKER_ZIP" -d "$tmp" && mv "$tmp"/yano-docker-*/* "$tmp"/yano-docker-*/.[!.]* "$dir"/ 2>/dev/null
  rm -rf "$tmp"
  set_env "$dir" INSTANCE_NAME "$inst"; set_env "$dir" YANO_HTTP_PORT "$http"; set_env "$dir" YANO_N2N_PORT "$n2n"
  set_env "$dir" YANO_UID "$(id -u)"; set_env "$dir" YANO_GID "$(id -g)"
  QA_CONTAINERS="$QA_CONTAINERS yano-$inst"
}

set_env() { # <bundle-dir> <key> <value>
  local f=$1/compose/.env
  if grep -q "^$2=" "$f"; then
    python3 - "$f" "$2" "$3" <<'PY'
import sys
path, key, value = sys.argv[1:4]
lines = open(path).read().splitlines()
open(path, "w").write("\n".join(f"{key}={value}" if l.startswith(key + "=") else l for l in lines) + "\n")
PY
  else echo "$2=$3" >> "$f"; fi
}

wait_http_ready() { # <http_port> <timeout_s>
  local t=${2:-120}
  for i in $(seq 1 "$t"); do
    curl -sf "http://localhost:$1/q/health/ready" >/dev/null 2>&1 && return 0
    sleep 1
  done
  return 1
}

api() { curl -s "http://localhost:$1/api/v1/$2"; }

# Remember networks that existed before the run so cleanup never removes someone else's.
NETWORKS_BEFORE=$(docker network ls --format '{{.Name}}' 2>/dev/null)
QA_CONTAINERS=""

docker_cleanup() {
  for c in $QA_CONTAINERS; do
    docker rm -fv "$c" >/dev/null 2>&1 && log "removed container $c"
  done
  for n in $(docker network ls --format '{{.Name}}' 2>/dev/null); do
    case "$n" in yano-qa-docker-*|compose_default) ;; *) continue ;; esac
    printf '%s\n' "$NETWORKS_BEFORE" | grep -qx "$n" && continue
    docker network rm "$n" >/dev/null 2>&1 && log "removed network $n"
  done
}
