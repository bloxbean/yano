#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
YANO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
STATE_DIR="${YANO_OBSERVABILITY_DIR:-${TMPDIR:-/tmp}/yano-observability-$(id -u)}"
DOCKER="${YANO_OBSERVABILITY_DOCKER:-docker}"
IMAGE='prom/prometheus:v3.13.1@sha256:3c42b892cf723fa54d2f262c37a0e1f80aa8c8ddb1da7b9b0df9455a35a7f893'
PORT="${YANO_OBSERVABILITY_PORT:-9090}"
INSTANCE="yano-observability-$(id -u)"
VOLUME="${INSTANCE}-data"
MARKER="$STATE_DIR/.yano-observability-v1"
ENV_FILE="$STATE_DIR/compose.env"
CONFIG_FILE="$STATE_DIR/prometheus.yml"

die() { printf 'error: %s\n' "$*" >&2; exit 1; }

usage() {
  cat <<'EOF'
Usage: ./yano.sh observability <command> [options]

  start [--target <origin>]... [--retention <duration>] [--retention-size <size>]
  status
  stop
  clean --yes

Defaults: discover a maintained local app-chain cluster, otherwise scrape
http://127.0.0.1:7070; retain 15d / 2GB; expose Prometheus on 127.0.0.1:9090.
EOF
}

require_docker() {
  command -v "$DOCKER" >/dev/null 2>&1 || die "Docker is required for the observability bundle; plain Yano remains available"
  "$DOCKER" compose version >/dev/null 2>&1 || die "Docker Compose v2 is required (docker compose)"
}

validate_state_path() {
  local canonical
  canonical="$(python3 - "$STATE_DIR" <<'PY'
import os, sys
print(os.path.realpath(sys.argv[1]))
PY
)" || die "cannot resolve observability state directory"
  [ "$canonical" != "/" ] && [ "$canonical" != "$HOME" ] && [ "$canonical" != "$YANO_ROOT" ] \
    || die "refusing unsafe observability state directory"
  STATE_DIR="$canonical"
  INSTANCE="yano-observability-$(id -u)-$(python3 - "$STATE_DIR" <<'PY'
import hashlib, sys
print(hashlib.sha256(sys.argv[1].encode()).hexdigest()[:10])
PY
)"
  VOLUME="${INSTANCE}-data"
  MARKER="$STATE_DIR/.yano-observability-v1"
  ENV_FILE="$STATE_DIR/compose.env"
  CONFIG_FILE="$STATE_DIR/prometheus.yml"
}

validate_marker() {
  [ -f "$MARKER" ] && [ ! -L "$MARKER" ] || die "observability state marker is missing or unsafe"
  [ "$(cat "$MARKER")" = "yano-observability-v1:$INSTANCE" ] || die "observability state marker belongs to another instance"
}

compose() {
  "$DOCKER" compose --project-name "$INSTANCE" --env-file "$ENV_FILE" -f "$SCRIPT_DIR/compose.yml" "$@"
}

normalize_targets() {
  python3 - "$@" <<'PY'
import sys
import ipaddress
import re
from urllib.parse import urlsplit
for raw in sys.argv[1:]:
    if len(raw) > 512 or raw != raw.strip(): raise SystemExit(2)
    value = urlsplit(raw)
    if value.scheme not in ('http', 'https') or not value.hostname or value.username or value.password:
        raise SystemExit(2)
    if value.path not in ('', '/') or value.query or value.fragment: raise SystemExit(2)
    try: port = value.port
    except ValueError: raise SystemExit(2)
    port = port or (443 if value.scheme == 'https' else 80)
    if not 1 <= port <= 65535: raise SystemExit(2)
    host = value.hostname.lower()
    try: ipaddress.ip_address(host)
    except ValueError:
        if not re.fullmatch(r'[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?', host) or '..' in host:
            raise SystemExit(2)
    display_host = f'[{host}]' if ':' in host else host
    display = f'{value.scheme}://{display_host}'
    if port != (443 if value.scheme == 'https' else 80): display += f':{port}'
    docker_host = 'host.docker.internal' if host in ('127.0.0.1', 'localhost', '::1') else host
    if ':' in docker_host: docker_host = f'[{docker_host}]'
    print(f'{value.scheme}\t{docker_host}:{port}\t{display}')
PY
}

discover_targets() {
  local cluster env
  cluster="${YANO_CLUSTER_DIR:-/tmp/yano-appchain-cluster}"
  env="$cluster/cluster.env"
  local base=7070 found=0 file index pid
  if [ -f "$env" ] && [ ! -L "$env" ]; then
    base="$(sed -n 's/^HTTP_BASE=\([0-9][0-9]*\)$/\1/p' "$env" | head -1)"
    [[ "$base" =~ ^[0-9]+$ ]] || base=7070
    for file in "$cluster"/node*.pid; do
      [ -f "$file" ] && [ ! -L "$file" ] || continue
      index="${file##*/node}"; index="${index%.pid}"
      [[ "$index" =~ ^[0-9]+$ ]] || continue
      pid="$(cat "$file" 2>/dev/null || true)"
      [[ "$pid" =~ ^[0-9]+$ ]] && kill -0 "$pid" 2>/dev/null || continue
      printf 'http://127.0.0.1:%s\n' "$((base + index))"
      found=1
    done
  fi
  [ "$found" -eq 1 ] || printf 'http://127.0.0.1:7070\n'
}

validate_retention() {
  local value="$1" kind="$2" amount unit normalized
  if [ "$kind" = time ]; then
    [[ "$value" =~ ^([1-9][0-9]*)(h|d)$ ]] || die "retention must use whole hours or days (1h to 90d)"
    amount="${BASH_REMATCH[1]}"; unit="${BASH_REMATCH[2]}"
    [ "${#amount}" -le 6 ] || die "retention value is too large"
    [ "$unit" = h ] && normalized="$amount" || normalized="$((amount * 24))"
    [ "$normalized" -ge 1 ] && [ "$normalized" -le 2160 ] || die "retention must be between 1h and 90d"
  else
    [[ "$value" =~ ^([1-9][0-9]*)(MB|GB)$ ]] || die "retention size must use MB or GB (256MB to 20GB)"
    amount="${BASH_REMATCH[1]}"; unit="${BASH_REMATCH[2]}"
    [ "${#amount}" -le 6 ] || die "retention size is too large"
    [ "$unit" = MB ] && normalized="$amount" || normalized="$((amount * 1024))"
    [ "$normalized" -ge 256 ] && [ "$normalized" -le 20480 ] || die "retention size must be between 256MB and 20GB"
  fi
}

start_bundle() {
  local retention=15d size=2GB line scheme target display cors="" explicit=0 console_origin="" metrics_url encoded_metrics
  local discovered normalized_output
  local -a raw_targets=() normalized=()
  shift
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --target) [ "$#" -ge 2 ] || die "--target requires an HTTP(S) origin"; raw_targets+=("$2"); explicit=1; shift 2;;
      --retention) [ "$#" -ge 2 ] || die "--retention requires a value"; retention="$2"; shift 2;;
      --retention-size) [ "$#" -ge 2 ] || die "--retention-size requires a value"; size="$2"; shift 2;;
      -h|--help) usage; exit 0;;
      *) die "unknown observability start option: $1";;
    esac
  done
  validate_retention "$retention" time
  validate_retention "$size" size
  [[ "$PORT" =~ ^[0-9]+$ ]] && [ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || die "invalid YANO_OBSERVABILITY_PORT"
  require_docker
  validate_state_path
  if [ "$explicit" -eq 0 ]; then
    discovered="$(discover_targets)" || die "unable to discover observability targets"
    while IFS= read -r line; do [ -n "$line" ] && raw_targets+=("$line"); done <<< "$discovered"
  fi
  [ "${#raw_targets[@]}" -gt 0 ] || die "at least one scrape target is required"
  normalized_output="$(normalize_targets "${raw_targets[@]}")" \
    || die "targets must be normalized HTTP(S) origins without credentials, paths, queries, or fragments"
  while IFS= read -r line; do [ -n "$line" ] && normalized+=("$line"); done <<< "$normalized_output"
  [ "${#normalized[@]}" -gt 0 ] || die "at least one scrape target is required"
  [ "${#normalized[@]}" -le 64 ] || die "at most 64 scrape targets are supported"

  mkdir -p "$STATE_DIR"; chmod 700 "$STATE_DIR"; umask 077
  if [ -e "$MARKER" ] || [ -L "$MARKER" ]; then validate_marker; else printf 'yano-observability-v1:%s\n' "$INSTANCE" > "$MARKER"; fi
  {
    printf 'global:\n  scrape_interval: 5s\n  evaluation_interval: 5s\nscrape_configs:\n'
    local index=0
    for line in "${normalized[@]}"; do
      IFS=$'\t' read -r scheme target display <<< "$line"
      printf '  - job_name: "yano-%s"\n    scheme: "%s"\n    metrics_path: /q/metrics\n    static_configs:\n      - targets: ["%s"]\n        labels:\n          yano_origin: "%s"\n' "$index" "$scheme" "$target" "$display"
      [ -z "$cors" ] && cors="$(printf '%s' "$display" | sed 's/[][(){}.^$*+?|\\]/\\&/g')" \
        || cors="$cors|$(printf '%s' "$display" | sed 's/[][(){}.^$*+?|\\]/\\&/g')"
      [ -n "$console_origin" ] || console_origin="$display"
      index=$((index + 1))
    done
  } > "$CONFIG_FILE"
  chmod 600 "$CONFIG_FILE"
  {
    printf 'YANO_PROMETHEUS_IMAGE=%s\n' "$IMAGE"
    printf 'YANO_PROMETHEUS_RETENTION=%s\n' "$retention"
    printf 'YANO_PROMETHEUS_RETENTION_SIZE=%s\n' "$size"
    printf 'YANO_PROMETHEUS_PORT=%s\n' "$PORT"
    printf 'YANO_PROMETHEUS_CONFIG=%s\n' "$CONFIG_FILE"
    printf 'YANO_PROMETHEUS_VOLUME=%s\n' "$VOLUME"
    printf 'YANO_OBSERVABILITY_INSTANCE=%s\n' "$INSTANCE"
    printf 'YANO_PROMETHEUS_CORS_ORIGIN=^(%s)$\n' "$cors"
  } > "$ENV_FILE"
  chmod 600 "$ENV_FILE"
  compose up -d --force-recreate
  metrics_url="http://127.0.0.1:$PORT"
  encoded_metrics="$(python3 - "$metrics_url" <<'PY'
import sys
from urllib.parse import quote
print(quote(sys.argv[1], safe=''))
PY
)"
  printf 'Yano observability started.\n  Prometheus: %s/\n  Console:    %s/ui/observability/?metrics=%s\n  Retention:  %s / %s\n  Targets:\n' "$metrics_url" "$console_origin" "$encoded_metrics" "$retention" "$size"
  for line in "${normalized[@]}"; do IFS=$'\t' read -r _ _ display <<< "$line"; printf '    - %s\n' "$display"; done
}

status_bundle() {
  validate_state_path
  if [ ! -e "$MARKER" ] && [ ! -L "$MARKER" ]; then printf 'Yano observability is not initialized.\n'; return 0; fi
  validate_marker; require_docker; compose ps
}

stop_bundle() {
  validate_state_path; validate_marker; require_docker; compose down
  printf 'Yano observability stopped; Prometheus history was preserved.\n'
}

clean_bundle() {
  [ "${2:-}" = --yes ] || die "clean requires --yes"
  validate_state_path; validate_marker
  python3 - "$STATE_DIR" <<'PY' >/dev/null || die "observability state contains unrecognized files; refusing cleanup"
import os, sys
allowed = {'.yano-observability-v1', 'compose.env', 'prometheus.yml'}
if set(os.listdir(sys.argv[1])) - allowed:
    raise SystemExit(1)
PY
  require_docker; compose down
  if "$DOCKER" volume inspect "$VOLUME" >/dev/null 2>&1; then
    [ "$("$DOCKER" volume inspect --format '{{ index .Labels "com.bloxbean.yano.owner" }}' "$VOLUME")" = observability ] \
      || die "refusing to remove a foreign Docker volume"
    [ "$("$DOCKER" volume inspect --format '{{ index .Labels "com.bloxbean.yano.instance" }}' "$VOLUME")" = "$INSTANCE" ] \
      || die "refusing to remove a volume owned by another instance"
    "$DOCKER" volume rm "$VOLUME" >/dev/null
  fi
  rm -f "$CONFIG_FILE" "$ENV_FILE" "$MARKER"
  rmdir "$STATE_DIR" 2>/dev/null || die "observability state contains unrecognized files; known state was removed but directory was preserved"
  printf 'Yano observability history and launcher state removed.\n'
}

case "${1:-help}" in
  start) start_bundle "$@";;
  status) status_bundle;;
  stop) stop_bundle;;
  clean) clean_bundle "$@";;
  help|-h|--help) usage;;
  *) die "unknown observability command: $1";;
esac
